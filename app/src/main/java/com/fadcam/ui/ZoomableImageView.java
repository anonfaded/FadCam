package com.fadcam.ui;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

public class ZoomableImageView extends AppCompatImageView {
    private static final float MIN_SCALE = 1f;
    private static final float MAX_SCALE = 5f;

    /**
     * Listener for single-tap events on the image, confirmed after
     * ruling out double-tap and scroll gestures.
     */
    public interface OnSingleTapListener {
        void onSingleTap();
    }

    private final Matrix matrix = new Matrix();
    private final float[] values = new float[9];
    private final PointF last = new PointF();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private boolean dragging = false;
    private float currentScale = 1f;
    private float baseScale = 1f;
    @Nullable
    private OnSingleTapListener singleTapListener;

    public ZoomableImageView(@NonNull Context context) {
        this(context, null);
    }

    public ZoomableImageView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        gestureDetector = new GestureDetector(context, new GestureListener());
    }

    /**
     * Sets a listener that will be called when the user performs a confirmed single tap.
     *
     * @param listener The listener to set, or null to remove.
     */
    public void setOnSingleTapListener(@Nullable OnSingleTapListener listener) {
        this.singleTapListener = listener;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fitToCenter();
    }

    @Override
    public void setImageDrawable(@Nullable android.graphics.drawable.Drawable drawable) {
        super.setImageDrawable(drawable);
        post(this::fitToCenter);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getDrawable() == null) {
            return super.onTouchEvent(event);
        }
        gestureDetector.onTouchEvent(event);
        scaleDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                last.set(event.getX(), event.getY());
                dragging = true;
                break;
            case MotionEvent.ACTION_MOVE:
                if (dragging && !scaleDetector.isInProgress() && currentScale > baseScale) {
                    float dx = event.getX() - last.x;
                    float dy = event.getY() - last.y;
                    matrix.postTranslate(dx, dy);
                    clampTranslation();
                    setImageMatrix(matrix);
                    last.set(event.getX(), event.getY());
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                break;
            default:
                break;
        }
        return true;
    }

    private void fitToCenter() {
        if (getDrawable() == null || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        float drawableW = getDrawable().getIntrinsicWidth();
        float drawableH = getDrawable().getIntrinsicHeight();
        if (drawableW <= 0 || drawableH <= 0) {
            return;
        }

        matrix.reset();
        float scale = Math.min(getWidth() / drawableW, getHeight() / drawableH);
        baseScale = scale;
        currentScale = scale;
        matrix.postScale(scale, scale);
        float redundantX = (getWidth() - drawableW * scale) * 0.5f;
        float redundantY = (getHeight() - drawableH * scale) * 0.5f;
        matrix.postTranslate(redundantX, redundantY);
        setImageMatrix(matrix);
    }

    private void resetZoom() {
        fitToCenter();
    }

    private void clampTranslation() {
        if (getDrawable() == null) return;
        float drawableW = getDrawable().getIntrinsicWidth();
        float drawableH = getDrawable().getIntrinsicHeight();

        matrix.getValues(values);
        float transX = values[Matrix.MTRANS_X];
        float transY = values[Matrix.MTRANS_Y];
        float scaledW = drawableW * values[Matrix.MSCALE_X];
        float scaledH = drawableH * values[Matrix.MSCALE_Y];

        float minX = Math.min(0, getWidth() - scaledW);
        float maxX = Math.max(0, getWidth() - scaledW);
        float minY = Math.min(0, getHeight() - scaledH);
        float maxY = Math.max(0, getHeight() - scaledH);

        float clampedX = Math.min(maxX, Math.max(minX, transX));
        float clampedY = Math.min(maxY, Math.max(minY, transY));
        matrix.postTranslate(clampedX - transX, clampedY - transY);
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float scaleFactor = detector.getScaleFactor();
            float targetScale = currentScale * scaleFactor;
            float minAllowed = Math.max(baseScale, MIN_SCALE * baseScale);
            float maxAllowed = MAX_SCALE * baseScale;
            if (targetScale < minAllowed) {
                scaleFactor = minAllowed / currentScale;
            } else if (targetScale > maxAllowed) {
                scaleFactor = maxAllowed / currentScale;
            }
            matrix.postScale(scaleFactor, scaleFactor, detector.getFocusX(), detector.getFocusY());
            currentScale *= scaleFactor;
            clampTranslation();
            setImageMatrix(matrix);
            return true;
        }
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            if (singleTapListener != null) {
                singleTapListener.onSingleTap();
            }
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            resetZoom();
            return true;
        }
    }
}
