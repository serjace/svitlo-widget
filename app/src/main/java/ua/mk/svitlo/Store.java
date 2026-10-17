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
    static final String F_BEFORE = "n_before";
    static final String F_RETURN = "n_return";
    static final String F_EMERGENCY = "n_emerg";
    static final String F_SCHEDULE = "n_sched";

    private final SharedPreferences p;

    Store(Context ctx) {
        p = ctx.getApplicationContext().getSharedPreferences("svitlo", Context.MODE_PRIVATE);
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
        p.edit().remove("ns_" + domId).apply();
    }

    // ---- widgets ----

    Address widgetAddress(int widgetId) {
        long d = p.getLong("w_" + widgetId, -1);
        Address a = find(d);
        return a != null ? a : active();
    }

    void cycleWidget(int widgetId) {
        List<Address> list = addresses();
        if (list.isEmpty()) return;
        Address cur = widgetAddress(widgetId);
        int idx = 0;
        for (int i = 0; i < list.size(); i++) {
            if (cur != null && list.get(i).domId == cur.domId) idx = i;
        }
        Address next = list.get((idx + 1) % list.size());
        p.edit().putLong("w_" + widgetId, next.domId).apply();
    }

    void removeWidget(int widgetId) {
        p.edit().remove("w_" + widgetId).apply();
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
        return Math.min(p.getInt("lead", 30), 30);
    }

    void setLead(int minutes) {
        p.edit().putInt("lead", minutes).apply();
    }

    /**
     * How often to download fresh data while the screen is on, in minutes. Fixed at 10:
     * these checks never wake a sleeping phone, so they cost almost no battery.
     */
    int interval() {
        return 10;
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

    long lastError() {
        return p.getLong("c_err", 0);
    }

    void setLastError(long t) {
        p.edit().putLong("c_err", t).apply();
    }

    String updatedLabel() {
        long f = fetchedAt();
        if (f == 0) return "Дані ще не завантажено";
        ZonedDateTime t = Instant.ofEpochMilli(f).atZone(Schedule.ZONE);
        boolean today = t.toLocalDate().equals(LocalDate.now(Schedule.ZONE));
        String s = "Оновлено " + t.format(DateTimeFormatter.ofPattern(today ? "HH:mm" : "dd.MM HH:mm"));
        if (lastError() > f) s += " · немає звʼязку";
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
