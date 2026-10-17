package ua.mk.svitlo;

/**
 * The three states people see (Світло є / Можливе відключення / Відключення)
 * and the colours and icons that go with each.
 */
final class Timeline {
    static final int UNKNOWN = -1, ON = 0, MAYBE = 1, OFF = 2;

    private Timeline() {}

    /** Website slot code → state. "Заплановане" and "актуальне" both mean no power. */
    static int level(int code) {
        if (code < 0) return UNKNOWN;
        return code >= Schedule.OFF ? OFF : code;
    }

    static String name(int lvl) {
        switch (lvl) {
            case ON: return "Світло є";
            case MAYBE: return "Можливе відключення";
            case OFF: return "Відключення";
            default: return "Невідомо";
        }
    }

    static int containerColor(int lvl) {
        switch (lvl) {
            case ON: return R.color.st_on_container;
            case MAYBE: return R.color.st_maybe_container;
            case OFF: return R.color.st_off_container;
            default: return R.color.st_unknown_container;
        }
    }

    static int contentColor(int lvl) {
        switch (lvl) {
            case ON: return R.color.st_on_content;
            case MAYBE: return R.color.st_maybe_content;
            case OFF: return R.color.st_off_content;
            default: return R.color.st_unknown_content;
        }
    }

    static int strongColor(int lvl) {
        switch (lvl) {
            case ON: return R.color.st_on_strong;
            case MAYBE: return R.color.st_maybe_strong;
            case OFF: return R.color.st_off_strong;
            default: return R.color.st_unknown_strong;
        }
    }

    static int circleRes(int lvl) {
        switch (lvl) {
            case ON: return R.drawable.circle_on;
            case MAYBE: return R.drawable.circle_maybe;
            case OFF: return R.drawable.circle_off;
            default: return R.drawable.circle_unknown;
        }
    }

    static int iconRes(int lvl) {
        switch (lvl) {
            case ON: return R.drawable.ic_status_on;
            case MAYBE: return R.drawable.ic_status_maybe;
            case OFF: return R.drawable.ic_status_off;
            default: return R.drawable.ic_status_unknown;
        }
    }

    static int pillRes(int lvl) {
        switch (lvl) {
            case ON: return R.drawable.pill_on;
            case MAYBE: return R.drawable.pill_maybe;
            case OFF: return R.drawable.pill_off;
            default: return R.drawable.pill_unknown;
        }
    }
}
