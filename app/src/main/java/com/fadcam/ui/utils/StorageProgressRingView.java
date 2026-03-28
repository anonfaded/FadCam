package com.fadcam.ui.utils;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Compact circular storage ring used in the Home storage card.
 * Progress represents AVAILABLE / TOTAL storage.
 */
public class StorageProgressRingView extends View {

    private static final float START_ANGLE = -90f;
    private static final float FULL_SWEEP = 360f;
    private static final float GRADIENT_ROTATION_DEGREES = -88f;
    private static final float MIN_VISIBLE_SWEEP = 1f;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcBounds = new RectF();

    private float progress = 0f;
    private float strokeWidthPx;
    private int trackColor = Color.parseColor("#66D7E4F2");
    private int gradientStartColor = Color.parseColor("#7BED9F");
    private int gradientMidColor = Color.parseColor("#F7DC6F");
    private int gradientEndColor = Color.parseColor("#FF6B6B");
    private int textColor = Color.parseColor("#EAF4FF");
    private SweepGradient sweepGradient;

    public StorageProgressRingView(Context context) {
        super(context);
        init();
    }

    public StorageProgressRingView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public StorageProgressRingView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        strokeWidthPx = dpToPx(2.6f);

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeCap(Paint.Cap.BUTT);
        trackPaint.setStrokeWidth(strokeWidthPx);
        trackPaint.setColor(trackColor);

        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeCap(Paint.Cap.BUTT);
        progressPaint.setStrokeWidth(strokeWidthPx);

        textPaint.setColor(textColor);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        textPaint.setTextSize(dpToPx(5.5f));
    }

    public void setProgress(float value) {
        float clamped = Math.max(0f, Math.min(1f, value));
        if (Math.abs(progress - clamped) < 0.0001f) {
            return;
        }
        progress = clamped;
        updateGradientColors();
        invalidate();
    }

    public float getProgress() {
        return progress;
    }

    private void updateGradientColors() {
        int baseColor;
        if (progress >= 0.5f) {
            float t = (progress - 0.5f) / 0.5f;
            baseColor = blendColors(Color.parseColor("#F5B041"), Color.parseColor("#2ECC71"), t);
        } else {
            float t = progress / 0.5f;
            baseColor = blendColors(Color.parseColor("#E74C3C"), Color.parseColor("#F5B041"), t);
        }

        gradientStartColor = lighten(baseColor, 0.22f);
        gradientMidColor = baseColor;
        gradientEndColor = darken(baseColor, 0.20f);
        sweepGradient = null;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float halfStroke = strokeWidthPx / 2f;
        arcBounds.set(halfStroke, halfStroke, w - halfStroke, h - halfStroke);
        sweepGradient = null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }

        canvas.drawArc(arcBounds, START_ANGLE, FULL_SWEEP, false, trackPaint);

        if (progress <= 0f) {
            drawCenterText(canvas);
            return;
        }

        if (sweepGradient == null) {
            sweepGradient = new SweepGradient(
                    getWidth() / 2f,
                    getHeight() / 2f,
                    new int[]{gradientStartColor, gradientMidColor, gradientEndColor},
                    new float[]{0f, 0.55f, 1f}
            );
        }
        progressPaint.setShader(sweepGradient);

        canvas.save();
        canvas.rotate(GRADIENT_ROTATION_DEGREES, getWidth() / 2f, getHeight() / 2f);
        float sweep = Math.max(MIN_VISIBLE_SWEEP, FULL_SWEEP * progress);
        canvas.drawArc(arcBounds, 0f, sweep, false, progressPaint);
        canvas.restore();

        drawCenterText(canvas);
    }

    private void drawCenterText(Canvas canvas) {
        String percentText = Math.round(progress * 100f) + "%";
        Paint.FontMetrics fontMetrics = textPaint.getFontMetrics();
        float textBaseline = (getHeight() / 2f) - ((fontMetrics.ascent + fontMetrics.descent) / 2f);
        canvas.drawText(percentText, getWidth() / 2f, textBaseline, textPaint);
    }

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    private int lighten(int color, float amount) {
        return blendColors(color, Color.WHITE, amount);
    }

    private int darken(int color, float amount) {
        return blendColors(color, Color.BLACK, amount);
    }

    private int blendColors(int from, int to, float amount) {
        float t = Math.max(0f, Math.min(1f, amount));
        int a = (int) (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t);
        int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * t);
        int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * t);
        int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t);
        return Color.argb(a, r, g, b);
    }
}
