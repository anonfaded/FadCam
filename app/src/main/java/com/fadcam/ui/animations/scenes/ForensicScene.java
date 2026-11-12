package com.fadcam.ui.animations.scenes;

import android.content.Context;
import android.graphics.Canvas;
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

    private final String[] confidence = new String[3];
    private final String[] score = new String[3];
    private final String[] time = new String[3];
    private final Drawable[] icons = new Drawable[3];

    public ForensicScene(Context context) {
        String person = context.getString(R.string.onboarding_demo_person);
        String gun = context.getString(R.string.onboarding_demo_gun);
        String knife = context.getString(R.string.onboarding_demo_knife);
        confidence[0] = context.getString(R.string.onboarding_demo_confidence, person, 96);
        confidence[1] = context.getString(R.string.onboarding_demo_confidence, gun, 91);
        confidence[2] = context.getString(R.string.onboarding_demo_confidence, knife, 88);
        int[] scores = {96, 91, 88};
        int[] minutes = {2, 5, 9};
        for (int i = 0; i < 3; i++) {
            score[i] = context.getString(R.string.onboarding_demo_score, scores[i]);
            time[i] = String.format(java.util.Locale.US, "02:%02d PM", minutes[i]);
        }
        icons[0] = ContextCompat.getDrawable(context, R.drawable.ic_detection_person);
        icons[1] = ContextCompat.getDrawable(context, R.drawable.ic_detection_gun);
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

        kit.label(canvas, confidence[idx], 106f, 65f, 6.4f, style.accent,
                Paint.Align.LEFT, true);
        kit.label(canvas, "SAVED", 106f, 74f, 6.4f,
                AnimationMath.withAlpha(style.ink, 235), Paint.Align.LEFT, true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.2f);
        paint.setColor(style.accent);
        canvas.drawLine(140f, 71f, 143f, 74f, paint);
        canvas.drawLine(143f, 74f, 149f, 67f, paint);

        // Evidence log; names stay in the viewer, the log carries time and score.
        kit.label(canvas, "EVIDENCE GALLERY", 14f, 22f, 6f, style.accent,
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
