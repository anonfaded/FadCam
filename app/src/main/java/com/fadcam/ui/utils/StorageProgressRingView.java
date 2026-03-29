package com.fadcam.ui.utils;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
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

    public static final int STYLE_RING = 0;
    public static final int STYLE_MICRO_PILL_BAR = 1;
    public static final int STYLE_VERTICAL_BAR = 2;

    private static final float START_ANGLE = -90f;
    private static final float FULL_SWEEP = 360f;
    private static final float GRADIENT_ROTATION_DEGREES = -88f;
    private static final float MIN_VISIBLE_SWEEP = 1f;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcBounds = new RectF();
    private final RectF barTrackBounds = new RectF();
    private final RectF barProgressBounds = new RectF();
    private final RectF verticalTrackBounds = new RectF();
    private final RectF verticalProgressBounds = new RectF();

    private float progress = 0f;
    private boolean invertColors = false;
    private float strokeWidthPx;
    private float barCornerRadiusPx;
    private int trackColor = Color.parseColor("#66D7E4F2");
    private int gradientStartColor = Color.parseColor("#7BED9F");
    private int gradientMidColor = Color.parseColor("#F7DC6F");
    private int gradientEndColor = Color.parseColor("#FF6B6B");
    private int textColor = Color.parseColor("#EAF4FF");
    private SweepGradient sweepGradient;
    private LinearGradient barGradient;
    private LinearGradient verticalGradient;
    private int indicatorStyle = STYLE_RING;

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
        barCornerRadiusPx = dpToPx(4f);

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
        textPaint.setTextSize(dpToPx(7.5f));
    }

    public void setIndicatorStyle(int style) {
        int normalized;
        if (style == STYLE_MICRO_PILL_BAR) {
            normalized = STYLE_MICRO_PILL_BAR;
        } else if (style == STYLE_VERTICAL_BAR) {
            normalized = STYLE_VERTICAL_BAR;
        } else {
            normalized = STYLE_RING;
        }
        if (indicatorStyle == normalized) {
            return;
        }
        indicatorStyle = normalized;
        sweepGradient = null;
        barGradient = null;
        verticalGradient = null;
        invalidate();
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

    /**
     * When true, the color gradient is inverted so that high progress = red (used/bad)
     * and low progress = green (free/good). Use this when progress represents a "used" fraction.
     */
    public void setColorsInverted(boolean inverted) {
        if (invertColors == inverted) return;
        invertColors = inverted;
        sweepGradient = null;
        barGradient = null;
        verticalGradient = null;
        updateGradientColors();
        invalidate();
    }

    private void updateGradientColors() {
        float colorProgress = invertColors ? (1f - progress) : progress;
        int baseColor;
        if (colorProgress >= 0.5f) {
            float t = (colorProgress - 0.5f) / 0.5f;
            baseColor = blendColors(Color.parseColor("#F5B041"), Color.parseColor("#2ECC71"), t);
        } else {
            float t = colorProgress / 0.5f;
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

        float barWidth = Math.max(dpToPx(20f), w - dpToPx(6f));
        float barHeight = Math.min(dpToPx(8f), Math.max(dpToPx(6f), h * 0.34f));
        float barLeft = (w - barWidth) / 2f;
        float barTop = (h - barHeight) / 2f;
        barTrackBounds.set(barLeft, barTop, barLeft + barWidth, barTop + barHeight);
        float verticalWidth = Math.min(dpToPx(9f), Math.max(dpToPx(7f), w * 0.40f));
        float verticalHeight = Math.max(dpToPx(18f), h - dpToPx(3f));
        float verticalLeft = (w - verticalWidth) / 2f;
        float verticalTop = (h - verticalHeight) / 2f;
        verticalTrackBounds.set(verticalLeft, verticalTop, verticalLeft + verticalWidth, verticalTop + verticalHeight);
        sweepGradient = null;
        barGradient = null;
        verticalGradient = null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }

        if (indicatorStyle == STYLE_MICRO_PILL_BAR) {
            drawMicroPillBar(canvas);
            return;
        }
        if (indicatorStyle == STYLE_VERTICAL_BAR) {
            drawVerticalBar(canvas);
            return;
        }

        canvas.drawArc(arcBounds, START_ANGLE, FULL_SWEEP, false, trackPaint);

        if (progress <= 0f) {
            drawCenterText(canvas, textColor, dpToPx(7.5f));
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

        drawCenterText(canvas, textColor, dpToPx(7.5f));
    }

    private void drawMicroPillBar(Canvas canvas) {
        trackPaint.setShader(null);
        trackPaint.setStyle(Paint.Style.FILL);
        trackPaint.setColor(Color.parseColor("#7AA9B9C4"));
        canvas.drawRoundRect(barTrackBounds, barCornerRadiusPx, barCornerRadiusPx, trackPaint);

        if (progress <= 0f) {
            drawCenterText(canvas, Color.parseColor("#F5FBFF"), dpToPx(5.1f));
            progressPaint.setStyle(Paint.Style.STROKE);
            trackPaint.setStyle(Paint.Style.STROKE);
            return;
        }

        if (barGradient == null) {
            barGradient = new LinearGradient(
                    barTrackBounds.left,
                    barTrackBounds.centerY(),
                    barTrackBounds.right,
                    barTrackBounds.centerY(),
                    new int[]{gradientStartColor, gradientMidColor, gradientEndColor},
                    new float[]{0f, 0.55f, 1f},
                    Shader.TileMode.CLAMP
            );
        }

        float progressRight = barTrackBounds.left + (barTrackBounds.width() * progress);
        barProgressBounds.set(
                barTrackBounds.left,
                barTrackBounds.top,
                Math.max(barTrackBounds.left + dpToPx(2f), progressRight),
                barTrackBounds.bottom
        );

        progressPaint.setShader(barGradient);
        progressPaint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(barProgressBounds, barCornerRadiusPx, barCornerRadiusPx, progressPaint);

        progressPaint.setShader(null);
        progressPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStyle(Paint.Style.STROKE);
        drawCenterText(canvas, resolveOverlayTextColor(gradientMidColor), dpToPx(5.1f));
    }

    private void drawVerticalBar(Canvas canvas) {
        trackPaint.setShader(null);
        trackPaint.setStyle(Paint.Style.FILL);
        trackPaint.setColor(Color.parseColor("#7AA9B9C4"));
        canvas.drawRoundRect(verticalTrackBounds, barCornerRadiusPx, barCornerRadiusPx, trackPaint);

        if (progress > 0f) {
            if (verticalGradient == null) {
                verticalGradient = new LinearGradient(
                        verticalTrackBounds.centerX(),
                        verticalTrackBounds.bottom,
                        verticalTrackBounds.centerX(),
                        verticalTrackBounds.top,
                        new int[]{gradientEndColor, gradientMidColor, gradientStartColor},
                        new float[]{0f, 0.55f, 1f},
                        Shader.TileMode.CLAMP
                );
            }

            float progressTop = verticalTrackBounds.bottom - (verticalTrackBounds.height() * progress);
            verticalProgressBounds.set(
                    verticalTrackBounds.left,
                    Math.min(verticalTrackBounds.bottom - dpToPx(2f), progressTop),
                    verticalTrackBounds.right,
                    verticalTrackBounds.bottom
            );

            progressPaint.setShader(verticalGradient);
            progressPaint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(verticalProgressBounds, barCornerRadiusPx, barCornerRadiusPx, progressPaint);
        }

        progressPaint.setShader(null);
        progressPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStyle(Paint.Style.STROKE);
        drawCenterText(canvas, progress > 0f ? resolveOverlayTextColor(gradientMidColor) : Color.parseColor("#F5FBFF"), dpToPx(4.9f));
    }

    private void drawCenterText(Canvas canvas, int color, float sizePx) {
        String percentText = Math.round(progress * 100f) + "%";
        textPaint.setColor(color);
        textPaint.setTextSize(sizePx);
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

    private int resolveOverlayTextColor(int backgroundColor) {
        double luminance = ((0.299 * Color.red(backgroundColor))
                + (0.587 * Color.green(backgroundColor))
                + (0.114 * Color.blue(backgroundColor))) / 255d;
        return luminance > 0.58d
                ? Color.parseColor("#12202A")
                : Color.parseColor("#F5FBFF");
    }
}
