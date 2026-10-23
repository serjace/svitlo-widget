package ua.mk.svitlo;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Background download, run by Android when there is internet. */
public class SyncJob extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try {
                // The regular 15-minute job doesn't download again what was just downloaded
                // (by the screen-on check or the app): widgets and notifications are still updated.
                Store st = new Store(getApplicationContext());
                long fresh = Math.min(5, st.interval() - 1) * 60 * 1000L;
                boolean recent = System.currentTimeMillis() - st.fetchedAt() < fresh;
                boolean fetch = params.getJobId() != Updater.JOB_PERIODIC || !recent;
                Updater.tick(getApplicationContext(), fetch);
            } finally {
                jobFinished(params, false);
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
