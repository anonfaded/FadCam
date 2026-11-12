package com.fadcam.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Slow, seamless chip marquee for the onboarding "Coming Soon" mini apps.
 *
 * <p>The track is painted as one continuous row; a second copy makes the loop
 * seamless. LTR moves right-to-left, RTL moves left-to-right while keeping the
 * normal reading order, and the edges fade out on both sides.</p>
 */
public class MiniAppsMarqueeView extends View {

    /** Slow enough to read, fast enough to feel alive. */
    private static final float SPEED_DP_PER_SECOND = 24f;

    private final Paint chipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final List<String> labels = new ArrayList<>();
    private final List<Float> widths = new ArrayList<>();

    private final float density;
    private final float chipHeight;
    private final float chipRadius;
    private final float chipPad;
    private final float chipGap;
    private final float edgeFade;

    private float contentWidth;
    private float offset;
    private boolean rtl;
    private boolean running;
    private ValueAnimator animator;
    private Shader leftFade;
    private Shader rightFade;

    public MiniAppsMarqueeView(Context context) {
        this(context, null);
    }

    public MiniAppsMarqueeView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MiniAppsMarqueeView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = getResources().getDisplayMetrics().density;
        chipHeight = 26f * density;
        // A squircle like the page indicators, not a fully rounded pill.
        chipRadius = 8f * density;
        chipPad = 11f * density;
        chipGap = 8f * density;
        edgeFade = 22f * density;

        textPaint.setTypeface(Typeface.MONOSPACE);
        textPaint.setTextSize(11f * getResources().getDisplayMetrics().scaledDensity);
        textPaint.setColor(0xFFF3F0E8);
        textPaint.setTextAlign(Paint.Align.LEFT);

        chipPaint.setColor(0xE60D0F10);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(Math.max(1f, 0.8f * density));
        borderPaint.setColor(0x66C6A66A);

        rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
    }

    /** Replaces the chips. Empty input hides the view. */
    public void setItems(@Nullable List<String> items) {
        labels.clear();
        widths.clear();
        if (items != null) {
            labels.addAll(items);
        }
        if (labels.isEmpty()) {
            setVisibility(GONE);
            stopMarquee();
            return;
        }
        setVisibility(VISIBLE);
        setContentDescription(TextUtils.join(", ", labels));
        measureChips();
        updateAnimator();
        invalidate();
    }

    /** Called by the slide while it is visible. */
    public void startMarquee() {
        running = true;
        updateAnimator();
    }

    /** Called when the slide leaves the screen. */
    public void stopMarquee() {
        running = false;
        cancelAnimator();
        offset = 0f;
        invalidate();
    }

    private void measureChips() {
        float total = 0f;
        for (int i = 0; i < labels.size(); i++) {
            float width = chipPad * 2f + textPaint.measureText(labels.get(i));
            widths.add(width);
            total += width + chipGap;
        }
        // The trailing gap keeps spacing consistent across the loop seam.
        contentWidth = total;
    }

    private void updateAnimator() {
        cancelAnimator();
        if (!running || labels.isEmpty() || getWidth() == 0) {
            return;
        }
        if (!animationsEnabled() || contentWidth <= getWidth() - 2f * edgeFade) {
            offset = 0f;
            invalidate();
            return;
        }
        float distance = contentWidth;
        long duration = Math.max(1000L,
                (long) (distance / (SPEED_DP_PER_SECOND * density) * 1000f));
        animator = ValueAnimator.ofFloat(0f, distance);
        animator.setDuration(duration);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.RESTART);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            offset = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private void cancelAnimator() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    private boolean animationsEnabled() {
        try {
            float scale = Settings.Global.getFloat(
                    getContext().getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            return scale != 0f;
        } catch (Exception ignored) {
            return true;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        float fade = Math.min(edgeFade, w / 4f);
        leftFade = new LinearGradient(0f, 0f, fade, 0f,
                0xFF000000, 0x00000000, Shader.TileMode.CLAMP);
        rightFade = new LinearGradient(w - fade, 0f, w, 0f,
                0x00000000, 0xFF000000, Shader.TileMode.CLAMP);
        updateAnimator();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (labels.isEmpty() || widths.isEmpty()) {
            return;
        }
        // Chips may be wider than the viewport at the edges; clip before the
        // edge fades so nothing leaks past the padded content area.
        canvas.clipRect(0f, 0f, getWidth(), getHeight());
        float top = (getHeight() - chipHeight) * 0.5f;
        boolean staticTrack = contentWidth <= getWidth() - 2f * edgeFade;
        if (staticTrack) {
            drawCopy(canvas, top, 0f);
            return;
        }
        drawCopy(canvas, top, 0f);
        drawCopy(canvas, top, contentWidth);

        float fade = Math.min(edgeFade, getWidth() / 4f);
        if (leftFade != null && rightFade != null) {
            fadePaint.setShader(leftFade);
            canvas.drawRect(0f, 0f, fade, getHeight(), fadePaint);
            fadePaint.setShader(rightFade);
            canvas.drawRect(getWidth() - fade, 0f, getWidth(), getHeight(), fadePaint);
            fadePaint.setShader(null);
        }
    }

    private void drawCopy(Canvas canvas, float top, float extra) {
        if (rtl) {
            float consumed = 0f;
            for (int i = 0; i < labels.size(); i++) {
                float width = widths.get(i);
                float right = getWidth() + offset - extra - consumed;
                float left = right - width;
                if (right > 0f && left < getWidth()) {
                    drawChip(canvas, labels.get(i), left, top, width);
                }
                consumed += width + chipGap;
            }
        } else {
            float x = -offset + extra;
            for (int i = 0; i < labels.size(); i++) {
                float width = widths.get(i);
                if (x + width > 0f && x < getWidth()) {
                    drawChip(canvas, labels.get(i), x, top, width);
                }
                x += width + chipGap;
            }
        }
    }

    private void drawChip(Canvas canvas, String label, float left, float top, float width) {
        rect.set(left, top, left + width, top + chipHeight);
        canvas.drawRoundRect(rect, chipRadius, chipRadius, chipPaint);
        canvas.drawRoundRect(rect, chipRadius, chipRadius, borderPaint);
        float baseline = top + chipHeight * 0.5f
                - (textPaint.descent() + textPaint.ascent()) * 0.5f;
        canvas.drawText(label, left + chipPad, baseline, textPaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        stopMarquee();
        super.onDetachedFromWindow();
    }
}
