package ua.mk.svitlo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Wakes up on schedule (and after phone restart) to refresh widgets and notifications. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        final PendingResult pr = goAsync();
        final Context app = ctx.getApplicationContext();
        final boolean isTick = Updater.ACTION_TICK.equals(intent.getAction())
                || Updater.ACTION_CHECK.equals(intent.getAction());
        new Thread(() -> {
            try {
                Updater.tick(app, false);
                Store st = new Store(app);
                long due = (st.interval() - 1) * 60 * 1000L;
                if (!isTick || System.currentTimeMillis() - st.fetchedAt() > due) {
                    Updater.requestFetch(app);
                }
            } finally {
                pr.finish();
            }
        }).start();
    }
}
