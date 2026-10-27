package ua.mk.svitlo;

import org.junit.Test;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import static org.junit.Assert.*;

public class ScheduleTest {
    private final LocalDate today = LocalDate.of(2026, 10, 27);
    private Address address() {
        Address a = new Address(); a.domId = 1; a.gpvId = 7; return a;
    }
    @Test public void freshlyFetchedEmptyActiveListMatchesWebsiteForTodayOnly() {
        Schedule s = new Schedule(); s.hasData = true;
        s.includeDefaultToday(today, today);
        assertArrayEquals(new int[48], s.slots(today, 7));
        assertFalse(s.hasDay(today.plusDays(1)));
        assertNull(s.slots(today.plusDays(1), 7));
        Status status = Status.compute(s, address(), today.atTime(10, 39).atZone(Schedule.ZONE));
        assertEquals(Status.GREEN, status.level);
        assertEquals("Світло є", status.title);
        assertEquals(1, DayOutages.entries(s, address(), today, today.atTime(10, 39).atZone(Schedule.ZONE)).size());
    }
    @Test public void failedOrStaleFetchDoesNotInventTodaysSchedule() {
        Schedule s = new Schedule(); s.hasData = true;
        s.includeDefaultToday(today.minusDays(1), today);
        assertNull(s.slots(today, 7));
        s.hasData = false;
        s.includeDefaultToday(today, today);
        assertNull(s.slots(today, 7));
    }
    @Test public void existingOutageScheduleIsNeverOverwritten() {
        Schedule s = new Schedule(); s.hasData = true;
        int[] slots = new int[48]; slots[22] = Schedule.OFF;
        s.days.put(today, new HashMap<>()); s.days.get(today).put(7, slots);
        s.includeDefaultToday(today, today);
        assertEquals(Schedule.OFF, s.slots(today, 7)[22]);
    }
    @Test public void todaysDefaultDoesNotChangePublishedTomorrow() {
        Schedule s = new Schedule(); s.hasData = true;
        int[] slots = new int[48]; slots[18] = Schedule.PROBABLY;
        s.days.put(today.plusDays(1), new HashMap<>()); s.days.get(today.plusDays(1)).put(7, slots);
        s.includeDefaultToday(today, today);
        assertArrayEquals(new int[48], s.slots(today, 7));
        assertEquals(Schedule.PROBABLY, s.slots(today.plusDays(1), 7)[18]);
    }
    @Test public void knownWorksStillOverrideTheDefaultGreenDay() {
        Schedule s = new Schedule(); s.hasData = true; s.includeDefaultToday(today, today);
        Works.Work work = new Works.Work(); work.id = 1; work.type = Works.PLANNED;
        work.start = today.atTime(9, 0).atZone(Schedule.ZONE).toEpochSecond();
        work.end = today.atTime(18, 0).atZone(Schedule.ZONE).toEpochSecond();
        s.works.put(1L, Collections.singletonList(work));
        Status status = Status.computeForApp(s, address(), today.atTime(10, 39).atZone(Schedule.ZONE));
        assertEquals(Status.YELLOW, status.level);
        assertEquals("Планові роботи", status.title);
        assertEquals(2, status.remaining().size());
        assertArrayEquals(new int[]{21, 36, Timeline.MAYBE, 2}, status.remaining().get(0));
        assertArrayEquals(new int[]{36, 48, Timeline.ON, 0}, status.remaining().get(1));
    }
    @Test public void missingHouseQueueDoesNotBecomeKnown() {
        Schedule s = new Schedule(); s.hasData = true; s.includeDefaultToday(today, today);
        Address a = address(); a.gpvId = 0;
        assertEquals(Status.UNKNOWN, Status.compute(s, a, today.atTime(10, 39).atZone(Schedule.ZONE)).level);
    }
}
