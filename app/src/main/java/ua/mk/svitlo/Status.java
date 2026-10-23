package ua.mk.svitlo;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Works out "is there light now, and when does it change" for an address. */
final class Status {
    static final int UNKNOWN = Timeline.UNKNOWN, GREEN = Timeline.ON, YELLOW = Timeline.MAYBE, RED = Timeline.OFF;

    int level = UNKNOWN;
    /** "Світло є" — the big word. */
    String title = "";
    /** "Вимкнуть о 14:00" — what happens next (big, app). */
    String line1 = "";
    /** "через 40 хв" — supporting line (app). */
    String line2 = "";
    /** "Потім вимкнуть о 16:00" — what comes after that (app, smaller). */
    String line3 = "";
    /** How many of {@link #next} the big texts already describe (now + the next change). */
    int shown = 1;
    /** One line for the 4×2 widget: "Вимкнуть о 14:00 · через 40 хв". */
    String detail = "";
    /** Very short, for the 2×2 widget: "до 14:00". */
    String shortDetail = "";
    int nowIdx;
    /** An emergency or planned work from www.energy.mk.ua happening now (it decides the status), or null. */
    Works.Work work;
    /** Upcoming periods: {start, end (-1 = unknown), level, 0 / 1 emergency / 2 planned works},
     *  slot indexes from today 00:00. */
    final List<int[]> next = new ArrayList<>();

    /** Fixed accent colour (used for notifications). */
    static int color(int level) {
        switch (level) {
            case GREEN: return 0xFF2E7D32;
            case YELLOW: return 0xFFE0A800;
            case RED: return 0xFFD32F2F;
            default: return 0xFF9E9E9E;
        }
    }

    static int nowIndex(ZonedDateTime now) {
        return now.getHour() * 2 + now.getMinute() / 30;
    }

    /** "14:30" for a slot index (0..96, counting from today 00:00). */
    static String hm(int idx) {
        if (idx > 0 && idx % 48 == 0) return "24:00";
        int i = idx % 48;
        return String.format(Locale.US, "%02d:%02d", i / 2, (i % 2) * 30);
    }

    /** Like hm(), but marks times that are tomorrow. */
    static String at(int idx) {
        return idx > 48 ? hm(idx) + " (завтра)" : hm(idx);
    }

    static String range(int start, int end) {
        return hm(start) + "–" + hm(end) + (start >= 48 ? " (завтра)" : "");
    }

    static ZonedDateTime timeOf(LocalDate today, int idx) {
        return ZonedDateTime.of(today.plusDays(idx / 48).atTime((idx % 48) / 2, (idx % 2) * 30), Schedule.ZONE);
    }

    /** "43 хв", "2 год 15 хв" from now until the slot. */
    static String until(ZonedDateTime now, LocalDate today, int idx) {
        long mins = (Duration.between(now, timeOf(today, idx)).getSeconds() + 59) / 60;
        if (mins < 1) mins = 1;
        if (mins < 60) return mins + " хв";
        long h = mins / 60, m = mins % 60;
        return m == 0 ? h + " год" : h + " год " + m + " хв";
    }

    /** Today + tomorrow as 96 slots; -1 where tomorrow is not published yet. */
    static int[] combined(Schedule sch, Address a, LocalDate today) {
        int[] r = new int[96];
        LocalDate tomorrow = today.plusDays(1);
        int[] t = a.gpvId > 0 ? sch.slots(today, a.gpvId) : null;
        int[] m = null;
        if (sch.hasDay(tomorrow)) m = a.gpvId > 0 ? sch.slots(tomorrow, a.gpvId) : new int[48];
        for (int i = 0; i < 48; i++) {
            r[i] = t != null ? t[i] : Schedule.ENABLE;
            r[48 + i] = m != null ? m[i] : -1;
        }
        return r;
    }

    /** First slot after "from" whose code is outside min..max. */
    static int runEnd(int[] c, int from, int min, int max) {
        int j = from;
        while (j < c.length && c[j] >= min && c[j] <= max) j++;
        return j;
    }

