package ua.mk.svitlo;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Works out "is there light now" for an address, and formats times. */
final class Status {
    static final int UNKNOWN = -1, GREEN = 0, YELLOW = 1, ORANGE = 2, RED = 3;

    int level = UNKNOWN;
    String title = "";
    String detail = "";

    static int color(int level) {
        switch (level) {
            case GREEN: return 0xFF43A047;
            case YELLOW: return 0xFFFBC02D;
            case ORANGE: return 0xFFFB8C00;
            case RED: return 0xFFE53935;
            default: return 0xFF9E9E9E;
        }
    }

    /** Colour of one half-hour cell in the timeline. */
    static int cellColor(int code) {
        switch (code) {
            case Schedule.ENABLE: return color(GREEN);
            case Schedule.PROBABLY: return color(YELLOW);
            case Schedule.OFF: return color(ORANGE);
            case Schedule.SURE_OFF: return color(RED);
            default: return color(UNKNOWN);
        }
    }

    static int nowIndex(ZonedDateTime now) {
        return now.getHour() * 2 + now.getMinute() / 30;
    }

    static float nowFraction(ZonedDateTime now) {
        return (now.getHour() * 60 + now.getMinute()) / 1440f;
    }

    /** "14:30" for a slot index (0..96, counting from today 00:00). */
    static String hm(int idx) {
        if (idx > 0 && idx % 48 == 0) return "24:00";
        int i = idx % 48;
        return String.format(Locale.US, "%02d:%02d", i / 2, (i % 2) * 30);
    }

    static String range(int start, int end) {
        return hm(start) + "–" + hm(end) + (start >= 48 ? " (завтра)" : "");
    }

    static ZonedDateTime timeOf(LocalDate today, int idx) {
        return ZonedDateTime.of(today.plusDays(idx / 48).atTime((idx % 48) / 2, (idx % 2) * 30), Schedule.ZONE);
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
            b.append("\nМожливі відключення: ");
            for (int i = 0; i < maybe.size(); i++) {
                if (i > 0) b.append(", ");
                b.append(range(maybe.get(i)[0], maybe.get(i)[1]));
            }
        }
        return b.toString();
    }

    static Status compute(Schedule sch, Address a, ZonedDateTime now) {
        Status s = new Status();
        if (a == null) {
            s.title = "Додайте адресу";
            s.detail = "Відкрийте застосунок і оберіть адресу";
            return s;
        }
        if (!sch.hasData) {
            s.title = "Немає даних";
            s.detail = "Перевірте інтернет і натисніть «Оновити»";
            return s;
        }
        LocalDate today = now.toLocalDate();
        int[] c = combined(sch, a, today);
        int ni = nowIndex(now);
        int code = c[ni];
        boolean gav = a.gavId > 0 && sch.isEnabled(a.gavId);
        boolean sgav = a.sgavId > 0 && sch.isEnabled(a.sgavId);
        boolean live = a.gpvId > 0 && sch.isEnabled(a.gpvId);

        if (gav || sgav) {
            s.level = RED;
            s.title = "Світла немає";
            String which = gav && sgav ? "ГАВ " + a.gavName + ", СГАВ " + a.sgavName
                    : gav ? "ГАВ " + a.gavName : "СГАВ " + a.sgavName;
            s.detail = "Аварійне відключення (" + which + ")";
        } else if (live || code == Schedule.SURE_OFF) {
            s.level = RED;
            s.title = "Світла немає";
            if (code >= Schedule.OFF) {
                s.detail = "Відключення до " + hm(runEnd(c, ni, Schedule.OFF, Schedule.SURE_OFF));
            } else {
                s.detail = "Діє відключення черги " + a.gpvName;
            }
        } else if (code == Schedule.OFF) {
            s.level = ORANGE;
            s.title = "Ймовірно, світла немає";
            s.detail = "Заплановане відключення до " + hm(runEnd(c, ni, Schedule.OFF, Schedule.SURE_OFF));
        } else if (code == Schedule.PROBABLY) {
            s.level = YELLOW;
            s.title = "Можливе відключення";
            s.detail = "Світло може зникнути до " + hm(runEnd(c, ni, Schedule.PROBABLY, Schedule.PROBABLY));
        } else {
            s.level = GREEN;
            s.title = "Світло є";
            int j = ni + 1;
            while (j < 96 && c[j] >= 0 && c[j] < Schedule.OFF) j++;
            if (j < 96 && c[j] >= Schedule.OFF) {
                s.detail = "Наступне відключення: " + range(j, runEnd(c, j, Schedule.OFF, Schedule.SURE_OFF));
            } else if (j < 96) {
                s.detail = "Сьогодні відключень більше немає";
            } else {
                s.detail = "Відключень за графіком немає";
            }
        }
        return s;
    }
}
