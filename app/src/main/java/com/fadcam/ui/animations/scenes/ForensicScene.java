package com.fadcam.ui.animations.scenes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;

import androidx.core.content.ContextCompat;

import com.fadcam.R;
import com.fadcam.ui.animations.AnimationMath;
import com.fadcam.ui.animations.CanvasKit;
import com.fadcam.ui.animations.Scene;
import com.fadcam.ui.animations.SceneFrame;
import com.fadcam.ui.animations.SceneStyle;

/**
 * Forensic intelligence: an evidence viewer with a golden scan frame and a
 * gallery log of detected objects with times and confidence scores.
 */
public final class ForensicScene implements Scene {

    /** Scene metrics are design units on the shared 168x92 canvas, not dp. */
    private static final float VIEWER_TEXT_X = 106f;
    private static final float VIEWER_TEXT_TOP_Y = 65f;
    private static final float VIEWER_TEXT_BOTTOM_Y = 74f;
    /** The tick mark owns everything right of this, so text must stop before it. */
    private static final float VIEWER_TEXT_RIGHT = 136f;
    private static final float VIEWER_TEXT_SIZE = 6.4f;
    private static final float VIEWER_TEXT_MIN_SIZE = 3.6f;

    private final String evidenceGallery;
    private final String savedLabel;
    /** Saved-label size, resolved once: long translations get smaller type, never overlap. */
    private float savedTextSize = VIEWER_TEXT_SIZE;
    private final String[] confidence = new String[3];
    private final String[] score = new String[3];
    private final String[] time = new String[3];
    private final Drawable[] icons = new Drawable[3];

    public ForensicScene(Context context) {
        String person = context.getString(R.string.onboarding_demo_person);
        // Everyday categories a home security camera actually sees.
        String car = context.getString(R.string.onboarding_demo_car);
        String knife = context.getString(R.string.onboarding_demo_knife);
        confidence[0] = context.getString(R.string.onboarding_demo_confidence, person, 96);
        confidence[1] = context.getString(R.string.onboarding_demo_confidence, car, 91);
        confidence[2] = context.getString(R.string.onboarding_demo_confidence, knife, 88);
        evidenceGallery = context.getString(R.string.onboarding_demo_evidence_gallery);
        savedLabel = context.getString(R.string.onboarding_demo_saved);
        savedTextSize = fitTextSize(savedLabel, VIEWER_TEXT_RIGHT - VIEWER_TEXT_X);
        int[] scores = {96, 91, 88};
        int[] minutes = {2, 5, 9};
        // The mock log has to read like the device's own clock, otherwise the times stay
        // Latin "02:02 PM" in Arabic.
        java.text.DateFormat timeFormat = android.text.format.DateFormat.getTimeFormat(context);
        java.util.Calendar clock = java.util.Calendar.getInstance();
        for (int i = 0; i < 3; i++) {
            score[i] = context.getString(R.string.onboarding_demo_score, scores[i]);
            clock.set(java.util.Calendar.HOUR_OF_DAY, 14);
            clock.set(java.util.Calendar.MINUTE, minutes[i]);
            clock.set(java.util.Calendar.SECOND, 0);
            time[i] = com.fadcam.utils.DisplayLocale.digits(
                    timeFormat.format(clock.getTime()), context);
        }
        icons[0] = ContextCompat.getDrawable(context, R.drawable.ic_detection_person);
        // Scene icons are untinted so they stay visible on the dark stage; the shared
        // UI glyphs carry an opaque tint intended for light surfaces.
        icons[1] = ContextCompat.getDrawable(context, R.drawable.ic_detection_car);
        icons[2] = ContextCompat.getDrawable(context, R.drawable.ic_detection_knife);
    }

    @Override
    public float designWidth() {
        return 168f;
    }

    @Override
    public float designHeight() {
        return 92f;
    }

    @Override
    public void focusRect(RectF out) {
        out.set(6f, 10f, 162f, 82f);
    }

