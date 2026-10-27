package ua.mk.svitlo;

/** Shared timing policy: randomized intervals, with an unjittered check at each new Kyiv day. */
final class RefreshPlan {
    static final int DEFAULT_MINUTES = 30;
    static final int JITTER_SECONDS = 60;

    static int interval(int minutes) {
        return minutes == 5 || minutes == 10 || minutes == 15 || minutes == 30
                ? minutes : DEFAULT_MINUTES;
    }

    static long nextAt(long now, long lastAttempt, long savedNext, int minutes, int offsetSeconds) {
        // First launch needs data immediately. A clock rollback must not stall checks for hours.
        if (lastAttempt <= 0) return now;
        if (lastAttempt > now) return after(now, minutes, offsetSeconds);
        if (savedNext > 0) return Math.min(savedNext, nextDayAt(lastAttempt));
        return after(lastAttempt, minutes, offsetSeconds);
    }

    static long after(long attempt, int minutes, int offsetSeconds) {
        int offset = Math.max(-JITTER_SECONDS, Math.min(JITTER_SECONDS, offsetSeconds));
        return Math.min(attempt + interval(minutes) * 60_000L + offset * 1_000L,
                nextDayAt(attempt));
    }

    static long nextDayAt(long attempt) {
        return java.time.Instant.ofEpochMilli(attempt).atZone(Schedule.ZONE).toLocalDate()
                .plusDays(1).atStartOfDay(Schedule.ZONE).toInstant().toEpochMilli();
    }
}
