package ua.mk.svitlo;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class ScheduleNewsTest {
    @Test public void firstEmptyTomorrowIsSilent() {
        assertFalse(ScheduleNews.relevant("", new int[48], 0));
    }
    @Test public void emptyUpdatesStaySilent() {
        assertFalse(ScheduleNews.relevant(Arrays.toString(new int[48]), new int[48], 0));
    }
    @Test public void possibleOutagesAreNewsToo() {
        int[] slots = new int[48]; slots[36] = Schedule.PROBABLY;
        assertTrue(ScheduleNews.relevant("", slots, 0));
    }
    @Test public void cancellationIsNews() {
        int[] previous = new int[48]; previous[36] = Schedule.OFF;
        assertTrue(ScheduleNews.relevant(Arrays.toString(previous), new int[48], 0));
    }
    @Test public void pastOutagesDoNotCreateCancellationNews() {
        int[] previous = new int[48]; previous[10] = Schedule.OFF;
        assertFalse(ScheduleNews.relevant(Arrays.toString(previous), new int[48], 24));
    }
    @Test public void disappearedScheduleDoesNotMeanCancellation() {
        int[] previous = new int[48]; previous[36] = Schedule.OFF;
        int[] unknown = new int[48]; Arrays.fill(unknown, -1);
        assertFalse(ScheduleNews.relevant(Arrays.toString(previous), null, 0));
        assertFalse(ScheduleNews.relevant(Arrays.toString(previous), unknown, 0));
    }
}
