package com.fadcam.ui.animations;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

import androidx.core.content.res.ResourcesCompat;

import com.fadcam.R;

/**
 * Reusable paint and path scratch space plus the small drawing vocabulary every
 * scene shares. One kit per host view; all calls are main-thread only.
 *
 * <p>Nothing here allocates during {@code draw()}: paints, rects and paths are
 * reused, and text is measured with the already-configured paint.</p>
 */
public final class CanvasKit {

    public final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    public final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    public final RectF rect = new RectF();
    public final Path path = new Path();

    private final Rect iconBounds = new Rect();
    private final Typeface mono = Typeface.MONOSPACE;
    private final Typeface monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
    private Typeface iconFont;

    public CanvasKit(Context context) {
        iconFont = ResourcesCompat.getFont(context, R.font.materialicons);
        textPaint.setSubpixelText(true);
        textPaint.setLinearText(false);
    }

    /** Replaces the icon ligature font, e.g. when a host ships a custom icon set. */
    public void setIconFont(Typeface typeface) {
        iconFont = typeface;
    }

    /** Paints the shared stage: background, layout grid and corner ticks. */
    public void stage(Canvas c, float width, float height, SceneStyle style) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(style.background);
        c.drawRect(0f, 0f, width, height, paint);

        if (style.stageGrid) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(0.35f);
            paint.setColor(AnimationMath.withAlpha(style.accent, 18));
            for (float x = 12f; x < width; x += 12f) c.drawLine(x, 0f, x, height, paint);
            for (float y = 12f; y < height; y += 12f) c.drawLine(0f, y, width, y, paint);
        }

        if (style.cornerTicks) {
            paint.setStrokeWidth(0.55f);
            paint.setColor(AnimationMath.withAlpha(style.accentDeep, 61));
            float m = 4f;
            float len = 9f;
            c.drawLine(m, m, m + len, m, paint);
            c.drawLine(m, m, m, m + len, paint);
            c.drawLine(width - m, m, width - m - len, m, paint);
            c.drawLine(width - m, m, width - m, m + len, paint);
            c.drawLine(m, height - m, m + len, height - m, paint);
            c.drawLine(m, height - m, m, height - m - len, paint);
            c.drawLine(width - m, height - m, width - m - len, height - m, paint);
            c.drawLine(width - m, height - m, width - m, height - m - len, paint);
        }
    }

    public void fillRound(Canvas c, float l, float t, float r, float b,
                          float radius, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        rect.set(l, t, r, b);
        c.drawRoundRect(rect, radius, radius, paint);
    }

    public void frameRound(Canvas c, float l, float t, float r, float b,
                           float radius, int color, float stroke) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setColor(color);
        rect.set(l, t, r, b);
        c.drawRoundRect(rect, radius, radius, paint);
    }

    public void dot(Canvas c, float x, float y, float radius, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        c.drawCircle(x, y, radius, paint);
    }

    /**
     * Clips the canvas to a rounded rectangle. The caller owns the matching
     * {@link Canvas#restore()}; kept explicit so scenes can wrap several draws
     * in one clip cheaply.
     */
    public void clipRound(Canvas c, float l, float t, float r, float b, float radius) {
        path.reset();
        rect.set(l, t, r, b);
        path.addRoundRect(rect, radius, radius, Path.Direction.CW);
        c.clipPath(path);
    }

    /** Baseline-centred monospace label, the shared annotation style. */
    public void label(Canvas c, CharSequence s, float x, float y, float size,
                      int color, Paint.Align align, boolean bold) {
        textPaint.setTypeface(bold ? monoBold : mono);
        textPaint.setTextSize(size);
        textPaint.setColor(color);
        textPaint.setTextAlign(align);
        c.drawText(s, 0, s.length(), x, y, textPaint);
    }

    public float measure(CharSequence s, float size, boolean bold) {
        textPaint.setTypeface(bold ? monoBold : mono);
        textPaint.setTextSize(size);
        return textPaint.measureText(s, 0, s.length());
    }

    /** Draws a Material icon ligature centred on the given point. */
    public void icon(Canvas c, String glyph, float cx, float cy, float size, int color) {
        textPaint.setTypeface(iconFont != null ? iconFont : Typeface.DEFAULT);
        textPaint.setTextSize(size);
        textPaint.setColor(color);
        textPaint.setTextAlign(Paint.Align.CENTER);
        iconBounds.setEmpty();
        textPaint.getTextBounds(glyph, 0, glyph.length(), iconBounds);
        float baseline = cy - (iconBounds.top + iconBounds.bottom) * 0.5f;
        c.drawText(glyph, cx, baseline, textPaint);
    }
}
