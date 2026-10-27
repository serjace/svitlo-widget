package ua.mk.svitlo;

import android.content.SharedPreferences;
import org.json.JSONObject;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/** Local diagnostics and persisted server cooldowns. Export only explicitly selected safe fields. */
final class DataHealth {
    static final String OFF = "off", WORKS = "works";
    private static final Object LOCK = new Object();
    private final SharedPreferences p;

    DataHealth(SharedPreferences preferences) { p = preferences; }

    private JSONObject read(String key) {
        try { return new JSONObject(p.getString("health_" + key, "{}")); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    void record(String key, DataProblem problem, int count, long now) {
        synchronized (LOCK) {
            JSONObject record = read(key);
            try {
                record.put("attempt", now);
                record.put("kind", problem == null ? "" : problem.kind);
                record.put("code", problem == null ? 200 : problem.code);
                if (problem == null) { record.put("success", now); record.put("count", count); }
                p.edit().putString("health_" + key, record.toString()).apply();
            } catch (Exception ignored) {}
        }
    }

    long pauseUntil(String origin) { return p.getLong("health_pause_" + origin, 0); }

    int pauseCode(String origin) { return p.getInt("health_pause_code_" + origin, 0); }

    void defer(String origin, int code, long until) {
        synchronized (LOCK) {
            if (until <= pauseUntil(origin)) return;
            p.edit().putLong("health_pause_" + origin, until)
                    .putInt("health_pause_code_" + origin, code).apply();
        }
    }

    String problemLabel(Store store, long now) {
        if (pauseUntil(OFF) > now) return "Сервер просить зачекати";
        if (pauseUntil(WORKS) > now) return "Роботи: сервер просить зачекати";
        DataProblem problem = problem(read("gpv"));
        if (problem != null) return problem.label();
        for (Address a : store.addresses()) {
            problem = problem(read("work_" + a.domId));
            if (problem != null) return "Роботи: " + problem.label();
        }
        if (store.lastError() > store.fetchedAt()) return "Не вдалося оновити дані";
        return "";
    }

    static DataProblem problem(JSONObject record) {
        String kind = record.optString("kind");
        return kind.isEmpty() ? null : new DataProblem(kind, record.optInt("code"), 0);
    }

    static String describe(JSONObject record) {
        if (!record.has("attempt")) return "Ще немає діагностики";
        DataProblem problem = problem(record);
        String state = problem == null ? "Отримано · записів: " + record.optInt("count") : problem.label();
        int code = record.optInt("code");
        if (code > 0) state += " · HTTP " + code;
        return state + "\nСпроба: " + time(record.optLong("attempt"))
                + "\nУспішно: " + time(record.optLong("success"));
    }

    String report(Store store, String version, long now) {
        java.util.List<Long> houses = new java.util.ArrayList<>();
        for (Address a : store.addresses()) houses.add(a.domId);
        return reportFor(version, store.interval(), p.getLong("fetch_attempt", 0), store.fetchedAt(),
                houses.isEmpty() ? 0 : Math.max(now, store.nextFetchAt(now)), houses, now);
    }

    String reportFor(String version, int interval, long attempt, long success, long next,
                     java.util.List<Long> houses, long now) {
        StringBuilder b = new StringBuilder("Світло · версія ").append(version)
                .append("\nІнтервал: ").append(interval).append(" хв")
                .append("\nОстання спроба оновлення: ").append(time(attempt))
                .append("\nОстаннє оновлення ГПВ: ").append(time(success))
                .append("\nНаступна автоперевірка: ")
                .append(houses.isEmpty() ? "Немає адрес" : time(next));
        b.append("\n\nГПВ та стани черг\n").append(describe(read("gpv")));
        int number = 0;
        for (long house : houses) {
            // Ordinals identify multiple results without exporting names, addresses or house IDs.
            b.append("\n\nРоботи й аварії · адреса ").append(++number).append("\n")
                    .append(describe(read("work_" + house)));
        }
        if (pauseUntil(OFF) > now) b.append("\n\nГПВ: повторний запит не раніше ").append(time(pauseUntil(OFF)));
        if (pauseUntil(WORKS) > now) b.append("\n\nРоботи: повторний запит не раніше ").append(time(pauseUntil(WORKS)));
        return b.toString();
    }

    private static String time(long value) {
        return value <= 0 ? "—" : Instant.ofEpochMilli(value).atZone(Schedule.ZONE)
                .format(DateTimeFormatter.ofPattern("dd.MM HH:mm:ss"));
    }
}
