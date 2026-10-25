package ua.mk.svitlo;

import android.content.Context;
import android.util.AttributeSet;
import android.util.TypedValue;
import androidx.appcompat.widget.AppCompatTextView;

/** Wraps the large status freely, reducing it only if a whole word cannot fit. */
public class StatusTextView extends AppCompatTextView {
    private final float preferredPx;

    public StatusTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        preferredPx = getTextSize();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec) - getCompoundPaddingLeft() - getCompoundPaddingRight();
        if (width > 0) {
            android.text.TextPaint paint = new android.text.TextPaint(getPaint());
            paint.setTextSize(preferredPx);
            float longest = 0;
            for (String word : getText().toString().split("\\s+")) {
                longest = Math.max(longest, paint.measureText(word));
            }
            float size = longest > width ? preferredPx * width / (longest + 1) : preferredPx;
            if (Math.abs(getTextSize() - size) > 0.1f) setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        }
        super.onMeasure(widthSpec, heightSpec);
    }
}