    /** [start, end) pairs of consecutive slots with code in min..max. */
    static List<int[]> runs(int[] slots, int min, int max) {
        List<int[]> r = new ArrayList<>();
        int i = 0;
        while (i < slots.length) {
            if (slots[i] >= min && slots[i] <= max) {
                int e = runEnd(slots, i, min, max);
                r.add(new int[]{i, e});
                i = e;
            } else {
                i++;
            }
        }
        return r;
    }

    /** One-day summary used in notifications and in the app. */
    static String describeDay(int[] slots) {
        List<int[]> off = runs(slots, Schedule.OFF, Schedule.SURE_OFF);
        List<int[]> maybe = runs(slots, Schedule.PROBABLY, Schedule.PROBABLY);
        StringBuilder b = new StringBuilder();
        if (off.isEmpty()) {
            b.append("Відключень за графіком немає");
        } else {
            b.append("Відключення: ");
            for (int i = 0; i < off.size(); i++) {
                if (i > 0) b.append(", ");
                b.append(range(off.get(i)[0], off.get(i)[1]));
            }
        }
        if (!maybe.isEmpty()) {
            b.append("\nМожливі: ");
            for (int i = 0; i < maybe.size(); i++) {
                if (i > 0) b.append(", ");
                b.append(range(maybe.get(i)[0], maybe.get(i)[1]));
            }
        }
        return b.toString();
    }

    /** Periods after the current one (the current one is already shown big). */
    List<int[]> upcoming(int max) {
        List<int[]> r = new ArrayList<>();
        for (int[] p : next) {
            if (p[0] == nowIdx) continue;
            r.add(p);
            if (r.size() >= max) break;
        }
        return r;
    }

    /** Time column of a period row: "зараз–12:00", "12:30–24:00". */
    static String rowTime(int[] r, int nowIdx) {
        String start = r[0] == nowIdx ? "зараз" : hm(r[0]);
        return r[1] < 0 ? start : start + "–" + hm(r[1]);
    }

    /** Text column of a period row. */
    static String rowLabel(int[] r) {
        String n = r[3] == 1 ? "Аварійне відключення" : r[3] == 2 ? "Планові роботи" : Timeline.name(r[2]);
        if (r[0] >= 48) n += " · завтра";
        return n;
    }

