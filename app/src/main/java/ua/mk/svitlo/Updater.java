package ua.mk.svitlo;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * The heart of the app: download fresh data, send notifications,
 * redraw widgets and plan the next wake-up.
 */
final class Updater {
    static final int JOB_PERIODIC = 1001;
    static final int JOB_NOW = 1002;
    static final String ACTION_TICK = "ua.mk.svitlo.TICK";

    private static final Object LOCK = new Object();
    private static volatile Runnable listener;

    private Updater() {}

    /** The open app screen registers here to redraw itself after each update. */
    static void setListener(Runnable r) {
        listener = r;
    }

    static void tick(Context ctx, boolean fetch) {
        Context app = ctx.getApplicationContext();
        synchronized (LOCK) {
            Store st = new Store(app);
            if (fetch && fetchAll(st) && System.currentTimeMillis() - st.updateCheckedAt() > 3 * 3600 * 1000L) {
                AppUpdate.check(app, st);   // new version of the app on GitHub? (every 3 hours)
            }
            Schedule sch = Schedule.load(st);
            ZonedDateTime now = ZonedDateTime.now(Schedule.ZONE);
            try {
                Alerts.check(app, st, sch, now);
            } catch (Exception ignored) {
            }
            PowerWidget.updateAll(app, st, sch, now, false);
            scheduleNext(app, st, sch, now);
            ensurePeriodic(app);
        }
        Runnable l = listener;
        if (l != null) new Handler(Looper.getMainLooper()).post(l);
    }

    static boolean fetchAll(Store st) {
        try {
            String ts = st.cache("ts");
            if (ts == null) ts = Api.get("/api/schedule/time-series");
            String active = Api.get("/api/v2/schedule/active");
            String q1 = Api.get("/api/outage-queue/by-type/1");
            String q2 = Api.get("/api/outage-queue/by-type/2");
            String q3 = Api.get("/api/outage-queue/by-type/3");
            // make sure everything is valid before saving
            new JSONArray(ts);
            new JSONArray(active);
            new JSONArray(q1);
            new JSONArray(q2);
            new JSONArray(q3);
            st.saveCache(ts, active, q1, q2, q3);
        } catch (Exception e) {
            st.setLastError(System.currentTimeMillis());
            return false;
        }

        // Re-check which queues each address belongs to twice a day (they can be reassigned).
        long now = System.currentTimeMillis();
        List<Address> list = st.addresses();
        boolean changed = false;
        for (Address a : list) {
            if (now - a.queuesCheckedAt < 12L * 3600 * 1000) continue;
            try {
                JSONArray oq = Api.getArray("/api/addr/dom/" + a.domId + "/outage-queue");
                if (oq.length() > 0) a.applyQueues(oq);
                a.queuesCheckedAt = now;
                changed = true;
            } catch (Exception ignored) {
            }
        }
        if (changed) st.saveAddresses(list);
        return true;
    }

    /** Wake up at the next half-hour boundary, or earlier to warn before an outage. */
    static void scheduleNext(Context ctx, Store st, Schedule sch, ZonedDateTime now) {
        LocalDate today = now.toLocalDate();
        int ni = Status.nowIndex(now);
        ZonedDateTime next = Status.timeOf(today, ni + 1).plusSeconds(5);
        ZonedDateTime check = now.plusMinutes(st.interval());
        if (check.isBefore(next)) next = check;

        if (sch.hasData && st.flag(Store.F_BEFORE)) {
            int lead = st.lead();
            for (Address a : st.addresses()) {
                if (!a.notify) continue;
                int[] c = Status.combined(sch, a, today);
                for (int j = ni + 1; j < 96 && c[j] >= 0; j++) {
                    if (c[j] >= Schedule.OFF && c[j - 1] < Schedule.OFF) {
                        ZonedDateTime warn = Status.timeOf(today, j).minusMinutes(lead);
                        if (warn.isAfter(now) && warn.isBefore(next)) next = warn;
                        break;
                    }
                }
            }
        }

        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;
        Intent i = new Intent(ctx, AlarmReceiver.class).setAction(ACTION_TICK);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 1, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        long at = next.toInstant().toEpochMilli();
        boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    /** Download fresh data every 15 minutes (Android's minimum) when there is internet. */
    static void ensurePeriodic(Context ctx) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(JOB_PERIODIC) != null) return;
        JobInfo ji = new JobInfo.Builder(JOB_PERIODIC, new ComponentName(ctx, SyncJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build();
        js.schedule(ji);
    }

    /** Download fresh data as soon as there is internet. */
    static void requestFetch(Context ctx) {
        JobScheduler js = ctx.getSystemService(JobScheduler.class);
        if (js == null) return;
        JobInfo ji = new JobInfo.Builder(JOB_NOW, new ComponentName(ctx, SyncJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build();
        js.schedule(ji);
    }
}
