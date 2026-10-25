package ua.mk.svitlo;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.GridLayout;
import android.widget.TextView;

/** Notification weekdays wrap into more rows instead of squeezing large labels. */
public class DayButtons extends GridLayout {
    public DayButtons(Context context, AttributeSet attrs) { super(context, attrs); }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        float dp = getResources().getDisplayMetrics().density;
        int width = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        float needed = 0;
        for (int i = 0; i < getChildCount(); i++) {
            TextView child = (TextView) getChildAt(i);
            needed = Math.max(needed, child.getPaint().measureText(child.getText().toString()) + 16 * dp);
        }
        int cols = needed * 7 <= width ? 7 : Math.max(1, Math.min(4, (int) (width / Math.max(48 * dp, needed))));
        // Clear old explicit positions before changing the column count.
        for (int i = 0; i < getChildCount(); i++) {
            LayoutParams lp = (LayoutParams) getChildAt(i).getLayoutParams();
            lp.columnSpec = spec(UNDEFINED);
            lp.rowSpec = spec(UNDEFINED);
        }
        setColumnCount(cols);
        for (int i = 0; i < getChildCount(); i++) {
            LayoutParams lp = (LayoutParams) getChildAt(i).getLayoutParams();
            lp.columnSpec = spec(i % cols);
            lp.rowSpec = spec(i / cols);
            lp.width = Math.max(1, width / cols - Math.round(4 * dp));
            lp.height = LayoutParams.WRAP_CONTENT;
        }
        super.onMeasure(widthSpec, heightSpec);
    }
}
