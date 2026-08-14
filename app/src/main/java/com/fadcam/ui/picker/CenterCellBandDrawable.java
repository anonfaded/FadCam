package com.fadcam.ui.picker;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

/**
 * Translucent rounded "cell" band drawn BEHIND a NumberPicker's digits, at the
 * center row. The wheel's own numbers render on top and scroll through the
 * band naturally — the selected value always sits inside the highlighted cell
 * in real time, with no overlay text or double rendering.
 *
 * <p>Press feedback: {@link #press(boolean)} flashes a bright overlay inside
 * the same rounded cell (bounded exactly to the cell shape) that fades out on
 * release — a ripple-like effect without covering the wheel.</p>
 */
public class CenterCellBandDrawable extends Drawable {

    private static final long PRESS_FADE_MS = 220L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float insetPx;
    private final float radiusPx;
    private int rowCount = 3;

    private boolean pressed;
    private float pressAlpha;
    private long pressStartMs;

    public CenterCellBandDrawable(int argb, float insetPx, float radiusPx) {
        paint.setColor(argb);
        highlightPaint.setColor(0x59FFFFFF); // white ~35% for the press flash
        this.insetPx = insetPx;
        this.radiusPx = radiusPx;
    }

    /** Rows visible in the wheel (default 3). */
    public void setRowCount(int rows) {
        if (rows > 0 && rows != rowCount) {
            rowCount = rows;
            invalidateSelf();
        }
    }

    /** Press feedback: instant flash inside the cell, fade-out on release. */
    public void press(boolean down) {
        if (pressed == down) {
            return;
        }
        pressed = down;
        unscheduleSelf(pressAnimator);
        pressAlpha = 1f;
        if (down) {
            invalidateSelf();
        } else {
            pressStartMs = SystemClock.uptimeMillis();
            scheduleSelf(pressAnimator, pressStartMs + PRESS_FADE_MS);
            invalidateSelf();
        }
    }

    private final Runnable pressAnimator = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            float t = Math.min(1f, (now - pressStartMs) / (float) PRESS_FADE_MS);
            pressAlpha = Math.max(0f, 1f - t);
            invalidateSelf();
            if (pressAlpha > 0f) {
                scheduleSelf(this, now + 16L);
            }
        }
    };

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty() || rowCount <= 0) {
            return;
        }
        float rowHeight = b.height() / (float) rowCount;
        float top = b.top + rowHeight * ((rowCount - 1) / 2f);
        float bottom = top + rowHeight;
        // Compact: keep the band at ~62% of the row, vertically centered, so
        // the wheel's divider lines and neighbors stay visible around it.
        float vPad = rowHeight * (1f - 0.62f) / 2f;
        float left = b.left + insetPx;
        float right = b.right - insetPx;

        canvas.drawRoundRect(left, top + vPad, right, bottom - vPad,
                radiusPx, radiusPx, paint);

        // Ripple-like flash, clipped to the exact cell shape.
        if (pressAlpha > 0f) {
            highlightPaint.setAlpha((int) (pressAlpha * 90f));
            canvas.drawRoundRect(left, top + vPad, right, bottom - vPad,
                    radiusPx, radiusPx, highlightPaint);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
