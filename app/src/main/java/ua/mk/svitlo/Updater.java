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
    static final String ACTION_CHECK = "ua.mk.svitlo.CHECK";

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
            if (fetch && fetchAll(st) && System.currentTimeMillis() - st.updateCheckedAt() > 3600 * 1000L) {
                AppUpdate.check(app, st);   // new version of the app on GitHub? (every hour)
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

    /**
     * Two alarms, to save battery:
     * 1) an exact wake-up only when something changes — the status of any address changes,
     *    a warning is due, or a new day starts (a few times a day);
     * 2) a regular check every few minutes that does NOT wake the phone: it runs as soon as the
     *    screen is on, so widgets are fresh when someone looks at them.
     * While the phone sleeps, the 15-minute background job (batched by Android) keeps data fresh.
     */
    static void scheduleNext(Context ctx, Store st, Schedule sch, ZonedDateTime now) {
        LocalDate today = now.toLocalDate();
        int ni = Status.nowIndex(now);
        ZonedDateTime next = Status.timeOf(today, 48).plusSeconds(5);   // tomorrow 00:00

        if (sch.hasData) {
            boolean warn = st.flag(Store.F_BEFORE);
            int lead = st.lead();
            for (Address a : st.addresses()) {
                int[] c = Status.combined(sch, a, today);
                int cur = Timeline.level(c[ni]);
                for (int j = ni + 1; j < 96 && c[j] >= 0; j++) {
                    if (Timeline.level(c[j]) != cur) {
                        ZonedDateTime change = Status.timeOf(today, j).plusSeconds(5);
                        if (change.isBefore(next)) next = change;
                        break;
                    }
                }
                if (warn && a.notify) {
                    for (int j = ni + 1; j < 96 && c[j] >= 0; j++) {
                        if (c[j] >= Schedule.OFF && c[j - 1] < Schedule.OFF) {
                            ZonedDateTime w = Status.timeOf(today, j).minusMinutes(lead);
                            if (w.isAfter(now) && w.isBefore(next)) next = w;
                            break;
                        }
                    }
                }
            }
        }

        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;

        // 1) exact wake-up at the next change
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 1,
                new Intent(ctx, AlarmReceiver.class).setAction(ACTION_TICK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        long at = next.toInstant().toEpochMilli();
        boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }

        // 2) regular check that waits for the screen to be on (no wake-up)
        PendingIntent check = PendingIntent.getBroadcast(ctx, 2,
                new Intent(ctx, AlarmReceiver.class).setAction(ACTION_CHECK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.set(AlarmManager.RTC, System.currentTimeMillis() + st.interval() * 60 * 1000L, check);
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
