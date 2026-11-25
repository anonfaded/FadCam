package com.fadcam.ui.animations;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * The closing payoff: two soft golden streams that fade in from the top corners
 * and converge into the FadCam icon, with two slow glowing particles.
 *
 * <p>The icon, its rounded clip and its gold frame are all drawn from one
 * rectangle in this view. That is deliberate: it is impossible for the icon to
 * sit off-centre or leave a gap inside the frame, because the bitmap shader,
 * the clip radius and the frame stroke share the same bounds and radius.</p>
 */
public class PayoffFlowView extends View implements AnimationClock.FrameListener {

    private static final long LOOP_MS = 6500L;
    private static final float PARTICLE_OFFSET = 0.5f;
    private static final float ICON_RADIUS_FRACTION = 0.19f;

    private final Path[] paths = {new Path(), new Path()};
    private final PathMeasure[] measures = {new PathMeasure(), new PathMeasure()};
    private final Paint beamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint floorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF iconRect = new RectF();
    private final RectF frameRect = new RectF();
    private final Matrix iconMatrix = new Matrix();
    private final Matrix floorMatrix = new Matrix();
    private final float[] point = new float[2];

    private Bitmap icon;
    private BitmapShader iconShader;
    private Bitmap beams;
    private Shader floorShader;
    private Shader floorHaze;
    private Shader frameShader;

    private float iconSize;
    private float iconBottomMargin;
    private boolean active;
    private boolean clockAttached;
    private float phase;

    public PayoffFlowView(Context context) {
        this(context, null);
    }

    public PayoffFlowView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PayoffFlowView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        AnimationClock.get().install(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        particlePaint.setColor(0xFFFFE0A6);
        haloPaint.setShader(new RadialGradient(0f, 0f, dp(7f),
                new int[]{0x99FFD78C, 0x33FFD78C, 0x00FFD78C},
                new float[]{0f, 0.42f, 1f}, Shader.TileMode.CLAMP));

        floorPaint.setStyle(Paint.Style.FILL);

        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(dp(2f));

        highlightPaint.setStyle(Paint.Style.STROKE);
        highlightPaint.setStrokeWidth(dp(0.6f));
        highlightPaint.setColor(0x40FFFFFF);
    }

    /** App icon bitmap; pass the full-bleed square icon for the current edition. */
    public void setIcon(@Nullable Bitmap bitmap) {
        icon = bitmap;
        iconShader = null;
        rebuildGeometry();
        invalidate();
    }

    /** Icon size and distance from the bottom of this view, in pixels. */
    public void setIconMetrics(float sizePx, float bottomMarginPx) {
        if (Math.abs(iconSize - sizePx) < 0.5f
                && Math.abs(iconBottomMargin - bottomMarginPx) < 0.5f) {
            return;
        }
        iconSize = sizePx;
        iconBottomMargin = bottomMarginPx;
        rebuildGeometry();
        invalidate();
    }

    /** Called when the slide becomes visible and when it leaves. */
    public void setActive(boolean enabled) {
        if (active == enabled) return;
        active = enabled;
        updateClock();
        invalidate();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        rebuildGeometry();
    }

