package ua.mk.svitlo;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;

/** Keeps the existing segmented row when it fits; otherwise gives each choice a full row. */
public class AdaptiveToggleGroup extends MaterialButtonToggleGroup {
    public AdaptiveToggleGroup(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int available = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        int count = getChildCount();
        boolean stack = false;
        for (int i = 0; i < count; i++) {
            MaterialButton b = (MaterialButton) getChildAt(i);
            float needed = b.getPaint().measureText(b.getText().toString())
                    + b.getCompoundPaddingLeft() + b.getCompoundPaddingRight() + 8;
            if (available > 0 && needed > available / (float) count) stack = true;
        }
        setOrientation(stack ? VERTICAL : HORIZONTAL);
        int minimum = Math.round(56 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < count; i++) {
            MaterialButton b = (MaterialButton) getChildAt(i);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
            lp.width = stack ? LayoutParams.MATCH_PARENT : 0;
            lp.height = LayoutParams.WRAP_CONTENT;
            lp.weight = stack ? 0 : 1;
            b.setMinimumHeight(minimum);
            b.setMaxLines(stack ? Integer.MAX_VALUE : 1);
            b.setEllipsize(null);
        }
        super.onMeasure(widthSpec, heightSpec);
    }
}
