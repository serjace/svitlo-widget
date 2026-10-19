package ua.mk.svitlo;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Decides which notifications to send. Called on every update.
 * Every notification starts with the address name ("Дім: …") and says the light status and times.
 */
final class Alerts {
    private Alerts() {}

    /** "Світло є — вимкнуть о 14:00" */
    private static String nowText(Status s) {
        return s.title + (s.line1.isEmpty() ? "" : " — " + lower(s.line1));
    }

    private static String lower(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    static void check(Context ctx, Store st, Schedule sch, ZonedDateTime now) throws JSONException {
        if (!sch.hasData) return;
        LocalDate today = now.toLocalDate();
        LocalDate tomorrow = today.plusDays(1);
        int ni = Status.nowIndex(now);

        for (Address a : st.addresses()) {
            JSONObject ns = st.notifyState(a.domId);
            boolean first = ns.length() == 0;   // just added: remember the state, don't notify
            boolean on = a.notify;
            Status cur = Status.compute(sch, a, now);
            long nowSec = now.toEpochSecond();
            long endOfToday = Status.timeOf(today, 48).toEpochSecond();
            int[] c = Status.combined(sch, a, today);

            // Notifications shown for this address: {key: no longer true after (epoch s), 0 = until replaced}.
            // Outdated ones are removed, so only what is still true stays in the shade.
            JSONObject live = ns.optJSONObject("live");
            if (live == null) live = new JSONObject();
            Set<String> starts = outageStarts(c, today);
            for (String k : keysOf(live)) {
                long until = live.optLong(k);
                boolean stale = until > 0 && nowSec >= until;
                // a warning for an outage that is no longer in the schedule
                if (k.startsWith("pre:") && !starts.contains(k.substring(k.lastIndexOf(':') + 1))) stale = true;
                if (stale) {
                    Notifier.cancel(ctx, k);
                    live.remove(k);
                }
            }

            // --- Emergency outages (ГАВ / СГАВ) ---
            boolean gav = a.gavId > 0 && sch.isEnabled(a.gavId);
            boolean sgav = a.sgavId > 0 && sch.isEnabled(a.sgavId);
            if (!first && on && st.flag(Store.F_EMERGENCY)) {
                if (gav != ns.optBoolean("gav")) emergency(ctx, live, nowSec, a, "ГАВ " + a.gavName, gav, cur);
                if (sgav != ns.optBoolean("sgav")) emergency(ctx, live, nowSec, a, "СГАВ " + a.sgavName, sgav, cur);
            }
            ns.put("gav", gav);
            ns.put("sgav", sgav);

            // A new ГПВ queue (found by Updater): one notification that also says what it means
            // for today and tomorrow, instead of separate "schedule changed" ones
            JSONObject qchg = ns.optJSONObject("qchg");
            ns.remove("qchg");

            // --- Schedule changed / tomorrow published ---
            if (a.gpvId > 0) {
                JSONObject old = ns.optJSONObject("h");
                if (old == null) old = new JSONObject();
                boolean tell = !first && on && st.flag(Store.F_SCHEDULE) && qchg == null;
                JSONObject fresh = new JSONObject();

                int[] t = sch.slots(today, a.gpvId);
                if (t == null) t = new int[48];
                String kt = today.toString();
                String ht = Arrays.toString(t);
                // Only changes from now until midnight matter; the past part is ignored
                if (tell && old.has(kt) && changedFrom(old.optString(kt), t, ni)) {
                    // same key as the earlier one for this day: the new text replaces the old
                    show(ctx, live, endOfToday, Notifier.CH_SCHEDULE, "sch" + a.domId + kt,
                            a.name + ": графік на сьогодні змінився",
                            Status.restOfDay(t, ni), Status.color(Status.YELLOW));
                }
                fresh.put(kt, ht);

                int[] m = sch.slots(tomorrow, a.gpvId);
                String km = tomorrow.toString();
                if (m != null) {
                    String hmh = Arrays.toString(m);
                    String date = tomorrow.format(DateTimeFormatter.ofPattern("dd.MM"));
                    if (tell && !old.has(km)) {
                        show(ctx, live, endOfToday + 86400, Notifier.CH_SCHEDULE, "sch" + a.domId + km,
                                a.name + ": графік на завтра, " + date,
                                Status.daySummary(m), Status.color(Status.GREEN));
                    } else if (tell && !hmh.equals(old.optString(km))) {
                        show(ctx, live, endOfToday + 86400, Notifier.CH_SCHEDULE, "sch" + a.domId + km,
                                a.name + ": змінився графік на завтра, " + date,
                                Status.daySummary(m), Status.color(Status.YELLOW));
                    }
                    fresh.put(km, hmh);
                } else if (old.has(km)) {
                    fresh.put(km, old.optString(km));
                }
                ns.put("h", fresh);
            }

            if (qchg != null && on && st.flag(Store.F_SCHEDULE) && a.gpvId > 0) {
                int[] t = sch.slots(today, a.gpvId);
                if (t == null) t = new int[48];
                int[] m = sch.slots(tomorrow, a.gpvId);
                String from = qchg.optString("from");
                String title, was;
                if (qchg.optBoolean("own")) {
                    title = a.name + ": сайт тепер вказує чергу вашого будинку — ГПВ " + a.gpvName;
                    was = "Досі була черга сусіднього будинку" + (from.isEmpty() ? "" : " (" + from + ")") + ".\n";
                } else {
                    title = a.name + ": зміна черги на ГПВ " + a.gpvName;
                    was = from.isEmpty() ? "" : "Була черга " + from + ".\n";
                }
                String text = was + Status.restOfDay(t, ni)
                        + (m != null ? "\n\nЗавтра:\n" + Status.daySummary(m) : "");
                show(ctx, live, endOfToday + 86400, Notifier.CH_SCHEDULE, "q:" + a.domId, title, text,
                        Status.color(Status.YELLOW));
            }

            // --- Before an outage / power should be back ---
            if (on && !first && a.gpvId > 0) {
                if (st.flag(Store.F_BEFORE)) {
                    int lead = st.lead();
                    for (int j = ni + 1; j < 96 && c[j] >= 0; j++) {
                        if (c[j] >= Schedule.OFF && c[j - 1] < Schedule.OFF) {
                            ZonedDateTime start = Status.timeOf(today, j);
                            long secs = Duration.between(now, start).getSeconds();
                            long mins = (secs + 59) / 60;
                            if (mins <= lead) {
                                String key = "pre:" + a.domId + ":" + start.toEpochSecond();
                                if (!st.wasSent(key)) {
                                    st.markSent(key);
                                    int end = Status.runEnd(c, j, Schedule.OFF, Schedule.SURE_OFF);
                                    String text = "Світла не буде з " + Status.at(j) + " до " + Status.at(end);
                                    if (end < 96 && c[end] == Schedule.PROBABLY) {
                                        text += ", потім можливі відключення до "
                                                + Status.hm(Status.runEnd(c, end, Schedule.PROBABLY, Schedule.PROBABLY));
                                    }
                                    // gone once the outage starts (or if it leaves the schedule)
                                    show(ctx, live, start.toEpochSecond(), Notifier.CH_OUTAGE, key,
                                            a.name + ": через " + mins + " хв вимкнуть світло",
                                            text + ".", Status.color(Status.RED));
                                }
                            }
                            break;
                        }
                    }
                }
                if (st.flag(Store.F_RETURN) && !gav && !sgav && ni >= 1
                        && c[ni] < Schedule.OFF && c[ni - 1] >= Schedule.OFF) {
                    ZonedDateTime end = Status.timeOf(today, ni);
                    if (Duration.between(end, now).toMinutes() < 20) {
                        String key = "ret:" + a.domId + ":" + end.toEpochSecond();
                        if (!st.wasSent(key)) {
                            st.markSent(key);
                            // gone when the next outage starts
                            int off = ni;
                            while (off < 96 && c[off] >= 0 && c[off] < Schedule.OFF) off++;
                            long until = off < 96 && c[off] >= 0
                                    ? Status.timeOf(today, off).toEpochSecond() : nowSec + 6 * 3600;
                            show(ctx, live, until, Notifier.CH_OUTAGE, key,
                                    a.name + ": світло має зʼявитися",
                                    "За графіком відключення закінчилось о " + Status.hm(ni) + ".\n"
                                            + "Зараз: " + nowText(cur),
                                    Status.color(Status.GREEN));
                        }
                    }
                }
            }

            if (gav || sgav) {   // an emergency outage: "light should be back" is no longer true
                for (String k : keysOf(live)) {
                    if (k.startsWith("ret:")) {
                        Notifier.cancel(ctx, k);
                        live.remove(k);
                    }
                }
            }
            ns.put("live", live);
            st.saveNotifyState(a.domId, ns);
        }
    }

    /** Did any slot from "from" to midnight change? "saved" is Arrays.toString() of the old day. */
    private static boolean changedFrom(String saved, int[] now, int from) {
        String[] p = saved.replace("[", "").replace("]", "").split(",");
        if (p.length != now.length) return !saved.equals(Arrays.toString(now));
        for (int i = from; i < now.length; i++) {
            try {
                if (Integer.parseInt(p[i].trim()) != now[i]) return true;
            } catch (NumberFormatException e) {
                return true;
            }
        }
        return false;
    }

    private static void emergency(Context ctx, JSONObject live, long nowSec, Address a, String queue,
                                  boolean started, Status cur) throws JSONException {
        // One key per queue: "cancelled" replaces "started" (and the other way round)
        String key = "em:" + a.domId + ":" + queue;
        if (started) {
            show(ctx, live, 0, Notifier.CH_OUTAGE, key,
                    a.name + ": аварійне відключення",
                    "Діє " + queue + ". Світла немає, час повернення невідомий.",
                    Status.color(Status.RED));
        } else {
            show(ctx, live, nowSec + 3 * 3600, Notifier.CH_OUTAGE, key,
                    a.name + ": аварійне відключення скасовано",
                    queue + " більше не діє.\nЗараз: " + nowText(cur),
                    Status.color(Status.GREEN));
        }
    }

    /** Shows a notification and remembers until when it stays true. */
    private static void show(Context ctx, JSONObject live, long untilSec, String channel, String key,
                             String title, String text, int color) throws JSONException {
        Notifier.show(ctx, channel, key, title, text, color);
        live.put(key, untilSec);
    }

    private static List<String> keysOf(JSONObject o) {
        List<String> r = new ArrayList<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) r.add(it.next());
        return r;
    }

    /** Start times (epoch seconds, as text) of the outages in today + tomorrow. */
    private static Set<String> outageStarts(int[] c, LocalDate today) {
        Set<String> r = new HashSet<>();
        for (int j = 1; j < c.length; j++) {
            if (c[j] >= Schedule.OFF && c[j - 1] < Schedule.OFF) {
                r.add(String.valueOf(Status.timeOf(today, j).toEpochSecond()));
            }
        }
        return r;
    }
}