    private void rebuildGeometry() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || iconSize <= 0f) return;

        float cx = w * 0.5f;
        float bottom = h - iconBottomMargin;
        iconRect.set(cx - iconSize * 0.5f, bottom - iconSize,
                cx + iconSize * 0.5f, bottom);

        if (icon != null) {
            if (iconShader == null) {
                iconShader = new BitmapShader(icon, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            }
            float scale = iconSize / icon.getWidth();
            iconMatrix.setScale(scale, scale);
            iconMatrix.postTranslate(iconRect.left, iconRect.top);
            iconShader.setLocalMatrix(iconMatrix);
            iconPaint.setShader(iconShader);
        }

        // Vertical, not diagonal: a left-to-right gradient made the frame look
        // brighter on one side, which read as if the floor glow was off-centre.
        frameShader = new LinearGradient(iconRect.left, iconRect.top,
                iconRect.left, iconRect.bottom,
                new int[]{0xFFF9E2A8, 0xFFE8C77B, 0xFFC79A4E, 0xFF9A7332},
                new float[]{0f, 0.35f, 0.78f, 1f}, Shader.TileMode.CLAMP);
        framePaint.setShader(frameShader);

        floorShader = new RadialGradient(0f, 0f, iconSize * 1.05f,
                new int[]{0x59F0D08A, 0x24E8C77B, 0x00E8C77B},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        floorHaze = new RadialGradient(0f, 0f, iconSize * 1.35f,
                new int[]{0x16E8C77B, 0x09E8C77B, 0x00E8C77B},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);

        float floorX = iconRect.centerX();
        float floorY = iconRect.bottom + dp(1f);
        floorMatrix.setScale(1f, 0.2f);
        floorMatrix.postTranslate(floorX, floorY);
        floorShader.setLocalMatrix(floorMatrix);
        floorHaze.setLocalMatrix(floorMatrix);

        buildStreams(w, h);
        invalidate();
    }

    /** Rebuilds the stationary stream paths and the pre-blurred beam band. */
    private void buildStreams(int w, int h) {
        float endX = iconRect.centerX();
        float endY = iconRect.centerY();
        float startY = dp(4f);

        for (int i = 0; i < 2; i++) {
            boolean left = i == 0;
            float startX = w * (left ? 0.12f : 0.88f);
            float middleX = w * (left ? 0.38f : 0.62f);

            Path path = paths[i];
            path.reset();
            path.moveTo(startX, startY);
            path.cubicTo(startX, h * 0.36f, middleX, h * 0.46f, endX, endY);
            measures[i].setPath(path, false);
        }

        if (beams != null) beams.recycle();
        beams = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(beams);
        drawIconHalo(canvas);
        for (Path path : paths) {
            // Wide, soft band first, then a tighter core. Blurred in software so
            // the edges melt instead of reading as a hard, drawn line.
            drawBeamPass(canvas, path, dp(5f), dp(3f), 0.14f, startY, endY);
            drawBeamPass(canvas, path, dp(2.5f), dp(1.5f), 0.24f, startY, endY);
            drawBeamPass(canvas, path, dp(1.2f), dp(0.6f), 0.45f, startY, endY);
            drawBeamPass(canvas, path, dp(0.8f), dp(0.2f), 0.85f, startY, endY);
        }
    }

    /** Soft aura hugging the frame, so the icon sits in light instead of on top of it. */
    private void drawIconHalo(Canvas canvas) {
        float radius = iconRect.width() * ICON_RADIUS_FRACTION;
        beamPaint.reset();
        beamPaint.setAntiAlias(true);
        beamPaint.setStyle(Paint.Style.STROKE);

        beamPaint.setStrokeWidth(dp(16f));
        beamPaint.setMaskFilter(new BlurMaskFilter(dp(24f), BlurMaskFilter.Blur.NORMAL));
        beamPaint.setColor(0x24F0D08A);
        canvas.drawRoundRect(iconRect, radius, radius, beamPaint);

        beamPaint.setStrokeWidth(dp(7f));
        beamPaint.setMaskFilter(new BlurMaskFilter(dp(12f), BlurMaskFilter.Blur.NORMAL));
        beamPaint.setColor(0x30F0D08A);
        canvas.drawRoundRect(iconRect, radius, radius, beamPaint);

        beamPaint.setStrokeWidth(dp(2f));
        beamPaint.setMaskFilter(new BlurMaskFilter(dp(5f), BlurMaskFilter.Blur.NORMAL));
        beamPaint.setColor(0x3DF0D08A);
        canvas.drawRoundRect(iconRect, radius, radius, beamPaint);

        beamPaint.setMaskFilter(null);
    }

    private void drawBeamPass(Canvas canvas, Path path, float width, float blur,
                              float peakAlpha, float startY, float endY) {
        beamPaint.reset();
        beamPaint.setAntiAlias(true);
        beamPaint.setStyle(Paint.Style.STROKE);
        beamPaint.setStrokeWidth(width);
        beamPaint.setStrokeCap(Paint.Cap.ROUND);
        beamPaint.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL));
        beamPaint.setShader(new LinearGradient(0f, startY, 0f, endY,
                new int[]{0x00E8C77B, alphaColor(peakAlpha * 0.45f), alphaColor(peakAlpha)},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawPath(path, beamPaint);
    }

    private int alphaColor(float alpha) {
        int a = Math.max(0, Math.min(255, (int) (alpha * 255f)));
        return (a << 24) | 0x00E8C77B;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (iconRect.isEmpty()) return;

        drawFloor(canvas);
        if (beams != null) canvas.drawBitmap(beams, 0f, 0f, null);
        if (active) drawParticles(canvas);
        drawIcon(canvas);
    }

    /** Soft golden pool that reads as light spilling on the floor below the frame. */
    private void drawFloor(Canvas canvas) {
        if (floorShader == null) return;
        // Both gradients are centred on the icon by their local matrix, so the
        // pool is symmetric no matter where the icon sits in this view.
        float centerX = iconRect.centerX();
        float centerY = iconRect.bottom + dp(1f);

        floorPaint.setShader(floorHaze);
        canvas.drawCircle(centerX, centerY, iconSize * 1.35f, floorPaint);
        floorPaint.setShader(floorShader);
        canvas.drawCircle(centerX, centerY, iconSize * 1.05f, floorPaint);
        floorPaint.setShader(null);
    }

    /**
     * The icon and its gold frame share one rect and one corner radius, so the
     * icon always fills the frame edge to edge.
     */
    private void drawIcon(Canvas canvas) {
        float radius = iconRect.width() * ICON_RADIUS_FRACTION;
        if (iconShader != null) {
            iconPaint.setShader(iconShader);
            canvas.drawRoundRect(iconRect, radius, radius, iconPaint);
        }

        float stroke = dp(2f);
        frameRect.set(iconRect);
        frameRect.inset(stroke * 0.5f, stroke * 0.5f);
        framePaint.setStrokeWidth(stroke);
        canvas.drawRoundRect(frameRect, radius - stroke * 0.5f, radius - stroke * 0.5f, framePaint);

        // Thin inner highlight so the frame reads as metal, not a flat outline.
        float inset = dp(2.4f);
        frameRect.set(iconRect);
        frameRect.inset(inset, inset);
        canvas.drawRoundRect(frameRect, radius - inset, radius - inset, highlightPaint);
    }

    private void drawParticles(Canvas canvas) {
        for (int i = 0; i < 2; i++) {
            PathMeasure measure = measures[i];
            float length = measure.getLength();
            if (length <= 0f) continue;

            float t = (phase + i * PARTICLE_OFFSET) % 1f;
            float envelope = (float) Math.sin(t * Math.PI);
            int alpha = (int) (255f * envelope);
            if (alpha < 4) continue;

            if (!measure.getPosTan(t * length, point, null)) continue;
            haloPaint.setAlpha((int) (alpha * 0.55f));
            canvas.drawCircle(point[0], point[1], dp(7f), haloPaint);
            particlePaint.setAlpha(alpha);
            canvas.drawCircle(point[0], point[1], dp(1.9f), particlePaint);
        }
        haloPaint.setAlpha(255);
        particlePaint.setAlpha(255);
    }

    @Override
    public void onAnimFrame(long timeMs) {
        phase = (timeMs % LOOP_MS) / (float) LOOP_MS;
        invalidate();
    }

    private void updateClock() {
        boolean should = active && isAttachedToWindow()
                && getWindowVisibility() == VISIBLE
                && isShown();
        if (should && !clockAttached) {
            AnimationClock.get().add(this);
            clockAttached = true;
        } else if (!should && clockAttached) {
            AnimationClock.get().remove(this);
            clockAttached = false;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateClock();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (clockAttached) {
            AnimationClock.get().remove(this);
            clockAttached = false;
        }
        if (beams != null) {
            beams.recycle();
            beams = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateClock();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateClock();
    }
}
