package ua.mk.svitlo;

import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;
import static org.junit.Assert.*;

public class PayloadValidationTest {
    private String times() throws Exception {
        JSONArray rows = new JSONArray();
        for (int i = 0; i < 48; i++) rows.put(new JSONObject().put("id", i + 1)
                .put("start", String.format(Locale.US, "%02d:%02d:00", i / 2, (i % 2) * 30)));
        return rows.toString();
    }
    @Test public void emptyActiveAndWorksRepliesAreValid() throws Exception {
        Schedule.validatePayload(times(), "[]", "[]", "[]", "[]");
        Works.validatePayload("{\"data\":[]}");
    }
    @Test public void missingTimeSlotsAndMalformedSchedulesAreRejected() {
        assertThrows(Exception.class, () -> Schedule.validatePayload("[]", "[]", "[]", "[]", "[]"));
        assertThrows(Exception.class, () -> Schedule.validatePayload(times(), "{}", "[]", "[]", "[]"));
        assertThrows(Exception.class, () -> Schedule.validatePayload(times(), "[{\"from\":\"invalid\"}]", "[]", "[]", "[]"));
    }
    @Test public void unknownSlotTypeIsNotSilentlyTreatedAsGreen() throws Exception {
        JSONObject row = new JSONObject().put("time_series_id", 1).put("outage_queue_id", 7).put("type", "NEW_FORMAT");
        String active = new JSONArray().put(new JSONObject().put("from", "2026-10-27T00:00:00Z")
                .put("series", new JSONArray().put(row))).toString();
        assertThrows(Exception.class, () -> Schedule.validatePayload(times(), active, "[]", "[]", "[]"));
    }
    @Test public void malformedWorkDatesAreRejectedBeforeSavingCache() {
        assertThrows(Exception.class, () -> Works.validatePayload("{\"data\":[{\"type\":\"PLAN\",\"start_at\":\"invalid\"}]}"));
        assertThrows(Exception.class, () -> Works.validatePayload("{\"data\":{}}"));
    }
    @Test public void unknownTimeReferenceCannotSilentlyRemoveOutages() {
        String active = "[{\"from\":\"2026-10-27T00:00:00Z\",\"series\":[{\"time_series_id\":999,\"outage_queue_id\":7,\"type\":\"OFF\"}]}]";
        assertThrows(Exception.class, () -> Schedule.validatePayload(times(), active, "[]", "[]", "[]"));
    }
}
