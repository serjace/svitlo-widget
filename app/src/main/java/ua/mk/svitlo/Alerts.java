package ua.mk.svitlo;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

/** Decides which notifications to send. Called on every update. */
final class Alerts {
    private Alerts() {}

    static void check(Context ctx, Store st, Schedule sch, ZonedDateTime now) throws JSONException {
        if (!sch.hasData) return;
        LocalDate today = now.toLocalDate();
        LocalDate tomorrow = today.plusDays(1);
        int ni = Status.nowIndex(now);

        for (Address a : st.addresses()) {
            JSONObject ns = st.notifyState(a.domId);
            boolean first = ns.length() == 0;   // just added: remember the state, don't notify
            boolean on = a.notify;

            // --- Emergency outages (ГАВ / СГАВ) ---
            boolean gav = a.gavId > 0 && sch.isEnabled(a.gavId);
            boolean sgav = a.sgavId > 0 && sch.isEnabled(a.sgavId);
            if (!first && on && st.flag(Store.F_EMERGENCY)) {
                if (gav != ns.optBoolean("gav")) emergency(ctx, a, "ГАВ " + a.gavName, gav);
                if (sgav != ns.optBoolean("sgav")) emergency(ctx, a, "СГАВ " + a.sgavName, sgav);
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
                            "Графік на сьогодні змінено",
                            a.name + "\n" + Status.describeDay(t), Status.color(Status.ORANGE));
                }
                fresh.put(kt, ht);

                int[] m = sch.slots(tomorrow, a.gpvId);
                String km = tomorrow.toString();
                if (m != null) {
                    String hmh = Arrays.toString(m);
                    String date = tomorrow.format(DateTimeFormatter.ofPattern("dd.MM"));
                    if (tell && !old.has(km)) {
                        Notifier.show(ctx, Notifier.CH_SCHEDULE, "sch" + a.domId + km,
                                "Графік на завтра (" + date + ")",
                                a.name + "\n" + Status.describeDay(m), Status.color(Status.GREEN));
                    } else if (tell && !hmh.equals(old.optString(km))) {
                        Notifier.show(ctx, Notifier.CH_SCHEDULE, "sch" + a.domId + km,
                                "Графік на завтра (" + date + ") змінено",
                                a.name + "\n" + Status.describeDay(m), Status.color(Status.ORANGE));
                    }
                    fresh.put(km, hmh);
                } else if (old.has(km)) {
                    fresh.put(km, old.optString(km));
                }
                ns.put("h", fresh);
            }

            // --- Before an outage / power should be back ---
            if (on && !first) {
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
                                    String kind = c[j] == Schedule.SURE_OFF ? "актуальне" : "заплановане";
                                    Notifier.show(ctx, Notifier.CH_OUTAGE, key,
                                            "Через " + mins + " хв — відключення світла",
                                            a.name + ": " + Status.range(j, end) + " (" + kind + ")",
                                            Status.color(Status.ORANGE));
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
                                    "Світло має зʼявитися",
                                    a.name + ": за графіком відключення закінчилось о " + Status.hm(ni),
                                    Status.color(Status.GREEN));
                        }
                    }
                }
            }

            st.saveNotifyState(a.domId, ns);
        }
    }

    private static void emergency(Context ctx, Address a, String queue, boolean started) {
        if (started) {
            Notifier.show(ctx, Notifier.CH_OUTAGE, "em:" + a.domId + queue + System.currentTimeMillis(),
                    "Аварійне відключення",
                    a.name + ": діє " + queue + ". Світла, ймовірно, немає.",
                    Status.color(Status.RED));
        } else {
            Notifier.show(ctx, Notifier.CH_OUTAGE, "em:" + a.domId + queue + System.currentTimeMillis(),
                    "Аварійне відключення скасовано",
                    a.name + ": " + queue + " більше не діє.",
                    Status.color(Status.GREEN));
        }
    }
}
