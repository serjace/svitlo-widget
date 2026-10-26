package ua.mk.svitlo;

import android.os.Build;
import android.view.View;

import androidx.activity.ComponentActivity;
import androidx.activity.EdgeToEdge;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.function.IntConsumer;

/**
 * Android 10+: the app draws under transparent system bars, so the gesture bar at the bottom
 * is see-through (with 3-button navigation Android keeps a light backdrop behind the buttons).
 * Older Android: nothing changes, the bars keep the app's colour.
 */
final class Bars {
    private Bars() {}

    /**
     * @param root     gets top/side padding for the status bar
     * @param onBottom receives the height to keep free at the bottom (gesture bar or keyboard)
     */
    static void apply(ComponentActivity act, View root, IntConsumer onBottom) {
        if (Build.VERSION.SDK_INT < 29) return;
        EdgeToEdge.enable(act);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, 0);
            if (onBottom != null) onBottom.accept(Math.max(bars.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
    }

    /**
     * Makes page swiping lighter and nicer: reacts to a shorter finger movement, keeps the
     * neighbouring page ready. Pages keep full opacity and size, including after resume.
     */
    static void smoothPager(androidx.viewpager2.widget.ViewPager2 pager) {
        pager.setOffscreenPageLimit(1);
        pager.setPageTransformer((page, position) -> {
            page.setAlpha(1f);
            page.setScaleX(1f);
            page.setScaleY(1f);
        });
        try {
            View child = pager.getChildAt(0);
            if (child instanceof androidx.recyclerview.widget.RecyclerView) {
                java.lang.reflect.Field f = androidx.recyclerview.widget.RecyclerView.class
                        .getDeclaredField("mTouchSlop");
                f.setAccessible(true);
                f.set(child, (int) f.get(child) / 2);
            }
        } catch (Exception ignored) {
            // keep the default sensitivity
        }
    }

    /** Adds extra bottom padding to a scrolling content view (remembers its own padding). */
    static void padBottom(View v, int extra) {
        Object tag = v.getTag(R.id.tag_base_padding);
        int base;
        if (tag instanceof Integer) {
            base = (Integer) tag;
        } else {
            base = v.getPaddingBottom();
            v.setTag(R.id.tag_base_padding, base);
        }
        v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), base + extra);
    }
}
