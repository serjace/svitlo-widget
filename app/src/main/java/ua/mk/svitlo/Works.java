package ua.mk.svitlo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Outages that Миколаївобленерго registers by hand on https://www.energy.mk.ua/vidklyuchennya/:
 * emergencies (with the reason and an estimated time the power is back) and planned repairs.
 * They come on top of the hourly schedule. House ids are the same as on off.energy.mk.ua.
 */
final class Works {
    private static final String URL = "https://www.energy.mk.ua/outage/api/v1/outage";
    static final int EMERGENCY = 1, PLANNED = 2;
    /** An emergency whose estimated end passed while its record was not updated stays shown
     *  ("час відновлення уточнюється") this long, but never past midnight. Dispatchers usually
     *  close a record with the real end time within half an hour. */
    private static final long WAIT_SEC = 2 * 3600;

    private Works() {}

    static final class Work {
        long id;
        int type;
        long start, end, updated;   // epoch seconds
        String reason = "";

        boolean emergency() {
            return type == EMERGENCY;
        }

        /** Until when this is shown as happening. */
        long shownUntil() {
            if (type != EMERGENCY || updated >= end) return end;   // closed with the real end time
            long midnight = Instant.ofEpochSecond(end).atZone(Schedule.ZONE).toLocalDate()
                    .plusDays(1).atStartOfDay(Schedule.ZONE).toEpochSecond();
            return Math.min(end + WAIT_SEC, midnight);
        }

        boolean showing(long now) {
            return start <= now && now < shownUntil();
        }

        /** The estimated end passed, the record wasn't updated: "час відновлення уточнюється". */
        boolean overdue(long now) {
            return showing(now) && now >= end;
        }
    }

    /** Today and tomorrow for one house: the raw answer of the website. */
    static String fetch(long domId) throws Exception {
        ZonedDateTime today = LocalDate.now(Schedule.ZONE).atStartOfDay(Schedule.ZONE);
        JSONObject body = new JSONObject();
        body.put("page", 1);
        body.put("perPage", 50);
        body.put("from", today.toEpochSecond());   // everything that overlaps these two days
        body.put("to", today.plusDays(2).toEpochSecond() - 1);
        body.put("house_id", domId);
        String json = Api.post(URL, body.toString());
        new JSONObject(json).getJSONArray("data");   // make sure it's the real answer
        return json;
    }

