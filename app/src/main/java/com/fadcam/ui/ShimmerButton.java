package com.fadcam.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;

/**
 * A MaterialButton with a built-in shimmer animation effect.
 * The shimmer stays within the button's rounded corners.
 */
public class ShimmerButton extends MaterialButton {

    private Paint shimmerPaint;
    private LinearGradient shimmerGradient;
    private Matrix shimmerMatrix;
    private ValueAnimator shimmerAnimator;
    private float shimmerTranslate = 0f;
    private boolean isShimmering = false;
    private RectF buttonRect;

    private int shimmerWidth = 120;
    private int shimmerColor = 0x40FFFFFF;
    private long shimmerDuration = 1800;
    private long shimmerDelay = 800;
    private float cornerRadius = 0f;

    public ShimmerButton(@NonNull Context context) {
        super(context);
        init();
    }

    public ShimmerButton(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ShimmerButton(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        shimmerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shimmerMatrix = new Matrix();
        buttonRect = new RectF();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            cornerRadius = h / 2f; // Pill shape radius
            buttonRect.set(0, 0, w, h);
            createShimmerGradient();
            // Start shimmer immediately without delay
            post(this::startShimmer);
        }
    }

    private void createShimmerGradient() {
        shimmerGradient = new LinearGradient(
            0, 0,
            shimmerWidth, 0,
            new int[]{0x00FFFFFF, shimmerColor, 0x00FFFFFF},
            new float[]{0f, 0.5f, 1f},
            Shader.TileMode.CLAMP
        );
        shimmerPaint.setShader(shimmerGradient);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        if (isShimmering && shimmerGradient != null) {
            int saveCount = canvas.save();
            
            // Clip to rounded rect matching button shape
            canvas.clipPath(getClipPath());
            
            shimmerMatrix.reset();
            shimmerMatrix.setTranslate(shimmerTranslate, 0);
            shimmerGradient.setLocalMatrix(shimmerMatrix);
            
            canvas.drawRect(buttonRect, shimmerPaint);
            canvas.restoreToCount(saveCount);
        }
    }

    private android.graphics.Path getClipPath() {
        android.graphics.Path path = new android.graphics.Path();
        path.addRoundRect(buttonRect, cornerRadius, cornerRadius, android.graphics.Path.Direction.CW);
        return path;
    }

    /**
     * Start the shimmer animation.
     */
    public void startShimmer() {
        if (shimmerAnimator != null && shimmerAnimator.isRunning()) {
            return;
        }

        int width = getWidth();
        if (width <= 0) {
            // Width not ready yet, will be called from onSizeChanged
            return;
        }

        isShimmering = true;
        shimmerAnimator = ValueAnimator.ofFloat(-shimmerWidth, width + shimmerWidth);
        shimmerAnimator.setDuration(shimmerDuration);
        shimmerAnimator.setInterpolator(new LinearInterpolator());
        shimmerAnimator.setRepeatCount(ValueAnimator.INFINITE);
        shimmerAnimator.setRepeatMode(ValueAnimator.RESTART);
        
        // Set initial value to show shimmer immediately at the start position
        shimmerTranslate = -shimmerWidth;
        isShimmering = true;
        invalidate();
        
        shimmerAnimator.addUpdateListener(animation -> {
            shimmerTranslate = (float) animation.getAnimatedValue();
            invalidate();
        });
        shimmerAnimator.start();
    }

    /**
     * Stop the shimmer animation.
     */
    public void stopShimmer() {
        isShimmering = false;
        if (shimmerAnimator != null) {
            shimmerAnimator.cancel();
            shimmerAnimator = null;
        }
        invalidate();
    }

    /**
     * Set shimmer parameters.
     * @param width Width of shimmer band
     * @param color Color with alpha for shimmer
     * @param duration Animation duration in ms
     * @param delay Delay between animations in ms
     */
    public void setShimmerParams(int width, int color, long duration, long delay) {
        this.shimmerWidth = width;
        this.shimmerColor = color;
        this.shimmerDuration = duration;
        this.shimmerDelay = delay;
        if (getWidth() > 0 && getHeight() > 0) {
            createShimmerGradient();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Start shimmer as soon as view is attached, don't wait for size
        post(() -> {
            if (getWidth() > 0) {
                startShimmer();
            }
        });
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopShimmer();
    }
}
