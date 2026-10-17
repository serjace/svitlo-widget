package ua.mk.svitlo;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Background download, run by Android when there is internet. */
public class SyncJob extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try {
                Updater.tick(getApplicationContext(), true);
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
