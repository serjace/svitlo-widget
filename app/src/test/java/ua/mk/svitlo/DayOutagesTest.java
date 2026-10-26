package ua.mk.svitlo;

import org.junit.Test;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import static org.junit.Assert.*;

public class DayOutagesTest {
    private final LocalDate day = LocalDate.of(2026, 10, 26);
    private final ZonedDateTime now = day.atTime(12, 0).atZone(Schedule.ZONE);
    private Address address() {
        Address a = new Address(); a.domId = 1; a.gpvId = 7; a.gpvName = "3.2"; return a;
    }
    private Schedule schedule(int[] slots) {
        Schedule s = new Schedule(); s.hasData = true;
        if (slots != null) { s.days.put(day, new HashMap<>()); s.days.get(day).put(7, slots); }
        return s;
    }
    private Works.Work work(long id, LocalDate startDay, int startHour, LocalDate endDay, int endHour) {
        Works.Work w = new Works.Work(); w.id = id; w.type = Works.PLANNED;
        w.start = startDay.atTime(startHour, 0).atZone(Schedule.ZONE).toEpochSecond();
        w.end = endDay.atTime(endHour, 0).atZone(Schedule.ZONE).toEpochSecond();
        w.reason = "Repair details"; return w;
    }
    @Test public void unpublishedQueueShowsOnlyKnownWorkOnce() {
        Schedule s = schedule(null);
        s.works.put(1L, Collections.singletonList(work(1, day, 9, day, 17)));
        List<DayOutages.Entry> entries = DayOutages.entries(s, address(), day, now);
        assertEquals(1, entries.size());
        assertEquals("09:00 – 17:00", entries.get(0).time);
        assertTrue(entries.get(0).label.contains("Repair details"));
        assertTrue(entries.get(0).current);
        int[] grid = Works.knownDay(s.works(1), day, now);
        assertEquals(-1, grid[0]); assertEquals(Schedule.PROBABLY, grid[18]); assertEquals(-1, grid[34]);
    }
    @Test public void publishedQueueIncludesGreenPeriodsAroundOutagesAndWorks() {
        int[] slots = new int[48]; Arrays.fill(slots, 14, 16, Schedule.OFF); slots[36] = Schedule.PROBABLY;
        Schedule s = schedule(slots);
        s.works.put(1L, Collections.singletonList(work(1, day, 9, day, 17)));
        List<DayOutages.Entry> entries = DayOutages.entries(s, address(), day, now);
        assertEquals(7, entries.size());
        assertEquals("00:00 – 07:00", entries.get(0).time);
        assertEquals(Timeline.ON, entries.get(0).level);
        assertEquals("07:00 – 08:00", entries.get(1).time);
        assertTrue(entries.get(1).past);
        assertEquals("08:00 – 09:00", entries.get(2).time);
        assertEquals("09:00 – 17:00", entries.get(3).time);
        assertTrue(entries.get(3).current);
        assertEquals("17:00 – 18:00", entries.get(4).time);
        assertEquals("18:00 – 18:30", entries.get(5).time);
        assertTrue(entries.get(5).label.contains("Можливе відключення за ГПВ"));
        assertEquals("18:30 – 24:00", entries.get(6).time);
    }
    @Test public void confirmedAndScheduledOffSlotsFormOneQueuePeriod() {
        int[] slots = new int[48]; slots[18] = Schedule.OFF; slots[19] = Schedule.SURE_OFF;
        List<DayOutages.Entry> entries = DayOutages.entries(schedule(slots), address(), day, now);
        assertEquals(3, entries.size()); assertEquals("09:00 – 10:00", entries.get(1).time);
    }
    @Test public void overnightWorksAppearInBothDaysAndEndAtMidnightDoesNotLeak() {
        Schedule s = schedule(null);
        s.works.put(1L, Collections.singletonList(work(1, day, 23, day.plusDays(1), 2)));
        assertEquals("23:00 – 24:00", DayOutages.entries(s, address(), day, now).get(0).time);
        DayOutages.Entry next = DayOutages.entries(s, address(), day.plusDays(1), now).get(0);
        assertEquals("00:00 – 02:00", next.time); assertTrue(next.label.contains("продовження"));
        s.works.put(1L, Collections.singletonList(work(2, day, 23, day.plusDays(1), 0)));
        assertTrue(DayOutages.entries(s, address(), day.plusDays(1), now).isEmpty());
    }
    @Test public void duplicateEmergencyUsesLatestProviderRecordOnce() {
        Schedule s = schedule(null);
        Works.Work a = work(1, day, 9, day, 18); a.type = Works.EMERGENCY;
        Works.Work b = work(2, day, 10, day, 16); b.type = Works.EMERGENCY; b.updated = b.end;
        s.works.put(1L, Arrays.asList(a, b));
        List<DayOutages.Entry> entries = DayOutages.entries(s, address(), day, now);
        assertEquals(1, entries.size()); assertEquals("09:00 – 16:00", entries.get(0).time);
    }
    @Test public void emptyPublishedAndUnpublishedDaysAreDifferent() {
        Schedule s = schedule(null);
        assertTrue(DayOutages.entries(s, address(), day, now).isEmpty());
        assertEquals("Розклад ще не опубліковано", DayOutages.emptyText(s, address(), day));
        s = schedule(new int[48]);
        List<DayOutages.Entry> published = DayOutages.entries(s, address(), day, now);
        assertEquals(1, published.size());
        assertEquals(Timeline.ON, published.get(0).level);
        assertEquals("00:00 – 24:00", published.get(0).time);
        assertTrue(published.get(0).current);
        assertEquals("Відключень не заплановано", DayOutages.emptyText(s, address(), day));
    }
    @Test public void activeEmergencyQueueIsShownOnlyTodayWithoutInventedEnd() {
        Schedule s = schedule(null); Address a = address(); a.gavId = 10; a.gavName = "2"; s.enabled.put(10, true);
        DayOutages.Entry e = DayOutages.entries(s, a, day, now).get(0);
        assertTrue(e.current); assertEquals(-1, e.end); assertEquals("Зараз", e.time);
        assertTrue(DayOutages.entries(s, a, day.plusDays(1), now).isEmpty());
    }

