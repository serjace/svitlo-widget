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
    /** "через 40 хв · до 16:00" — supporting line (app). */
    String line2 = "";
    /** One line for the 4×2 widget: "Вимкнуть о 14:00 · через 40 хв". */
    String detail = "";
    /** Very short, for the 2×2 widget: "до 14:00". */
    String shortDetail = "";
    int nowIdx;
    /** Upcoming periods: {start, end (-1 = unknown), level, emergency 0/1}, slot indexes from today 00:00. */
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
        String n = r[3] == 1 ? "Аварійне відключення" : Timeline.name(r[2]);
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
        else b.append("Світла не буде:\n").append(joinRanges(off));
        if (!maybe.isEmpty()) b.append("\nМожливі відключення:\n").append(joinRanges(maybe));
        return b.toString();
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
            from = ni + 1;
        } else if (code >= Schedule.OFF) {
            s.level = RED;
            s.title = "Світла немає";
            int end = runEnd(c, ni, Schedule.OFF, Schedule.SURE_OFF);
            String u = until(now, today, end);
            String l2 = "через " + u;
            if (end < 96 && c[end] == Schedule.PROBABLY) {
                l2 += " · потім можливе до " + hm(runEnd(c, end, Schedule.PROBABLY, Schedule.PROBABLY));
            }
            s.set("Зʼявиться о " + at(end), l2, "Зʼявиться о " + hm(end) + " · через " + u, "до " + hm(end));
        } else if (code == Schedule.PROBABLY) {
            s.level = YELLOW;
            s.title = "Можливе відключення";
            int e = runEnd(c, ni, Schedule.PROBABLY, Schedule.PROBABLY);
            String u = until(now, today, e);
            s.set("до " + at(e), "ще " + u + " · світло, ймовірно, є",
                    "до " + hm(e) + " · ще " + u, "до " + hm(e));
        } else {
            s.level = GREEN;
            s.title = "Світло є";
            int j = ni + 1;
            while (j < 96 && c[j] >= 0 && c[j] < Schedule.OFF) j++;
            int p = ni + 1;
            while (p < 96 && c[p] == Schedule.ENABLE) p++;
            if (j < 96 && c[j] >= Schedule.OFF) {
                String u = until(now, today, j);
                int e = runEnd(c, j, Schedule.OFF, Schedule.SURE_OFF);
                s.set("Вимкнуть о " + at(j), "через " + u + " · до " + hm(e),
                        "Вимкнуть о " + hm(j) + " · через " + u, "до " + hm(j));
            } else if (p < 96 && c[p] == Schedule.PROBABLY) {
                String u = until(now, today, p);
                s.set("Можливе відключення о " + at(p), "через " + u,
                        "Можливе о " + hm(p) + " · через " + u, "до " + hm(p));
            } else if (j < 96) {
                s.set("Сьогодні відключень більше немає", "", "Сьогодні відключень більше немає", "сьогодні без відключень");
            } else {
                s.set("Відключень за графіком немає", "", "Відключень за графіком немає", "без відключень");
            }
        }

        int i = from;
        while (i < 96 && c[i] >= 0 && s.next.size() < 6) {
            int lvl = Timeline.level(c[i]);
            int e = i;
            while (e < 96 && c[e] >= 0 && Timeline.level(c[e]) == lvl) e++;
            s.next.add(new int[]{i, e, lvl, 0});
            i = e;
        }
        return s;
    }
}