    @Override
    public void draw(Canvas canvas, SceneFrame frame, CanvasKit kit) {
        SceneStyle style = frame.style;
        Paint paint = kit.paint;

        int idx = frame.ambient
                ? ((int) (frame.phase * 3f)) % 3
                : (int) Math.max(0f, Math.min(2f, (frame.progress - 0.3f) * 4f));

        kit.fillRound(canvas, 10f, 14f, 158f, 78f, 3f, 0xFF101214);
        kit.frameRound(canvas, 10f, 14f, 158f, 78f, 3f, style.line, 0.8f);

        // Evidence viewer; the scan stays inside the golden frame.
        kit.fillRound(canvas, 102f, 18f, 156f, 76f, 2.5f, 0xFF1B1F22);
        kit.frameRound(canvas, 102f, 18f, 156f, 76f, 2.5f, style.line, 0.6f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.3f);
        paint.setColor(0x12FFFFFF);
        for (float x = 108f; x < 156f; x += 8f) canvas.drawLine(x, 20f, x, 74f, paint);

        float sweep = frame.ambient ? frame.phase : AnimationMath.seg(frame.progress, 0.45f, 0.85f);
        float sy = 22f + 34f * sweep;
        paint.setStrokeWidth(0.7f);
        paint.setColor(AnimationMath.withAlpha(style.accent, 0x66));
        canvas.drawLine(106f, sy, 152f, sy, paint);

        drawIcon(kit, canvas, idx, 111f, 22f, 145f, 56f, 255);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.45f);
        paint.setColor(AnimationMath.withAlpha(style.accent, 170));
        kit.rect.set(106f, 22f, 152f, 56f);
        canvas.drawRect(kit.rect, paint);
        paint.setStrokeWidth(1.6f);
        paint.setColor(style.accent);
        canvas.drawLine(106f, 22f, 111f, 22f, paint);
        canvas.drawLine(106f, 22f, 106f, 27f, paint);
        canvas.drawLine(152f, 22f, 147f, 22f, paint);
        canvas.drawLine(152f, 22f, 152f, 27f, paint);
        canvas.drawLine(106f, 56f, 111f, 56f, paint);
        canvas.drawLine(106f, 56f, 106f, 51f, paint);
        canvas.drawLine(152f, 56f, 147f, 56f, paint);
        canvas.drawLine(152f, 56f, 152f, 51f, paint);

        kit.label(canvas, confidence[idx], VIEWER_TEXT_X, VIEWER_TEXT_TOP_Y,
                VIEWER_TEXT_SIZE, style.accent, Paint.Align.LEFT, true);
        kit.label(canvas, savedLabel, VIEWER_TEXT_X, VIEWER_TEXT_BOTTOM_Y,
                savedTextSize, AnimationMath.withAlpha(style.ink, 235), Paint.Align.LEFT, true);
        // Same rounded tick as the QR result card, at the viewer's smaller scale.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.1f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(style.accent);
        kit.path.reset();
        kit.path.moveTo(142.5f, 71.5f);
        kit.path.lineTo(144.5f, 73.5f);
        kit.path.lineTo(147.5f, 69.5f);
        canvas.drawPath(kit.path, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeJoin(Paint.Join.MITER);

        // Evidence log; names stay in the viewer, the log carries time and score.
        kit.label(canvas, evidenceGallery, 14f, 22f, 6f, style.accent,
                Paint.Align.LEFT, true);
        for (int i = 0; i < 3; i++) {
            float reveal = frame.ambient ? 1f
                    : AnimationMath.seg(frame.progress, 0.1f + i * 0.12f, 0.32f + i * 0.12f);
            int alpha = (int) (255f * reveal);
            if (alpha <= 0) continue;
            float top = 26f + i * 17f;
            boolean selected = i == idx;
            kit.fillRound(canvas, 14f, top, 98f, top + 15f, 1.5f,
                    AnimationMath.withAlpha(0x151719, alpha));
            kit.frameRound(canvas, 14f, top, 98f, top + 15f, 1.5f,
                    AnimationMath.withAlpha(selected ? style.accent : style.line, alpha),
                    selected ? 0.9f : 0.5f);
            drawIcon(kit, canvas, i, 18f, top + 1.5f, 30f, top + 13.5f, alpha);
            kit.label(canvas, time[i], 33f, top + 11f, 6.2f,
                    AnimationMath.withAlpha(selected ? style.accent : style.muted, alpha),
                    Paint.Align.LEFT, true);
            kit.label(canvas, score[i], 94f, top + 11f, 6.4f,
                    AnimationMath.withAlpha(selected ? style.accent : style.muted, alpha),
                    Paint.Align.RIGHT, true);
        }
    }

    /**
     * Largest size at which {@code text} fits {@code width}, capped at the default size.
     * Measured once per scene: "SAVED" fits at full size, "GESPEICHERT" and
     * "ΑΠΟΘΗΚΕΥΜΕΝΟ" need smaller type so they never reach the tick mark.
     */
    private static float fitTextSize(String text, float width) {
        if (text == null || text.isEmpty() || width <= 0f) return VIEWER_TEXT_SIZE;
        Paint measure = new Paint(Paint.ANTI_ALIAS_FLAG);
        measure.setTypeface(android.graphics.Typeface.create(
                android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD));
        measure.setTextSize(VIEWER_TEXT_SIZE);
        float natural = measure.measureText(text);
        if (natural <= width || natural <= 0f) return VIEWER_TEXT_SIZE;
        return Math.max(VIEWER_TEXT_MIN_SIZE, VIEWER_TEXT_SIZE * width / natural);
    }

    private void drawIcon(CanvasKit kit, Canvas c, int idx,
                          float l, float t, float r, float b, int alpha) {
        Drawable icon = icons[idx];
        if (icon == null) return;
        icon.setBounds(Math.round(l), Math.round(t), Math.round(r), Math.round(b));
        icon.setAlpha(alpha);
        icon.draw(c);
        icon.setAlpha(255);
    }
}
