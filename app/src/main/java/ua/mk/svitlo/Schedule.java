package ua.mk.svitlo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

/**
 * The hourly outage schedule (ГПВ) in 48 half-hour slots per day, plus the
 * "outage is happening right now" flags for every queue (ГПВ, ГАВ, СГАВ).
 */
final class Schedule {
    static final ZoneId ZONE = zone();

    static final int ENABLE = 0;    // Є світло
    static final int PROBABLY = 1;  // Електропостачання можливе
    static final int OFF = 2;       // Заплановане відключення
    static final int SURE_OFF = 3;  // Актуальне відключення

    final Map<LocalDate, Map<Integer, int[]>> days = new HashMap<>();
    final Map<Integer, Boolean> enabled = new HashMap<>();
    boolean hasData;

    private static ZoneId zone() {
        try {
            return ZoneId.of("Europe/Kyiv");
        } catch (Exception e) {
            try {
                return ZoneId.of("Europe/Kiev");
            } catch (Exception e2) {
                return ZoneId.systemDefault();
            }
        }
    }

    static int code(String type) {
        if ("SURE_OFF".equals(type)) return SURE_OFF;
        if ("OFF".equals(type)) return OFF;
        if ("PROBABLY_OFF".equals(type)) return PROBABLY;
        return ENABLE;
    }

    static Schedule load(Store st) {
        Schedule s = new Schedule();
        String ts = st.cache("ts");
        String act = st.cache("active");
        if (ts == null || act == null) return s;
        try {
            Map<Integer, Integer> slotOf = new HashMap<>();
            JSONArray t = new JSONArray(ts);
            for (int i = 0; i < t.length(); i++) {
                JSONObject o = t.getJSONObject(i);
                String[] hm = o.optString("start").split(":");
                if (hm.length < 2) continue;
                int idx = Integer.parseInt(hm[0]) * 2 + Integer.parseInt(hm[1]) / 30;
                if (idx >= 0 && idx < 48) slotOf.put(o.getInt("id"), idx);
            }

            JSONArray a = new JSONArray(act);
            for (int i = 0; i < a.length(); i++) {
                JSONObject item = a.getJSONObject(i);
                LocalDate d = Instant.parse(item.getString("from")).atZone(ZONE).toLocalDate();
                Map<Integer, int[]> day = s.days.get(d);
                if (day == null) {
                    day = new HashMap<>();
                    s.days.put(d, day);
                }
                JSONArray ser = item.optJSONArray("series");
                if (ser == null) continue;
                for (int j = 0; j < ser.length(); j++) {
                    JSONObject x = ser.getJSONObject(j);
                    Integer idx = slotOf.get(x.optInt("time_series_id"));
                    if (idx == null) continue;
                    int q = x.optInt("outage_queue_id");
                    int[] arr = day.get(q);
                    if (arr == null) {
                        arr = new int[48];
                        day.put(q, arr);
                    }
                    // same rule as the website: "актуальне" is never overwritten
                    if (arr[idx] != SURE_OFF) arr[idx] = code(x.optString("type"));
                }
            }

            for (String k : new String[]{"q1", "q2", "q3"}) {
                String j = st.cache(k);
                if (j == null) continue;
                JSONArray qa = new JSONArray(j);
                for (int i = 0; i < qa.length(); i++) {
                    JSONObject o = qa.getJSONObject(i);
                    s.enabled.put(o.optInt("id"), o.optInt("enabled") == 1);
                }
            }
            s.hasData = true;
        } catch (Exception e) {
            s.days.clear();
            s.enabled.clear();
            s.hasData = false;
        }
        return s;
    }

    boolean hasDay(LocalDate d) {
        return days.containsKey(d);
    }

    /** 48 slot codes for the queue on that day, or null if that day is not published. */
    int[] slots(LocalDate d, int queueId) {
        Map<Integer, int[]> day = days.get(d);
        if (day == null) return null;
        int[] arr = day.get(queueId);
        return arr != null ? arr.clone() : new int[48];
    }

    boolean isEnabled(int queueId) {
        Boolean b = enabled.get(queueId);
        return b != null && b;
    }
}
