package com.fadcam.ui.widgets;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * FrameLayout that always keeps a width-to-height ratio.
 *
 * <p>ConstraintLayout's {@code layout_constraintDimensionRatio} is parsed when the LayoutParams
 * are created and cannot be changed afterwards, so a grid that needs different ratios per
 * column count measures its own height instead.</p>
 */
public class RatioFrameLayout extends FrameLayout {

    private float ratio = 16f / 9f;

    public RatioFrameLayout(@NonNull Context context) {
        super(context);
    }

    public RatioFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public RatioFrameLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /** Width divided by height. Values above 1 are landscape, 1 is square. */
    public void setRatio(float ratio) {
        if (ratio <= 0f || this.ratio == ratio) return;
        this.ratio = ratio;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (width <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        int height = Math.round(width / ratio);
        super.onMeasure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
}