    @Test public void screenshotTodayHasCurrentGreenBeforeEveningOutage() {
        int[] slots = new int[48]; slots[42] = Schedule.PROBABLY;
        Arrays.fill(slots, 43, 48, Schedule.OFF);
        ZonedDateTime afternoon = day.atTime(14, 4).atZone(Schedule.ZONE);
        List<DayOutages.Entry> entries = DayOutages.entries(schedule(slots), address(), day, afternoon);
        assertEquals(3, entries.size());
        assertEquals("00:00 – 21:00", entries.get(0).time);
        assertEquals(Timeline.ON, entries.get(0).level);
        assertTrue(entries.get(0).current);
        assertEquals("21:00 – 21:30", entries.get(1).time);
        assertEquals("21:30 – 24:00", entries.get(2).time);
    }

    @Test public void screenshotTomorrowHasGreenOnBothSidesOfPlannedWorks() {
        int[] slots = new int[48]; slots[0] = Schedule.PROBABLY;
        Schedule s = schedule(slots);
        s.works.put(1L, Collections.singletonList(work(1, day, 9, day, 18)));
        List<DayOutages.Entry> entries = DayOutages.entries(s, address(), day, now.minusDays(1));
        assertEquals(4, entries.size());
        assertEquals("00:00 – 00:30", entries.get(0).time);
        assertEquals("00:30 – 09:00", entries.get(1).time);
        assertEquals(Timeline.ON, entries.get(1).level);
        assertEquals("09:00 – 18:00", entries.get(2).time);
        assertEquals("18:00 – 24:00", entries.get(3).time);
        assertEquals(Timeline.ON, entries.get(3).level);
        for (DayOutages.Entry e : entries) assertFalse(e.current);
    }

    @Test public void missingQueueNeverInventsGreenPeriods() {
        Address a = address(); a.gpvId = 0;
        Schedule s = schedule(new int[48]);
        s.works.put(1L, Collections.singletonList(work(1, day, 9, day, 18)));
        List<DayOutages.Entry> entries = DayOutages.entries(s, a, day, now);
        assertEquals(1, entries.size()); assertEquals(Timeline.MAYBE, entries.get(0).level);
    }
}
