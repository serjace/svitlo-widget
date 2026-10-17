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
import java.util.List;

/** The home-screen widget. */
public class PowerWidget extends AppWidgetProvider {
    static final String ACTION_SWITCH = "ua.mk.svitlo.SWITCH";
    static final String ACTION_REFRESH = "ua.mk.svitlo.REFRESH";

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        Store st = new Store(ctx);
        Schedule sch = Schedule.load(st);
        ZonedDateTime now = ZonedDateTime.now(Schedule.ZONE);
        for (int id : ids) mgr.updateAppWidget(id, build(ctx, st, sch, now, id, false));
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
                    build(ctx, st, Schedule.load(st), ZonedDateTime.now(Schedule.ZONE), id, false));
        } else if (ACTION_REFRESH.equals(action)) {
            Store st = new Store(ctx);
            updateAll(ctx, st, Schedule.load(st), ZonedDateTime.now(Schedule.ZONE), true);
            Updater.requestFetch(ctx);
        }
    }

    static void updateAll(Context ctx, Store st, Schedule sch, ZonedDateTime now, boolean refreshing) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, PowerWidget.class));
        for (int id : ids) mgr.updateAppWidget(id, build(ctx, st, sch, now, id, refreshing));
    }

    static RemoteViews build(Context ctx, Store st, Schedule sch, ZonedDateTime now, int id, boolean refreshing) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_power);

        Intent open = new Intent(ctx, MainActivity.class);
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));

        Intent refresh = new Intent(ctx, PowerWidget.class).setAction(ACTION_REFRESH);
        v.setOnClickPendingIntent(R.id.w_refresh, PendingIntent.getBroadcast(ctx, id * 10 + 1, refresh,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));

        Intent sw = new Intent(ctx, PowerWidget.class).setAction(ACTION_SWITCH)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        v.setOnClickPendingIntent(R.id.w_switch, PendingIntent.getBroadcast(ctx, id * 10 + 2, sw,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));

        List<Address> list = st.addresses();
        v.setViewVisibility(R.id.w_switch, list.size() > 1 ? View.VISIBLE : View.GONE);

        Address a = st.widgetAddress(id);
        Status s = Status.compute(sch, a, now);
        String head = a == null ? "Світло" : a.name + (a.gpvId > 0 ? " · черга " + a.gpvName : "");
        v.setTextViewText(R.id.w_address, head);
        v.setTextColor(R.id.w_dot, Status.color(s.level));
        v.setTextViewText(R.id.w_status, s.title);
        v.setTextViewText(R.id.w_detail, s.detail);

        if (a != null && sch.hasData) {
            int[] today = a.gpvId > 0 ? sch.slots(now.toLocalDate(), a.gpvId) : null;
            if (today == null) today = new int[48];
            v.setImageViewBitmap(R.id.w_timeline,
                    Timeline.render(today, Status.nowFraction(now), true, 720, 84));
            v.setViewVisibility(R.id.w_timeline, View.VISIBLE);
        } else {
            v.setViewVisibility(R.id.w_timeline, View.GONE);
        }

        v.setTextViewText(R.id.w_updated, refreshing ? "Оновлення…" : st.updatedLabel());
        return v;
    }
}
