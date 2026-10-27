package ua.mk.svitlo;

import java.net.SocketTimeoutException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** Safe diagnostic metadata: never includes URLs, response bodies or exception messages. */
final class DataProblem {
    static final String HTTP = "http", TIMEOUT = "timeout", CONNECTION = "connection",
            OFFLINE = "offline", FORMAT = "format", PAUSED = "paused";
    final String kind;
    final int code;
    final long retryAt;

    DataProblem(String kind, int code, long retryAt) {
        this.kind = kind; this.code = code; this.retryAt = retryAt;
    }

    static DataProblem from(Exception e) {
        if (e instanceof Api.Failure) return ((Api.Failure) e).problem;
        if (e instanceof SocketTimeoutException || e instanceof InterruptedException) {
            return new DataProblem(TIMEOUT, 0, 0);
        }
        if (e instanceof org.json.JSONException || e instanceof IllegalArgumentException
                || e instanceof java.time.format.DateTimeParseException) {
            return new DataProblem(FORMAT, 200, 0);
        }
        return new DataProblem(CONNECTION, 0, 0);
    }

    String label() {
        switch (kind) {
            case OFFLINE: return "Немає інтернету";
            case TIMEOUT: return "Сервер не відповідає вчасно";
            case FORMAT: return "Не вдалося прочитати дані";
            case PAUSED: return "Сервер просить зачекати";
            case HTTP:
                if (code == 429) return "Перевірки тимчасово обмежені";
                if (code == 401 || code == 403) return "Сервер відхилив запит";
                if (code >= 500) return "Помилка сервера";
                return "Не вдалося отримати дані";
            default: return "Помилка з’єднання";
        }
    }

    static long retryAt(String header, long now) {
        if (header == null || header.trim().isEmpty()) return 0;
        String value = header.trim();
        try {
            if (value.matches("[0-9]+")) {
                return Math.addExact(now, Math.multiplyExact(Long.parseLong(value), 1000L));
            }
            long at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
            return at > now ? at : 0;
        } catch (Exception ignored) { return 0; }
    }
}
