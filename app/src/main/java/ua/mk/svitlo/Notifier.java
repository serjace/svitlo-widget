package ua.mk.svitlo;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

final class Notifier {
    static final String CH_OUTAGE = "outages";
    static final String CH_SCHEDULE = "schedule";
    static final String CH_UPDATE = "updates";
    private static final String KEY_UPDATE = "app-update";

    private Notifier() {}

    static void createChannels(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel out = new NotificationChannel(CH_OUTAGE, "Відключення світла",
                NotificationManager.IMPORTANCE_HIGH);
        out.setDescription("Попередження перед відключенням, повернення світла, аварійні відключення");
        NotificationChannel sch = new NotificationChannel(CH_SCHEDULE, "Зміни графіка",
                NotificationManager.IMPORTANCE_DEFAULT);
        sch.setDescription("Графік змінено або опубліковано графік на завтра");
        NotificationChannel upd = new NotificationChannel(CH_UPDATE, "Оновлення застосунку",
                NotificationManager.IMPORTANCE_DEFAULT);
        upd.setDescription("Нова версія застосунку «Світло»");
        nm.createNotificationChannel(out);
        nm.createNotificationChannel(sch);
        nm.createNotificationChannel(upd);
    }

    static void show(Context ctx, String channel, String key, String title, String text, int color) {
        Intent open = new Intent(ctx, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        post(ctx, channel, key, title, text, color, pi);
    }

    static void showUpdate(Context ctx, int versionCode) {
        Intent dl = new Intent(ctx, UpdateReceiver.class).setAction(AppUpdate.ACTION_DOWNLOAD);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 5, dl,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        post(ctx, CH_UPDATE, KEY_UPDATE, "Доступна нова версія 1.0." + versionCode,
                "Торкніться, щоб завантажити і встановити оновлення", 0xFF3A5BA9, pi);
    }

    static void showInstall(Context ctx, Intent install) {
        PendingIntent pi = PendingIntent.getActivity(ctx, 6, install,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        post(ctx, CH_UPDATE, KEY_UPDATE, "Оновлення завантажено",
                "Торкніться, щоб встановити нову версію", 0xFF3A5BA9, pi);
    }

    static void showUpdateFailed(Context ctx) {
        show(ctx, CH_UPDATE, KEY_UPDATE, "Не вдалося завантажити оновлення",
                "Відкрийте застосунок і спробуйте ще раз", 0xFFD32F2F);
    }

    static void cancelUpdate(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(KEY_UPDATE.hashCode());
    }

    private static void post(Context ctx, String channel, String key, String title, String text,
                             int color, PendingIntent pi) {
        createChannels(ctx);
        if (Build.VERSION.SDK_INT >= 33
                && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        Notification n = new Notification.Builder(ctx, channel)
                .setSmallIcon(R.drawable.ic_bolt)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setColor(color)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(key.hashCode(), n);
    }
}
