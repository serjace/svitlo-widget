package ua.mk.svitlo;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.RemoteViews;

import java.time.ZonedDateTime;

/** The main 4×2 home-screen widget (SmallWidget reuses this for the 2×1 one). */
public class PowerWidget extends AppWidgetProvider {
    static final String ACTION_SWITCH = "ua.mk.svitlo.SWITCH";
    static final String ACTION_REFRESH = "ua.mk.svitlo.REFRESH";

    private static final int[] ROWS = {R.id.w_row1, R.id.w_row2, R.id.w_row3};
    private static final int[] PILLS = {R.id.w_row1_pill, R.id.w_row2_pill, R.id.w_row3_pill};
    private static final int[] TIMES = {R.id.w_row1_time, R.id.w_row2_time, R.id.w_row3_time};
    private static final int[] LABELS = {R.id.w_row1_label, R.id.w_row2_label, R.id.w_row3_label};

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

    static RemoteViews build(Context ctx, Store st, Schedule sch, ZonedDateTime now, int id,
                             boolean small, boolean refreshing) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), small ? R.layout.widget_small : R.layout.widget_power);

        Intent open = new Intent(ctx, MainActivity.class);
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));

        Address a = st.widgetAddress(id);
        Status s = Status.compute(sch, a, now);
        v.setInt(R.id.w_icon, "setBackgroundResource", Timeline.circleRes(s.level));
        v.setImageViewResource(R.id.w_icon, Timeline.iconRes(s.level));
        v.setTextViewText(R.id.w_status, s.title);

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

        String head = a == null ? "Світло" : a.name + (a.gpvId > 0 ? " · черга " + a.gpvName : "");
        v.setTextViewText(R.id.w_address, head);
        v.setTextViewText(R.id.w_updated, refreshing ? "оновлення…" : st.updatedShort());
        v.setTextViewText(R.id.w_detail, s.detail);

        v.setViewVisibility(R.id.w_divider, s.next.isEmpty() ? View.GONE : View.VISIBLE);
        for (int k = 0; k < ROWS.length; k++) {
            if (k < s.next.size()) {
                int[] r = s.next.get(k);
                v.setViewVisibility(ROWS[k], View.VISIBLE);
                v.setImageViewResource(PILLS[k], Timeline.pillRes(r[2]));
                v.setTextViewText(TIMES[k], Status.rowTime(r, s.nowIdx));
                v.setTextViewText(LABELS[k], Status.rowLabel(r));
            } else {
                v.setViewVisibility(ROWS[k], View.GONE);
            }
        }
        return v;
    }
}
