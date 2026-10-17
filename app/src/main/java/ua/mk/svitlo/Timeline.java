package ua.mk.svitlo;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/** Draws a day as a strip of 48 coloured half-hour cells. */
final class Timeline {
    private Timeline() {}

    /**
     * @param slots   48 slot codes (null = unknown)
     * @param nowFrac position of "now" in the day 0..1, or -1 to not show it
     * @param dark    true for light-on-dark (widget), false for dark-on-light (app)
     */
    static Bitmap render(int[] slots, float nowFrac, boolean dark, int w, int h) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        float barH = h * 0.6f;
        float cellW = w / 48f;
        float gap = Math.max(1.5f, cellW * 0.12f);
        float radius = cellW * 0.25f;
        for (int i = 0; i < 48; i++) {
            int code = slots == null ? -1 : slots[i];
            p.setColor(Status.cellColor(code));
            boolean past = nowFrac >= 0 && (i + 1) / 48f <= nowFrac;
            p.setAlpha(past ? 90 : 255);
            RectF r = new RectF(i * cellW + gap / 2, 0, (i + 1) * cellW - gap / 2, barH);
            cv.drawRoundRect(r, radius, radius, p);
        }

        if (nowFrac >= 0) {
            p.setColor(dark ? 0xFFFFFFFF : 0xFF1B1F24);
            p.setStrokeWidth(Math.max(2f, w / 220f));
            float x = nowFrac * w;
            cv.drawLine(x, 0, x, barH + h * 0.08f, p);
        }

        p.setColor(dark ? 0xB3FFFFFF : 0xFF5F6B7A);
        p.setTextSize(h * 0.3f);
        int[] hours = {0, 6, 12, 18, 24};
        for (int hr : hours) {
            String label = String.format(java.util.Locale.US, "%02d", hr);
            float x = hr / 24f * w;
            if (hr == 0) p.setTextAlign(Paint.Align.LEFT);
            else if (hr == 24) p.setTextAlign(Paint.Align.RIGHT);
            else p.setTextAlign(Paint.Align.CENTER);
            cv.drawText(label, x, h - h * 0.04f, p);
        }
        return bmp;
    }
}
