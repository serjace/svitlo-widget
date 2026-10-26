package ua.mk.svitlo;

/** Shared timing policy for automatic requests; never changes local status/alert times. */
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
        if (savedNext > 0) return savedNext;
        return after(lastAttempt, minutes, offsetSeconds);
    }

    static long after(long attempt, int minutes, int offsetSeconds) {
        int offset = Math.max(-JITTER_SECONDS, Math.min(JITTER_SECONDS, offsetSeconds));
        return attempt + interval(minutes) * 60_000L + offset * 1_000L;
    }
}
