package ua.mk.svitlo;

import org.junit.Test;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import static org.junit.Assert.*;

public class StatusTest {
    private final LocalDate day = LocalDate.of(2026, 10, 26);
    private Address address() {
        Address a = new Address(); a.domId = 1; a.gpvId = 7; a.gpvName = "3.2";
        return a;
    }
    private Schedule schedule(int[] today, int[] tomorrow) {
        Schedule s = new Schedule(); s.hasData = true;
        if (today != null) { s.days.put(day, new HashMap<>()); s.days.get(day).put(7, today); }
        if (tomorrow != null) { s.days.put(day.plusDays(1), new HashMap<>()); s.days.get(day.plusDays(1)).put(7, tomorrow); }
        return s;
    }
    private Works.Work work(LocalDate start, int hour, LocalDate end, int endHour) {
        Works.Work w = new Works.Work(); w.id = 1; w.type = Works.PLANNED;
        w.start = start.atTime(hour, 0).atZone(Schedule.ZONE).toEpochSecond();
        w.end = end.atTime(endHour, 0).atZone(Schedule.ZONE).toEpochSecond();
        w.reason = "Detailed repair reason";
        return w;
    }
    @Test public void tomorrowDoesNotLeakIntoTodaysBannerOrPeriods() {
        int[] tomorrow = new int[48]; Arrays.fill(tomorrow, 0, 8, Schedule.OFF);
        Schedule s = schedule(new int[48], tomorrow);
        Status result = Status.compute(s, address(), day.atTime(22, 0).atZone(Schedule.ZONE));
        assertEquals(Status.GREEN, result.level);
        assertEquals("Сьогодні відключень не заплановано", result.line1);
        assertTrue(result.upcoming(10).isEmpty());
        assertTrue(result.after(10).isEmpty());
        for (int[] p : result.next) assertTrue(p[1] <= 48);
    }
    @Test public void midnightUsesNewDayWithoutNeedingFreshData() {
        int[] tomorrow = new int[48]; Arrays.fill(tomorrow, 0, 8, Schedule.OFF);
        Status result = Status.compute(schedule(new int[48], tomorrow), address(),
                day.plusDays(1).atStartOfDay(Schedule.ZONE));
        assertEquals(Status.RED, result.level);
        assertTrue(result.line1.contains("04:00"));
    }
    @Test public void missingTodayIsNeverGreenEvenWhenTomorrowExists() {
        Status result = Status.compute(schedule(null, new int[48]), address(), day.atTime(12, 0).atZone(Schedule.ZONE));
        assertEquals(Status.UNKNOWN, result.level);
        assertEquals("", result.line1);
        assertTrue(result.upcoming(10).isEmpty());
    }
    @Test public void onlyKnownEventsAppearInUnpublishedDay() {
        Schedule s = schedule(null, null);
        s.works.put(1L, Collections.singletonList(work(day, 9, day, 17)));
        Status result = Status.compute(s, address(), day.atTime(8, 0).atZone(Schedule.ZONE));
        assertEquals(Status.UNKNOWN, result.level);
        assertFalse(result.line1.contains("невідом"));
        assertFalse(result.line3.contains("невідом"));
        assertEquals(1, result.upcoming(10).size());
        assertArrayEquals(new int[]{18, 34, Timeline.MAYBE, 2}, result.upcoming(10).get(0));
        assertTrue(result.after(10).isEmpty());
    }
    @Test public void futureWorkTomorrowDoesNotAppearToday() {
        Schedule s = schedule(new int[48], null);
        s.works.put(1L, Collections.singletonList(work(day.plusDays(1), 9, day.plusDays(1), 17)));
        Status result = Status.compute(s, address(), day.atTime(20, 0).atZone(Schedule.ZONE));
        assertTrue(result.upcoming(10).isEmpty());
        assertTrue(result.after(10).isEmpty());
    }
    @Test public void overnightRepairRemainsActiveAfterMidnight() {
        Schedule s = schedule(null, null);
        s.works.put(1L, Collections.singletonList(work(day, 23, day.plusDays(1), 2)));
        Status late = Status.compute(s, address(), day.atTime(23, 30).atZone(Schedule.ZONE));
        assertEquals(Status.YELLOW, late.level);
        assertTrue(late.line1.contains("02:00 (завтра)"));
        Status early = Status.compute(s, address(), day.plusDays(1).atTime(1, 0).atZone(Schedule.ZONE));
        assertEquals(Status.YELLOW, early.level);
        assertTrue(early.line1.contains("02:00"));
        assertFalse(early.line1.contains("завтра"));
    }
    @Test public void bothCardsUseCompactSourceSummaries() {
        Schedule s = schedule(new int[48], null);
        s.works.put(1L, Collections.singletonList(work(day, 9, day.plusDays(1), 17)));
        ZonedDateTime now = day.atTime(8, 0).atZone(Schedule.ZONE);
        String today = Status.cardSummary(s, address(), day, now);
        String tomorrow = Status.cardSummary(s, address(), day.plusDays(1), now);
        assertEquals("Планові роботи", today);
        assertEquals("Планові роботи\nГПВ ще не опубліковано", tomorrow);
        assertFalse(today.contains("Detailed"));
    }
    @Test public void queueAndRepairsAreBothNamed() {
        int[] today = new int[48]; today[18] = Schedule.OFF;
        Schedule s = schedule(today, null);
        s.works.put(1L, Collections.singletonList(work(day, 9, day, 17)));
        String text = Status.cardSummary(s, address(), day, day.atTime(8, 0).atZone(Schedule.ZONE));
        assertTrue(text.contains("ГПВ · черга 3.2"));
        assertTrue(text.contains("Планові роботи"));
        assertFalse(text.contains("не заплановано"));
    }

