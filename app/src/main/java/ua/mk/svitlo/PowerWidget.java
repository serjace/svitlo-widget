package ua.mk.svitlo;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The big 4×2 widget (SmallWidget reuses this for the square one).
 * Background colour = status. Each widget shows one address: the first of the list at first,
 * ⇄ switches to the next one. Tapping opens the app on that address.
 */
public class PowerWidget extends AppWidgetProvider {
    static final String ACTION_SWITCH = "ua.mk.svitlo.SWITCH";
    static final String ACTION_OPEN = "ua.mk.svitlo.WIDGET_OPEN";
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
        } else if (ACTION_OPEN.equals(intent.getAction())) {
            try {
                ctx.startActivity(openIntent(ctx, intent.getLongExtra(MainActivity.EXTRA_DOM, 0)));
            } catch (Exception ignored) {
            }
        }
    }

    /** Opens the app on this address. */
    private static Intent openIntent(Context ctx, long dom) {
        Intent open = new Intent(ctx, MainActivity.class)
                .setAction("ua.mk.svitlo.OPEN")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (dom > 0) open.putExtra(MainActivity.EXTRA_DOM, dom);
        return open;
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

    private static void size(RemoteViews v, int viewId, float dp) {
        v.setTextViewTextSize(viewId, TypedValue.COMPLEX_UNIT_DIP, dp);
    }

    /** Same colour with some transparency, for secondary text. */
    private static int soft(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    /** On older MIUI, let TextView wrap in the actual host rather than relying on
     * the launcher's height estimate to cap primary text size or force one line. */
    private static boolean legacyMiui() {
        return Build.VERSION.SDK_INT < 29 && ("Xiaomi".equalsIgnoreCase(Build.MANUFACTURER)
                || "Redmi".equalsIgnoreCase(Build.MANUFACTURER)
                || "Redmi".equalsIgnoreCase(Build.BRAND));
    }

    static RemoteViews build(Context ctx, Store st, Schedule sch, ZonedDateTime now, int id, boolean small) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), small ? R.layout.widget_small : R.layout.widget_power);

        Address a = st.widgetAddress(id);

        // Tap = open the app on this widget's address
        long dom = a != null ? a.domId : 0;
        PendingIntent openPi;
        if (Build.VERSION.SDK_INT < 29) {
            // Android 9 and older (MIUI 11 + Nova): opening an activity straight from the widget
            // is silently blocked there, while broadcasts (⇄) work — so go through our receiver.
            Intent o = new Intent(ctx, PowerWidget.class).setAction(ACTION_OPEN)
                    .putExtra(MainActivity.EXTRA_DOM, dom);
            openPi = PendingIntent.getBroadcast(ctx, 3000 + id, o,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        } else {
            openPi = PendingIntent.getActivity(ctx, 1000 + id, openIntent(ctx, dom),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        }
        v.setOnClickPendingIntent(R.id.w_root, openPi);

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

        // Fixed text sizes × the size chosen in Settings; a phrase too long for the width
        // gets a little smaller. Portrait size: width = MIN_WIDTH, height = MAX_HEIGHT.
        float k = st.widgetScale();
        Bundle opts = AppWidgetManager.getInstance(ctx).getAppWidgetOptions(id);
        int wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        int hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        float inner = wDp > 0 ? wDp - (small ? 24 : 28) : 0;
        float nameW = inner > 0 ? inner - (small ? 21 : 24) - (several ? (small ? 44 : 50) : 0) : 0;
        v.setInt(R.id.w_status, "setMaxLines", small ? 2 : 1);
        v.setInt(R.id.w_detail, "setMaxLines", small ? 2 : 1);
        v.setViewVisibility(R.id.w_header, View.VISIBLE);
        if (!small) {
            v.setViewVisibility(R.id.w_icon, View.VISIBLE);
            v.setInt(R.id.w_status_row, "setMinimumHeight", Math.round(40 * ctx.getResources().getDisplayMetrics().density));
        }
        if (ctx.getResources().getConfiguration().fontScale > 1.05f
                || ctx.getResources().getConfiguration().screenWidthDp < 360) {
            accessibleText(ctx, v, s, place, k, inner, nameW, hDp, small);
            return v;
        }
        size(v, R.id.w_address, Fit.size(ctx, place, (small ? 16 : 18) * k, 13, nameW, true, 1));

        if (small) {
            size(v, R.id.w_status, Fit.size(ctx, s.title, 26 * k, 18 * k, inner, true, 2));
            v.setTextViewText(R.id.w_detail, s.shortDetail);
            size(v, R.id.w_detail, Fit.size(ctx, s.shortDetail, 19 * k, 14 * k, inner, true, 2));
            if (legacyMiui()) {
                v.setInt(R.id.w_detail, "setMaxLines", 3);
                size(v, R.id.w_detail, 19 * k);
            }
            smallNext(ctx, v, s, inner, hDp - 20 - 32 - 4
                    - 2 * Fit.lineDp(26 * k) - (legacyMiui() ? 3 : 2) * Fit.lineDp(19 * k), 17 * k);
            return v;
        }

        v.setImageViewResource(R.id.w_icon, Timeline.iconRes(s.level));
        String title = s.title;
        float titleW = inner > 0 ? inner - 42 : 0;
        // "Аварійне відключення" is long: a narrow widget says just "Аварія"
        if (s.work != null && s.work.emergency() && !Fit.fits(ctx, title, 22 * k, titleW, true, 1)) {
            title = "Аварія";
            v.setTextViewText(R.id.w_status, title);
        }
        float statusSize = Fit.size(ctx, title, 31 * k, 22 * k, titleW, true, 1);
        size(v, R.id.w_status, statusSize);

        // Next change: with the countdown if it fits, otherwise just the time
        String detail = s.detail;
        float detailMax = 19 * k;
        if (!Fit.fits(ctx, detail, detailMax * 0.85f, inner, true, 1)) detail = s.line1;
        float detailSize = Fit.size(ctx, detail, detailMax, 14 * k, inner, true, 1);
        int detailLines = 1;
        if (legacyMiui()) {
            detailSize = detailMax;
            detailLines = 3;
            v.setInt(R.id.w_detail, "setMaxLines", detailLines);
        }
        v.setTextViewText(R.id.w_detail, detail);
        size(v, R.id.w_detail, detailSize);

        // What comes after that; a taller widget shows more of the coming periods.
        // An emergency / planned works: the reason first (up to 2 lines). During an emergency
        // the schedule doesn't matter, so nothing else.
        float rowSize = 17 * k;
        float used = 20 + 34 + 2 + Math.max(40, Fit.lineDp(statusSize)) + detailLines * Fit.lineDp(detailSize) + 4 + 4;
        int rows = hDp == 0 ? 2 : (int) Math.max(0, Math.min(ROWS.length,
                (hDp - used) / (Fit.lineDp(rowSize) + 1)));
        List<String> texts = new ArrayList<>();
        boolean reason = s.work != null && !s.work.reason.isEmpty();
        if (s.work == null || !s.work.overdue(now.toEpochSecond())) {
            for (int[] r : s.after(ROWS.length)) {
                texts.add(Status.rowTime(r, s.nowIdx) + "  " + Status.rowLabel(r).toLowerCase());
            }
        }
        if (reason) texts.add(s.work.reason);
        int room = rows;
        for (int n = 0; n < ROWS.length; n++) {
            String text = n < texts.size() ? texts.get(n) : null;
            int lines = reason && n == texts.size() - 1 ? 2 : 1;
            float sz = text == null ? rowSize : Fit.size(ctx, text, rowSize, 12 * k, inner, false, lines);
            if (lines == 2 && (room == 1 || Fit.fits(ctx, text, sz, inner, false, 1))) lines = 1;
            if (text != null && room >= lines) {
                room -= lines;
                v.setViewVisibility(ROWS[n], View.VISIBLE);
                v.setTextViewText(ROWS[n], text);
                v.setInt(ROWS[n], "setMaxLines", lines);
                v.setTextColor(ROWS[n], soft(on, 0xF2));
                size(v, ROWS[n], sz);
            } else {
                room = 0;
                v.setViewVisibility(ROWS[n], View.GONE);
            }
        }
        return v;
    }

    /** Convert SP through Android (including Android 14's nonlinear scaling) to measured DP. */
    private static float scaled(Context ctx, float sp) {
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, dm) / dm.density;
    }

    /** Large system fonts: use all the width, then spend remaining height on secondary rows.
     * Widgets cannot scroll, so the status and next time always take priority. */
    private static void accessibleText(Context ctx, RemoteViews v, Status s, String place, float k,
                                       float inner, float nameW, int height, boolean small) {
        boolean legacy = legacyMiui();
        float name = Fit.size(ctx, place, scaled(ctx, (small ? 16 : 18) * k), 13, nameW, true, 1);
        size(v, R.id.w_address, name);
        float header = Math.max(small ? 32 : 34, Fit.lineDp(name));
        if (!legacy && height > 0 && height < 100) {
            v.setViewVisibility(R.id.w_header, View.GONE);
            header = 0;
        }
        float available = Math.max(24, (height > 0 ? height : (small ? 150 : 180)) - 20 - header - 6);
        if (!small) {
            v.setViewVisibility(R.id.w_icon, View.GONE);
            v.setInt(R.id.w_status_row, "setMinimumHeight", 0);
        }

        String title = s.title;
        float desired = scaled(ctx, (small ? 26 : 31) * k);
        int maxLines = legacy ? 2 : (available < 100 ? 1 : 2);
        if (s.work != null && s.work.emergency() && !Fit.fits(ctx, title, desired, inner, true, maxLines)) {
            title = "Аварія";
        }
        int titleLines = Fit.fits(ctx, title, desired, inner, true, 1) ? 1 : maxLines;
        float titleMax = Math.min(desired, Math.max(12, (available * 0.55f / titleLines - 1) / 1.18f));
        if (legacy) titleMax = desired;
        float titleSize = Fit.size(ctx, title, titleMax, Math.min(18, titleMax), inner, true, titleLines);
        v.setInt(R.id.w_status, "setMaxLines", titleLines);
        v.setTextViewText(R.id.w_status, title);
        size(v, R.id.w_status, titleSize);

        String detail = small ? s.shortDetail : s.line1;
        float detailWanted = scaled(ctx, 19 * k);
        if (!Fit.fits(ctx, detail, detailWanted, inner, true, maxLines)) detail = s.shortDetail;
        int detailLines = Fit.fits(ctx, detail, detailWanted, inner, true, 1) ? 1 : maxLines;
        float usedTitle = titleLines * Fit.lineDp(titleSize);
        float detailMax = Math.min(detailWanted,
                Math.max(12, ((available - usedTitle) / detailLines - 1) / 1.18f));
        float detailSize = Fit.size(ctx, detail, detailMax, Math.min(14, detailMax), inner, true, detailLines);
        if (legacy) {
            detail = small ? s.shortDetail : s.line1;
            detailLines = 3;
            detailSize = detailWanted;
        }
        v.setInt(R.id.w_detail, "setMaxLines", detailLines);
        v.setTextViewText(R.id.w_detail, detail);
        size(v, R.id.w_detail, detailSize);
        v.setContentDescription(R.id.w_root, place + ". " + s.title + ". " + s.line1 + ". " + s.line2);
        float left = available - usedTitle - detailLines * Fit.lineDp(detailSize) - 4;
        float rowSize = scaled(ctx, 17 * k);
        if (small) {
            smallNext(ctx, v, s, inner, left, rowSize);
            return;
        }
        List<String> rows = new ArrayList<>();
        if (s.work == null || s.next.size() > 1) {
            for (int[] r : s.after(ROWS.length)) {
                rows.add(Status.rowTime(r, s.nowIdx) + "  " + Status.rowLabel(r).toLowerCase());
            }
        }
        if (s.work != null && !s.work.reason.isEmpty()) rows.add(s.work.reason);
        for (int i = 0; i < ROWS.length; i++) {
            String row = i < rows.size() ? rows.get(i) : null;
            int lines = row != null && Fit.fits(ctx, row, rowSize, inner, false, 1) ? 1 : 2;
            boolean show = row != null && left >= lines * Fit.lineDp(rowSize)
                    && Fit.fits(ctx, row, rowSize, inner, false, lines);
            v.setViewVisibility(ROWS[i], show ? View.VISIBLE : View.GONE);
            if (show) {
                left -= lines * Fit.lineDp(rowSize);
                v.setTextViewText(ROWS[i], row);
                v.setInt(ROWS[i], "setMaxLines", lines);
                v.setTextColor(ROWS[i], ctx.getColor(Timeline.contentColor(s.level)));
                size(v, ROWS[i], rowSize);
            }
        }
    }

    /** The square widget gains a next-period row when the user makes it tall enough. */
    private static void smallNext(Context ctx, RemoteViews v, Status s, float width, float height, float size) {
        List<int[]> next = s.after(1);
        v.setViewVisibility(R.id.w_row1, View.GONE);
        if (next.isEmpty()) return;
        int[] p = next.get(0);
        String text = Status.rowTime(p, s.nowIdx) + "  " + Status.rowLabel(p).toLowerCase();
        int lines = Fit.fits(ctx, text, size, width, false, 1) ? 1 : 2;
        if (height < lines * Fit.lineDp(size) || !Fit.fits(ctx, text, size, width, false, lines)) return;
        v.setViewVisibility(R.id.w_row1, View.VISIBLE);
        v.setInt(R.id.w_row1, "setMaxLines", lines);
        v.setTextViewText(R.id.w_row1, text);
        v.setTextColor(R.id.w_row1, ctx.getColor(Timeline.contentColor(s.level)));
        size(v, R.id.w_row1, size);
    }
}
