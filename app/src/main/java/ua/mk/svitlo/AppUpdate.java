package ua.mk.svitlo;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Finds new versions on GitHub, downloads them and opens Android's installer. */
final class AppUpdate {
    static final String LATEST = "https://api.github.com/repos/serjace/svitlo-widget/releases/latest";
    static final String ACTION_DOWNLOAD = "ua.mk.svitlo.DOWNLOAD_UPDATE";

    private AppUpdate() {}

    static int installedCode(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    static String installedName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    static boolean available(Context ctx, Store st) {
        return st.updateUrl() != null && st.updateCode() > installedCode(ctx);
    }

    /** Asks GitHub for the newest release. Uses the network: call off the main thread. */
    static void check(Context ctx, Store st) {
        st.markUpdateChecked();
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(LATEST).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "SvitloWidget/1.0 (Android)");
            String body;
            try {
                if (c.getResponseCode() != 200) return;
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    body = out.toString("UTF-8");
                }
            } finally {
                c.disconnect();
            }
            JSONObject o = new JSONObject(body);
            String tag = o.optString("tag_name");          // "v1.0.12"
            int code = Integer.parseInt(tag.substring(tag.lastIndexOf('.') + 1));
            String url = null;
            JSONArray assets = o.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url");
                }
            }
            if (url == null) return;
            st.saveUpdate(code, url);
            if (code > installedCode(ctx) && st.updateNotified() < code) {
                st.setUpdateNotified(code);
                Notifier.showUpdate(ctx, code);
            }
        } catch (Exception ignored) {
        }
    }

    static File apkFile(Context ctx) {
        return new File(ctx.getExternalFilesDir("updates"), "svitlo.apk");
    }

    /** Starts downloading the new version; UpdateReceiver opens the installer when it's done. */
    static void download(Context ctx) {
        Store st = new Store(ctx);
        String url = st.updateUrl();
        if (url == null) return;
        File f = apkFile(ctx);
        if (f.exists()) //noinspection ResultOfMethodCallIgnored
            f.delete();
        DownloadManager dm = ctx.getSystemService(DownloadManager.class);
        if (dm == null) return;
        DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url))
                .setTitle("Світло 1.0." + st.updateCode())
                .setDescription("Завантаження оновлення")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(ctx, "updates", "svitlo.apk");
        st.setDownloadId(dm.enqueue(r));
    }

    static Intent installIntent(Context ctx) {
        Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".files", apkFile(ctx));
        return new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
