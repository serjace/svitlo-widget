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

import java.time.ZonedDateTime;
import java.util.List;

/** The big 4×2 widget (SmallWidget reuses this for the square 2×2 one). Background colour = status. */
public class PowerWidget extends AppWidgetProvider {
    static final String ACTION_SWITCH = "ua.mk.svitlo.SWITCH";
    static final String ACTION_REFRESH = "ua.mk.svitlo.REFRESH";

    private static final int[] ROWS = {R.id.w_row1, R.id.w_row2};

    protected boolean small() {
        return false;
    }

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        Store st = new Store(ctx);
        Schedule sch = Schedule.load(st);
        ZonedDateTime now = ZonedDateTime.now(Schedule.ZONE);
        for (int id : ids) mgr.updateAppWidget(id, build(ctx, st, sch, now, id, small(), false));
        Updater.ensurePeriodic(ctx);
        if (System.currentTimeMillis() - st.fetchedAt() > 10 * 60 * 1000L) Updater.requestFetch(ctx);
    }

    /** The widget was resized: redraw it to fit the new size. */
    @Override
    public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager mgr, int id, Bundle options) {
        Store st = new Store(ctx);
        mgr.updateAppWidget(id, build(ctx, st, Schedule.load(st), ZonedDateTime.now(Schedule.ZONE), id, small(), false));
    }

    @Override
    public void onDeleted(Context ctx, int[] ids) {
        Store st = new Store(ctx);
        for (int id : ids) st.removeWidget(id);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        super.onReceive(ctx, intent);
        String action = intent.getAction();
        if (ACTION_SWITCH.equals(action)) {
            int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
            if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return;
            Store st = new Store(ctx);
            st.cycleWidget(id);
            AppWidgetManager.getInstance(ctx).updateAppWidget(id,
                    build(ctx, st, Schedule.load(st), ZonedDateTime.now(Schedule.ZONE), id, false, false));
        } else if (ACTION_REFRESH.equals(action)) {
            Store st = new Store(ctx);
            updateAll(ctx, st, Schedule.load(st), ZonedDateTime.now(Schedule.ZONE), true);
            Updater.requestFetch(ctx);
        }
    }

    static void updateAll(Context ctx, Store st, Schedule sch, ZonedDateTime now, boolean refreshing) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        for (int id : mgr.getAppWidgetIds(new ComponentName(ctx, PowerWidget.class))) {
            mgr.updateAppWidget(id, build(ctx, st, sch, now, id, false, refreshing));
        }
        for (int id : mgr.getAppWidgetIds(new ComponentName(ctx, SmallWidget.class))) {
            mgr.updateAppWidget(id, build(ctx, st, sch, now, id, true, refreshing));
        }
    }

    /** Same colour with some transparency, for secondary text. */
    private static int soft(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    static RemoteViews build(Context ctx, Store st, Schedule sch, ZonedDateTime now, int id,
                             boolean small, boolean refreshing) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), small ? R.layout.widget_small : R.layout.widget_power);

        Intent open = new Intent(ctx, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));

        Address a = st.widgetAddress(id);
        Status s = Status.compute(sch, a, now);
        // Status colours are the same in light and dark theme, so fixed values are safe here.
        int on = ctx.getColor(Timeline.contentColor(s.level));
        v.setInt(R.id.w_root, "setBackgroundResource", Timeline.widgetBgRes(s.level));
        v.setImageViewResource(R.id.w_icon, Timeline.iconRes(s.level));
        v.setTextViewText(R.id.w_status, s.title);
        v.setTextColor(R.id.w_status, on);
        v.setTextColor(R.id.w_detail, on);

        if (small) {
            v.setTextViewText(R.id.w_detail, refreshing ? "оновлення…" : s.shortDetail);
            return v;
        }

        Intent refresh = new Intent(ctx, PowerWidget.class).setAction(ACTION_REFRESH);
        v.setOnClickPendingIntent(R.id.w_refresh, PendingIntent.getBroadcast(ctx, id * 10 + 1, refresh,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        Intent sw = new Intent(ctx, PowerWidget.class).setAction(ACTION_SWITCH)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        v.setOnClickPendingIntent(R.id.w_switch, PendingIntent.getBroadcast(ctx, id * 10 + 2, sw,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        v.setViewVisibility(R.id.w_switch, st.addresses().size() > 1 ? View.VISIBLE : View.GONE);
        v.setInt(R.id.w_switch, "setColorFilter", on);
        v.setInt(R.id.w_refresh, "setColorFilter", on);

        String head = a == null ? "Світло" : a.name + (a.gpvId > 0 ? " · черга " + a.gpvName : "");
        v.setTextViewText(R.id.w_address, head);
        v.setTextColor(R.id.w_address, soft(on, 0xE6));
        v.setTextViewText(R.id.w_updated, refreshing ? "оновлення…" : st.updatedShort());
        v.setTextColor(R.id.w_updated, soft(on, 0xCC));
        v.setTextViewText(R.id.w_detail, s.detail);

        // Show as many period rows as the widget's real height allows (launchers differ a lot)
        int height = AppWidgetManager.getInstance(ctx).getAppWidgetOptions(id)
                .getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        int rows = height == 0 || height >= 158 ? 2 : height >= 134 ? 1 : 0;
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
