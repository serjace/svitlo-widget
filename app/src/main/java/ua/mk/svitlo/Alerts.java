package ua.mk.svitlo;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

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

            // --- Emergency outages (ГАВ / СГАВ) ---
            boolean gav = a.gavId > 0 && sch.isEnabled(a.gavId);
            boolean sgav = a.sgavId > 0 && sch.isEnabled(a.sgavId);
            if (!first && on && st.flag(Store.F_EMERGENCY)) {
                if (gav != ns.optBoolean("gav")) emergency(ctx, a, "ГАВ " + a.gavName, gav, cur);
                if (sgav != ns.optBoolean("sgav")) emergency(ctx, a, "СГАВ " + a.sgavName, sgav, cur);
            }
            ns.put("gav", gav);
            ns.put("sgav", sgav);

            // --- Schedule changed / tomorrow published ---
            if (a.gpvId > 0) {
                JSONObject old = ns.optJSONObject("h");
                if (old == null) old = new JSONObject();
                boolean tell = !first && on && st.flag(Store.F_SCHEDULE);
                JSONObject fresh = new JSONObject();

                int[] t = sch.slots(today, a.gpvId);
                if (t == null) t = new int[48];
                String kt = today.toString();
                String ht = Arrays.toString(t);
                if (tell && old.has(kt) && !ht.equals(old.optString(kt))) {
                    Notifier.show(ctx, Notifier.CH_SCHEDULE, "sch" + a.domId + kt,
                            a.name + ": змінився графік на сьогодні",
                            "Зараз: " + nowText(cur) + "\n\n" + Status.daySummary(t),
                            Status.color(Status.YELLOW));
                }
                fresh.put(kt, ht);

                int[] m = sch.slots(tomorrow, a.gpvId);
                String km = tomorrow.toString();
                if (m != null) {
                    String hmh = Arrays.toString(m);
                    String date = tomorrow.format(DateTimeFormatter.ofPattern("dd.MM"));
                    if (tell && !old.has(km)) {
                        Notifier.show(ctx, Notifier.CH_SCHEDULE, "sch" + a.domId + km,
                                a.name + ": графік на завтра, " + date,
                                Status.daySummary(m), Status.color(Status.GREEN));
                    } else if (tell && !hmh.equals(old.optString(km))) {
                        Notifier.show(ctx, Notifier.CH_SCHEDULE, "sch" + a.domId + km,
                                a.name + ": змінився графік на завтра, " + date,
                                Status.daySummary(m), Status.color(Status.YELLOW));
                    }
                    fresh.put(km, hmh);
                } else if (old.has(km)) {
                    fresh.put(km, old.optString(km));
                }
                ns.put("h", fresh);
            }

            // --- Before an outage / power should be back ---
            if (on && !first && a.gpvId > 0) {
                int[] c = Status.combined(sch, a, today);
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
                                    Notifier.show(ctx, Notifier.CH_OUTAGE, key,
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
                            Notifier.show(ctx, Notifier.CH_OUTAGE, key,
                                    a.name + ": світло має зʼявитися",
                                    "За графіком відключення закінчилось о " + Status.hm(ni) + ".\n"
                                            + "Зараз: " + nowText(cur),
                                    Status.color(Status.GREEN));
                        }
                    }
                }
            }

            st.saveNotifyState(a.domId, ns);
        }
    }

    private static void emergency(Context ctx, Address a, String queue, boolean started, Status cur) {
        if (started) {
            Notifier.show(ctx, Notifier.CH_OUTAGE, "em:" + a.domId + queue + System.currentTimeMillis(),
                    a.name + ": аварійне відключення",
                    "Діє " + queue + ". Світла немає, час повернення невідомий.",
                    Status.color(Status.RED));
        } else {
            Notifier.show(ctx, Notifier.CH_OUTAGE, "em:" + a.domId + queue + System.currentTimeMillis(),
                    a.name + ": аварійне відключення скасовано",
                    queue + " більше не діє.\nЗараз: " + nowText(cur),
                    Status.color(Status.GREEN));
        }
    }
}
