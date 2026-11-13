package com.fadcam.ui.animations.scenes;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PathMeasure;

import com.fadcam.ui.animations.AnimationMath;
import com.fadcam.ui.animations.CanvasKit;
import com.fadcam.ui.animations.Scene;
import com.fadcam.ui.animations.SceneFrame;
import com.fadcam.ui.animations.SceneStyle;

/**
 * Screen recording: three annotation layers on a canvas, a typed note, a colour
 * pencil stroke and a bottom drawing toolbar.
 */
public final class ScreenRecordScene implements Scene {

    private final PathMeasure strokeMeasure = new PathMeasure();
    private final android.graphics.Path strokeDst = new android.graphics.Path();
    private final float[] pos = new float[2];

    @Override
    public float designWidth() {
        return 168f;
    }

    @Override
    public float designHeight() {
        return 92f;
    }

    public ScreenRecordScene() {
        android.graphics.Path stroke = new android.graphics.Path();
        stroke.moveTo(22f, 47f);
        stroke.cubicTo(36f, 18f, 50f, 56f, 68f, 34f);
        stroke.cubicTo(82f, 17f, 96f, 50f, 120f, 30f);
        strokeMeasure.setPath(stroke, false);
    }

    @Override
    public void draw(Canvas canvas, SceneFrame frame, CanvasKit kit) {
        SceneStyle style = frame.style;
        Paint paint = kit.paint;
        float progress = frame.progress;

        kit.fillRound(canvas, 14f, 8f, 154f, 62f, 4f, 0xFF101214);
        kit.frameRound(canvas, 14f, 8f, 154f, 62f, 4f, style.line, 0.8f);

        canvas.save();
        kit.clipRound(canvas, 14f, 8f, 154f, 62f, 3f);

        // Canvas grid.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.4f);
        paint.setColor(0x12FFFFFF);
        for (float x = 24f; x < 126f; x += 16f) canvas.drawLine(x, 12f, x, 58f, paint);
        for (float y = 18f; y < 58f; y += 12f) canvas.drawLine(18f, y, 126f, y, paint);

        int activeLayer = frame.ambient ? ((int) (frame.phase * 3f)) % 3 : 0;

        // Layer 1: freehand pen stroke.
        int l1 = activeLayer == 0 ? 255 : 42;
        float reveal = AnimationMath.seg(progress, 0.08f, 0.72f);
        if (reveal > 0f) {
            strokeDst.reset();
            float len = strokeMeasure.getLength() * reveal;
            strokeMeasure.getSegment(0f, len, strokeDst, true);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(activeLayer == 0 ? 1.8f : 1.2f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(AnimationMath.withAlpha(style.accent, l1));
            canvas.drawPath(strokeDst, paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
            if (activeLayer == 0 && strokeMeasure.getPosTan(len, pos, null)) {
                kit.dot(canvas, pos[0], pos[1], 1.5f,
                        AnimationMath.withAlpha(style.accent, 255));
            }
        }

        // Layer 2: typed text annotation.
        int l2 = activeLayer == 1 ? 255 : 42;
        drawTextAnnotation(kit, canvas, frame, l2);

        // Layer 3: colour pencil stroke.
        int l3 = activeLayer == 2 ? 255 : 42;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.4f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(AnimationMath.withAlpha(style.alert, l3));
        kit.path.reset();
        kit.path.moveTo(28f, 50f);
        kit.path.cubicTo(44f, 30f, 64f, 54f, 82f, 38f);
        kit.path.cubicTo(94f, 28f, 104f, 36f, 114f, 30f);
        canvas.drawPath(kit.path, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);

        kit.dot(canvas, 20f, 16f, 1.4f,
                AnimationMath.withAlpha(style.alert, (int) (55f + 200f * frame.pulse(3f))));

        canvas.restore();

        drawLayer(kit, canvas, frame, 132f, 15f, 0, activeLayer == 0);
        drawLayer(kit, canvas, frame, 132f, 31f, 1, activeLayer == 1);
        drawLayer(kit, canvas, frame, 132f, 47f, 2, activeLayer == 2);

        // Bottom drawing toolbar: text, palette, pen.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.45f);
        paint.setColor(style.line);
        canvas.drawLine(16f, 65f, 152f, 65f, paint);

        int toolColor = activeLayer == 1 ? style.accent : style.muted;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(activeLayer == 1 ? 1.2f : 1.0f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(toolColor);
        canvas.drawLine(19f, 68.5f, 29f, 68.5f, paint);
        canvas.drawLine(24f, 68.5f, 24f, 78.5f, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);

        int paletteColor = activeLayer == 2 ? style.accent : style.muted;
        paint.setColor(paletteColor);
        paint.setStrokeWidth(activeLayer == 2 ? 1.1f : 0.8f);
        canvas.drawCircle(48f, 73f, 6f, paint);
        kit.dot(canvas, 46f, 71f, 1.4f, style.accent);
        kit.dot(canvas, 51f, 72f, 1.4f, style.ink);
        kit.dot(canvas, 47f, 76f, 1.4f, style.alert);

        int penColor = activeLayer == 0 ? style.accent : style.muted;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(activeLayer == 0 ? 1.2f : 1.0f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(penColor);
        canvas.drawLine(66f, 78.5f, 78f, 67.5f, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawLayer(CanvasKit kit, Canvas c, SceneFrame frame,
                           float x, float y, int kind, boolean active) {
        SceneStyle style = frame.style;
        int activeGold = frame.ambient
                ? AnimationMath.withAlpha(style.accent, (int) (255f * (0.65f + 0.35f * frame.wave())))
                : style.accent;
        kit.fillRound(c, x, y, x + 18f, y + 10f, 1.5f,
                active ? AnimationMath.withAlpha(style.accent, 0x1A) : 0xFF151719);
        kit.frameRound(c, x, y, x + 18f, y + 10f, 1.5f,
                active ? activeGold : style.line, 0.7f);
        int ink = active ? activeGold : style.muted;
        if (kind == 0) {
            kit.paint.setStyle(Paint.Style.STROKE);
            kit.paint.setStrokeWidth(0.55f);
            kit.paint.setStrokeCap(Paint.Cap.ROUND);
            kit.paint.setColor(ink);
            c.drawLine(x + 6f, y + 7f, x + 12f, y + 3.5f, kit.paint);
            kit.paint.setStrokeCap(Paint.Cap.BUTT);
        } else if (kind == 1) {
            kit.label(c, "T", x + 9f, y + 7.8f, 5.6f, ink, Paint.Align.CENTER, true);
        } else {
            kit.dot(c, x + 9f, y + 5f, 2.1f, ink);
        }
    }

    /** "I <heart> FadCam", soft green with a quiet text box behind it. */
    private void drawTextAnnotation(CanvasKit kit, Canvas c, SceneFrame frame, int alpha) {
        SceneStyle style = frame.style;
        int color = AnimationMath.withAlpha(style.success, alpha);
        String first = "I ";
        String last = " FadCam";
        float size = 7.2f;
        float wFirst = kit.measure(first, size, true);
        float wLast = kit.measure(last, size, true);
        float heart = 7.4f;
        float total = wFirst + heart + wLast;
        float x = 70f - total * 0.5f;
        float baseline = 25f;

        kit.fillRound(c, x - 3f, baseline - 7.4f, x + total + 3f, baseline + 2.4f,
                1.5f, 0x101213);
        kit.frameRound(c, x - 3f, baseline - 7.4f, x + total + 3f, baseline + 2.4f,
                1.5f, AnimationMath.withAlpha(style.success, (int) (alpha * 0.45f)), 0.45f);

        float pulse = frame.ambient && alpha > 200
                ? 0.78f + 0.22f * frame.wave() : 1f;
        kit.label(c, first, x, baseline, size, color, Paint.Align.LEFT, true);
        heart(kit, c, x + wFirst + heart * 0.5f, baseline - 2.4f, 3.6f,
                AnimationMath.withAlpha(style.success, (int) (alpha * pulse)));
        kit.label(c, last, x + wFirst + heart, baseline, size, color,
                Paint.Align.LEFT, true);
    }

    private void heart(CanvasKit kit, Canvas c, float cx, float cy, float s, int color) {
        kit.path.reset();
        kit.path.moveTo(cx, cy + s * 0.78f);
        kit.path.cubicTo(cx - s * 1.15f, cy - s * 0.05f,
                cx - s * 0.62f, cy - s * 0.98f, cx, cy - s * 0.32f);
        kit.path.cubicTo(cx + s * 0.62f, cy - s * 0.98f,
                cx + s * 1.15f, cy - s * 0.05f, cx, cy + s * 0.78f);
        kit.path.close();
        kit.paint.setStyle(Paint.Style.FILL);
        kit.paint.setColor(color);
        c.drawPath(kit.path, kit.paint);
    }
}
