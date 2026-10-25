package ua.mk.svitlo;

/** Schedule announcements concern outages, including cancellation of announced outages. */
final class ScheduleNews {
    private ScheduleNews() {}

    static boolean hasOutages(int[] slots, int from) {
        if (slots == null) return false;
        for (int i = Math.max(0, from); i < slots.length; i++) {
            if (slots[i] >= Schedule.PROBABLY) return true;
        }
        return false;
    }

    static boolean hadOutages(String previous, int from) {
        if (previous == null || !previous.startsWith("[") || !previous.endsWith("]")) return false;
        String[] codes = previous.substring(1, previous.length() - 1).split(",");
        try {
            for (int i = Math.max(0, from); i < codes.length; i++) {
                if (Integer.parseInt(codes[i].trim()) >= Schedule.PROBABLY) return true;
            }
        } catch (NumberFormatException ignored) {}
        return false;
    }

    static boolean relevant(String previous, int[] slots, int from) {
        if (slots == null) return false;
        boolean known = false;
        for (int i = Math.max(0, from); i < slots.length; i++) known |= slots[i] >= 0;
        return known && (hasOutages(slots, from) || hadOutages(previous, from));
    }
}