    static List<Work> parse(String json) {
        List<Work> r = new ArrayList<>();
        if (json == null) return r;
        try {
            JSONArray a = new JSONObject(json).getJSONArray("data");
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String t = o.optString("type");
                Work w = new Work();
                // "GAO" = the hourly schedule, which the app already has
                if ("AVARIYA".equals(t)) w.type = EMERGENCY;
                else if ("PLAN".equals(t)) w.type = PLANNED;
                else continue;
                w.id = o.optLong("id");
                w.start = Instant.parse(o.getString("start_at")).getEpochSecond();
                w.end = Instant.parse(o.getString("finish_at")).getEpochSecond();
                w.updated = Instant.parse(o.getString("updated_at")).getEpochSecond();
                w.reason = o.isNull("reason") ? "" : o.optString("reason").trim();
                if (w.end - w.start < 5 * 60) continue;   // switching over, a minute or two
                r.add(w);
            }
        } catch (Exception ignored) {
        }
        Collections.sort(r, (x, y) -> Long.compare(x.start, y.start));
        merge(r);
        return r;
    }

    /**
     * The same emergency is sometimes registered twice: announced ahead (09:00–18:00), then
     * recorded again when it really happened (10:24–16:23, closed with the real end). The record
     * updated last says how it is now: an older one is cut to its end and counts as closed with it.
     */
    private static void merge(List<Work> r) {
        int n = r.size();
        long[] end = new long[n], upd = new long[n];
        for (int i = 0; i < n; i++) {
            end[i] = r.get(i).end;
            upd[i] = r.get(i).updated;
        }
        for (int i = 0; i < n; i++) {
            Work w = r.get(i);
            for (int j = 0; j < n; j++) {
                Work x = r.get(j);
                if (i == j || upd[j] <= upd[i] || !sameEvent(w, end[i], x, end[j])) continue;
                if (end[j] < w.end) {
                    w.end = end[j];
                    w.updated = Math.max(w.updated, upd[j]);
                }
            }
        }
    }

    /** Two emergency records about the same outage: overlapping times, the same reason. */
    static boolean sameEvent(Work a, long aEnd, Work b, long bEnd) {
        if (!a.emergency() || !b.emergency() || a.start >= bEnd || b.start >= aEnd) return false;
        String x = a.reason.toLowerCase(), y = b.reason.toLowerCase();
        return x.isEmpty() || y.isEmpty() || x.equals(y);
    }

    static boolean sameWork(Work a, Work b) {
        if (a.type != b.type) return false;
        if (a.id == b.id) return true;
        return a.start < b.end && b.start < a.end
                && a.reason.trim().equalsIgnoreCase(b.reason.trim());
    }

    /** A provider may publish multiple records for one event. The latest update decides its end. */
    static List<Work> events(List<Work> source) {
        List<Work> result = new ArrayList<>();
        for (Work w : source) {
            Work copy = new Work();
            copy.id = w.id; copy.type = w.type; copy.start = w.start; copy.end = w.end;
            copy.updated = w.updated; copy.reason = w.reason;
            for (int i = result.size() - 1; i >= 0; i--) {
                Work old = result.get(i);
                if (!sameWork(old, copy)) continue;
                if (old.updated > copy.updated || (old.updated == copy.updated && old.end > copy.end)) {
                    copy.end = old.end; copy.updated = old.updated; copy.id = old.id;
                }
                copy.start = Math.min(copy.start, old.start);
                result.remove(i);
            }
            result.add(copy);
        }
        Collections.sort(result, (a, b) -> Long.compare(a.start, b.start));
        return result;
    }

    /** The emergency (or planned work) happening now; the one lasting longest if several. */
    static Work now(List<Work> ws, int type, long now) {
        Work best = null;
        for (Work w : ws) {
            if (w.type == type && w.showing(now) && (best == null || w.end > best.end)) best = w;
        }
        return best;
    }

    /** What starts later today or tomorrow. */
    static List<Work> coming(List<Work> ws, ZonedDateTime now) {
        long until = now.toLocalDate().plusDays(2).atStartOfDay(Schedule.ZONE).toEpochSecond();
        List<Work> r = new ArrayList<>();
        for (Work w : ws) if (w.start > now.toEpochSecond() && w.start < until) r.add(w);
        return r;
    }

    /** "18:00", "02:00 (завтра)" or "25.10 18:00". */
    static String at(long sec, ZonedDateTime now) {
        ZonedDateTime t = Instant.ofEpochSecond(sec).atZone(Schedule.ZONE);
        String hm = t.format(DateTimeFormatter.ofPattern("HH:mm"));
        LocalDate d = t.toLocalDate(), today = now.toLocalDate();
        if (d.equals(today)) return hm;
        if (d.equals(today.plusDays(1))) return hm + " (завтра)";
        return t.format(DateTimeFormatter.ofPattern("dd.MM")) + " " + hm;
    }

    static String hm(long sec) {
        return Instant.ofEpochSecond(sec).atZone(Schedule.ZONE).format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    /** Local-clock seconds from today's date, matching the schedule's 48 slots per day.
     * Elapsed seconds since midnight would shift slots on daylight-saving change days. */
    private static long localSeconds(long sec, LocalDate today) {
        ZonedDateTime local = Instant.ofEpochSecond(sec).atZone(Schedule.ZONE);
        return (local.toLocalDate().toEpochDay() - today.toEpochDay()) * 86400L
                + local.toLocalTime().toSecondOfDay();
    }

    /** Local half-hour slot of a moment, rounded down; may be < 0 or > 96. */
    static int slot(long sec, LocalDate today) {
        return (int) Math.floorDiv(localSeconds(sec, today), 1800L);
    }

    /** Same, rounded up (the first half-hour that is free again). */
    static int slotUp(long sec, LocalDate today) {
        return (int) -Math.floorDiv(-localSeconds(sec, today), 1800L);
    }

    /** Known works without a published queue schedule; all other hours stay unknown.
     * Null means there is no known work overlapping this day. */
    static int[] knownDay(List<Work> ws, LocalDate day, ZonedDateTime now) {
        int[] unknown = new int[48];
        java.util.Arrays.fill(unknown, -1);
        int[] result = paint(unknown, ws, day, now);
        for (int code : result) if (code >= 0) return result;
        return null;
    }

    /** One day's schedule with emergencies and planned works overlaid. */
    static int[] paint(int[] slots, List<Work> ws, LocalDate day, ZonedDateTime now) {
        int[] r = slots.clone();
        long nowSec = now.toEpochSecond();
        for (Work w : ws) {
            long end = w.end;
            if (w.overdue(nowSec)) end = Math.max(end, nowSec + 1);   // still not back: up to now
            int a = Math.max(0, slot(w.start, day)), b = Math.min(r.length, slotUp(end, day));
            for (int i = a; i < b; i++) {
                if (w.emergency()) r[i] = Math.max(r[i], Schedule.OFF);
                else if (r[i] < Schedule.PROBABLY) r[i] = Schedule.PROBABLY;
            }
        }
        return r;
    }

    /** Combined display periods, retaining the source and unknown gaps between known events. */
    static List<int[]> periods(int[] base, List<Work> ws, LocalDate day, ZonedDateTime now, int from) {
        int[] codes = paint(base, ws, day, now);
        int[] kinds = new int[codes.length];
        for (Work w : ws) {
            long end = w.overdue(now.toEpochSecond()) ? now.toEpochSecond() + 1 : w.end;
            int start = Math.max(0, slot(w.start, day));
            int stop = Math.min(codes.length, slotUp(end, day));
            for (int i = start; i < stop; i++) {
                if (w.emergency()) kinds[i] = 1;
                else if (kinds[i] == 0 && base[i] < Schedule.OFF) kinds[i] = 2;
            }
        }
        List<int[]> result = new ArrayList<>();
        int i = Math.max(0, from);
        while (i < codes.length && result.size() < 6) {
            int level = Timeline.level(codes[i]), end = i + 1;
            while (end < codes.length && Timeline.level(codes[end]) == level && kinds[end] == kinds[i]) end++;
            result.add(new int[]{i, end, level, kinds[i]});
            i = end;
        }
        return result;
    }
}
