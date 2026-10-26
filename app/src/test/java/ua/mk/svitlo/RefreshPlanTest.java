package ua.mk.svitlo;

import org.junit.Test;
import static org.junit.Assert.*;

public class RefreshPlanTest {
    private final long start = 1_800_000_000_000L;

    @Test public void supportedIntervalsKeepExistingChoicesAndDefaultToThirty() {
        for (int m : new int[]{5, 10, 15, 30}) assertEquals(m, RefreshPlan.interval(m));
        assertEquals(30, RefreshPlan.interval(0));
        assertEquals(30, RefreshPlan.interval(-1));
        assertEquals(30, RefreshPlan.interval(60));
    }
    @Test public void jitterStaysWithinOneMinuteAtEveryInterval() {
        for (int m : new int[]{5, 10, 15, 30}) {
            for (int seconds = -60; seconds <= 60; seconds++) {
                long next = RefreshPlan.after(start, m, seconds);
                assertTrue(next >= start + (m - 1) * 60_000L);
                assertTrue(next <= start + (m + 1) * 60_000L);
                assertTrue(next > start);
            }
        }
        assertEquals(start + 29 * 60_000L, RefreshPlan.after(start, 30, -60));
        assertEquals(start + 31 * 60_000L, RefreshPlan.after(start, 30, 60));
    }
    @Test public void repeatedAppAndJobChecksReuseTheSameDeadline() {
        long saved = RefreshPlan.after(start, 30, 42);
        for (int elapsed = 1; elapsed < 30; elapsed++) {
            long next = RefreshPlan.nextAt(start + elapsed * 60_000L, start, saved, 30, -60);
            assertEquals(saved, next);
        }
        assertFalse(start + 15 * 60_000L >= saved); // 15-minute background fallback must not fetch.
    }
    @Test public void eachAttemptPlansItsOwnNextIntervalIncludingFailures() {
        long first = RefreshPlan.after(start, 30, 45);
        long second = RefreshPlan.after(first, 30, -20);
        assertEquals(start + 30 * 60_000L + 45_000L, first);
        assertEquals(first + 30 * 60_000L - 20_000L, second);
    }
    @Test public void changingIntervalReanchorsToLastAttempt() {
        long now = start + 8 * 60_000L;
        long shorter = RefreshPlan.nextAt(now, start, 0, 5, 10);
        assertTrue(shorter < now);
        long longer = RefreshPlan.nextAt(now, start, 0, 30, -10);
        assertEquals(start + 30 * 60_000L - 10_000L, longer);
    }
    @Test public void firstLaunchIsImmediateAndClockRollbackDoesNotStallRequests() {
        assertEquals(start, RefreshPlan.nextAt(start, 0, 0, 30, 60));
        long rollback = start - 6 * 3600_000L;
        assertEquals(rollback + 30 * 60_000L + 5_000L,
                RefreshPlan.nextAt(rollback, start, start + 30 * 60_000L, 30, 5));
    }
}
