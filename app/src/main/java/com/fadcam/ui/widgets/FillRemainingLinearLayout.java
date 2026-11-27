package com.fadcam.ui.widgets;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

/**
 * Vertical container that grows its last child into the leftover height.
 *
 * <p>In portrait the home rail stretches the recording-controls card to match the
 * tallest column ({@link EqualHeightRowLayout}), but the card holds a title plus a
 * fixed-height tile row, so the extra height showed up as an empty band. This
 * container measures normally, then re-measures its last visible child into the
 * remaining space — but only when the height is known up front. Landscape cards
 * stay {@code wrap_content}, so they keep their intrinsic tile height.</p>
 */
public class FillRemainingLinearLayout extends LinearLayout {

    public FillRemainingLinearLayout(Context context) {
        super(context);
    }

    public FillRemainingLinearLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public FillRemainingLinearLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY) return;

        final int innerHeight = getMeasuredHeight() - getPaddingTop() - getPaddingBottom();
        if (innerHeight <= 0) return;

        View last = null;
        int used = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            last = child;
        }
        if (last == null) return;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child == last || child.getVisibility() == GONE) continue;
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            used += child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
        }

        LayoutParams lastLp = (LayoutParams) last.getLayoutParams();
        int target = innerHeight - used - lastLp.topMargin - lastLp.bottomMargin;
        if (target <= last.getMeasuredHeight()) return;

        last.measure(
                MeasureSpec.makeMeasureSpec(last.getMeasuredWidth(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(target, MeasureSpec.EXACTLY));
    }
}
