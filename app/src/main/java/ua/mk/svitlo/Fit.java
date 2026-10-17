package ua.mk.svitlo;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.StaticLayout;
import android.text.TextPaint;

/**
 * Picks widget text sizes: the wanted size when the text fits the width, otherwise
 * the largest smaller size that does (never below the minimum). Widgets can't autosize
 * text when the size is set from code, so this does it instead.
 */
final class Fit {
    private Fit() {}

    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    /** @param widthDp room for the text; 0 = unknown (use maxDp, long text gets "…") */
    static float size(Context ctx, CharSequence text, float maxDp, float minDp, float widthDp,
                      boolean medium, int lines) {
        if (widthDp <= 0 || text == null || text.length() == 0) return maxDp;
        if (fits(ctx, text, maxDp, widthDp, medium, lines)) return maxDp;
        for (float s = (float) Math.floor(maxDp); s > minDp; s -= 1f) {
            if (fits(ctx, text, s, widthDp, medium, lines)) return s;
        }
        return minDp;
    }

    static boolean fits(Context ctx, CharSequence text, float sizeDp, float widthDp, boolean medium, int lines) {
        if (widthDp <= 0) return true;
        float d = ctx.getResources().getDisplayMetrics().density;
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(medium ? MEDIUM : Typeface.DEFAULT);
        p.setTextSize(sizeDp * d);
        int w = (int) (widthDp * d * 0.95f);   // a little spare room: launchers round sizes
        if (lines <= 1) return p.measureText(text, 0, text.length()) <= w;
        StaticLayout l = StaticLayout.Builder.obtain(text, 0, text.length(), p, w).build();
        if (l.getLineCount() > lines) return false;
        // a single word wider than the line gets broken in the middle — doesn't count as fitting
        for (int i = 0; i < l.getLineCount(); i++) {
            int end = l.getLineEnd(i);
            if (end > 0 && end < text.length() && !Character.isWhitespace(text.charAt(end - 1))
                    && !Character.isWhitespace(text.charAt(end))) return false;
        }
        return true;
    }

    /** Height of one line of text, in dp (font padding included). */
    static float lineDp(float sizeDp) {
        return sizeDp * 1.18f + 1f;
    }
}
