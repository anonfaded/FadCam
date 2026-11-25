package com.fadcam.ui.animations.parts;

import android.graphics.Canvas;
import android.graphics.Paint;

import com.fadcam.ui.animations.AnimationMath;
import com.fadcam.ui.animations.CanvasKit;

/**
 * Stick figures shared by the motion, lounge and street scenes.
 *
 * <p>All three keep the detached-head look of the onboarding illustrations.
 * Coordinates are in each scene's design space; {@code phase} is a continuous
 * animation time, so callers decide the cadence.</p>
 */
public final class Walkers {

    private Walkers() {
    }

    /** Tall indoor figure used by the motion-detection scene. */
    public static void motion(CanvasKit kit, Canvas c, float x, float phase,
                              boolean walking, int alpha) {
        float swing = walking
                ? (float) Math.sin(phase * Math.PI * 2.0 * 9.0) * 2.6f : 0f;
        int ink = AnimationMath.withAlpha(0xFFF3F0E8, alpha);
        kit.dot(c, x, 37f, 3.2f, ink);
        Paint paint = kit.paint;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.4f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(ink);
        c.drawLine(x, 41.5f, x, 54f, paint);
        c.drawLine(x, 44f, x - 5.2f - swing, 50f, paint);
        c.drawLine(x, 44f, x + 5.2f + swing, 50f, paint);
        c.drawLine(x, 54f, x - 2.4f - swing, 63f, paint);
        c.drawLine(x, 54f, x + 2.4f + swing, 63f, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    /** Compact figure walking across the lounge CCTV scene. */
    public static void lounge(CanvasKit kit, Canvas c, float x, float floorY,
                              float phase) {
        int ink = 0xFFE8E4D8;
        kit.dot(c, x, floorY - 9.6f, 1.5f, ink);
        Paint paint = kit.paint;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.1f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(ink);
        c.drawLine(x, floorY - 8.1f, x, floorY - 4f, paint);
        float swing = (float) Math.sin(phase * Math.PI * 2.0 * 7.0) * 1.7f;
        c.drawLine(x, floorY - 7f, x - 2.2f - swing * 0.45f, floorY - 4.6f, paint);
        c.drawLine(x, floorY - 7f, x + 2.2f + swing * 0.45f, floorY - 4.6f, paint);
        c.drawLine(x, floorY - 4f, x - 1.4f - swing, floorY, paint);
        c.drawLine(x, floorY - 4f, x + 1.4f + swing, floorY, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    /**
     * Pedestrian in the bodycam street. {@code phase} is absolute animation
     * time in seconds so walkers keep a natural cadence.
     */
    public static void street(CanvasKit kit, Canvas c, float x, float groundY,
                              float scale, float phase, boolean right, int alpha) {
        int ink = AnimationMath.withAlpha(0xFFD9D5C8, alpha);
        kit.dot(c, x, groundY - 6.2f * scale, 1.05f * scale, ink);
        Paint paint = kit.paint;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.6f * scale);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(ink);
        c.drawLine(x, groundY - 5.1f * scale, x, groundY - 2.1f * scale, paint);
        float swing = (float) Math.sin(phase) * 1.1f * scale;
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
}
