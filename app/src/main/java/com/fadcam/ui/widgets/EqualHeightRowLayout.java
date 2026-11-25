package com.fadcam.ui.widgets;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

/**
 * Horizontal row whose children all end at the same height.
 *
 * <p>Portrait and landscape home both put the storage/elapsed column next to the
 * clock/stats/AF column. The cards inside have different natural heights, and a
 * {@code match_parent} child inside a wrap-content {@link LinearLayout} is not
 * reliably re-measured to the tallest sibling. This container measures the row
 * normally, then re-measures every child to that exact height, so a child with a
 * weighted last card (the AF card) fills the difference and both columns finish
 * flush.</p>
 */
public class EqualHeightRowLayout extends LinearLayout {

    public EqualHeightRowLayout(Context context) {
        super(context);
    }

    public EqualHeightRowLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public EqualHeightRowLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        final int innerHeight = getMeasuredHeight() - getPaddingTop() - getPaddingBottom();
        if (innerHeight <= 0 || getChildCount() == 0) return;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            int margins = lp.topMargin + lp.bottomMargin;
            int target = Math.max(0, innerHeight - margins);
            if (child.getMeasuredHeight() == target) continue;
            child.measure(
                    MeasureSpec.makeMeasureSpec(child.getMeasuredWidth(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(target, MeasureSpec.EXACTLY));
        }
        // Children were re-measured inside the same bounds; the row itself is unchanged.
        setMeasuredDimension(getMeasuredWidth(), getMeasuredHeight());
    }
}
