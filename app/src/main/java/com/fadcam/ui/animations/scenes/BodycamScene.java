package com.fadcam.ui.animations.scenes;

import android.graphics.Canvas;
import android.graphics.Paint;

import com.fadcam.ui.animations.AnimationMath;
import com.fadcam.ui.animations.CanvasKit;
import com.fadcam.ui.animations.Scene;
import com.fadcam.ui.animations.SceneFrame;
import com.fadcam.ui.animations.SceneStyle;
import com.fadcam.ui.animations.parts.PhoneShell;
import com.fadcam.ui.animations.parts.Walkers;

/**
 * Bodycam: a chest-mounted phone over a busy street. Walkers move in both
 * directions at a natural cadence and wrap fully off-screen.
 */
public final class BodycamScene implements Scene {

    private static final long STREET_PERIOD_MS = 9000L;

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

        canvas.save();
        canvas.translate(84f, 46f);
        canvas.scale(1.18f, 1.18f);
        canvas.translate(-84f, -46f);

        // Head and torso.
        kit.dot(canvas, 84f, 24f, 8f, 0xFF4A4E52);
        kit.fillRound(canvas, 64f, 36f, 104f, 76f, 11f, 0xFF3D4145);

        // Lanyard sits behind the phone.
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(AnimationMath.withAlpha(style.muted, 150));
        paint.setStrokeWidth(0.6f);
        canvas.drawLine(68f, 41f, 76f, 48f, paint);
        canvas.drawLine(100f, 41f, 92f, 48f, paint);

        // Chest-mounted phone, thin rim.
        PhoneShell.draw(kit, canvas, 75f, 42f, 93f, 70f, 1f, 2f,
                0xFF101214, style.accent, 0.7f, 0xFF10161A);

        canvas.save();
        kit.clipRound(canvas, 75f, 42f, 93f, 70f, 2f);
        drawStreet(kit, canvas, frame, style);
        canvas.restore();

        kit.dot(canvas, 76.8f, 43.8f, 0.7f,
                AnimationMath.withAlpha(style.alert, (int) (255f * (0.22f + 0.78f * frame.pulse(3f)))));
        canvas.restore();
    }

    /** Street seen through the phone screen. Coordinates are absolute design units. */
    private void drawStreet(CanvasKit kit, Canvas c, SceneFrame frame, SceneStyle style) {
        Paint paint = kit.paint;
        int alpha = 255;

        // Skyline.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(AnimationMath.withAlpha(0xFF1B2329, alpha));
        c.drawRect(75f, 42f, 93f, 53f, paint);
        paint.setColor(AnimationMath.withAlpha(0xFF2A3238, alpha));
        c.drawRect(75f, 46f, 79f, 53f, paint);
        c.drawRect(81f, 44f, 85f, 53f, paint);
        c.drawRect(87f, 47f, 91f, 53f, paint);
        c.drawRect(91.5f, 45f, 93f, 53f, paint);
        paint.setColor(AnimationMath.withAlpha(style.accent, 90));
        c.drawRect(76f, 48f, 77.5f, 49.5f, paint);
        c.drawRect(82f, 46f, 83.5f, 47.5f, paint);
        c.drawRect(88f, 49f, 90f, 50.5f, paint);

        // Pavement.
        paint.setColor(AnimationMath.withAlpha(0xFF2A3036, alpha));
        c.drawRect(75f, 53f, 93f, 70f, paint);
        paint.setColor(AnimationMath.withAlpha(0xFF3A4044, alpha));
        c.drawRect(89.5f, 45f, 90.2f, 61f, paint);
        paint.setColor(style.accent);
        c.drawRect(88.5f, 44.5f, 91.2f, 45.6f, paint);
        paint.setColor(AnimationMath.withAlpha(0xFF232A30, alpha));
        c.drawRect(76f, 61f, 82f, 62.2f, paint);
        c.drawRect(76.5f, 62.2f, 77.2f, 64f, paint);
        c.drawRect(80.8f, 62.2f, 81.5f, 64f, paint);

        float flow = (frame.timeMs % STREET_PERIOD_MS) / (float) STREET_PERIOD_MS;
        walk(kit, c, frame, flow, 0.00f, 66f, 36f, true, 66f, 1.15f, alpha);
        walk(kit, c, frame, flow, 0.31f, 66f, 36f, false, 68.5f, 1f, alpha);
        walk(kit, c, frame, flow, 0.57f, 66f, 36f, true, 64f, 1.25f, alpha);
        walk(kit, c, frame, flow, 0.79f, 66f, 36f, false, 69.5f, 0.95f, alpha);
    }

    private void walk(CanvasKit kit, Canvas c, SceneFrame frame, float flow,
                      float offset, float minX, float span, boolean right,
                      float groundY, float scale, int alpha) {
        float t = (flow + offset) % 1f;
        float x = right ? minX + span * t : minX + span * (1f - t);
        float phase = (frame.timeMs / 1000f) * 4.2f + offset * 6.283f;
        Walkers.street(kit, c, x, groundY, scale, right ? phase : -phase, right, alpha);
    }
}
