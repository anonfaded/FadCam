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
import androidx.appcompat.widget.AppCompatTextView;

/**
 * A TextView with a built-in shimmer animation effect.
 * Used for the FadCam Pro header button in HomeFragment.
 */
public class ShimmerTextView extends AppCompatTextView {

    private Paint shimmerPaint;
    private LinearGradient shimmerGradient;
    private Matrix shimmerMatrix;
    private ValueAnimator shimmerAnimator;
    private float shimmerTranslate = 0f;
    private boolean isShimmering = false;
    private RectF viewRect;

    private int shimmerWidth = 50;
    private int shimmerColor = 0x45FFFFFF;
    private long shimmerDuration = 1200;
    private long shimmerDelay = 0;
    private float cornerRadius = 0f;

    public ShimmerTextView(@NonNull Context context) {
        super(context);
        init();
    }

    public ShimmerTextView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ShimmerTextView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        shimmerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shimmerMatrix = new Matrix();
        viewRect = new RectF();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            cornerRadius = h / 2f; // Match pill shape
            viewRect.set(0, 0, w, h);
            createShimmerGradient();
            startShimmer();
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
            android.graphics.Path clipPath = new android.graphics.Path();
            clipPath.addRoundRect(viewRect, cornerRadius, cornerRadius, android.graphics.Path.Direction.CW);
            canvas.clipPath(clipPath);
            
            shimmerMatrix.reset();
            shimmerMatrix.setTranslate(shimmerTranslate, 0);
            shimmerGradient.setLocalMatrix(shimmerMatrix);
            
            canvas.drawRect(viewRect, shimmerPaint);
            canvas.restoreToCount(saveCount);
        }
    }

    /**
     * Start the shimmer animation.
     */
    public void startShimmer() {
        if (shimmerAnimator != null && shimmerAnimator.isRunning()) {
            return;
        }

        isShimmering = true;
        shimmerAnimator = ValueAnimator.ofFloat(-shimmerWidth, getWidth() + shimmerWidth);
        shimmerAnimator.setDuration(shimmerDuration);
        shimmerAnimator.setInterpolator(new LinearInterpolator());
        shimmerAnimator.setRepeatCount(ValueAnimator.INFINITE);
        shimmerAnimator.setRepeatMode(ValueAnimator.RESTART);
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

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getWidth() > 0) {
            startShimmer();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopShimmer();
    }
}
