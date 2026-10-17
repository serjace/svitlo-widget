package ua.mk.svitlo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import com.google.android.material.color.MaterialColors;

import java.util.Locale;

/** 24 labelled hour boxes in two rows (00–11, 12–23). Half-coloured when only half an hour differs. */
public class HourGridView extends View {
    private int[] slots;
    private int currentHour = -1;
    private final float dp;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    public HourGridView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        dp = getResources().getDisplayMetrics().density;
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(12 * getResources().getDisplayMetrics().scaledDensity);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2 * dp);
        ring.setColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface));
    }

    /** @param currentHour hour to outline and before which boxes are faded, or -1 */
    public void setData(int[] slots, int currentHour) {
        this.slots = slots;
        this.currentHour = currentHour;
        invalidate();
    }

    private float cellH() {
        return 34 * dp;
    }

    private float gap() {
        return 4 * dp;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = (int) (2 * cellH() + gap() + 4 * dp);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float pad = 2 * dp;
        float gap = gap();
        float ch = cellH();
        float cw = (getWidth() - 2 * pad - 11 * gap) / 12f;
        float rad = 8 * dp;
        for (int hr = 0; hr < 24; hr++) {
            float left = pad + (hr % 12) * (cw + gap);
            float top = pad + (hr / 12) * (ch + gap);
            r.set(left, top, left + cw, top + ch);
            int a = slots == null ? Timeline.UNKNOWN : Timeline.level(slots[hr * 2]);
            int b = slots == null ? Timeline.UNKNOWN : Timeline.level(slots[hr * 2 + 1]);
            int alpha = currentHour >= 0 && hr < currentHour ? 90 : 255;

            fill.setColor(getContext().getColor(Timeline.containerColor(a)));
            fill.setAlpha(alpha);
            canvas.drawRoundRect(r, rad, rad, fill);
            if (b != a) {
                canvas.save();
                canvas.clipRect(left + cw / 2, top, left + cw, top + ch);
                fill.setColor(getContext().getColor(Timeline.containerColor(b)));
                fill.setAlpha(alpha);
                canvas.drawRoundRect(r, rad, rad, fill);
                canvas.restore();
            }

            text.setColor(getContext().getColor(Timeline.contentColor(a)));
            text.setAlpha(alpha);
            float ty = r.centerY() - (text.descent() + text.ascent()) / 2;
            canvas.drawText(String.format(Locale.US, "%02d", hr), r.centerX(), ty, text);

            if (hr == currentHour) {
                r.inset(-1.5f * dp, -1.5f * dp);
                canvas.drawRoundRect(r, rad + dp, rad + dp, ring);
            }
        }
    }
}
