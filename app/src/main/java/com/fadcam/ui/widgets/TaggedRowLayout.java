package com.fadcam.ui.widgets;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import com.fadcam.R;

/**
 * One spec row: icon, label and a trailing status tag. Unlike a plain horizontal
 * LinearLayout, the tag keeps its intrinsic width: when the label and tag cannot share a
 * line, the tag drops below the label instead of being squeezed away. The row lays itself
 * out from the logical start, so it mirrors correctly in RTL.
 */
public class TaggedRowLayout extends ViewGroup {

    private final int iconGap;
    private final int tagGap;
    private final int minHeight;
    private boolean tagBelowLabel;

    public TaggedRowLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        iconGap = getResources().getDimensionPixelSize(R.dimen.pro_showcase_feature_gap);
        tagGap = getResources().getDimensionPixelSize(R.dimen.pro_showcase_chip_gap);
        minHeight = Math.round(32f * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int available = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        if (getChildCount() < 3) {
            setMeasuredDimension(width, minHeight + getPaddingTop() + getPaddingBottom());
            return;
        }
        View icon = getChildAt(0);
        View label = getChildAt(1);
        View tag = getChildAt(2);

        measureChild(icon,
                MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        measureChild(tag,
                MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));

        int labelMax = Math.max(0, available - icon.getMeasuredWidth() - iconGap);
        label.measure(
                MeasureSpec.makeMeasureSpec(labelMax, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));

        tagBelowLabel = label.getMeasuredWidth() + tagGap + tag.getMeasuredWidth() > labelMax;
        if (tagBelowLabel) {
            // The tag needs its own line: the label may use the full width first.
            label.measure(
                    MeasureSpec.makeMeasureSpec(labelMax, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        }

        int contentHeight = tagBelowLabel
                ? label.getMeasuredHeight() + tagGap + tag.getMeasuredHeight()
                : Math.max(Math.max(icon.getMeasuredHeight(), label.getMeasuredHeight()),
                        tag.getMeasuredHeight());
        setMeasuredDimension(width,
                Math.max(contentHeight, minHeight) + getPaddingTop() + getPaddingBottom());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        if (getChildCount() < 3) return;
        View icon = getChildAt(0);
        View label = getChildAt(1);
        View tag = getChildAt(2);
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;

        int innerHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        int contentHeight = tagBelowLabel
                ? label.getMeasuredHeight() + tagGap + tag.getMeasuredHeight()
                : Math.max(Math.max(icon.getMeasuredHeight(), label.getMeasuredHeight()),
                        tag.getMeasuredHeight());
        int top = getPaddingTop() + Math.max(0, (innerHeight - contentHeight) / 2);

        int iconX = rtl
                ? getWidth() - getPaddingRight() - icon.getMeasuredWidth()
                : getPaddingLeft();
        int iconY = tagBelowLabel
                ? top
                : top + (contentHeight - icon.getMeasuredHeight()) / 2;
        icon.layout(iconX, iconY, iconX + icon.getMeasuredWidth(), iconY + icon.getMeasuredHeight());

        int labelX = rtl
                ? iconX - iconGap - label.getMeasuredWidth()
                : iconX + icon.getMeasuredWidth() + iconGap;
        label.layout(labelX, top, labelX + label.getMeasuredWidth(), top + label.getMeasuredHeight());

        int tagX;
        int tagY;
        if (tagBelowLabel) {
            tagX = rtl
                    ? labelX + label.getMeasuredWidth() - tag.getMeasuredWidth()
                    : labelX;
            tagY = top + label.getMeasuredHeight() + tagGap;
        } else {
            tagX = rtl
                    ? labelX - tagGap - tag.getMeasuredWidth()
                    : labelX + label.getMeasuredWidth() + tagGap;
            tagY = top + (label.getMeasuredHeight() - tag.getMeasuredHeight()) / 2;
        }
        tag.layout(tagX, tagY, tagX + tag.getMeasuredWidth(), tagY + tag.getMeasuredHeight());
    }
}
