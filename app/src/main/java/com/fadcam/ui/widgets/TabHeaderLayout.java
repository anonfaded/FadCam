package com.fadcam.ui.widgets;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

import com.fadcam.R;

/**
 * A tab header with a guaranteed content height that can still grow.
 *
 * <p>{@code android:minHeight} is not reliable here because the status-band is
 * injected later as top padding. This container measures its children normally
 * (so an inline delete/export dock or repair banner expands it), then raises the
 * measured height to {@code app_header_content_height + padding} when the content
 * is shorter. The four tab headers therefore stay visually identical at rest and
 * still extend for operation notifications.</p>
 */
public class TabHeaderLayout extends LinearLayout {

    public TabHeaderLayout(Context context) {
        super(context);
    }

    public TabHeaderLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public TabHeaderLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int minContent = getResources().getDimensionPixelSize(
                R.dimen.app_header_content_height);
        int minimum = minContent + getPaddingTop() + getPaddingBottom();
        if (getMeasuredHeight() < minimum) {
            setMeasuredDimension(getMeasuredWidth(), minimum);
        }
    }
}
