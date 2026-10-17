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
        final boolean isTick = Updater.ACTION_TICK.equals(intent.getAction());
        new Thread(() -> {
            try {
                Updater.tick(app, false);
                Store st = new Store(app);
                if (!isTick || System.currentTimeMillis() - st.fetchedAt() > 12 * 60 * 1000L) {
                    Updater.requestFetch(app);
                }
            } finally {
                pr.finish();
            }
        }).start();
    }
}
