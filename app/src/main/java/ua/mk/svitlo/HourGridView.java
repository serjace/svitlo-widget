package ua.mk.svitlo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import com.google.android.material.color.MaterialColors;

/**
 * 24 numbered hour boxes in two rows (0–11, 12–23) in bold status colours.
 * Half-coloured when only half an hour differs; past hours are grey; the current hour is outlined.
 */
public class HourGridView extends View {
    private int[] slots;
    private int currentHour = -1;
    private final float dp;
    private final float baseTextSize;
    private int columns = 12;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    public HourGridView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        dp = getResources().getDisplayMetrics().density;
        text.setTextAlign(Paint.Align.CENTER);
        // follows the phone's font size setting
        baseTextSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16, getResources().getDisplayMetrics());
        text.setTextSize(baseTextSize);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(3 * dp);
        ring.setColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface));
    }

    /** @param currentHour hour to outline and before which boxes are grey, or -1 */
    public void setData(int[] slots, int currentHour) {
        this.slots = slots;
        this.currentHour = currentHour;
        invalidate();
    }

    private float cellH() {
        return Math.max(40 * dp, text.getTextSize() * 2.2f);
    }

    private float gap() {
        return 5 * dp;
    }

    /** Big phone font settings: keep "23" inside its box. */
    private void fitText(int width) {
        boolean enlarged = getResources().getConfiguration().fontScale > 1.05f
                || getResources().getConfiguration().screenWidthDp < 360;
        columns = 12;
        text.setTextSize(baseTextSize);
        if (enlarged) {
            float needed = text.measureText("23") + 12 * dp;
            for (int n : new int[]{12, 8, 6, 4, 3, 2, 1}) {
                columns = n;
                if ((width - 8 * dp - (n - 1) * gap()) / n >= needed) break;
            }
        } else {
            float cw = (width - 8 * dp - 11 * gap()) / 12f;
            text.setTextSize(cw > 0 ? Math.min(baseTextSize, cw * 0.62f) : baseTextSize);
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        fitText(w);
        int rows = 24 / columns;
        int h = (int) (rows * cellH() + (rows - 1) * gap() + 8 * dp);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Context ctx = getContext();
        float pad = 4 * dp;
        float gap = gap();
        float ch = cellH();
        float cw = (getWidth() - 2 * pad - (columns - 1) * gap) / columns;
        float rad = 8 * dp;
        fitText(getWidth());
        for (int hr = 0; hr < 24; hr++) {
            float left = pad + (hr % columns) * (cw + gap);
            float top = pad + (hr / columns) * (ch + gap);
            r.set(left, top, left + cw, top + ch);
            boolean past = currentHour >= 0 && hr < currentHour;
            int a = slots == null ? Timeline.UNKNOWN : Timeline.level(slots[hr * 2]);
            int b = slots == null ? Timeline.UNKNOWN : Timeline.level(slots[hr * 2 + 1]);

            fill.setColor(past ? ctx.getColor(R.color.grid_past_fill) : ctx.getColor(Timeline.containerColor(a)));
            canvas.drawRoundRect(r, rad, rad, fill);
            if (b != a && !past) {
                canvas.save();
                canvas.clipRect(left + cw / 2, top, left + cw, top + ch);
                fill.setColor(ctx.getColor(Timeline.containerColor(b)));
                canvas.drawRoundRect(r, rad, rad, fill);
                canvas.restore();
            }

            text.setColor(past ? ctx.getColor(R.color.grid_past_text) : ctx.getColor(Timeline.contentColor(a)));
            float ty = r.centerY() - (text.descent() + text.ascent()) / 2;
            canvas.drawText(String.valueOf(hr), r.centerX(), ty, text);

            if (hr == currentHour) {
                r.inset(-2.5f * dp, -2.5f * dp);
                canvas.drawRoundRect(r, rad + 2 * dp, rad + 2 * dp, ring);
            }
        }
    }
}
