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
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

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
    public static final int TYPE_BODYCAM = 5;
    public static final int TYPE_FLASHLIGHT = 6;
    public static final int TYPE_QR = 7;

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

    private static final String T_TIME = "00:12";

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final Path strokeDst = new Path();
    private final PathMeasure strokeMeasure = new PathMeasure();
    private final float[] pos = new float[2];
    private final DashPathEffect dash = new DashPathEffect(new float[]{2f, 2f}, 0f);
    private final Typeface mono = Typeface.MONOSPACE;
    private final Typeface monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
    private final boolean[][] qr = new boolean[21][21];
    private final String[] objectConfidence = new String[3];
    private final Drawable[] objectIcons = new Drawable[3];
    private Drawable torchIcon;
    private String qrValue;

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
        qrValue = context.getString(R.string.onboarding_demo_qr_value);
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
            case TYPE_REMOTE:
                drawRemote(canvas, progress);
                break;
            case TYPE_BODYCAM:
                drawBodycam(canvas, progress);
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

        // Camera preview.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.5f);
        paint.setColor(0x1FFFFFFF);
        c.drawLine(64f, 64f, 104f, 34f, paint);
        c.drawLine(64f, 72f, 96f, 46f, paint);
        frameRound(c, 77f, 40f, 93f, 56f, 1f, 0x88E8C77B, 0.7f);

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

        // The recording indicator stays alive after the screen goes dark.
        dot(c, 66f, 20f, 1.6f,
                withAlpha(C_RED, (int) (55f + 200f * recBlink())));
        label(c, T_TIME, 102f, 22f, 5.5f, C_INK, Paint.Align.RIGHT, false);
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

        // Layer 2: shape.
        int l2 = activeLayer == 1 ? 255 : 42;
        frameRound(c, 58f, 24f, 108f, 50f, 4f, withAlpha(C_GOLD, l2), 1.2f);
        dot(c, 82f, 37f, 3f, withAlpha(C_GOLD, l2));

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

        drawLayer(c, 132f, 15f, activeLayer == 0);
        drawLayer(c, 132f, 31f, activeLayer == 1);
        drawLayer(c, 132f, 47f, activeLayer == 2);

        // Bottom drawing toolbar: shapes, color, pen.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.45f);
        paint.setColor(C_LINE);
        c.drawLine(16f, 65f, 152f, 65f, paint);

        int toolColor = activeLayer == 1 ? C_GOLD : C_MUTED;
        paint.setColor(toolColor);
        paint.setStrokeWidth(activeLayer == 1 ? 1.1f : 0.8f);
        c.drawRect(18f, 69f, 26f, 77f, paint);
        c.drawCircle(32f, 73f, 4f, paint);

        int paletteColor = activeLayer == 2 ? C_GOLD : C_MUTED;
        paint.setColor(paletteColor);
        paint.setStrokeWidth(activeLayer == 2 ? 1.1f : 0.8f);
        c.drawCircle(55f, 73f, 6f, paint);
        dot(c, 53f, 71f, 1.4f, C_GOLD);
        dot(c, 58f, 72f, 1.4f, C_INK);
        dot(c, 54f, 76f, 1.4f, C_RED);

        // Pen tool: a simple line, no arrow head.
        int penColor = activeLayer == 0 ? C_GOLD : C_MUTED;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(activeLayer == 0 ? 1.2f : 0.9f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(penColor);
        c.drawLine(75f, 80f, 89f, 67f, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawLayer(Canvas c, float x, float y, boolean active) {
        int activeGold = ambientOn
                ? withAlpha(C_GOLD, (int) (255f * (0.65f + 0.35f * ambientWave)))
                : C_GOLD;
        fillRound(c, x, y, x + 18f, y + 10f, 1.5f, active ? 0x1AE8C77B : 0xFF151719);
        frameRound(c, x, y, x + 18f, y + 10f, 1.5f, active ? activeGold : C_LINE, 0.7f);
        // Small stacked-sheet mark.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.45f);
        paint.setColor(active ? activeGold : C_MUTED);
        c.drawRect(x + 5f, y + 3f, x + 12f, y + 7f, paint);
        c.drawLine(x + 7f, y + 8f, x + 13f, y + 8f, paint);
    }

    // ── 03 video editor ─────────────────────────────────────────────────────

    private void drawEditor(Canvas c, float p) {
        // Preview.
        fillRound(c, 12f, 8f, 156f, 42f, 3f, 0xFF101214);
        frameRound(c, 12f, 8f, 156f, 42f, 3f, C_LINE, 0.8f);
        fillRound(c, 18f, 12f, 150f, 38f, 2f, 0xFF1B1F22);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.6f);
        paint.setColor(0x22E8C77B);
        c.drawLine(24f, 33f, 68f, 19f, paint);
        c.drawLine(24f, 33f, 118f, 25f, paint);
        path.reset();
        path.moveTo(78f, 18f);
        path.lineTo(90f, 25f);
        path.lineTo(78f, 32f);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xCCE8C77B);
        c.drawPath(path, paint);

        float progress = ambientOn
                ? 0.5f + 0.5f * (float) Math.sin(ambientLocal * Math.PI * 2)
                : seg(p, 0.08f, 0.9f);
        fillRound(c, 18f, 39.5f, 150f, 41f, 0.5f, 0xFF2A2C2E);
        fillRound(c, 18f, 39.5f, 18f + 132f * progress, 41f, 0.5f, C_GOLD);

        // Timeline.
        fillRound(c, 12f, 48f, 156f, 78f, 3f, 0xFF101214);
        frameRound(c, 12f, 48f, 156f, 78f, 3f, C_LINE, 0.8f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.4f);
        paint.setColor(0x22FFFFFF);
        for (float x = 16f; x < 152f; x += 8f) c.drawLine(x, 51f, x, 55f, paint);

        clip(c, 18f, 62f, 0xFF1A1D1F, C_LINE, false);
        clip(c, 62f, 100f, 0xFF1A1D1F, C_LINE, false);
        clip(c, 100f, 150f, 0xFF2A2415, C_GOLD, true);

        // Clean trim handles on the selected clip.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(C_GOLD);
        c.drawLine(100f, 55f, 100f, 74f, paint);
        c.drawLine(150f, 55f, 150f, 74f, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);

        // Playhead glides through the selected clip.
        float head = 106f + 34f * progress;
        paint.setColor(0xFFEFECE4);
        paint.setStrokeWidth(0.8f);
        c.drawLine(head, 53f, head, 76f, paint);
        path.reset();
        path.moveTo(head - 2.4f, 51f);
        path.lineTo(head + 2.4f, 51f);
        path.lineTo(head, 55f);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        c.drawPath(path, paint);
    }

    private void clip(Canvas c, float l, float r, int fill, int border, boolean selected) {
        fillRound(c, l, 58f, r, 73f, 1.5f, fill);
        frameRound(c, l, 58f, r, 73f, 1.5f, border, selected ? 1f : 0.6f);
        if (selected) {
            // Filmstrip frames inside the gold-selected clip.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(0.35f);
            paint.setColor(0x44E8C77B);
            for (float x = l + 8f; x < r - 3f; x += 7f) {
                c.drawLine(x, 61f, x, 70f, paint);
            }
        }
    }

    // ── 04 motion detection ─────────────────────────────────────────────────

    private void drawMotion(Canvas c, float p) {
        fillRound(c, 12f, 4f, 114f, 70f, 3f, 0xFF101214);
        frameRound(c, 12f, 4f, 114f, 70f, 3f, C_LINE, 0.8f);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.35f);
        paint.setColor(0x14FFFFFF);
        for (float x = 22f; x < 112f; x += 14f) c.drawLine(x, 8f, x, 66f, paint);
        c.drawLine(16f, 64f, 110f, 64f, paint);

        dot(c, 16f, 13f, 1.4f,
                withAlpha(C_RED, (int) (55f + 200f * recBlink())));

        int idx = ambientOn ? ((int) (ambientLocal * 3f)) % 3 : 0;
        float stage = ambientLocal * 3f;
        float local = stage - (int) stage;
        float flash = ambientOn ? Math.max(0f, 1f - local / 0.2f) : 0f;
        float pop = 1f - 0.05f * flash;
        c.save();
        c.scale(pop, pop, 60f, 44f);
        drawObjectIcon(c, idx, 36f, 14f, 86f, 64f, 255);
        c.restore();

        float box = ambientOn ? 1f : seg(p, 0.2f, 0.5f);
        if (box > 0f) {
            float l, t, r, b;
            if (idx == 1) { l = 40f; t = 26f; r = 80f; b = 55f; }
            else if (idx == 2) { l = 38f; t = 31f; r = 84f; b = 50f; }
            else { l = 38f; t = 17f; r = 82f; b = 61f; }
            int gold = withAlpha(C_GOLD, (int) (220f * box));
            if (flash > 0f) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(withAlpha(C_GOLD, (int) (22f * flash)));
                rect.set(l, t, r, b);
                c.drawRect(rect, paint);
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(0.8f);
            paint.setColor(gold);
            rect.set(l, t, r, b);
            c.drawRect(rect, paint);
            paint.setStrokeWidth(1.4f);
            c.drawLine(l, t, l + 5f, t, paint);
            c.drawLine(l, t, l, t + 5f, paint);
            c.drawLine(r, t, r - 5f, t, paint);
            c.drawLine(r, t, r, t + 5f, paint);
            c.drawLine(l, b, l + 5f, b, paint);
            c.drawLine(l, b, l, b - 5f, paint);
            c.drawLine(r, b, r - 5f, b, paint);
            c.drawLine(r, b, r, b - 5f, paint);

            // Detection label with confidence.
            String confidence = objectConfidence[idx];
            float textSize = 6.8f;
            float tw = measureLabel(confidence, textSize, true);
            float tagB = t - 1.5f;
            float tagT = Math.max(4f, tagB - 9f);
            fillRound(c, l, tagT, l + tw + 5f, tagB, 1.4f, 0xE60B0C0D);
            frameRound(c, l, tagT, l + tw + 5f, tagB, 1.4f,
                    withAlpha(C_GOLD, (int) (200f * box)), 0.7f);
            label(c, confidence, l + 2.5f, tagB - 2.6f, textSize, gold,
                    Paint.Align.LEFT, true);
        }

        // Event gallery: person, gun, knife; selected event gets a gold frame.
        fillRound(c, 120f, 4f, 158f, 70f, 3f, 0xFF101214);
        frameRound(c, 120f, 4f, 158f, 70f, 3f, C_LINE, 0.8f);
        eventThumb(c, 124f, 17f, 0, idx == 0);
        eventThumb(c, 124f, 33f, 1, idx == 1);
        eventThumb(c, 124f, 49f, 2, idx == 2);
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
        // Camera source.
        fillRound(c, 10f, 22f, 48f, 70f, 4f, 0xFF121416);
        frameRound(c, 10f, 22f, 48f, 70f, 4f, C_LINE, 0.8f);
        fillRound(c, 14f, 26f, 44f, 66f, 3f, 0xFF1A1D1F);
        frameRound(c, 21f, 31f, 37f, 47f, 4f, 0x99C6A66A, 0.8f);
        dot(c, 29f, 39f, 3f, 0xFF2B3033);
        dot(c, 16f, 29f, 1.3f,
                withAlpha(C_RED, (int) (55f + 200f * recBlink())));

        // Signal arcs.
        float signal = seg(p, 0.2f, 0.55f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.8f);
        paint.setColor(withAlpha(C_GOLD, (int) (200f * signal)));
        for (int i = 1; i <= 3; i++) {
            float r = 4f + i * 4f;
            rect.set(56f - r, 46f - r, 56f + r, 46f + r);
            c.drawArc(rect, -52f, 104f, false, paint);
        }

        // Connection pulse.
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

        // Web dashboard.
        fillRound(c, 82f, 8f, 158f, 74f, 4f, 0xFF121416);
        frameRound(c, 82f, 8f, 158f, 74f, 4f, C_LINE, 0.8f);
        dot(c, 87f, 13f, 1f, C_DIM);
        dot(c, 91f, 13f, 1f, C_DIM);
        dot(c, 95f, 13f, 1f, C_DIM);
        paint.setColor(C_LINE);
        paint.setStrokeWidth(0.4f);
        c.drawLine(100f, 13f, 152f, 13f, paint);

        fillRound(c, 87f, 19f, 123f, 47f, 2f, 0xFF1B1F22);
        frameRound(c, 87f, 19f, 123f, 47f, 2f, C_LINE, 0.6f);
        dot(c, 91f, 24f, 1.1f, withAlpha(C_RED, (int) (55f + 200f * recBlink())));
        dot(c, 105f, 36f, 3.2f, 0xFF55595D);
        fillRound(c, 101f, 39f, 109f, 46f, 3f, 0xFF45494D);

        // Remote control pad.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.7f);
        paint.setColor(0x66C6A66A);
        c.drawCircle(138f, 58f, 8f, paint);
        dot(c, 138f, 58f, 2.2f, C_GOLD);
        paint.setColor(C_LINE);
        paint.setStrokeWidth(0.4f);
        c.drawLine(138f, 49f, 138f, 52f, paint);
        c.drawLine(138f, 64f, 138f, 67f, paint);
        c.drawLine(129f, 58f, 132f, 58f, paint);
        c.drawLine(144f, 58f, 147f, 58f, paint);
    }

    // ── 06 bodycam / dashcam ────────────────────────────────────────────────

    private void drawBodycam(Canvas c, float p) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.4f);
        paint.setColor(0x24FFFFFF);
        c.drawLine(84f, 8f, 84f, 86f, paint);

        float body = seg(p, 0.06f, 0.34f);
        if (body > 0f) {
            int a = (int) (255f * body);
            dot(c, 42f, 26f, 8f, withAlpha(0xFF4A4E52, a));
            fillRound(c, 26f, 36f, 58f, 70f, 10f, withAlpha(0xFF3D4145, a));
            // Chest-mounted phone, portrait and upside down.
            fillRound(c, 35f, 45f, 49f, 64f, 2.5f, withAlpha(0xFF101214, a));
            frameRound(c, 35f, 45f, 49f, 64f, 2.5f, withAlpha(C_GOLD, a), 0.8f);
            fillRound(c, 37f, 47f, 47f, 61f, 1.5f, withAlpha(0xFF1B1F22, a));
            dot(c, 42f, 60f, 1f, withAlpha(C_RED,
                    (int) (a * (0.22f + 0.78f * recBlink()))));
            paint.setColor(withAlpha(C_MUTED, (int) (160f * body)));
            paint.setStrokeWidth(0.5f);
            c.drawLine(29f, 40f, 36f, 47f, paint);
            c.drawLine(55f, 40f, 48f, 47f, paint);
        }

        float dash = seg(p, 0.22f, 0.55f);
        if (dash > 0f) {
            int a = (int) (255f * dash);
            // Phone on a dash stand, left of the wheel.
            fillRound(c, 96f, 29f, 112f, 53f, 3f, withAlpha(0xFF101214, a));
            frameRound(c, 96f, 29f, 112f, 53f, 3f, withAlpha(C_GOLD, a), 0.8f);
            fillRound(c, 98f, 31f, 110f, 49f, 2f, withAlpha(0xFF1B1F22, a));
            dot(c, 101f, 34f, 1f, withAlpha(C_RED,
                    (int) (a * (0.22f + 0.78f * recBlink()))));
            paint.setColor(withAlpha(C_MUTED, (int) (180f * dash)));
            paint.setStrokeWidth(0.8f);
            c.drawLine(104f, 53f, 104f, 59f, paint);
            c.drawLine(96f, 59f, 112f, 59f, paint);

            // Steering wheel; slight driving motion.
            float steer = ambientOn
                    ? (float) Math.sin(ambientLocal * Math.PI * 2) * 14f : 0f;
            c.save();
            c.rotate(steer, 134f, 47f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f);
            paint.setColor(withAlpha(C_OLIVE, a));
            c.drawCircle(134f, 47f, 15f, paint);
            paint.setStrokeWidth(0.8f);
            paint.setColor(withAlpha(C_MUTED, a));
            c.drawCircle(134f, 47f, 4f, paint);
            c.drawLine(134f, 47f, 134f, 32f, paint);
            c.drawLine(134f, 47f, 121f, 54f, paint);
            c.drawLine(134f, 47f, 147f, 54f, paint);
            dot(c, 134f, 47f, 1.8f, withAlpha(C_GOLD, a));
            c.restore();
        }
    }

    // ── mini: flashlight ────────────────────────────────────────────────────

    private void drawFlashlight(Canvas c, float p) {
        float glow = 0.35f + 0.65f * seg(p, 0.1f, 0.55f);
        if (ambientOn) glow = 0.82f + 0.18f * ambientWave;

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
            torchIcon.setAlpha(255);
            torchIcon.draw(c);
        }
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
