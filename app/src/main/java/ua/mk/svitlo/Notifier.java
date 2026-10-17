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
        nm.createNotificationChannel(out);
        nm.createNotificationChannel(sch);
    }

    static void show(Context ctx, String channel, String key, String title, String text, int color) {
        createChannels(ctx);
        if (Build.VERSION.SDK_INT >= 33
                && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        Intent open = new Intent(ctx, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
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