    @Test public void remainingStartsWithCurrentAndContainsNoPastOrTomorrow() {
        int[] today = new int[48]; Arrays.fill(today, 28, 32, Schedule.OFF);
        Status result = Status.computeForApp(schedule(today, new int[48]), address(),
                day.atTime(13, 15).atZone(Schedule.ZONE));
        assertEquals(3, result.remaining().size());
        assertArrayEquals(new int[]{26, 28, Timeline.ON, 0}, result.remaining().get(0));
        assertArrayEquals(new int[]{28, 32, Timeline.OFF, 0}, result.remaining().get(1));
        assertArrayEquals(new int[]{32, 48, Timeline.ON, 0}, result.remaining().get(2));
        assertEquals("зараз–14:00", Status.rowTime(result.remaining().get(0), result.nowIdx));
    }

    @Test public void noMoreChangesStillShowsCurrentStateUntilMidnight() {
        Status result = Status.computeForApp(schedule(new int[48], null), address(),
                day.atTime(22, 0).atZone(Schedule.ZONE));
        assertEquals(1, result.remaining().size());
        assertArrayEquals(new int[]{44, 48, Timeline.ON, 0}, result.remaining().get(0));
    }

    @Test public void unknownCurrentIsBriefAndFutureUnknownGapsAreOmitted() {
        Schedule s = schedule(null, null);
        s.works.put(1L, Collections.singletonList(work(day, 9, day, 17)));
        Status result = Status.computeForApp(s, address(), day.atTime(8, 0).atZone(Schedule.ZONE));
        assertEquals(2, result.remaining().size());
        assertArrayEquals(new int[]{16, -1, Timeline.UNKNOWN, 0}, result.remaining().get(0));
        assertArrayEquals(new int[]{18, 34, Timeline.MAYBE, 2}, result.remaining().get(1));
    }

    @Test public void remainingDoesNotMutateOvernightEmergencyUsedByWidgets() {
        Schedule s = schedule(null, null);
        Works.Work w = work(day, 23, day.plusDays(1), 2); w.type = Works.EMERGENCY;
        s.works.put(1L, Collections.singletonList(w));
        Status result = Status.compute(s, address(), day.atTime(23, 30).atZone(Schedule.ZONE));
        int end = result.next.get(0)[1];
        assertTrue(end > 48);
        assertEquals(48, result.remaining().get(0)[1]);
        assertEquals(end, result.next.get(0)[1]);
        assertTrue(result.shortDetail.contains("02:00 (завтра)"));
    }

    @Test public void appShowsAllRemainingPeriodsWithoutChangingWidgetLimits() {
        int[] today = new int[48];
        for (int i = 0; i < 48; i++) today[i] = i % 2 == 0 ? Schedule.ENABLE : Schedule.OFF;
        Schedule s = schedule(today, null);
        ZonedDateTime now = day.atStartOfDay(Schedule.ZONE);
        assertEquals(48, Status.computeForApp(s, address(), now).remaining().size());
        assertEquals(12, Status.compute(s, address(), now).next.size());
        s.works.put(1L, Collections.singletonList(work(day, 0, day.plusDays(1), 0)));
        assertEquals(48, Status.computeForApp(s, address(), now).remaining().size());
        assertEquals(6, Status.compute(s, address(), now).next.size());
    }
}
