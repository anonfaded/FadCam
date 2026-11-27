package com.fadcam.ui.widgets;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

/**
 * Two-column grid that gives corresponding cards the same measured height.
 *
 * <p>The columns remain independent so their visual order can mirror in RTL, while each row
 * grows to the taller translated card. Non-column children (the fixed middle spacer) are
 * ignored.</p>
 */
public class EqualHeightGridLayout extends LinearLayout {

    public EqualHeightGridLayout(Context context) {
        super(context);
    }

    public EqualHeightGridLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public EqualHeightGridLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        LinearLayout firstColumn = null;
        LinearLayout secondColumn = null;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (!(child instanceof LinearLayout) || child.getVisibility() == GONE) continue;
            if (firstColumn == null) {
                firstColumn = (LinearLayout) child;
            } else {
                secondColumn = (LinearLayout) child;
                break;
            }
        }

        if (firstColumn == null || secondColumn == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        // Always begin from natural content height so a shorter previous translation cannot
        // constrain a newly selected language or a changed value.
        resetRowsToWrapContent(firstColumn);
        resetRowsToWrapContent(secondColumn);
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        int pairedRows = Math.min(firstColumn.getChildCount(), secondColumn.getChildCount());
        for (int i = 0; i < pairedRows; i++) {
            View first = firstColumn.getChildAt(i);
            View second = secondColumn.getChildAt(i);
            if (first.getVisibility() == GONE || second.getVisibility() == GONE) continue;
            int rowHeight = Math.max(first.getMeasuredHeight(), second.getMeasuredHeight());
            first.getLayoutParams().height = rowHeight;
            second.getLayoutParams().height = rowHeight;
        }

        // Re-run the normal LinearLayout measurement with the paired row heights applied.
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private static void resetRowsToWrapContent(LinearLayout column) {
        for (int i = 0; i < column.getChildCount(); i++) {
            ViewGroup.LayoutParams params = column.getChildAt(i).getLayoutParams();
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        }
    }
}
