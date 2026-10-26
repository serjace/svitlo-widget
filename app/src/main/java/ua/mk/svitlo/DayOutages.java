package ua.mk.svitlo;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** One chronological list of the day's known outages, retaining each source's details. */
final class DayOutages {
    static final class Entry {
        long start, end;
        int level;
        String time, label;
        boolean current, past;
    }

    static List<Entry> entries(Schedule sch, Address address, LocalDate day, ZonedDateTime now) {
        List<Entry> result = new ArrayList<>();
        long start = day.atStartOfDay(Schedule.ZONE).toEpochSecond();
        long stop = day.plusDays(1).atStartOfDay(Schedule.ZONE).toEpochSecond();
        long sec = now.toEpochSecond();
        int[] queue = address.gpvId > 0 ? sch.slots(day, address.gpvId) : null;
        if (queue != null) {
            int i = 0;
            while (i < queue.length) {
                int level = Timeline.level(queue[i]), end = i + 1;
                while (end < queue.length && Timeline.level(queue[end]) == level) end++;
                if (level == Timeline.MAYBE || level == Timeline.OFF) {
                    Entry e = new Entry();
                    e.start = Status.timeOf(day, i).toEpochSecond();
                    e.end = Status.timeOf(day, end).toEpochSecond();
                    e.level = level;
                    e.time = Status.hm(i) + " – " + Status.hm(end);
                    e.label = (level == Timeline.OFF ? "Відключення за ГПВ" : "Можливе відключення за ГПВ")
                            + " · черга " + address.gpvName;
                    result.add(e);
                }
                i = end;
            }
        }
        for (Works.Work w : Works.events(sch.works(address.domId))) {
            boolean overdue = w.overdue(sec);
            long end = overdue ? Math.max(w.end, sec + 1) : w.end;
            if (w.start >= stop || end <= start) continue;
            Entry e = new Entry();
            e.start = Math.max(start, w.start);
            e.end = overdue ? -1 : Math.min(stop, end);
            e.level = w.emergency() ? Timeline.OFF : Timeline.MAYBE;
            e.time = (w.start < start ? "00:00" : Works.hm(w.start))
                    + (overdue ? "" : " – " + (end >= stop ? "24:00" : Works.hm(end)));
            e.label = w.emergency() ? "Аварійне відключення" : "Планові роботи · можливе відключення";
            if (w.start < start) e.label += " · продовження";
            if (overdue) e.label += " · завершення уточнюється";
            if (!w.reason.isEmpty()) e.label += "\n" + w.reason;
            result.add(e);
        }
        if (day.equals(now.toLocalDate())) {
            if (address.gavId > 0 && sch.isEnabled(address.gavId)) {
                result.add(emergency(sec, "ГАВ · черга " + address.gavName));
            }
            if (address.sgavId > 0 && sch.isEnabled(address.sgavId)) {
                result.add(emergency(sec, "СГАВ · черга " + address.sgavName));
            }
        }
        result.sort(Comparator.comparingLong(e -> e.start));
        for (Entry e : result) {
            e.past = e.end >= 0 && e.end <= sec;
            e.current = e.start <= sec && (e.end < 0 || sec < e.end);
        }
        return result;
    }

    static String emptyText(Schedule sch, Address address, LocalDate day) {
        return address.gpvId > 0 && sch.slots(day, address.gpvId) != null
                ? "Відключень не заплановано" : "Розклад ще не опубліковано";
    }

    private static Entry emergency(long now, String queue) {
        Entry e = new Entry();
        e.start = now; e.end = -1; e.level = Timeline.OFF;
        e.time = "Зараз"; e.label = "Аварійне відключення · " + queue;
        return e;
    }
}
