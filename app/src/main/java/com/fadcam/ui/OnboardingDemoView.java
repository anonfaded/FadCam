package com.fadcam.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import com.fadcam.R;

/**
 * Small Canvas product previews for the onboarding multimedia brief.
 *
 * <p>Every demo shares one design grid (168x92 for feature cards, 120x64 for the
 * compact mini-app cards), one palette and one animation. The view draws a
 * meaningful final state; animation only reveals that state.</p>
 */
public class OnboardingDemoView extends View {

    public static final int TYPE_BACKGROUND = 0;
    public static final int TYPE_SCREEN = 1;
    public static final int TYPE_EDITOR = 2;
    public static final int TYPE_MOTION = 3;
    public static final int TYPE_REMOTE = 4;
    public static final int TYPE_FLASHLIGHT = 6;
    public static final int TYPE_QR = 7;
    public static final int TYPE_BODYCAM_SOLO = 8;
    public static final int TYPE_DASHCAM = 9;
    public static final int TYPE_FORENSIC = 10;

    private static final float DESIGN_W = 168f;
    private static final float DESIGN_H = 92f;
    private static final float COMPACT_W = 120f;
    private static final float COMPACT_H = 64f;

    private static final int C_BG = 0xFF070808;
    private static final int C_PANEL = 0xFF101213;
    private static final int C_PANEL_2 = 0xFF181B1D;
    private static final int C_PANEL_3 = 0xFF202326;
    private static final int C_LINE = 0xFF2C2F31;
    private static final int C_GOLD = 0xFFE8C77B;
    private static final int C_OLIVE = 0xFFC6A66A;
    private static final int C_INK = 0xFFF3F0E8;
    private static final int C_MUTED = 0xFF9C9991;
    private static final int C_DIM = 0xFF5C605F;
    private static final int C_RED = 0xFFD9564A;
    private static final int C_GREEN = 0xFF86D3A0;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Rect iconBounds = new Rect();
    private final Path path = new Path();
    private final Path strokeDst = new Path();
    private final PathMeasure strokeMeasure = new PathMeasure();
    private final float[] pos = new float[2];
    private final DashPathEffect dash = new DashPathEffect(new float[]{2f, 2f}, 0f);
    private final android.graphics.Shader beamPool = new android.graphics.RadialGradient(
            0f, 0f, 26f,
            new int[]{0x3DFFFFFF, 0x00FFFFFF}, null,
            android.graphics.Shader.TileMode.CLAMP);
    private final Typeface mono = Typeface.MONOSPACE;
    private Typeface iconFont;
    private final Typeface monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
    private final boolean[][] qr = new boolean[21][21];
    private final String[] objectConfidence = new String[3];
    private final String[] objectScore = new String[3];
    private final String[] objectTime = new String[3];
    private final Drawable[] objectIcons = new Drawable[3];
    private Drawable torchIcon;
    private String qrValue;
    private long lockStartedMs;
    private long cctvElapsedMs;
    private long cctvLastTickMs;

    private int type = TYPE_BACKGROUND;
    private boolean compact;
    private float progress;
    private ValueAnimator animator;
    private boolean played;

    private boolean ambientOn;
    private float ambientOffset;
    private float ambientLocal;
    private float ambientWave;

    public OnboardingDemoView(Context context) {
        this(context, null);
    }