    /** "08:00–10:00 і 18:00–20:00" */
    static String joinRanges(List<int[]> r) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < r.size(); i++) {
            if (i > 0) b.append(i == r.size() - 1 ? " і " : ", ");
            b.append(hm(r.get(i)[0])).append("–").append(hm(r.get(i)[1]));
        }
        return b.toString();
    }

    /** Big-text summary of a whole day (used for tomorrow). */
    static String daySummary(int[] slots) {
        List<int[]> off = runs(slots, Schedule.OFF, Schedule.SURE_OFF);
        List<int[]> maybe = runs(slots, Schedule.PROBABLY, Schedule.PROBABLY);
        StringBuilder b = new StringBuilder();
        if (off.isEmpty()) b.append("Відключень не заплановано");
        else b.append("Світло відсутнє:\n").append(joinRanges(off));
        if (!maybe.isEmpty()) b.append("\nМожливі відключення:\n").append(joinRanges(maybe));
        return b.toString();
    }

    /**
     * Short summary of today from the current half-hour to midnight (for the "schedule changed"
     * notification): "Світла не буде: зараз–16:00, 20:00–22:00\nМожливі відключення: 18:00–20:00".
     */
    static String restOfDay(int[] slots, int from) {
        int[] rest = slots.clone();
        for (int i = 0; i < from && i < rest.length; i++) rest[i] = Schedule.ENABLE;
        List<int[]> off = runs(rest, Schedule.OFF, Schedule.SURE_OFF);
        List<int[]> maybe = runs(rest, Schedule.PROBABLY, Schedule.PROBABLY);
        if (off.isEmpty() && maybe.isEmpty()) return "До кінця дня відключень немає";
        StringBuilder b = new StringBuilder();
        if (!off.isEmpty()) b.append("Світла не буде: ").append(restRanges(off, from));
        if (!maybe.isEmpty()) {
            if (b.length() > 0) b.append("\n");
            b.append("Можливі відключення: ").append(restRanges(maybe, from));
        }
        return b.toString();
    }

    private static String restRanges(List<int[]> r, int from) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < r.size(); i++) {
            if (i > 0) b.append(", ");
            int[] p = r.get(i);
            b.append(p[0] <= from ? "зараз" : hm(p[0])).append("–").append(hm(p[1]));
        }
        return b.toString();
    }

    /**
     * Fills the texts from the periods: now (title) → the next change (line1, with a countdown)
     * → the change after that (line3). Possible outages count as a change too.
     */
    private void describe(ZonedDateTime now, LocalDate today, int[] c) {
        if (next.isEmpty()) return;
        int[] cur = next.get(0);
        if (next.size() < 2) {
            shown = 1;
            String l;
            if (level == GREEN) {
                // were there outages (or possible ones) earlier today?
                boolean earlier = false;
                for (int k = 0; k < nowIdx && k < 48; k++) if (c[k] >= Schedule.PROBABLY) earlier = true;
                l = earlier ? "Сьогодні відключень більше немає" : "Сьогодні відключень не заплановано";
            } else {
                l = "до " + at(cur[1]) + (cur[1] < 96 ? " · далі графіка ще немає" : "");
            }
            set(l, "", l, l);
            return;
        }
        shown = 2;
        int[] nx = next.get(1);
        String p1 = change(nx[2], cur[2], nx[0]);
        String u = until(now, today, nx[0]);
        if (next.size() > 2) {
            int[] af = next.get(2);
            line3 = "Потім " + lower(change(af[2], nx[2], af[0]));
        }
        set(p1, "через " + u, p1 + " · через " + u, shortChange(nx[2], cur[2], nx[0]));
    }

    /** "Вимкнуть о 20:30", "Можливе відключення з 20:00", "Увімкнуть о 22:00". */
    static String change(int lvl, int prevLvl, int start) {
        String t = String.format(Locale.US, "%02d:%02d", (start % 48) / 2, (start % 2) * 30)
                + (start >= 48 ? " (завтра)" : "");
        if (lvl == RED) return "Вимкнуть о " + t;
        if (lvl == YELLOW) return "Можливе відключення з " + t;
        return prevLvl == RED ? "Увімкнуть о " + t : "Світло буде з " + t;
    }

    /** For the small square widget: "вимкнуть о 20:30", "можливе з 20:00". */
    static String shortChange(int lvl, int prevLvl, int start) {
        String t = hm(start % 48) + (start >= 48 ? " завтра" : "");
        if (lvl == RED) return "вимкнуть о " + t;
        if (lvl == YELLOW) return "можливе з " + t;
        return prevLvl == RED ? "увімкнуть о " + t : "світло з " + t;
    }

    static String lower(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /** Periods after those the big texts already describe (widget rows). */
    List<int[]> after(int max) {
        List<int[]> r = new ArrayList<>();
        for (int k = shown; k < next.size() && r.size() < max; k++) r.add(next.get(k));
        return r;
    }

    private void set(String line1, String line2, String detail, String shortDetail) {
        this.line1 = line1;
        this.line2 = line2;
        this.detail = detail;
        this.shortDetail = shortDetail;
    }

    static Status compute(Schedule sch, Address a, ZonedDateTime now) {
        Status s = new Status();
        if (a == null) {
            s.title = "Додайте адресу";
            s.set("Щоб бачити графік світла", "", "Відкрийте застосунок і оберіть адресу", "немає адреси");
            return s;
        }
        if (!sch.hasData) {
            s.title = "Немає даних";
            s.set("Перевірте інтернет", "і натисніть «Оновити»", "Перевірте інтернет", "немає даних");
            return s;
        }
        LocalDate today = now.toLocalDate();
        int[] c = combined(sch, a, today);
        int ni = nowIndex(now);
        s.nowIdx = ni;
        int code = c[ni];
        boolean gav = a.gavId > 0 && sch.isEnabled(a.gavId);
        boolean sgav = a.sgavId > 0 && sch.isEnabled(a.sgavId);
        // The website's "outage now" flag for ГПВ queues often stays on long after an outage
        // ends, so the hourly schedule alone decides. Only ГАВ/СГАВ emergency flags are used.
        int from = ni;   // where the regular list of periods starts
        long nowSec = now.toEpochSecond();
        List<Works.Work> ws = sch.works(a.domId);

        // An emergency registered for this house on www.energy.mk.ua: that's what is happening,
        // whatever the schedule says. Reason and the estimated time, no schedule texts.
        Works.Work em = Works.now(ws, Works.EMERGENCY, nowSec);
        if (em != null) {
            s.work = em;
            s.level = RED;
            s.title = "Аварійне відключення";
            boolean late = em.overdue(nowSec);
            String until = late ? "Час відновлення уточнюється" : "Орієнтовно до " + Works.at(em.end, now);
            s.set(until, em.reason, until, late ? "час відновлення уточнюється" : "до " + Works.at(em.end, now));
            int end = late ? -1 : Works.slotUp(em.end, today);
            s.next.add(new int[]{ni, end, RED, 1});
            if (end > ni) s.addPeriods(c, end);   // what the schedule says after it ("Найближчі години")
            return s;
        }

        if (!gav && !sgav && a.gpvId == 0) {
            // Without a queue we know nothing — never pretend there is light.
            s.title = "Черга невідома";
            s.set("Сайт не вказав черги для цієї адреси", "Змініть адресу в налаштуваннях",
                    "Черга невідома — змініть адресу", "черга невідома");
            return s;
        }

        if (gav || sgav) {
            s.level = RED;
            s.title = "Світла немає";
            String which = gav && sgav ? "ГАВ " + a.gavName + ", СГАВ " + a.sgavName
                    : gav ? "ГАВ " + a.gavName : "СГАВ " + a.sgavName;
            s.set("Аварійне відключення", which + " · час повернення невідомий",
                    "Аварійне відключення (" + which + ")", "аварійне");
            s.next.add(new int[]{ni, -1, RED, 1});
            s.shown = 1;
            from = ni + 1;
        } else if (code >= Schedule.OFF) {
            s.level = RED;
            s.title = "Світла немає";
        } else if (code == Schedule.PROBABLY) {
            s.level = YELLOW;
            s.title = "Можливе відключення";
        } else {
            s.level = GREEN;
            s.title = "Світло є";
        }

        s.addPeriods(c, from);

        // Planned works now (and the schedule doesn't say worse): their own status. The light
        // may go off for a while, not necessarily for the whole time.
        Works.Work pw = gav || sgav || code >= Schedule.OFF ? null : Works.now(ws, Works.PLANNED, nowSec);
        if (pw != null) {
            s.work = pw;
            s.level = YELLOW;
            s.title = "Планові роботи";
            String until = "Можливе відключення до " + Works.at(pw.end, now);
            s.set(until, pw.reason, until, "до " + Works.at(pw.end, now));
            s.shown = 1;
            if (s.next.size() > 1 && s.next.get(1)[2] == RED) {
                int[] nx = s.next.get(1);
                s.line3 = "За графіком: " + lower(change(RED, s.next.get(0)[2], nx[0]));
            }
            return s;
        }
        if (!gav && !sgav) s.describe(now, today, c);
        return s;
    }

    /** The schedule's periods from slot "from" on (up to 6 in all). */
    private void addPeriods(int[] c, int from) {
        int i = from;
        while (i < 96 && c[i] >= 0 && next.size() < 6) {
            int lvl = Timeline.level(c[i]);
            int e = i;
            while (e < 96 && c[e] >= 0 && Timeline.level(c[e]) == lvl) e++;
            next.add(new int[]{i, e, lvl, 0});
            i = e;
        }
    }
}
