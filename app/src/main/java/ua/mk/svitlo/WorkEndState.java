package ua.mk.svitlo;

/** Per-event end reminders. A deadline is an estimate; a later provider update is separate news. */
final class WorkEndState {
    long estimatedEnd;
    boolean recordedEnd;

    // 0 = no news, 1 = estimated deadline, 2 = provider updated the end record.
    int observe(Works.Work work, long now) {
        if (work.end > now) {
            recordedEnd = false;
            return 0;
        }
        boolean recorded = work.updated >= work.end;
        if (recorded) {
            if (recordedEnd) return 0;
            recordedEnd = true;
            estimatedEnd = work.end;
            return now - Math.max(work.end, work.updated) <= 20 * 60 ? 2 : 0;
        }
        if (estimatedEnd == work.end) return 0;
        estimatedEnd = work.end;
        return now - work.end <= 20 * 60 ? 1 : 0;
    }
}
