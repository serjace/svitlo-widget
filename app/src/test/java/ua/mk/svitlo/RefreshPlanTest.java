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

    @Test public void everyIntervalStopsAtKyivMidnightRegardlessOfJitter() {
        long late = local("2026-10-28T23:59:30");
        long midnight = local("2026-10-29T00:00:00");
        for (int minutes : new int[]{5, 10, 15, 30}) {
            for (int jitter : new int[]{-60, 0, 60}) {
                assertEquals(midnight, RefreshPlan.after(late, minutes, jitter));
            }
        }
        assertEquals(local("2026-10-28T23:54:00"),
                RefreshPlan.after(local("2026-10-28T23:50:00"), 5, -60));
    }

    @Test public void savedDeadlineFromPreviousVersionIsCappedAndRemainsDueAfterRestart() {
        long last = local("2026-10-28T23:55:00");
        long saved = local("2026-10-29T00:25:00");
        long midnight = local("2026-10-29T00:00:00");
        for (String time : new String[]{"2026-10-28T23:59:00", "2026-10-29T00:00:00",
                "2026-10-29T00:08:00", "2026-10-30T09:00:00"}) {
            assertEquals(midnight, RefreshPlan.nextAt(local(time), last, saved, 30, 60));
        }
    }

    @Test public void midnightAttemptStartsANewCycleAndConcurrentTriggerIsNotDue() {
        long midnight = local("2026-10-29T00:00:00");
        long next = RefreshPlan.after(midnight, 30, 42);
        assertEquals(midnight + 30 * 60_000L + 42_000L, next);
        assertEquals(next, RefreshPlan.nextAt(midnight + 1_000, midnight, next, 30, -60));
        assertTrue(next > midnight + 1_000);
        // A failed attempt also reserves this deadline; there is no retry loop at midnight.
        long delayed = local("2026-10-29T00:08:00");
        assertEquals(delayed + 30 * 60_000L - 20_000L, RefreshPlan.after(delayed, 30, -20));
    }

    @Test public void midnightUsesCalendarDatesAcrossDstAndYearBoundaries() {
        for (String day : new String[]{"2026-03-29", "2026-10-25", "2026-12-31"}) {
            java.time.LocalDate date = java.time.LocalDate.parse(day);
            long attempt = date.atTime(23, 59).atZone(Schedule.ZONE).toInstant().toEpochMilli();
            long midnight = date.plusDays(1).atStartOfDay(Schedule.ZONE).toInstant().toEpochMilli();
            assertEquals(midnight, RefreshPlan.after(attempt, 30, 60));
            assertEquals(midnight, RefreshPlan.nextDayAt(date.atStartOfDay(Schedule.ZONE)
                    .toInstant().toEpochMilli()));
        }
    }

    private static long local(String dateTime) {
        return java.time.LocalDateTime.parse(dateTime).atZone(Schedule.ZONE).toInstant().toEpochMilli();
    }
}
