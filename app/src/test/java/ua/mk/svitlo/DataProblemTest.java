package ua.mk.svitlo;

import org.junit.Test;
import java.time.Instant;
import static org.junit.Assert.*;

public class DataProblemTest {
    private final long now = Instant.parse("2026-10-27T10:00:00Z").toEpochMilli();
    @Test public void retryAfterAcceptsSecondsAndHttpDate() {
        assertEquals(now + 120_000, DataProblem.retryAt("120", now));
        assertEquals(now + 120_000, DataProblem.retryAt("Tue, 27 Oct 2026 10:02:00 GMT", now));
    }
    @Test public void invalidAndExpiredRetryAfterDoNotCreateLongPauses() {
        for (String header : new String[]{null, "", "-5", "garbage", "99999999999999999999999",
                "Tue, 27 Oct 2026 09:59:00 GMT"}) assertEquals(0, DataProblem.retryAt(header, now));
    }
    @Test public void accessErrorsNeverClaimIpBlockAndDifferentiateRateLimits() {
        String denied = new DataProblem(DataProblem.HTTP, 403, 0).label();
        assertFalse(denied.contains("IP")); assertFalse(denied.contains("заблок"));
        assertNotEquals(denied, new DataProblem(DataProblem.HTTP, 429, 0).label());
        assertEquals(DataProblem.TIMEOUT, DataProblem.from(new java.net.SocketTimeoutException()).kind);
        assertEquals(DataProblem.CONNECTION, DataProblem.from(new java.net.UnknownHostException("PRIVATE_URL")).kind);
    }
}
