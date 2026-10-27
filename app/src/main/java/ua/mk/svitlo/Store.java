package ua.mk.svitlo;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Everything the app remembers on the phone. */
final class Store {
    private static final Object FETCH_LOCK = new Object();
    static final String F_BEFORE = "n_before";
    static final String F_RETURN = "n_return";
    static final String F_EMERGENCY = "n_emerg";
    static final String F_SCHEDULE = "n_sched";

    private final SharedPreferences p;
    final DataHealth health;

    Store(Context ctx) {
        p = ctx.getApplicationContext().getSharedPreferences("svitlo", Context.MODE_PRIVATE);
        health = new DataHealth(p);
    }

    // ---- addresses ----

    List<Address> addresses() {
        List<Address> r = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.getString("addresses", "[]"));
            for (int i = 0; i < a.length(); i++) r.add(Address.fromJson(a.getJSONObject(i)));
        } catch (JSONException ignored) {
        }
        return r;
    }

    void saveAddresses(List<Address> list) {
        JSONArray a = new JSONArray();
        for (Address x : list) a.put(x.toJson());
        p.edit().putString("addresses", a.toString()).apply();
    }

    Address find(long domId) {
        for (Address a : addresses()) if (a.domId == domId) return a;
        return null;
    }

    void setActiveDom(long domId) {
        p.edit().putLong("active", domId).apply();
    }

    Address active() {
        List<Address> list = addresses();
        long d = p.getLong("active", -1);
        for (Address a : list) if (a.domId == d) return a;
        return list.isEmpty() ? null : list.get(0);
    }

    void removeAddress(long domId) {
        List<Address> list = addresses();
        for (Iterator<Address> it = list.iterator(); it.hasNext(); ) {
            if (it.next().domId == domId) it.remove();
        }
        saveAddresses(list);
        p.edit().remove("ns_" + domId).remove("c_w_" + domId).remove("health_work_" + domId).apply();
    }

    /** An address was changed to another house: move widget links and the "shown" mark to it. */
    void replaceDomRefs(long oldDom, long newDom) {
        SharedPreferences.Editor e = p.edit();
        for (java.util.Map.Entry<String, ?> en : p.getAll().entrySet()) {
            if (en.getKey().startsWith("w_") && en.getValue() instanceof Long
                    && (Long) en.getValue() == oldDom) {
                e.putLong(en.getKey(), newDom);
            }
        }
        if (p.getLong("active", -1) == oldDom) e.putLong("active", newDom);
        e.remove("ns_" + oldDom);
        e.remove("c_w_" + oldDom);
        e.remove("health_work_" + oldDom);
        e.apply();
    }

    /** One-time tips. */
    boolean seen(String key) {
        return p.getBoolean("seen_" + key, false);
    }

    void markSeen(String key) {
        p.edit().putBoolean("seen_" + key, true).apply();
    }

    // ---- widgets ----

    Address widgetAddress(int widgetId) {
        return active();
    }

    void cycleWidget(int widgetId) {
        List<Address> list = addresses();
        if (list.isEmpty()) return;
        Address cur = active();
        int idx = 0;
        for (int i = 0; i < list.size(); i++) {
            if (cur != null && list.get(i).domId == cur.domId) idx = i;
        }
        setActiveDom(list.get((idx + 1) % list.size()).domId);
    }

    void removeWidget(int widgetId) {
        p.edit().remove("w_" + widgetId).remove("wl_" + widgetId).apply();
    }

    /** Text size on all widgets, chosen in Settings: 0.85, 1 (default), 1.15 or 1.3. */
    float widgetScale() {
        return p.getFloat("w_scale", 1f);
    }

    void setWidgetScale(float k) {
        p.edit().putFloat("w_scale", k).apply();
    }

    // ---- settings ----

    boolean flag(String key) {
        return p.getBoolean(key, true);
    }

    void setFlag(String key, boolean v) {
        p.edit().putBoolean(key, v).apply();
    }

    /** Minutes before an outage to warn: 10, 15 or 30. */
    int lead() {
        return Math.min(p.getInt("lead", 10), 30);
    }

    void setLead(int minutes) {
        p.edit().putInt("lead", minutes).apply();
    }

    /**
     * Automatic request interval in minutes. Explicit choices survive app upgrades.
     */
    int interval() {
        return RefreshPlan.interval(p.getInt("interval", RefreshPlan.DEFAULT_MINUTES));
    }

    void setInterval(int minutes) {
        synchronized (FETCH_LOCK) {
            p.edit().putInt("interval", RefreshPlan.interval(minutes)).remove("fetch_due").apply();
        }
    }

    /** One persisted randomized deadline, shared by alarms, jobs, widgets and app opening. */
    long nextFetchAt(long now) {
        synchronized (FETCH_LOCK) {
            long last = p.getLong("fetch_attempt", fetchedAt());
            long saved = p.getLong("fetch_due", 0);
            if (last <= 0) return now;
            if (last <= now && saved > 0) return saved;
            int offset = java.util.concurrent.ThreadLocalRandom.current().nextInt(
                    -RefreshPlan.JITTER_SECONDS, RefreshPlan.JITTER_SECONDS + 1);
            long next = RefreshPlan.nextAt(now, last, saved, interval(), offset);
            SharedPreferences.Editor edit = p.edit().putLong("fetch_due", next);
            if (last > now) edit.putLong("fetch_attempt", now);
            edit.apply();
            return next;
        }
    }

    boolean autoFetchDue(long now) {
        return now >= nextFetchAt(now);
    }

    /** Reserve before network I/O: failures and simultaneous triggers cannot cause a burst. */
    void markFetchAttempt(long now) {
        synchronized (FETCH_LOCK) {
            int offset = java.util.concurrent.ThreadLocalRandom.current().nextInt(
                    -RefreshPlan.JITTER_SECONDS, RefreshPlan.JITTER_SECONDS + 1);
            p.edit().putLong("fetch_attempt", now)
                    .putLong("fetch_due", RefreshPlan.after(now, interval(), offset)).apply();
        }
    }

    // ---- app updates ----

    long updateCheckedAt() {
        return p.getLong("upd_at", 0);
    }

    void markUpdateChecked() {
        p.edit().putLong("upd_at", System.currentTimeMillis()).apply();
    }

    /** Remembers the newest version found on GitHub. */
    void saveUpdate(int versionCode, String url) {
        p.edit().putInt("upd_code", versionCode)
                .putString("upd_url", url)
                .apply();
    }

    int updateCode() {
        return p.getInt("upd_code", 0);
    }

    String updateUrl() {
        return p.getString("upd_url", null);
    }

    int updateNotified() {
        return p.getInt("upd_notified", 0);
    }

    void setUpdateNotified(int versionCode) {
        p.edit().putInt("upd_notified", versionCode).apply();
    }

    long downloadId() {
        return p.getLong("upd_dl", -1);
    }

    void setDownloadId(long id) {
        p.edit().putLong("upd_dl", id).apply();
    }

    // ---- cached website data ----

    String cache(String key) {
        return p.getString("c_" + key, null);
    }

    void saveCache(String ts, String active, String q1, String q2, String q3) {
        p.edit()
                .putString("c_ts", ts)
                .putString("c_active", active)
                .putString("c_q1", q1)
                .putString("c_q2", q2)
                .putString("c_q3", q3)
                .putLong("c_time", System.currentTimeMillis())
                .apply();
    }

    long fetchedAt() {
        return p.getLong("c_time", 0);
    }

    /** Emergencies and planned works of one house (www.energy.mk.ua), as the website sent them. */
    String works(long domId) {
        return p.getString("c_w_" + domId, null);
    }

    void saveWorks(long domId, String json) {
        p.edit().putString("c_w_" + domId, json).apply();
    }

    long lastError() {
        return p.getLong("c_err", 0);
    }

    void setLastError(long t) {
        p.edit().putLong("c_err", t).apply();
    }

    String updatedLabel() {
        long f = fetchedAt();
        if (f == 0) {
            String problem = health.problemLabel(this, System.currentTimeMillis());
            return problem.isEmpty() ? "Дані ще не завантажено" : problem;
        }
        ZonedDateTime t = Instant.ofEpochMilli(f).atZone(Schedule.ZONE);
        boolean today = t.toLocalDate().equals(LocalDate.now(Schedule.ZONE));
        String s = "Оновлено " + t.format(DateTimeFormatter.ofPattern(today ? "HH:mm" : "dd.MM HH:mm"));
        String problem = health.problemLabel(this, System.currentTimeMillis());
        if (!problem.isEmpty()) s += " · " + problem;
        return s;
    }

    /** Short version for the widget header: "11:17" or "немає звʼязку". */
    String updatedShort() {
        long f = fetchedAt();
        if (f == 0) return "";
        if (lastError() > f) return "немає звʼязку";
        ZonedDateTime t = Instant.ofEpochMilli(f).atZone(Schedule.ZONE);
        boolean today = t.toLocalDate().equals(LocalDate.now(Schedule.ZONE));
        return t.format(DateTimeFormatter.ofPattern(today ? "HH:mm" : "dd.MM HH:mm"));
    }

    // ---- notification memory ----

    JSONObject notifyState(long domId) {
        try {
            return new JSONObject(p.getString("ns_" + domId, "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    void saveNotifyState(long domId, JSONObject o) {
        p.edit().putString("ns_" + domId, o.toString()).apply();
    }

    boolean wasSent(String key) {
        return sent().has(key);
    }

    void markSent(String key) {
        JSONObject s = sent();
        long now = System.currentTimeMillis();
        try {
            s.put(key, now);
            JSONObject pruned = new JSONObject();
            for (Iterator<String> it = s.keys(); it.hasNext(); ) {
                String k = it.next();
                long t = s.optLong(k);
                if (now - t < 2L * 24 * 3600 * 1000) pruned.put(k, t);
            }
            p.edit().putString("sent", pruned.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    private JSONObject sent() {
        try {
            return new JSONObject(p.getString("sent", "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }
}
