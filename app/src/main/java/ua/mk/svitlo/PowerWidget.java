package ua.mk.svitlo;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The big 4×2 widget (SmallWidget reuses this for the square one).
 * Background colour = status. Each widget shows the address chosen when it was placed
 * (WidgetConfigActivity), in normal or large text. Tapping opens the app on that address.
 */
public class PowerWidget extends AppWidgetProvider {
    static final String ACTION_SWITCH = "ua.mk.svitlo.SWITCH";
    private static final int[] ROWS = {R.id.w_row1, R.id.w_row2, R.id.w_row3, R.id.w_row4};

    /** Tap on ⇄: show the next saved address on this widget. */
    @Override
    public void onReceive(Context ctx, Intent intent) {
        super.onReceive(ctx, intent);
        if (ACTION_SWITCH.equals(intent.getAction())) {
            int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
            if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return;
            new Store(ctx).cycleWidget(id);
            updateOne(ctx, id);
        }
    }

    protected boolean small() {
        return false;
    }

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        Store st = new Store(ctx);
        Schedule sch = Schedule.load(st);
        ZonedDateTime now = ZonedDateTime.now(Schedule.ZONE);
        for (int id : ids) mgr.updateAppWidget(id, build(ctx, st, sch, now, id, small()));
        Updater.ensurePeriodic(ctx);
        if (System.currentTimeMillis() - st.fetchedAt() > 10 * 60 * 1000L) Updater.requestFetch(ctx);
    }

    /** The widget was resized: redraw it. */
    @Override
    public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager mgr, int id, Bundle options) {
        Store st = new Store(ctx);
        mgr.updateAppWidget(id, build(ctx, st, Schedule.load(st), ZonedDateTime.now(Schedule.ZONE), id, small()));
    }

    @Override
    public void onDeleted(Context ctx, int[] ids) {
        Store st = new Store(ctx);
        for (int id : ids) st.removeWidget(id);
    }

    static void updateAll(Context ctx, Store st, Schedule sch, ZonedDateTime now, boolean refreshing) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        for (int id : mgr.getAppWidgetIds(new ComponentName(ctx, PowerWidget.class))) {
            mgr.updateAppWidget(id, build(ctx, st, sch, now, id, false));
        }
        for (int id : mgr.getAppWidgetIds(new ComponentName(ctx, SmallWidget.class))) {
            mgr.updateAppWidget(id, build(ctx, st, sch, now, id, true));
        }
    }

    /** Redraws one widget (after it was set up). */
    static void updateOne(Context ctx, int id) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        android.appwidget.AppWidgetProviderInfo info = mgr.getAppWidgetInfo(id);
        boolean small = info != null && info.provider != null
                && info.provider.getClassName().endsWith("SmallWidget");
        Store st = new Store(ctx);
        mgr.updateAppWidget(id, build(ctx, st, Schedule.load(st), ZonedDateTime.now(Schedule.ZONE), id, small));
    }

    /** Same colour with some transparency, for secondary text. */
    private static int soft(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    static RemoteViews build(Context ctx, Store st, Schedule sch, ZonedDateTime now, int id, boolean small) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), small ? R.layout.widget_small : R.layout.widget_power);

        Address a = st.widgetAddress(id);

        // Tap = open the app on this widget's address
        Intent open = new Intent(ctx, MainActivity.class)
                .setAction("ua.mk.svitlo.OPEN")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (a != null) open.putExtra(MainActivity.EXTRA_DOM, a.domId);
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(ctx, 1000 + id, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));

        Status s = Status.compute(sch, a, now);
        // Status colours are the same in light and dark theme, so fixed values are safe here.
        int on = ctx.getColor(Timeline.contentColor(s.level));
        v.setInt(R.id.w_root, "setBackgroundResource", Timeline.widgetBgRes(s.level));
        v.setInt(R.id.w_pin, "setColorFilter", on);
        v.setTextColor(R.id.w_address, on);
        v.setTextColor(R.id.w_status, on);
        v.setTextColor(R.id.w_detail, on);

        // Which place this is — always clearly visible
        String place = a == null ? "Світло" : a.name;
        long f = st.fetchedAt();
        if (f > 0 && System.currentTimeMillis() - f > 45 * 60 * 1000L) {
            place += " · дані на " + Instant.ofEpochMilli(f).atZone(Schedule.ZONE)
                    .format(DateTimeFormatter.ofPattern("HH:mm"));
        }
        v.setTextViewText(R.id.w_address, place);
        v.setTextViewText(R.id.w_status, s.title);

        // ⇄ switch: only the ⇄ icon switches the address
        boolean several = st.addresses().size() > 1;
        v.setViewVisibility(R.id.w_switch, several ? View.VISIBLE : View.GONE);
        if (several) {
            boolean lightText = (on & 0x00FFFFFF) == 0x00FFFFFF;
            v.setInt(R.id.w_switch, "setBackgroundResource",
                    lightText ? R.drawable.switch_pill_light : R.drawable.switch_pill_dark);
            v.setInt(R.id.w_switch, "setColorFilter", on);
            Intent sw = new Intent(ctx, PowerWidget.class).setAction(ACTION_SWITCH)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
            PendingIntent swPi = PendingIntent.getBroadcast(ctx, 2000 + id, sw,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            v.setOnClickPendingIntent(R.id.w_switch, swPi);
        }

        if (small) {
            v.setTextViewText(R.id.w_detail, s.shortDetail);
            return v;
        }

        v.setImageViewResource(R.id.w_icon, Timeline.iconRes(s.level));
        v.setTextViewText(R.id.w_detail, s.detail);

        // Text sizes stay the same; a taller widget simply shows more coming periods.
        // Fixed part: padding 20 + name 34 + status 46 + time 28 = 128dp, then 24dp per row.
        int height = AppWidgetManager.getInstance(ctx).getAppWidgetOptions(id)
                .getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        int rows = height == 0 ? 2 : Math.max(0, Math.min(ROWS.length, (height - 132) / 24));
        List<int[]> up = s.upcoming(ROWS.length);
        for (int k = 0; k < ROWS.length; k++) {
            if (k < up.size() && k < rows) {
                int[] r = up.get(k);
                v.setViewVisibility(ROWS[k], View.VISIBLE);
                v.setTextViewText(ROWS[k], Status.rowTime(r, s.nowIdx) + "  " + Status.rowLabel(r).toLowerCase());
                v.setTextColor(ROWS[k], soft(on, 0xF2));
            } else {
                v.setViewVisibility(ROWS[k], View.GONE);
            }
        }
        return v;
    }
}
