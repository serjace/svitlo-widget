package ua.mk.svitlo;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;

/** "Download" tap on the update notification, and "download finished" from Android. */
public class UpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (AppUpdate.ACTION_DOWNLOAD.equals(action)) {
            Notifier.cancelUpdate(ctx);
            AppUpdate.download(ctx);
        } else if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(action)) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            Store st = new Store(ctx);
            if (id == -1 || id != st.downloadId()) return;
            if (!succeeded(ctx, id)) {
                Notifier.showUpdateFailed(ctx);
                return;
            }
            Intent install = AppUpdate.installIntent(ctx);
            Notifier.showInstall(ctx, install);
            try {
                ctx.startActivity(install);   // works when the app is open; otherwise the notification is used
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean succeeded(Context ctx, long id) {
        DownloadManager dm = ctx.getSystemService(DownloadManager.class);
        if (dm == null) return false;
        try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
            if (c == null || !c.moveToFirst()) return false;
            int col = c.getColumnIndex(DownloadManager.COLUMN_STATUS);
            return col >= 0 && c.getInt(col) == DownloadManager.STATUS_SUCCESSFUL;
        }
    }
}
