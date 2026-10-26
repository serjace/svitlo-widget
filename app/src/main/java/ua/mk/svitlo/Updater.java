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
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
        tick(ctx, fetch, false);
    }

    /** @param queuesNow also re-read every address's queues right now (⟳ in the app) */
    static void tick(Context ctx, boolean fetch, boolean queuesNow) {
        tick(ctx, fetch, queuesNow, true);
    }

    /** Automatic triggers must all respect the same deadline, rechecked under LOCK. */
    static void tickDue(Context ctx) {
        tick(ctx, true, false, false);
    }

    private static void tick(Context ctx, boolean fetch, boolean queuesNow, boolean force) {
        Context app = ctx.getApplicationContext();
        synchronized (LOCK) {
            Store st = new Store(app);
            long attemptAt = System.currentTimeMillis();
            if (fetch && !st.addresses().isEmpty() && (force || st.autoFetchDue(attemptAt))) {
                st.markFetchAttempt(attemptAt);
                if (fetchAll(st, queuesNow) && System.currentTimeMillis() - st.updateCheckedAt() > 3600 * 1000L) {
                    AppUpdate.check(app, st);   // new version of the app on GitHub? (every hour)
                }
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
        notifyListener();
    }

    static void notifyListener() {
        Runnable l = listener;
        if (l != null) new Handler(Looper.getMainLooper()).post(l);
    }

    static boolean fetchAll(Store st, boolean queuesNow) {
        List<Address> list = st.addresses();
        // Everything is downloaded at the same time, so the radio is busy for a few seconds
        // instead of one request after another: the schedule, the queue flags and, for each
        // address, its emergencies and planned works on www.energy.mk.ua (if that site doesn't
        // answer, the last known list stays).
        String cachedTs = st.cache("ts");
        String[] paths = {cachedTs == null ? "/api/schedule/time-series" : null, "/api/v2/schedule/active",
                "/api/outage-queue/by-type/1", "/api/outage-queue/by-type/2", "/api/outage-queue/by-type/3"};
        String[] got = new String[paths.length];
        List<Runnable> jobs = new ArrayList<>();
        for (int i = 0; i < paths.length; i++) {
            if (paths[i] == null) continue;
            final int k = i;
            jobs.add(() -> {
                try {
                    got[k] = Api.get(paths[k]);
                } catch (Exception ignored) {
                }
            });
        }
        for (Address a : list) {
            jobs.add(() -> {
                try {
                    st.saveWorks(a.domId, Works.fetch(a.domId));
                } catch (Exception ignored) {
                }
            });
        }
        parallel(jobs);
        try {
            String ts = cachedTs != null ? cachedTs : got[0];
            // make sure everything is valid before saving
            for (int i = 0; i < got.length; i++) new JSONArray(i == 0 ? ts : got[i]);
            st.saveCache(ts, got[1], got[2], got[3], got[4]);
        } catch (Exception e) {
            st.setLastError(System.currentTimeMillis());
            return false;
        }

        // Re-check which queues each address belongs to every 30 minutes and whenever the app is
        // opened or refreshed: queues get reassigned after damage to the grid. One small request
        // per address (all at once). A change counts only when two checks in a row agree.
        long now = System.currentTimeMillis();
        AtomicBoolean changed = new AtomicBoolean();
        jobs.clear();
        for (Address a : list) {
            boolean due = queuesNow || !a.pendQ.isEmpty() || now - a.queuesCheckedAt >= 30 * 60 * 1000L;
            if (due) jobs.add(() -> {
                if (checkQueues(st, a, now)) changed.set(true);
            });
        }
        parallel(jobs);
        if (changed.get()) st.saveAddresses(list);
        return true;
    }

    /** One address's queue check (see fetchAll). @return the address was changed */
    private static boolean checkQueues(Store st, Address a, long now) {
        boolean changed = false;
        try {
            boolean own = false;
            JSONArray oq = null;
            if (a.qDomId > 0 && a.qDomId != a.domId) {
                // borrowed from a neighbour: does the website list queues for the house itself now?
                JSONArray mine = Api.getArray("/api/addr/dom/" + a.domId + "/outage-queue");
                if (mine.length() > 0) {
                    oq = mine;
                    own = true;
                }
            }
            if (oq == null) oq = Api.getArray("/api/addr/dom/" + a.queueSource() + "/outage-queue");
            a.queuesCheckedAt = now;
            changed = true;
            if (oq.length() == 0) {   // a glitch: keep what we have
                a.pendQ = "";
                return true;
            }
            Address fresh = new Address();
            fresh.applyQueues(oq);
            if (!own && fresh.gpvId == a.gpvId && fresh.gavId == a.gavId && fresh.sgavId == a.sgavId) {
                a.applyQueues(oq);   // the same queues (names may be spelled differently)
                a.pendQ = "";
                return true;
            }
            String seen = fresh.gpvId + ":" + fresh.gavId + ":" + fresh.sgavId + ":" + (own ? 1 : 0);
            if (!seen.equals(a.pendQ)) {   // first time: wait for the next check to confirm
                a.pendQ = seen;
                return true;
            }
            // confirmed: take the new queues
            a.pendQ = "";
            int oldGpv = a.gpvId;
            String oldName = a.gpvName;
            a.applyQueues(oq);
            if (own) {
                a.qDomId = 0;
                a.qDomName = "";
            }
            // ГАВ / СГАВ change silently; a new ГПВ queue gets a notification (Alerts)
            if (own || a.gpvId != oldGpv) {
                JSONObject ns = st.notifyState(a.domId);
                JSONObject ev = new JSONObject();
                ev.put("from", oldGpv > 0 ? oldName : "");
                ev.put("own", own);
                ns.put("qchg", ev);
                st.saveNotifyState(a.domId, ns);
            }
        } catch (Exception ignored) {
        }
        return changed;
    }

    /** Runs the jobs at the same time and waits for all of them (at most a minute). */
    private static void parallel(List<Runnable> jobs) {
        if (jobs.isEmpty()) return;
        ExecutorService ex = Executors.newFixedThreadPool(Math.min(jobs.size(), 8));
        try {
            List<Callable<Object>> cs = new ArrayList<>();
            for (Runnable r : jobs) cs.add(Executors.callable(r));
            ex.invokeAll(cs, 60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            ex.shutdownNow();
        }
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
                            if (!a.notifyAllowed(Status.timeOf(today, j))) break;   // outside its hours
                            ZonedDateTime w = Status.timeOf(today, j).minusMinutes(lead);
                            if (w.isAfter(now) && w.isBefore(next)) next = w;
                            break;
                        }
                    }
                }
            }
        }

        // an emergency or planned work starts or ends (or its "уточнюється" time runs out)
        long nowSec = now.toEpochSecond();
        for (Address a : st.addresses()) {
            for (Works.Work w : sch.works(a.domId)) {
                for (long t : new long[]{w.start, w.end, w.shownUntil()}) {
                    ZonedDateTime z = ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond(t + 5), Schedule.ZONE);
                    if (t > nowSec && z.isBefore(next)) next = z;
                }
            }
        }

        // news postponed until an address's notification hours start
        for (Address a : st.addresses()) {
            JSONObject d = st.notifyState(a.domId).optJSONObject("defer");
            if (a.notify && d != null && d.length() > 0) {
                ZonedDateTime w = a.nextAllowed(now);
                if (w != null && w.plusSeconds(5).isBefore(next)) next = w.plusSeconds(5);
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
        long wallNow = System.currentTimeMillis();
        long due = st.nextFetchAt(wallNow);
        // If a network-constrained job is waiting offline, avoid an immediate alarm loop.
        am.set(AlarmManager.RTC, due > wallNow ? due : wallNow + 5 * 60_000L, check);
    }

    /** Background fallback every 15 minutes; SyncJob downloads only when actually due. */
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
