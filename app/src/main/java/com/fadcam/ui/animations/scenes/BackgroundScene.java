package com.fadcam.ui.animations.scenes;

import android.graphics.Canvas;
import android.graphics.Paint;

import com.fadcam.ui.animations.AnimationMath;
import com.fadcam.ui.animations.CanvasKit;
import com.fadcam.ui.animations.Scene;
import com.fadcam.ui.animations.SceneFrame;
import com.fadcam.ui.animations.SceneStyle;
import com.fadcam.ui.animations.parts.PhoneShell;

/**
 * Background recording: a phone home screen with a centred clock and app grid,
 * then the screen locks and the capture timer starts.
 */
public final class BackgroundScene implements Scene {

    private final StringBuilder clockText = new StringBuilder(5);
    private long lockStartedMs;
    private long lastClockSecond = -1L;

    @Override
    public float designWidth() {
        return 168f;
    }

    @Override
    public float designHeight() {
        return 92f;
    }

    @Override
    public void reset() {
        lockStartedMs = 0L;
        lastClockSecond = -1L;
    }

    @Override
    public void draw(Canvas canvas, SceneFrame frame, CanvasKit kit) {
        SceneStyle style = frame.style;
        Paint paint = kit.paint;

        // Thin-bezel phone body, matching the motion preview.
        PhoneShell.draw(kit, canvas, 62f, 13f, 106f, 79f, 1f, 4f,
                0xFF141719, AnimationMath.withAlpha(style.accentDeep, 90), 0.6f,
                0xFF1B1F22);

        canvas.save();
        kit.clipRound(canvas, 62f, 13f, 106f, 79f, 4f);

        // Home screen: centred clock.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.5f);
        paint.setColor(AnimationMath.withAlpha(style.accent, 0x77));
        canvas.drawCircle(84f, 30f, 6f, paint);
        canvas.drawLine(84f, 30f, 84f, 25.5f, paint);
        canvas.drawLine(84f, 30f, 87.5f, 31.8f, paint);

        // App grid.
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                float bx = 68.5f + col * 12f;
                float by = 41f + row * 11f;
                kit.fillRound(canvas, bx, by, bx + 7f, by + 7f, 1.6f, 0xFF24282B);
                kit.frameRound(canvas, bx, by, bx + 7f, by + 7f, 1.6f,
                        AnimationMath.withAlpha(style.accent, 0x33), 0.3f);
            }
        }

        // The lock takes over; the capture indicator starts exactly with it.
        float dim = AnimationMath.seg(frame.progress, 0.35f, 0.72f);
        if (dim > 0f) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(AnimationMath.withAlpha(style.background, (int) (255f * dim)));
            canvas.drawRect(62f, 13f, 106f, 79f, paint);

            int lockColor = AnimationMath.withAlpha(style.accent, (int) (190f * dim));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.4f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(lockColor);
            kit.path.reset();
            kit.path.moveTo(80f, 49f);
            kit.path.lineTo(80f, 44f);
            kit.path.cubicTo(80f, 36.5f, 90f, 36.5f, 90f, 44f);
            kit.path.lineTo(90f, 49f);
            canvas.drawPath(kit.path, paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
            kit.fillRound(canvas, 76f, 47f, 94f, 62f, 3f, 0xFF0D0F10);
            kit.frameRound(canvas, 76f, 47f, 94f, 62f, 3f, lockColor, 1.2f);
            kit.dot(canvas, 85f, 53f, 1.3f, lockColor);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1f);
            canvas.drawLine(85f, 54f, 85f, 57.5f, paint);
        }

        if (dim > 0.5f) {
            if (lockStartedMs == 0L) {
                lockStartedMs = frame.timeMs;
            }
            long seconds = (frame.timeMs - lockStartedMs) / 1000L;
            if (seconds != lastClockSecond) {
                lastClockSecond = seconds;
                formatClock(seconds);
            }
            kit.dot(canvas, 66f, 20f, 1.6f,
                    AnimationMath.withAlpha(style.alert, (int) (55f + 200f * frame.pulse(3f))));
            kit.label(canvas, clockText, 102f, 22f, 5.5f, style.ink,
                    Paint.Align.RIGHT, false);
        }

        canvas.restore();
    }

    private void formatClock(long seconds) {
        long m = (seconds / 60L) % 60L;
        long s = seconds % 60L;
        clockText.setLength(0);
        appendTwo(clockText, (int) m);
        clockText.append(':');
        appendTwo(clockText, (int) s);
    }

    private static void appendTwo(StringBuilder out, int value) {
        if (value < 10) out.append('0');
        out.append(value);
    }
}
