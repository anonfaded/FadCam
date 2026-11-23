package com.fadcam.ui.custom;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.slider.Slider;

/**
 * A slider that only changes its value when the gesture starts on the thumb.
 *
 * <p>The Material slider moves the thumb to wherever the user presses, so a stray tap while
 * scrolling a settings sheet silently changes the value. Declining the down event when it lands
 * away from the thumb leaves that gesture to the scroll parent instead of consuming it, and the
 * value only changes once the user has actually grabbed the thumb.</p>
 */
public class ThumbOnlySlider extends Slider {

    /** Smallest hit radius around the thumb centre, in dp. Keeps the grab area finger-sized. */
    private static final float MIN_HIT_RADIUS_DP = 20f;

    public ThumbOnlySlider(@NonNull Context context) {
        super(context);
    }

    public ThumbOnlySlider(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ThumbOnlySlider(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && !isDownOnThumb(event.getX())) {
            // Not on the thumb: do not start dragging, let the parent scroll instead.
            return false;
        }
        return super.onTouchEvent(event);
    }

    private boolean isDownOnThumb(float x) {
        float range = getValueTo() - getValueFrom();
        if (range <= 0f) {
            return true;
        }
        int trackWidth = getTrackWidth();
        if (trackWidth <= 0) {
            // Not laid out yet; behave normally rather than swallowing the gesture.
            return true;
        }
        float fraction = (getValue() - getValueFrom()) / range;
        float center = getTrackSidePadding() + fraction * trackWidth;
        float density = getResources().getDisplayMetrics().density;
        float radius = Math.max(getThumbWidth() / 2f, MIN_HIT_RADIUS_DP * density);
        return Math.abs(x - center) <= radius;
    }
}
