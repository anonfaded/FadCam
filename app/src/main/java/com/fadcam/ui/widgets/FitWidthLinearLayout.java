package com.fadcam.ui.widgets;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

/**
 * LinearLayout that never lets an unconstrained first measure pass blow the comparison table
 * past its viewport. The weighted columns already fit any width; this only guards against
 * UNSPECIFIED specs from the surrounding ScrollView measurement dance.
 */
public class FitWidthLinearLayout extends LinearLayout {

    public FitWidthLinearLayout(Context context) {
        super(context);
    }

    public FitWidthLinearLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public FitWidthLinearLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            View parent = (View) getParent();
            int available = parent != null ? parent.getMeasuredWidth() : 0;
            int floor = Math.max(available, getMinimumWidth());
            if (floor > 0) {
                widthMeasureSpec = MeasureSpec.makeMeasureSpec(floor, MeasureSpec.EXACTLY);
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