    public OnboardingDemoView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public OnboardingDemoView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs,
                    R.styleable.OnboardingDemoView, defStyleAttr, 0);
            type = a.getInt(R.styleable.OnboardingDemoView_demoType, TYPE_BACKGROUND);
            compact = a.getBoolean(R.styleable.OnboardingDemoView_demoCompact, false);
            a.recycle();
        }
        String person = context.getString(R.string.onboarding_demo_person);
        String gun = context.getString(R.string.onboarding_demo_gun);
        String knife = context.getString(R.string.onboarding_demo_knife);
        objectConfidence[0] = context.getString(
                R.string.onboarding_demo_confidence, person, 96);
        objectConfidence[1] = context.getString(
                R.string.onboarding_demo_confidence, gun, 91);
        objectConfidence[2] = context.getString(
                R.string.onboarding_demo_confidence, knife, 88);
        int[] scores = {96, 91, 88};
        int[] minutes = {2, 5, 9};
        for (int i = 0; i < 3; i++) {
            objectScore[i] = context.getString(R.string.onboarding_demo_score, scores[i]);
            objectTime[i] = String.format(java.util.Locale.US, "02:%02d PM", minutes[i]);
        }
        qrValue = context.getString(R.string.onboarding_demo_qr_value);
        iconFont = ResourcesCompat.getFont(context, R.font.materialicons);
        objectIcons[0] = ContextCompat.getDrawable(context, R.drawable.ic_detection_person);
        objectIcons[1] = ContextCompat.getDrawable(context, R.drawable.ic_detection_gun);
        objectIcons[2] = ContextCompat.getDrawable(context, R.drawable.ic_detection_knife);
        torchIcon = ContextCompat.getDrawable(context, R.drawable.ic_detection_torch);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        buildScreenStroke();
        buildQrMatrix();
        progress = animationsEnabled() ? 0f : 1f;
    }

    private void buildScreenStroke() {
        path.moveTo(22f, 47f);
        path.cubicTo(36f, 18f, 50f, 56f, 68f, 34f);
        path.cubicTo(82f, 17f, 96f, 50f, 120f, 30f);
        strokeMeasure.setPath(path, false);
    }

    private void buildQrMatrix() {
        // Deterministic non-sensitive pattern: finder squares plus a stable fill.
        for (int x = 0; x < 21; x++) {
            for (int y = 0; y < 21; y++) {
                boolean finder = (x < 7 && y < 7) || (x > 13 && y < 7) || (x < 7 && y > 13);
                if (finder) {
                    qr[x][y] = false;
                } else {
                    int v = (x * 31 + y * 17 + x * y * 7) % 11;
                    qr[x][y] = v < 5;
                }
            }
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

    /** Starts the one-shot reveal. Safe to call more than once. */
    public void playOnce(long startDelayMs) {
        if (played) return;
        played = true;
        ambientOn = false;
        progress = 0f;
        lockStartedMs = 0L;
        cctvElapsedMs = 0L;
        cctvLastTickMs = 0L;
        if (!animationsEnabled()) {
            progress = 1f;
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(4200L);
        animator.setStartDelay(startDelayMs);
        // Even velocity across the whole story; each beat ease is handled by seg().
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    public void setAmbientOffset(float offset) {
        ambientOffset = offset;
    }

    /** One shared clock drives every preview; a per-view offset keeps them out of sync. */
    public void setAmbientPhase(float phase) {
        // Never run the ambient loop on top of the one-shot reveal.
        if (progress < 0.999f) {
            ambientOn = false;
            return;
        }
        ambientOn = true;
        ambientLocal = phase + ambientOffset;
        ambientLocal -= (float) Math.floor(ambientLocal);
        ambientWave = 0.5f + 0.5f * (float) Math.sin(ambientLocal * Math.PI * 2);
        invalidate();
    }

    public void clearAmbient() {
        ambientOn = false;
        invalidate();
    }

    /** Rewinds the demo so the next visit replays the one-shot from the start. */
    public void resetDemo() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        played = false;
        progress = 0f;
        ambientOn = false;
        lockStartedMs = 0L;
        cctvElapsedMs = 0L;
        cctvLastTickMs = 0L;
        invalidate();
    }

    /** Stops a running preview and leaves the meaningful final state. */
    public void stopDemo() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        progress = 1f;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        progress = 1f;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float dw = compact ? COMPACT_W : DESIGN_W;
        float dh = compact ? COMPACT_H : DESIGN_H;
        if (getWidth() == 0 || getHeight() == 0) return;
        float scale = Math.min(getWidth() / dw, getHeight() / dh);
        float ox = (getWidth() - dw * scale) * 0.5f;
        float oy = (getHeight() - dh * scale) * 0.5f;
        canvas.save();
        canvas.translate(ox, oy);
        canvas.scale(scale, scale);
        drawStage(canvas, dw, dh);
        switch (type) {
            case TYPE_SCREEN:
                drawScreenRecording(canvas, progress);
                break;
            case TYPE_EDITOR:
                drawEditor(canvas, progress);
                break;
            case TYPE_MOTION:
                drawMotion(canvas, progress);
                break;
            case TYPE_FORENSIC:
                drawForensic(canvas, progress);
                break;
            case TYPE_REMOTE:
                drawRemote(canvas, progress);
                break;
            case TYPE_BODYCAM_SOLO:
                drawBodycamSolo(canvas, progress);
                break;
            case TYPE_DASHCAM:
                drawDashcamSolo(canvas, progress);
                break;
            case TYPE_FLASHLIGHT:
                drawFlashlight(canvas, progress);
                break;
            case TYPE_QR:
                drawQr(canvas, progress);
                break;
            case TYPE_BACKGROUND:
            default:
                drawBackground(canvas, progress);
                break;
        }
        canvas.restore();
    }

    private void drawStage(Canvas c, float dw, float dh) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(C_BG);
        c.drawRect(0f, 0f, dw, dh, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.35f);
        paint.setColor(0x12E8C77B);
        for (float x = 12f; x < dw; x += 12f) c.drawLine(x, 0f, x, dh, paint);
        for (float y = 12f; y < dh; y += 12f) c.drawLine(0f, y, dw, y, paint);

        // Corner registration ticks, matching the dossier paper.
        paint.setStrokeWidth(0.55f);
        paint.setColor(0x3DC6A66A);
        float m = 4f;
        float l = 9f;
        c.drawLine(m, m, m + l, m, paint);
        c.drawLine(m, m, m, m + l, paint);
        c.drawLine(dw - m, m, dw - m - l, m, paint);
        c.drawLine(dw - m, m, dw - m, m + l, paint);
        c.drawLine(m, dh - m, m + l, dh - m, paint);
        c.drawLine(m, dh - m, m, dh - m - l, paint);
        c.drawLine(dw - m, dh - m, dw - m - l, dh - m, paint);
        c.drawLine(dw - m, dh - m, dw - m, dh - m - l, paint);
    }

    // ── 01 background recording ──────────────────────────────────────────────

    private void drawBackground(Canvas c, float p) {
        fillRound(c, 58f, 8f, 110f, 84f, 7f, 0xFF141719);
        frameRound(c, 58f, 8f, 110f, 84f, 7f, 0x55C6A66A, 0.8f);
        fillRound(c, 62f, 13f, 106f, 79f, 4f, 0xFF1B1F22);

        // Home screen: a clock and an app grid, then the phone locks.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.5f);
        paint.setColor(0x77E8C77B);
        c.drawCircle(76f, 26f, 6f, paint);
        c.drawLine(76f, 26f, 76f, 21.5f, paint);
        c.drawLine(76f, 26f, 79.5f, 27.8f, paint);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                float bx = 68.5f + col * 12f;
                float by = 41f + row * 11f;
                fillRound(c, bx, by, bx + 7f, by + 7f, 1.6f, 0xFF24282B);
                frameRound(c, bx, by, bx + 7f, by + 7f, 1.6f, 0x33E8C77B, 0.3f);
            }
        }

        float dim = seg(p, 0.35f, 0.72f);
        if (dim > 0f) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(C_BG);
            paint.setAlpha((int) (255f * dim));
            c.drawRect(62f, 13f, 106f, 79f, paint);
            paint.setAlpha(255);

            // Clear padlock: shackle, body, keyhole.
            int lockColor = withAlpha(C_GOLD, (int) (190f * dim));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.4f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(lockColor);
            path.reset();
            path.moveTo(80f, 49f);
            path.lineTo(80f, 44f);
            path.cubicTo(80f, 36.5f, 90f, 36.5f, 90f, 44f);
            path.lineTo(90f, 49f);
            c.drawPath(path, paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
            fillRound(c, 76f, 47f, 94f, 62f, 3f, 0xFF0D0F10);
            frameRound(c, 76f, 47f, 94f, 62f, 3f, lockColor, 1.2f);
            dot(c, 85f, 53f, 1.3f, lockColor);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1f);
            c.drawLine(85f, 54f, 85f, 57.5f, paint);
        }

        // The capture indicator starts exactly when the lock screen appears.
        if (dim > 0.5f) {
            if (lockStartedMs == 0L) lockStartedMs = android.os.SystemClock.uptimeMillis();
            long seconds = (android.os.SystemClock.uptimeMillis() - lockStartedMs) / 1000L;
            dot(c, 66f, 20f, 1.6f,
                    withAlpha(C_RED, (int) (55f + 200f * recBlink())));
            label(c, clockText(seconds), 102f, 22f, 5.5f, C_INK,
                    Paint.Align.RIGHT, false);
        }
    }

    // ── 02 screen recording ─────────────────────────────────────────────────

    private void drawScreenRecording(Canvas c, float p) {
        fillRound(c, 14f, 8f, 154f, 62f, 4f, 0xFF101214);
        frameRound(c, 14f, 8f, 154f, 62f, 4f, C_LINE, 0.8f);

        // Canvas area, with a vertical layer column on the right.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.4f);
        paint.setColor(0x12FFFFFF);
        for (float x = 24f; x < 126f; x += 16f) c.drawLine(x, 12f, x, 58f, paint);
        for (float y = 18f; y < 58f; y += 12f) c.drawLine(18f, y, 126f, y, paint);

        int activeLayer = ambientOn ? ((int) (ambientLocal * 3f)) % 3 : 0;

        // Layer 1: freehand pen stroke.
        int l1 = activeLayer == 0 ? 255 : 42;
        float reveal = seg(p, 0.08f, 0.72f);
        if (reveal > 0f) {
            strokeDst.reset();
            float len = strokeMeasure.getLength() * reveal;
            strokeMeasure.getSegment(0f, len, strokeDst, true);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(activeLayer == 0 ? 1.8f : 1.2f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(withAlpha(C_GOLD, l1));
            c.drawPath(strokeDst, paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
            if (activeLayer == 0 && strokeMeasure.getPosTan(len, pos, null)) {
                dot(c, pos[0], pos[1], 1.5f, withAlpha(C_GOLD, 255));
            }
        }

        // Layer 2: typed text annotation.
        int l2 = activeLayer == 1 ? 255 : 42;
        drawTextAnnotation(c, l2);

        // Layer 3: colour pencil stroke.
        int l3 = activeLayer == 2 ? 255 : 42;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.4f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(withAlpha(C_RED, l3));
        path.reset();
        path.moveTo(28f, 50f);
        path.cubicTo(44f, 30f, 64f, 54f, 82f, 38f);
        path.cubicTo(94f, 28f, 104f, 36f, 114f, 30f);
        c.drawPath(path, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);

        dot(c, 20f, 16f, 1.4f,
                withAlpha(C_RED, (int) (55f + 200f * recBlink())));

        drawLayer(c, 132f, 15f, 0, activeLayer == 0);
        drawLayer(c, 132f, 31f, 1, activeLayer == 1);
        drawLayer(c, 132f, 47f, 2, activeLayer == 2);

        // Bottom drawing toolbar: shapes, color, pen.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.45f);
        paint.setColor(C_LINE);
        c.drawLine(16f, 65f, 152f, 65f, paint);

        // Text tool drawn as a stroked icon, matching the palette and pen weights.
        int toolColor = activeLayer == 1 ? C_GOLD : C_MUTED;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(activeLayer == 1 ? 1.2f : 1.0f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(toolColor);
        c.drawLine(19f, 68.5f, 29f, 68.5f, paint);
        c.drawLine(24f, 68.5f, 24f, 78.5f, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);

        int paletteColor = activeLayer == 2 ? C_GOLD : C_MUTED;
        paint.setColor(paletteColor);
        paint.setStrokeWidth(activeLayer == 2 ? 1.1f : 0.8f);
        c.drawCircle(48f, 73f, 6f, paint);
        dot(c, 46f, 71f, 1.4f, C_GOLD);
        dot(c, 51f, 72f, 1.4f, C_INK);
        dot(c, 47f, 76f, 1.4f, C_RED);

        // Pen tool: a simple line, no arrow head.
        int penColor = activeLayer == 0 ? C_GOLD : C_MUTED;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(activeLayer == 0 ? 1.2f : 1.0f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(penColor);
        c.drawLine(66f, 78.5f, 78f, 67.5f, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawLayer(Canvas c, float x, float y, int kind, boolean active) {
        int activeGold = ambientOn
                ? withAlpha(C_GOLD, (int) (255f * (0.65f + 0.35f * ambientWave)))
                : C_GOLD;
        fillRound(c, x, y, x + 18f, y + 10f, 1.5f, active ? 0x1AE8C77B : 0xFF151719);
        frameRound(c, x, y, x + 18f, y + 10f, 1.5f, active ? activeGold : C_LINE, 0.7f);
        int ink = active ? activeGold : C_MUTED;
        if (kind == 0) {
            // Pen mark.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(0.55f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(ink);
            c.drawLine(x + 6f, y + 7f, x + 12f, y + 3.5f, paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
        } else if (kind == 1) {
            // Text mark.
            label(c, "T", x + 9f, y + 7.8f, 5.6f, ink, Paint.Align.CENTER, true);
        } else {
            // Colour mark.
            dot(c, x + 9f, y + 5f, 2.1f, ink);
        }
    }

    // ── 03 video editor ─────────────────────────────────────────────────────

    private void drawEditor(Canvas c, float p) {
        // Main grid: preview.
        fillRound(c, 10f, 8f, 112f, 42f, 3f, 0xFF101214);
        frameRound(c, 10f, 8f, 112f, 42f, 3f, C_LINE, 0.8f);
        fillRound(c, 14f, 12f, 108f, 38f, 2f, 0xFF1B1F22);
        path.reset();
        path.moveTo(52f, 15f);
        path.lineTo(71f, 26f);
        path.lineTo(52f, 37f);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xCCE8C77B);
        c.drawPath(path, paint);

        float progress = ambientOn
                ? 0.5f + 0.5f * (float) Math.sin(ambientLocal * Math.PI * 2)
                : seg(p, 0.08f, 0.9f);
        fillRound(c, 14f, 39.5f, 108f, 41f, 0.5f, 0xFF2A2C2E);
        fillRound(c, 14f, 39.5f, 14f + 94f * progress, 41f, 0.5f, C_GOLD);

        // Main grid: timeline.
        fillRound(c, 10f, 46f, 112f, 78f, 3f, 0xFF101214);
        frameRound(c, 10f, 46f, 112f, 78f, 3f, C_LINE, 0.8f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.4f);
        paint.setColor(0x22FFFFFF);
        for (float x = 14f; x < 108f; x += 8f) c.drawLine(x, 49f, x, 53f, paint);

        clip(c, 14f, 46f, 0xFF1A1D1F, C_LINE, false);
        clip(c, 48f, 70f, 0xFF1A1D1F, C_LINE, false);
        clip(c, 72f, 106f, 0xFF2A2415, C_GOLD, true);

        // Trim handles on the selected clip.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(C_GOLD);
        c.drawLine(72f, 53f, 72f, 73f, paint);
        c.drawLine(106f, 53f, 106f, 73f, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);

        // Playhead.
        float head = 76f + 28f * progress;
        paint.setColor(0xFFEFECE4);
        paint.setStrokeWidth(0.8f);
        c.drawLine(head, 51f, head, 76f, paint);
        path.reset();
        path.moveTo(head - 2.4f, 49f);
        path.lineTo(head + 2.4f, 49f);
        path.lineTo(head, 53f);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        c.drawPath(path, paint);

        // Right grid: trim, cut, merge, duplicate, mute.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.4f);
        paint.setColor(0x22FFFFFF);
        c.drawLine(115f, 8f, 115f, 84f, paint);
        toolTile(c, 118f, 8f, true, "crop_free");
        toolTile(c, 118f, 23f, false, "content_cut");
        toolTile(c, 118f, 38f, false, "call_merge");
        toolTile(c, 118f, 53f, false, "content_copy");
        toolTile(c, 118f, 68f, false, "volume_off");
    }

    private void toolTile(Canvas c, float x, float y, boolean active, String glyph) {
        fillRound(c, x, y, x + 38f, y + 13f, 2.5f,
                active ? 0x1FE8C77B : 0xFF121416);
        frameRound(c, x, y, x + 38f, y + 13f, 2.5f,
                active ? C_GOLD : C_LINE, active ? 0.9f : 0.5f);
        drawIconGlyph(c, glyph, x + 19f, y + 6.5f, 10f,
                active ? C_GOLD : C_MUTED);
    }

    /** Draws a Material icon ligature centred on the given point. */
    private void drawIconGlyph(Canvas c, String glyph, float cx, float cy,
                               float size, int color) {
        textPaint.setTypeface(iconFont != null ? iconFont : Typeface.DEFAULT);
        textPaint.setTextSize(size);
        textPaint.setColor(color);
        textPaint.setTextAlign(Paint.Align.CENTER);
        iconBounds.setEmpty();
        textPaint.getTextBounds(glyph, 0, glyph.length(), iconBounds);
        float baseline = cy - (iconBounds.top + iconBounds.bottom) * 0.5f;
        c.drawText(glyph, cx, baseline, textPaint);
    }

    private void clip(Canvas c, float l, float r, int fill, int border, boolean selected) {
        fillRound(c, l, 56f, r, 72f, 1.5f, fill);
        frameRound(c, l, 56f, r, 72f, 1.5f, border, selected ? 1f : 0.6f);
        if (selected) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(0.35f);
            paint.setColor(0x44E8C77B);
            for (float x = l + 8f; x < r - 3f; x += 7f) {
                c.drawLine(x, 59f, x, 69f, paint);
            }
        }
    }

    // ── 04 motion detection ─────────────────────────────────────────────────

    private void drawMotion(Canvas c, float p) {
        // Scene frame, centred between the corner ticks.
        fillRound(c, 10f, 14f, 158f, 78f, 3f, 0xFF101214);
        frameRound(c, 10f, 14f, 158f, 78f, 3f, C_LINE, 0.8f);
        c.save();
        c.clipRect(11f, 15f, 157f, 77f);

        // Wall and floor.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.3f);
        paint.setColor(0x12FFFFFF);
        for (float x = 26f; x < 158f; x += 16f) c.drawLine(x, 16f, x, 66f, paint);
        c.drawLine(10f, 66f, 158f, 66f, paint);

        // Computer desk: monitor, stand, keyboard.
        paint.setColor(0x66C6A66A);
        paint.setStrokeWidth(0.8f);
        c.drawLine(54f, 54f, 92f, 54f, paint);
        c.drawLine(56f, 54f, 56f, 66f, paint);
        c.drawLine(90f, 54f, 90f, 66f, paint);
        fillRound(c, 62f, 32f, 84f, 48f, 1.2f, 0xFF181B1D);
        frameRound(c, 62f, 32f, 84f, 48f, 1.2f, 0x99E8C77B, 0.6f);
        fillRound(c, 64f, 34f, 82f, 46f, 0.8f, 0xFF23282C);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0x66C6A66A);
        c.drawLine(73f, 48f, 73f, 54f, paint);
        c.drawLine(69f, 54f, 77f, 54f, paint);
        fillRound(c, 58f, 55f, 86f, 57.5f, 0.7f, 0xFF1B1F22);
        frameRound(c, 58f, 55f, 86f, 57.5f, 0.7f, C_LINE, 0.3f);

        // Doorway; the leaf swings open for the exit.
        fillRound(c, 128f, 30f, 150f, 66f, 1f, 0xFF0A0C0D);
        frameRound(c, 126f, 28f, 152f, 66f, 1f, C_LINE, 0.6f);

        // Walk in, pause, then leave through the door.
        float t = ambientOn ? ambientLocal : p;
        float px;
        boolean active;
        if (t < 0.44f) {
            px = 40f + 64f * (t / 0.44f);
            active = true;
        } else if (t < 0.56f) {
            px = 104f;
            active = false;
        } else if (t < 0.86f) {
            px = 104f + 35f * ((t - 0.56f) / 0.30f);
            active = true;
        } else {
            px = 139f;
            active = t < 0.90f;
        }
        float fade = 1f - seg(t, 0.86f, 0.93f);
        int walkerAlpha = (int) (255f * fade);

        // Mounted phone: bright body, gold ring, lens lit only while recording.
        fillRound(c, 12f, 30f, 17f, 44f, 1f, 0xFF23282C);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        paint.setColor(0xFF3A3F43);
        c.drawLine(17f, 37f, 20f, 37f, paint);
        fillRound(c, 20f, 26f, 34f, 50f, 3.5f, 0xFF202529);
        frameRound(c, 20f, 26f, 34f, 50f, 3.5f, 0xAAE8C77B, 0.7f);
        fillRound(c, 22f, 28f, 32f, 48f, 2f, 0xFF2B3237);

        // Soft white flash converging exactly on the lens centre (27, 31).
        if (active) {
            fillCone(c, 27f, 31f, 27f, 31f, 152f, 66f, 38f, 66f, 10);
            fillCone(c, 27f, 31f, 27f, 31f, 146f, 66f, 50f, 66f, 14);
            fillCone(c, 27f, 31f, 27f, 31f, 138f, 66f, 64f, 66f, 18);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(beamPool);
            c.save();
            c.translate(95f, 66f);
            c.scale(2.1f, 0.24f);
            c.drawCircle(0f, 0f, 26f, paint);
            c.restore();
            paint.setShader(null);
        }

        // Lens dot on top so the beam visibly starts at its centre.
        if (active) {
            dot(c, 27f, 31f, 3.4f, 0x33FFFFFF);
            dot(c, 27f, 31f, 1.7f, 0xFFFFFFFF);
        } else {
            dot(c, 27f, 31f, 1.2f, 0xFF3A4044);
        }

        if (fade > 0.02f) drawWalker(c, px, t, active, walkerAlpha);

        if (active && fade > 0.02f) {
            float l = px - 7f, r = px + 7f, tp = 33f, b = 65f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(C_GOLD, (int) (18f * fade)));
            rect.set(l, tp, r, b);
            c.drawRect(rect, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(0.8f);
            paint.setColor(withAlpha(C_GOLD, (int) (225f * fade)));
            rect.set(l, tp, r, b);
            c.drawRect(rect, paint);
            paint.setStrokeWidth(1.4f);
            c.drawLine(l, tp, l + 4f, tp, paint);
            c.drawLine(l, tp, l, tp + 4f, paint);
            c.drawLine(r, tp, r - 4f, tp, paint);
            c.drawLine(r, tp, r, tp + 4f, paint);
            c.drawLine(l, b, l + 4f, b, paint);
            c.drawLine(l, b, l, b - 4f, paint);
            c.drawLine(r, b, r - 4f, b, paint);
            c.drawLine(r, b, r, b - 4f, paint);
        }

        // Door leaf on top, swinging toward the hinge.
        float open = seg(t, 0.72f, 0.84f) * (1f - seg(t, 0.965f, 1f));
        float leafW = 21f * (1f - 0.82f * open);
        float leafL = 150f - leafW;
        fillRound(c, leafL, 30f, 150f, 66f, 0.8f, 0xFF202427);
        frameRound(c, leafL, 30f, 150f, 66f, 0.8f, 0xCCE8C77B, 0.6f);
        dot(c, leafL + 3f, 49f, 0.8f, C_GOLD);

        // Human-readable CCTV clock; it freezes whenever motion stops.
        updateCctvClock(active);
        String stamp = cctvText();
        float stampW = measureLabel(stamp, 5.8f, false);
        fillRound(c, 20.5f, 16.2f, 22f + stampW + 1.5f, 22.5f, 1f,
                0xA60B0C0D);
        label(c, stamp, 22f, 21.5f, 5.8f, C_INK, Paint.Align.LEFT, false);
        drawPlayPauseBadge(c, 146f, 22f, active);
        dot(c, 16f, 19f, 1.4f, withAlpha(C_RED, (int) (55f + 200f * recBlink())));
        c.restore();
    }

    private void fillCone(Canvas c, float x1, float y1, float x2, float y2,
                          float x3, float y3, float x4, float y4, int alpha) {
        path.reset();
        path.moveTo(x1, y1);
        path.lineTo(x3, y3);
        path.lineTo(x4, y4);
        path.lineTo(x2, y2);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(alpha << 24 | 0x00FFFFFF);
        c.drawPath(path, paint);
    }

    private void drawWalker(Canvas c, float x, float phase, boolean walking, int alpha) {
        float swing = walking
                ? (float) Math.sin(phase * Math.PI * 2 * 9) * 2.6f : 0f;
        int ink = withAlpha(C_INK, alpha);
        dot(c, x, 37f, 3.2f, ink);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.4f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(ink);
        // Head stays detached, as requested.
        c.drawLine(x, 41.5f, x, 54f, paint);
        c.drawLine(x, 44f, x - 5.2f - swing, 50f, paint);
        c.drawLine(x, 44f, x + 5.2f + swing, 50f, paint);
        c.drawLine(x, 54f, x - 2.4f - swing, 63f, paint);
        c.drawLine(x, 54f, x + 2.4f + swing, 63f, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    /** Gold play while recording motion, muted pause between events. */
    private void drawPlayPauseBadge(Canvas c, float x, float y, boolean playing) {
        int accent = playing ? C_GOLD : C_MUTED;
        fillRound(c, x - 6f, y - 6f, x + 6f, y + 6f, 6f, 0xE60B0C0D);
        frameRound(c, x - 6f, y - 6f, x + 6f, y + 6f, 6f, accent, 0.8f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(accent);
        if (playing) {
            path.reset();
            path.moveTo(x - 1.6f, y - 3f);
            path.lineTo(x + 3.4f, y);
            path.lineTo(x - 1.6f, y + 3f);
            path.close();
            c.drawPath(path, paint);
        } else {
            c.drawRect(x - 3f, y - 3f, x - 0.9f, y + 3f, paint);
            c.drawRect(x + 0.9f, y - 3f, x + 3f, y + 3f, paint);
        }
    }

    // ── forensic intelligence ───────────────────────────────────────────────

    private void drawForensic(Canvas c, float p) {
        int idx = ambientOn
                ? ((int) (ambientLocal * 3f)) % 3
                : (int) Math.max(0f, Math.min(2f, (p - 0.3f) * 4f));

        fillRound(c, 10f, 14f, 158f, 78f, 3f, 0xFF101214);
        frameRound(c, 10f, 14f, 158f, 78f, 3f, C_LINE, 0.8f);

        // Evidence viewer; the scan stays inside the golden frame.
        fillRound(c, 102f, 18f, 156f, 76f, 2.5f, 0xFF1B1F22);
        frameRound(c, 102f, 18f, 156f, 76f, 2.5f, C_LINE, 0.6f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.3f);
        paint.setColor(0x12FFFFFF);
        for (float x = 108f; x < 156f; x += 8f) c.drawLine(x, 20f, x, 74f, paint);

        float sweep = ambientOn ? ambientLocal : seg(p, 0.45f, 0.85f);
        float sy = 22f + 34f * sweep;
        paint.setStrokeWidth(0.7f);
        paint.setColor(0x66E8C77B);
        c.drawLine(106f, sy, 152f, sy, paint);

        drawObjectIcon(c, idx, 111f, 22f, 145f, 56f, 255);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.45f);
        paint.setColor(withAlpha(C_GOLD, 170));
        rect.set(106f, 22f, 152f, 56f);
        c.drawRect(rect, paint);
        paint.setStrokeWidth(1.6f);
        paint.setColor(C_GOLD);
        c.drawLine(106f, 22f, 111f, 22f, paint);
        c.drawLine(106f, 22f, 106f, 27f, paint);
        c.drawLine(152f, 22f, 147f, 22f, paint);
        c.drawLine(152f, 22f, 152f, 27f, paint);
        c.drawLine(106f, 56f, 111f, 56f, paint);
        c.drawLine(106f, 56f, 106f, 51f, paint);
        c.drawLine(152f, 56f, 147f, 56f, paint);
        c.drawLine(152f, 56f, 152f, 51f, paint);

        label(c, objectConfidence[idx], 106f, 65f, 6.4f, C_GOLD,
                Paint.Align.LEFT, true);
        label(c, "SAVED", 106f, 74f, 6.4f, withAlpha(C_INK, 235),
                Paint.Align.LEFT, true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.2f);
        paint.setColor(C_GOLD);
        c.drawLine(140f, 71f, 143f, 74f, paint);
        c.drawLine(143f, 74f, 149f, 67f, paint);

        // Evidence log; names stay in the viewer, the log carries time and score.
        label(c, "DETECTION LOG", 14f, 22f, 6f, C_GOLD,
                Paint.Align.LEFT, true);
        for (int i = 0; i < 3; i++) {
            float reveal = ambientOn ? 1f
                    : seg(p, 0.1f + i * 0.12f, 0.32f + i * 0.12f);
            int a = (int) (255f * reveal);
            if (a <= 0) continue;
            float top = 26f + i * 17f;
            boolean selected = i == idx;
            fillRound(c, 14f, top, 98f, top + 15f, 1.5f, withAlpha(0x151719, a));
            frameRound(c, 14f, top, 98f, top + 15f, 1.5f,
                    withAlpha(selected ? C_GOLD : C_LINE, a), selected ? 0.9f : 0.5f);
            drawObjectIcon(c, i, 18f, top + 1.5f, 30f, top + 13.5f, a);
            label(c, objectTime[i], 33f, top + 11f, 6.2f,
                    withAlpha(selected ? C_GOLD : C_MUTED, a), Paint.Align.LEFT, true);
            label(c, objectScore[i], 94f, top + 11f, 6.4f,
                    withAlpha(selected ? C_GOLD : C_MUTED, a), Paint.Align.RIGHT, true);
        }
    }

    private void drawObjectIcon(Canvas c, int idx, float l, float t, float r,
                                float b, int alpha) {
        Drawable icon = objectIcons[idx];
        if (icon == null) return;
        icon.setBounds(Math.round(l), Math.round(t), Math.round(r), Math.round(b));
        icon.setAlpha(alpha);
        icon.draw(c);
        icon.setAlpha(255);
    }

    private void eventThumb(Canvas c, float x, float y, int idx, boolean active) {
        int gold = ambientOn
                ? withAlpha(C_GOLD, (int) (255f * (0.65f + 0.35f * ambientWave)))
                : C_GOLD;
        fillRound(c, x, y, x + 30f, y + 13f, 1f,
                active ? 0x1AE8C77B : 0xFF151719);
        frameRound(c, x, y, x + 30f, y + 13f, 1f,
                active ? gold : C_LINE, active ? 0.9f : 0.6f);
        drawObjectIcon(c, idx, x + 9f, y + 0.5f, x + 21f, y + 12.5f,
                active ? 255 : 110);
    }

    // ── 05 remote camera ────────────────────────────────────────────────────

    private void drawRemote(Canvas c, float p) {
        // Camera source phone.
        fillRound(c, 10f, 20f, 48f, 72f, 5f, 0xFF121416);
        frameRound(c, 10f, 20f, 48f, 72f, 5f, C_LINE, 0.8f);
        fillRound(c, 14f, 24f, 44f, 68f, 3f, 0xFF1A1D1F);
        fillRound(c, 21f, 24f, 37f, 31f, 3f, 0xFF0B0D0E);
        frameRound(c, 21f, 24f, 37f, 31f, 3f, 0x99C6A66A, 0.6f);
        dot(c, 26.5f, 27.5f, 2f, 0xFF3A4044);
        dot(c, 26.5f, 27.5f, 1.3f, 0xFF121416);
        dot(c, 32f, 27.5f, 1.4f, 0xFF2B3033);
        dot(c, 16f, 27f, 1.2f,
                withAlpha(C_RED, (int) (55f + 200f * recBlink())));

        // Signal arcs and travelling dot.
        float signal = seg(p, 0.2f, 0.55f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.8f);
        paint.setColor(withAlpha(C_GOLD, (int) (200f * signal)));
        for (int i = 1; i <= 3; i++) {
            float r = 4f + i * 4f;
            rect.set(56f - r, 46f - r, 56f + r, 46f + r);
            c.drawArc(rect, -52f, 104f, false, paint);
        }
        paint.setPathEffect(dash);
        paint.setColor(withAlpha(C_OLIVE, (int) (120f * signal)));
        c.drawLine(54f, 46f, 80f, 46f, paint);
        paint.setPathEffect(null);
        float travel = ambientOn ? ambientLocal : seg(p, 0.4f, 0.8f);
        int travelAlpha = (int) (220f * signal);
        if (ambientOn) {
            float edge = Math.min(ambientLocal, 1f - ambientLocal);
            travelAlpha = (int) (220f * Math.min(1f, edge * 8f));
        }
        dot(c, 54f + 26f * travel, 46f, 1.4f, withAlpha(C_GOLD, travelAlpha));

        // Dashboard shell.
        fillRound(c, 82f, 8f, 158f, 74f, 4f, 0xFF121416);
        frameRound(c, 82f, 8f, 158f, 74f, 4f, C_LINE, 0.8f);
        dot(c, 87f, 13f, 1f, C_DIM);
        dot(c, 91f, 13f, 1f, C_DIM);
        dot(c, 95f, 13f, 1f, C_DIM);
        paint.setColor(C_LINE);
        paint.setStrokeWidth(0.4f);
        c.drawLine(100f, 13f, 152f, 13f, paint);

        // Upper grid: the CCTV scene only.
        fillRound(c, 85f, 17f, 155f, 47f, 2f, 0xFF1B2228);
        frameRound(c, 85f, 17f, 155f, 47f, 2f, C_LINE, 0.5f);
        c.save();
        c.clipRect(86f, 18f, 154f, 46f);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF202830);
        c.drawRect(86f, 18f, 154f, 34f, paint);
        paint.setColor(0xFF2A3239);
        c.drawRect(86f, 34f, 154f, 46f, paint);
        paint.setColor(0x33C6A66A);
        c.drawRect(86f, 34f, 154f, 34.5f, paint);

        // Window with a night view.
        fillRound(c, 96f, 21f, 110f, 31f, 0.8f, 0xFF0C141D);
        frameRound(c, 96f, 21f, 110f, 31f, 0.8f, 0x88E8C77B, 0.5f);
        dot(c, 99f, 24f, 0.5f, 0xFFB9C6D1);
        dot(c, 104f, 22.5f, 0.4f, 0xFFB9C6D1);
        dot(c, 107f, 26f, 0.5f, 0xFFB9C6D1);

        // Framed picture on the left wall.
        fillRound(c, 88f, 22f, 94f, 28f, 0.5f, 0xFF12181C);
        frameRound(c, 88f, 22f, 94f, 28f, 0.5f, 0x66C6A66A, 0.4f);

        // Sofa.
        fillRound(c, 112f, 30f, 132f, 35f, 2f, 0xFF333C43);
        fillRound(c, 113f, 34f, 131f, 39f, 1.5f, 0xFF3E4850);
        fillRound(c, 110f, 33f, 114f, 40f, 1.2f, 0xFF333C43);
        fillRound(c, 130f, 33f, 134f, 40f, 1.2f, 0xFF333C43);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.4f);
        paint.setColor(0x22FFFFFF);
        c.drawLine(118f, 31f, 118f, 34f, paint);
        c.drawLine(126f, 31f, 126f, 34f, paint);
        dot(c, 113f, 40.5f, 0.6f, C_DIM);
        dot(c, 131f, 40.5f, 0.6f, C_DIM);

        // Coffee table and plant.
        fillRound(c, 118f, 41f, 128f, 43f, 0.8f, 0xFF262D33);
        fillRound(c, 120f, 43f, 126f, 44f, 0.5f, 0xFF1C2227);
        fillRound(c, 136f, 38f, 140f, 42f, 0.8f, 0xFF2A3136);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.6f);
        paint.setColor(0xFF3C5A44);
        c.drawLine(138f, 38f, 138f, 34f, paint);
        c.drawLine(138f, 36f, 135.5f, 34f, paint);
        c.drawLine(138f, 36f, 140.5f, 34f, paint);
        dot(c, 138f, 33.5f, 1.8f, 0x663C5A44);

        // The walker crosses the lounge, with a real stride.
        float walk = (android.os.SystemClock.uptimeMillis() % 8000L) / 8000f;
        drawLoungeWalker(c, 82f + 76f * walk, 45f, walk);

        // CCTV scanlines.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.3f);
        paint.setColor(0x0CFFFFFF);
        for (float y = 18f; y < 46f; y += 2f) c.drawLine(86f, y, 154f, y, paint);
        c.restore();
        dot(c, 87f, 19f, 1.1f,
                withAlpha(C_RED, (int) (55f + 200f * recBlink())));

        // Lower grid: compact controls, no label.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.4f);
        paint.setColor(0x22FFFFFF);
        c.drawLine(85f, 50f, 155f, 50f, paint);

        // Torch toggle.
        paint.setColor(0x99C6A66A);
        paint.setStrokeWidth(0.7f);
        c.drawCircle(97f, 61f, 5f, paint);
        drawIconGlyph(c, "flashlight_on", 97f, 61f, 7.5f, C_GOLD);

        // D-pad.
        paint.setColor(0x66C6A66A);
        c.drawCircle(120f, 61f, 7.5f, paint);
        dot(c, 120f, 61f, 2f, C_GOLD);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x99C6A66A);
        path.reset();
        path.moveTo(120f, 52.5f);
        path.lineTo(118.4f, 55.2f);
        path.lineTo(121.6f, 55.2f);
        path.close();
        c.drawPath(path, paint);
        path.reset();
        path.moveTo(120f, 69.5f);
        path.lineTo(118.4f, 66.8f);
        path.lineTo(121.6f, 66.8f);
        path.close();
        c.drawPath(path, paint);
        path.reset();
        path.moveTo(111.5f, 61f);
        path.lineTo(114.2f, 59.4f);
        path.lineTo(114.2f, 62.6f);
        path.close();
        c.drawPath(path, paint);
        path.reset();
        path.moveTo(128.5f, 61f);
        path.lineTo(125.8f, 59.4f);
        path.lineTo(125.8f, 62.6f);
        path.close();
        c.drawPath(path, paint);

        // Record.
        paint.setColor(0x99C6A66A);
        c.drawCircle(143f, 61f, 5f, paint);
        dot(c, 143f, 61f, 2f, C_RED);
    }

    /** A lounge walker with swinging arms and legs; keeps a detached head. */
    private void drawLoungeWalker(Canvas c, float x, float floorY, float phase) {
        int ink = 0xFFE8E4D8;
        dot(c, x, floorY - 9.6f, 1.5f, ink);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.1f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(ink);
        c.drawLine(x, floorY - 8.1f, x, floorY - 4f, paint);
        float swing = (float) Math.sin(phase * Math.PI * 2 * 6) * 1.7f;
        c.drawLine(x, floorY - 7f, x - 2.2f - swing * 0.45f, floorY - 4.6f, paint);
        c.drawLine(x, floorY - 7f, x + 2.2f + swing * 0.45f, floorY - 4.6f, paint);
        c.drawLine(x, floorY - 4f, x - 1.4f - swing, floorY, paint);
        c.drawLine(x, floorY - 4f, x + 1.4f + swing, floorY, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    // ── 06 bodycam / dashcam ────────────────────────────────────────────────

    /** Lite card: bodycam only, centred on the stage. */
    private void drawBodycamSolo(Canvas c, float p) {
        float body = 1f;
        int a = 255;
        c.save();
        c.translate(84f, 46f);
        c.scale(1.05f, 1.05f);
        c.translate(-84f, -46f);
        dot(c, 84f, 24f, 8f, withAlpha(0xFF4A4E52, a));
        fillRound(c, 64f, 36f, 104f, 76f, 11f, withAlpha(0xFF3D4145, a));
        // Chest-mounted phone, showing the street ahead.
        fillRound(c, 72f, 40f, 96f, 72f, 4f, withAlpha(0xFF101214, a));
        frameRound(c, 72f, 40f, 96f, 72f, 4f, withAlpha(C_GOLD, a), 0.9f);
        fillRound(c, 75f, 42f, 93f, 70f, 2f, withAlpha(0xFF10161A, a));
        c.save();
        rect.set(75f, 42f, 93f, 70f);
        path.reset();
        path.addRoundRect(rect, 2f, 2f, Path.Direction.CW);
        c.clipPath(path);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(0xFF1B2329, a));
        c.drawRect(75f, 42f, 93f, 53f, paint);
        paint.setColor(withAlpha(0xFF2A3238, a));
        c.drawRect(75f, 46f, 79f, 53f, paint);
        c.drawRect(81f, 44f, 85f, 53f, paint);
        c.drawRect(87f, 47f, 91f, 53f, paint);
        c.drawRect(91.5f, 45f, 93f, 53f, paint);
        paint.setColor(withAlpha(C_GOLD, (int) (90f * a / 255f)));
        c.drawRect(76f, 48f, 77.5f, 49.5f, paint);
        c.drawRect(82f, 46f, 83.5f, 47.5f, paint);
        c.drawRect(88f, 49f, 90f, 50.5f, paint);
        paint.setColor(withAlpha(0xFF2A3036, a));
        c.drawRect(75f, 53f, 93f, 70f, paint);
        paint.setColor(withAlpha(0xFF3A4044, a));
        c.drawRect(89.5f, 45f, 90.2f, 61f, paint);
        paint.setColor(withAlpha(0x99E8C77B, a));
        c.drawRect(88.5f, 44.5f, 91.2f, 45.6f, paint);
        paint.setColor(withAlpha(0xFF232A30, a));
        c.drawRect(76f, 61f, 82f, 62.2f, paint);
        c.drawRect(76.5f, 62.2f, 77.2f, 64f, paint);
        c.drawRect(80.8f, 62.2f, 81.5f, 64f, paint);
        float flow = (android.os.SystemClock.uptimeMillis() % 6200L) / 6200f;
        streetWalker(c, 70f + 28f * ((flow + 0.05f) % 1f), 66f, 1.15f, flow, true, a);
        streetWalker(c, 98f - 28f * ((1.05f - flow) % 1f), 68.5f, 1f, flow, false, a);
        streetWalker(c, 70f + 28f * ((flow * 0.8f + 0.45f) % 1f), 64f, 1.25f, flow, true, a);
        c.restore();
        dot(c, 76.8f, 43.8f, 0.7f, withAlpha(C_RED,
                (int) (a * (0.22f + 0.78f * recBlink()))));
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(withAlpha(C_MUTED, (int) (150f * body)));
        paint.setStrokeWidth(0.6f);
        c.drawLine(68f, 41f, 76f, 48f, paint);
        c.drawLine(100f, 41f, 92f, 48f, paint);
        c.restore();
    }

    /** Lite card: dashcam only, phone plus steering wheel. */
    private void drawDashcamSolo(Canvas c, float p) {
        float a = 255f;
        c.save();
        c.translate(84f, 46f);
        c.scale(1.08f, 1.08f);
        c.translate(-84f, -46f);
        // Landscape dash phone.
        fillRound(c, 43f, 24f, 83f, 50f, 4.5f, withAlpha(0xFF101214, (int) a));
        frameRound(c, 43f, 24f, 83f, 50f, 4.5f, withAlpha(C_GOLD, (int) a), 0.9f);
        fillRound(c, 46f, 27f, 80f, 47f, 2.5f, withAlpha(0xFF0E1317, (int) a));
        c.save();
        rect.set(46f, 27f, 80f, 47f);
        path.reset();
        path.addRoundRect(rect, 2.5f, 2.5f, Path.Direction.CW);
        c.clipPath(path);

        // Sky and horizon glow.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(0xFF151B20, (int) a));
        c.drawRect(46f, 27f, 80f, 33f, paint);
        paint.setColor(withAlpha(0x33E8C77B, (int) a));
        c.drawRect(46f, 32.6f, 80f, 33f, paint);

        // Road.
        path.reset();
        path.moveTo(46f, 47f);
        path.lineTo(57f, 33f);
        path.lineTo(69f, 33f);
        path.lineTo(80f, 47f);
        path.close();
        paint.setColor(withAlpha(0xFF252C32, (int) a));
        c.drawPath(path, paint);

        float flow = (android.os.SystemClock.uptimeMillis() % 1800L) / 1800f;

        // Lane dashes rushing toward the viewer.
        paint.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < 4; i++) {
            float f = (i / 4f + flow) % 1f;
            float y = 33f + 14f * f;
            float len = 0.5f + 3f * f;
            paint.setStrokeWidth(0.35f + 1.1f * f);
            paint.setColor(withAlpha(C_GOLD, (int) (a * (0.3f + 0.6f * f))));
            c.drawLine(63f, y, 63f, y + len, paint);
        }

        // A few distant trees at the very edges; the road stays clear.
        for (int i = 0; i < 2; i++) {
            float f = (i / 2f + flow) % 1f;
            float y = 33f + 14f * f;
            float size = 0.8f + 1.3f * f;
            float spread = (y - 33f) * 0.79f;
            tree(c, 57f - spread - 2f, y, size, (int) (a * 0.78f));
            tree(c, 69f + spread + 2f, y, size, (int) (a * 0.78f));
        }
        c.restore();
        dot(c, 48.5f, 29.5f, 1.1f, withAlpha(C_RED,
                (int) (a * (0.22f + 0.78f * recBlink()))));

        // Stand.
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(withAlpha(C_MUTED, 180));
        paint.setStrokeWidth(0.9f);
        c.drawLine(63f, 50f, 63f, 56f, paint);
        c.drawLine(43f, 56f, 83f, 56f, paint);

        // Steering wheel.
        float steer = ambientOn
                ? (float) Math.sin(ambientLocal * Math.PI * 2) * 14f : 0f;
        c.save();
        c.rotate(steer, 107f, 47f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f);
        paint.setColor(withAlpha(C_OLIVE, (int) a));
        c.drawCircle(107f, 47f, 18f, paint);
        paint.setStrokeWidth(1f);
        paint.setColor(withAlpha(C_MUTED, (int) a));
        c.drawCircle(107f, 47f, 5f, paint);
        c.drawLine(107f, 47f, 107f, 29f, paint);
        c.drawLine(107f, 47f, 92f, 56f, paint);
        c.drawLine(107f, 47f, 122f, 56f, paint);
        dot(c, 107f, 47f, 2f, withAlpha(C_GOLD, (int) a));
        c.restore();
        c.restore();
    }

    private void tree(Canvas c, float x, float groundY, float size, int alpha) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(0xFF3A2E22, alpha));
        c.drawRect(x - size * 0.16f, groundY - size * 1.8f,
                x + size * 0.16f, groundY, paint);
        paint.setColor(withAlpha(0xFF3C5A44, alpha));
        c.drawCircle(x, groundY - size * 1.9f, size, paint);
    }

    // ── mini: flashlight ────────────────────────────────────────────────────

    private void drawFlashlight(Canvas c, float p) {
        float glow = torchGlow(p);

        // Beam from the torch lens.
        path.reset();
        path.moveTo(55f, 25f);
        path.lineTo(112f, 4f);
        path.lineTo(112f, 60f);
        path.lineTo(55f, 39f);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(C_GOLD);
        paint.setAlpha((int) (30f * glow));
        c.drawPath(path, paint);
        path.reset();
        path.moveTo(56f, 29f);
        path.lineTo(106f, 12f);
        path.lineTo(106f, 52f);
        path.lineTo(56f, 35f);
        path.close();
        paint.setAlpha((int) (48f * glow));
        c.drawPath(path, paint);
        paint.setAlpha(255);

        // Forensics asset pack: proper side-view flashlight.
        if (torchIcon != null) {
            torchIcon.setBounds(8, 4, 64, 60);
            // The tube dims too, not just the beam.
            torchIcon.setAlpha(165 + (int) (90f * glow));
            torchIcon.draw(c);
            torchIcon.setAlpha(255);
        }
        if (glow > 0.55f) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(C_GOLD, (int) (70f * glow)));
            c.drawCircle(59f, 32f, 4.5f, paint);
        }
    }

    /**
     * Old fluorescent-tube behaviour: a stuttering ignition before the light settles,
     * then a barely-there mains hum with the occasional dip.
     */
    private float torchGlow(float p) {
        if (ambientOn) {
            // An old tube keeps burning with two short stutter bursts per cycle.
            float t = ambientLocal;
            if (t > 0.14f && t < 0.26f) {
                int step = (int) (t * 24f);
                return ((step * 7 + 3) % 3) == 0 ? 1f : 0.14f;
            }
            if (t > 0.68f && t < 0.74f) {
                int step = (int) (t * 24f);
                return ((step * 5) % 2) == 0 ? 0.95f : 0.22f;
            }
            return 0.94f + 0.06f * ambientWave;
        }
        if (p >= 0.55f) return 1f;
        if (p < 0.06f) return 0.08f;
        int step = (int) (p * 70f);
        int hash = (step * 1103515245 + 12345) & 0x7fffffff;
        return (hash % 100) < 42 ? 1f : 0.10f;
    }

    // ── mini: QR scanner ───────────────────────────────────────────────────

    private void drawQr(Canvas c, float p) {
        float left = 14f;
        float top = 8f;
        float size = 46f;
        float cell = size / 21f;

        // Quiet panel.
        fillRound(c, left - 2f, top - 2f, left + size + 2f, top + size + 2f,
                2f, 0xFF0C0E0F);

        // Finder patterns.
        finder(c, left, top, cell);
        finder(c, left + 14f * cell, top, cell);
        finder(c, left, top + 14f * cell, cell);

        // Data modules.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFE7E4D9);
        for (int x = 0; x < 21; x++) {
            for (int y = 0; y < 21; y++) {
                if (!qr[x][y]) continue;
                float mx = left + x * cell;
                float my = top + y * cell;
                c.drawRect(mx, my, mx + cell - 0.25f, my + cell - 0.25f, paint);
            }
        }

        float sweep = ambientOn ? ambientLocal : seg(p, 0.12f, 0.6f);
        if (sweep > 0f && sweep < 1f) {
            float y = top + size * sweep;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x22E8C77B);
            c.drawRect(left, y - 3f, left + size, y, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(0.9f);
            paint.setColor(C_GOLD);
            c.drawLine(left, y, left + size, y, paint);
        }

        // Result card: the scanned value, then a saved check.
        float saved = seg(p, 0.72f, 0.92f);
        if (saved > 0f) {
            int a = (int) (255f * saved);
            fillRound(c, 64f, 16f, 116f, 44f, 2f, withAlpha(0xFF121416, a));
            frameRound(c, 64f, 16f, 116f, 44f, 2f, withAlpha(C_GOLD, a), 0.8f);
            label(c, qrValue, 67f, 29f, 5.4f,
                    withAlpha(C_INK, a), Paint.Align.LEFT, false);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.2f);
            paint.setColor(withAlpha(C_GOLD, a));
            c.drawLine(106f, 36f, 109f, 39f, paint);
            c.drawLine(109f, 39f, 113f, 33f, paint);
        }
    }

    private void finder(Canvas c, float x, float y, float cell) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFE7E4D9);
        c.drawRect(x, y, x + 7f * cell, y + 7f * cell, paint);
        paint.setColor(0xFF0C0E0F);
        c.drawRect(x + cell, y + cell, x + 6f * cell, y + 6f * cell, paint);
        paint.setColor(0xFFE7E4D9);
        c.drawRect(x + 2f * cell, y + 2f * cell, x + 5f * cell, y + 5f * cell, paint);
    }

    /** Filled heart glyph used by the screen-recording text layer. */
    private void heart(Canvas c, float cx, float cy, float s, int color) {
        path.reset();
        path.moveTo(cx, cy + s * 0.78f);
        path.cubicTo(cx - s * 1.15f, cy - s * 0.05f,
                cx - s * 0.62f, cy - s * 0.98f, cx, cy - s * 0.32f);
        path.cubicTo(cx + s * 0.62f, cy - s * 0.98f,
                cx + s * 1.15f, cy - s * 0.05f, cx, cy + s * 0.78f);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        c.drawPath(path, paint);
    }

    /** "I <heart> FadCam", in a soft green with a quiet text box behind it. */
    private void drawTextAnnotation(Canvas c, int alpha) {
        int color = withAlpha(C_GREEN, alpha);
        float size = 7.2f;
        String first = "I ";
        String last = " FadCam";
        float wFirst = measureLabel(first, size, true);
        float wLast = measureLabel(last, size, true);
        float heart = 7.4f;
        float total = wFirst + heart + wLast;
        float x = 70f - total * 0.5f;
        float baseline = 25f;

        fillRound(c, x - 3f, baseline - 7.4f, x + total + 3f, baseline + 2.4f,
                1.5f, withAlpha(0x101213, (int) (alpha * 0.82f)));
        frameRound(c, x - 3f, baseline - 7.4f, x + total + 3f, baseline + 2.4f,
                1.5f, withAlpha(C_GREEN, (int) (alpha * 0.45f)), 0.45f);

        // The heart must dim with its layer, not glow while the layer is inactive.
        float pulse = ambientOn && alpha > 200
                ? 0.78f + 0.22f * ambientWave : 1f;
        int beat = withAlpha(C_GREEN, (int) (alpha * pulse));
        label(c, first, x, baseline, size, color, Paint.Align.LEFT, true);
        heart(c, x + wFirst + heart * 0.5f, baseline - 2.4f, 3.6f, beat);
        label(c, last, x + wFirst + heart, baseline, size, color,
                Paint.Align.LEFT, true);
    }

    private String clockText(long seconds) {
        long m = (seconds / 60L) % 60L;
        long s = seconds % 60L;
        return String.format(java.util.Locale.US, "%02d:%02d", m, s);
    }

    private void streetWalker(Canvas c, float x, float groundY, float scale,
                              float phase, boolean right, int alpha) {
        int ink = withAlpha(0xFFD9D5C8, alpha);
        dot(c, x, groundY - 6.2f * scale, 1.05f * scale, ink);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.6f * scale);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(ink);
        c.drawLine(x, groundY - 5.1f * scale, x, groundY - 2.1f * scale, paint);
        float swing = (float) Math.sin((right ? phase : -phase)
                * Math.PI * 2 * 7) * 1.1f * scale;
        c.drawLine(x, groundY - 4.3f * scale,
                x - 1.2f * scale - swing * 0.4f, groundY - 2.7f * scale, paint);
        c.drawLine(x, groundY - 4.3f * scale,
                x + 1.2f * scale + swing * 0.4f, groundY - 2.7f * scale, paint);
        c.drawLine(x, groundY - 2.1f * scale,
                x - 0.9f * scale - swing, groundY, paint);
        c.drawLine(x, groundY - 2.1f * scale,
                x + 0.9f * scale + swing, groundY, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    /** Accumulate CCTV time only while motion is being recorded. */
    private void updateCctvClock(boolean active) {
        long now = android.os.SystemClock.uptimeMillis();
        if (cctvLastTickMs == 0L) cctvLastTickMs = now;
        if (active) cctvElapsedMs += now - cctvLastTickMs;
        cctvLastTickMs = now;
    }

    private String cctvText() {
        long total = 80778000L + cctvElapsedMs; // 10:26:18.000 PM
        int ms = (int) (total % 1000L);
        long secs = total / 1000L;
        int s = (int) (secs % 60L);
        int m = (int) ((secs / 60L) % 60L);
        int h24 = (int) ((secs / 3600L) % 24L);
        int h = h24 % 12;
        if (h == 0) h = 12;
        return String.format(java.util.Locale.US, "%02d:%02d:%02d.%03d %s",
                h, m, s, ms, h24 < 12 ? "AM" : "PM");
    }

    // ── drawing helpers ────────────────────────────────────────────────────

    private void fillRound(Canvas c, float l, float t, float r, float b,
                           float radius, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        rect.set(l, t, r, b);
        c.drawRoundRect(rect, radius, radius, paint);
    }

    private void frameRound(Canvas c, float l, float t, float r, float b,
                            float radius, int color, float stroke) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setColor(color);
        rect.set(l, t, r, b);
        c.drawRoundRect(rect, radius, radius, paint);
    }

    private void dot(Canvas c, float x, float y, float radius, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        c.drawCircle(x, y, radius, paint);
    }

    private float measureLabel(String s, float size, boolean bold) {
        textPaint.setTypeface(bold ? monoBold : mono);
        textPaint.setTextSize(size);
        return textPaint.measureText(s);
    }

    private void label(Canvas c, String s, float x, float y, float size,
                       int color, Paint.Align align, boolean bold) {
        textPaint.setTypeface(bold ? monoBold : mono);
        textPaint.setSubpixelText(true);
        textPaint.setLinearText(false);
        textPaint.setTextSize(size);
        textPaint.setColor(color);
        textPaint.setTextAlign(align);
        c.drawText(s, x, y, textPaint);
    }

    /** Slow, readable record-indicator pulse; the frames stay still. */
    private float recBlink() {
        if (!ambientOn) return 1f;
        return 0.22f + 0.78f * (0.5f + 0.5f * (float) Math.cos(ambientLocal * Math.PI * 2 * 3));
    }

    private static float seg(float p, float start, float end) {
        if (p <= start) return 0f;
        if (p >= end) return 1f;
        float t = (p - start) / (end - start);
        return t * t * (3f - 2f * t);
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)),
                Color.red(color), Color.green(color), Color.blue(color));
    }
}
