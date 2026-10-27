package ua.mk.svitlo;

import org.junit.Test;
import org.json.JSONObject;
import java.time.Instant;
import java.util.Arrays;
import static org.junit.Assert.*;

public class DataHealthTest {
    private final MemoryPreferences memory = new MemoryPreferences();
    private final DataHealth health = new DataHealth(memory.preferences);
    private final long now = Instant.parse("2026-10-27T10:00:00Z").toEpochMilli();

    @Test public void errorKeepsLastSuccessAndDoesNotStoreRawMessages() throws Exception {
        health.record("gpv", null, 3, now);
        health.record("gpv", new DataProblem(DataProblem.HTTP, 403, 0), 0, now + 1000);
        JSONObject state = new JSONObject(memory.preferences.getString("health_gpv", "{}"));
        assertEquals(now, state.getLong("success"));
        assertEquals(now + 1000, state.getLong("attempt"));
        assertTrue(DataHealth.describe(state).contains("HTTP 403"));
        assertFalse(DataHealth.describe(state).contains("IP"));
    }
    @Test public void successfulEmptyListClearsEarlierError() throws Exception {
        health.record("gpv", new DataProblem(DataProblem.HTTP, 503, 0), 0, now);
        health.record("gpv", null, 0, now + 1000);
        JSONObject state = new JSONObject(memory.preferences.getString("health_gpv", "{}"));
        assertNull(DataHealth.problem(state));
        assertTrue(DataHealth.describe(state).contains("записів: 0"));
    }
    @Test public void retryDelayPersistsAndCannotBeShortenedByConcurrentFailure() {
        health.defer(DataHealth.OFF, 429, now + 60_000);
        health.defer(DataHealth.OFF, 503, now + 10_000);
        DataHealth reopened = new DataHealth(memory.preferences);
        assertEquals(now + 60_000, reopened.pauseUntil(DataHealth.OFF));
        assertEquals(429, reopened.pauseCode(DataHealth.OFF));
        assertEquals(0, reopened.pauseUntil(DataHealth.WORKS));
    }
    @Test public void reportExportsOnlyAllowedFieldsAndSeparatesHouseResults() throws Exception {
        long house = 987654321;
        memory.preferences.edit().putString("addresses", "PRIVATE_NAME PRIVATE_STREET")
                .putString("health_gpv", new JSONObject().put("attempt", now).put("success", now)
                        .put("kind", "").put("code", 200).put("url", "PRIVATE_URL")
                        .put("ip", "203.0.113.17").toString()).apply();
        health.record("work_" + house, new DataProblem(DataProblem.HTTP, 429, 0), 0, now);
        String report = health.reportFor("1.0.test", 30, now, now, now + 30 * 60_000,
                Arrays.asList(house, house + 1), now);
        assertTrue(report.contains("ГПВ та стани черг"));
        assertTrue(report.contains("адреса 1")); assertTrue(report.contains("адреса 2"));
        assertTrue(report.contains("HTTP 429"));
        for (String secret : new String[]{"PRIVATE_NAME", "PRIVATE_STREET", "PRIVATE_URL", "203.0.113.17", "987654321"}) {
            assertFalse(report.contains(secret));
        }
    }
}
