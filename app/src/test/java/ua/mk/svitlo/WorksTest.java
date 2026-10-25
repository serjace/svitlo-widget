package ua.mk.svitlo;

import org.junit.Test;
import static org.junit.Assert.*;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class WorksTest {
    private final LocalDate day = LocalDate.of(2026, 10, 26);
    private long at(LocalDate d, int h, int m) { return d.atTime(h, m).atZone(Schedule.ZONE).toEpochSecond(); }
    private ZonedDateTime now() { return day.minusDays(1).atTime(19, 0).atZone(Schedule.ZONE); }
    private Works.Work work(long id, int type, long start, long end) {
        Works.Work w = new Works.Work();
        w.id = id; w.type = type; w.start = start; w.end = end; w.updated = start - 3600; w.reason = "Ремонт";
        return w;
    }
    @Test public void unpublishedHoursStayUnknown() {
        Works.Work w = work(1, Works.PLANNED, at(day,9,0), at(day,17,0));
        int[] slots = Works.knownDay(Collections.singletonList(w), day, now());
        assertEquals(-1, slots[17]); assertEquals(Schedule.PROBABLY, slots[18]);
        assertEquals(Schedule.PROBABLY, slots[33]); assertEquals(-1, slots[34]);
        assertTrue(Status.partialDaySummary(slots, true).contains("09:00–17:00"));
        assertFalse(Status.partialDaySummary(slots, true).contains("Відключень не заплановано"));
        assertTrue(Status.partialDaySummary(slots, false).startsWith("Черга ГПВ невідома"));
    }
    @Test public void wholeDayHasNoOtherHoursNote() {
        Works.Work w = work(1, Works.PLANNED, at(day,0,0), at(day.plusDays(1),0,0));
        int[] slots = Works.knownDay(Collections.singletonList(w), day, now());
        assertFalse(Status.partialDaySummary(slots, true).contains("Інші години"));
    }
    @Test public void overnightWorkClipsToBothDays() {
        Works.Work w = work(1, Works.PLANNED, at(day,23,0), at(day.plusDays(1),2,0));
        int[] today = Works.knownDay(Collections.singletonList(w), day, now());
        int[] tomorrow = Works.knownDay(Collections.singletonList(w), day.plusDays(1), now());
        assertEquals(Schedule.PROBABLY,today[46]); assertEquals(-1,today[45]);
        assertEquals(Schedule.PROBABLY,tomorrow[3]); assertEquals(-1,tomorrow[4]);
        assertNull(Works.knownDay(Collections.singletonList(w),day.plusDays(2),now()));
    }
    @Test public void queueOutageTakesPriorityOverMaintenance() {
        int[] base = new int[48]; Arrays.fill(base,18,20,Schedule.OFF);
        Works.Work w = work(1,Works.PLANNED,at(day,9,0),at(day,11,0));
        List<int[]> p = Works.periods(base,Collections.singletonList(w),day,now(),18);
        assertArrayEquals(new int[]{18,20,Timeline.OFF,0},p.get(0));
        assertArrayEquals(new int[]{20,22,Timeline.MAYBE,2},p.get(1));
        assertArrayEquals(new int[]{22,48,Timeline.ON,0},p.get(2));
    }
    @Test public void combinedTimelineRetainsUnknownAfterWorks() {
        int[] base = new int[48]; Arrays.fill(base,-1);
        Works.Work w = work(1,Works.PLANNED,at(day,9,0),at(day,17,0));
        List<int[]> p=Works.periods(base,Collections.singletonList(w),day,now(),0);
        assertArrayEquals(new int[]{0,18,Timeline.UNKNOWN,0},p.get(0));
        assertArrayEquals(new int[]{18,34,Timeline.MAYBE,2},p.get(1));
        assertArrayEquals(new int[]{34,48,Timeline.UNKNOWN,0},p.get(2));
    }
    @Test public void duplicateUsesLatestEndAndDoesNotMutateSource() {
        Works.Work a=work(1,Works.EMERGENCY,at(day,9,0),at(day,18,0));
        Works.Work b=work(2,Works.EMERGENCY,at(day,10,0),at(day,16,0)); b.updated=at(day,16,5);
        List<Works.Work> events=Works.events(Arrays.asList(a,b));
        assertEquals(1,events.size()); assertEquals(at(day,16,0),events.get(0).end);
        assertEquals(at(day,9,0),events.get(0).start); assertEquals(at(day,18,0),a.end);
    }
    @Test public void estimatedEndIsOnceAndExtensionRearmsIt() {
        Works.Work w=work(1,Works.PLANNED,at(day,9,0),at(day,17,0));
        WorkEndState s=new WorkEndState();
        assertEquals(0,s.observe(w,at(day,16,59)));
        assertEquals(1,s.observe(w,at(day,17,0)));
        assertEquals(0,s.observe(w,at(day,17,1)));
        w.end=at(day,18,0);
        assertEquals(0,s.observe(w,at(day,17,2)));
        assertEquals(1,s.observe(w,at(day,18,0)));
    }
    @Test public void updatedEndIsSeparateNewsOnce() {
        Works.Work w=work(1,Works.EMERGENCY,at(day,9,0),at(day,17,0));
        WorkEndState s=new WorkEndState(); assertEquals(1,s.observe(w,at(day,17,0)));
        w.updated=at(day,17,5);
        assertEquals(2,s.observe(w,at(day,17,6))); assertEquals(0,s.observe(w,at(day,17,7)));
    }
    @Test public void staleDeadlinesDoNotNotifyLater() {
        Works.Work w=work(1,Works.PLANNED,at(day,9,0),at(day,17,0));
        WorkEndState s=new WorkEndState(); assertEquals(0,s.observe(w,at(day,18,0)));
        assertEquals(0,s.observe(w,at(day,18,1)));
    }
    @Test public void clockChangeUsesWallClockSlots() {
        LocalDate fall=LocalDate.of(2026,10,25),spring=LocalDate.of(2026,3,29);
        for(LocalDate d:Arrays.asList(fall,spring)) {
            assertEquals(18,Works.slot(at(d,9,0),d));
            assertEquals(36,Works.slotUp(at(d,18,0),d));
            assertEquals(48,Works.slot(at(d.plusDays(1),0,0),d));
        }
    }
}
