package com.fadcam.ui.animations.parts;

import android.graphics.Canvas;
import android.graphics.Paint;

import com.fadcam.ui.animations.AnimationMath;
import com.fadcam.ui.animations.CanvasKit;
import com.fadcam.ui.animations.SceneFrame;

/**
 * The lounge CCTV view: sofa, plant, window and a walker crossing the room.
 *
 * <p>Local design space is 70x30 so it can be reused at any size. Callers pick
 * {@code fit=true} to letterbox the whole room (the dashboard grid) or
 * {@code fit=false} to centre-crop it (a portrait phone screen).</p>
 */
public final class LoungeRoom {

    public static final float DESIGN_W = 70f;
    public static final float DESIGN_H = 30f;

    private static final long WALK_PERIOD_MS = 6400L;

    private final NightSky nightSky = new NightSky();

    public void draw(CanvasKit kit, Canvas c, SceneFrame frame,
                     float l, float t, float r, float b,
                     boolean fit, float corner) {
        float scale = fit
                ? Math.min((r - l) / DESIGN_W, (b - t) / DESIGN_H)
                : Math.max((r - l) / DESIGN_W, (b - t) / DESIGN_H);
        float drawW = DESIGN_W * scale;
        float drawH = DESIGN_H * scale;
        float ox = l + (r - l - drawW) * 0.5f;
        float oy = t + (b - t - drawH) * 0.5f;

        c.save();
        kit.clipRound(c, l, t, r, b, corner);
        c.translate(ox, oy);
        c.scale(scale, scale);

        paintLounge(kit, c, frame);

        c.restore();
    }

    /** Draws the room in its own 70x30 space; useful for custom compositions. */
    public void drawLocal(CanvasKit kit, Canvas c, SceneFrame frame) {
        paintLounge(kit, c, frame);
    }

    private void paintLounge(CanvasKit kit, Canvas c, SceneFrame frame) {
        Paint paint = kit.paint;

        // Wall, floor and baseboard.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF202830);
        c.drawRect(0f, 0f, DESIGN_W, 17f, paint);
        paint.setColor(0xFF2A3239);
        c.drawRect(0f, 17f, DESIGN_W, DESIGN_H, paint);
        paint.setColor(AnimationMath.withAlpha(frame.style.accentDeep, 0x33));
        c.drawRect(0f, 17f, DESIGN_W, 17.5f, paint);

        // Window: the shared night sky, framed.
        nightSky.draw(kit, c, frame, 11f, 4f, 25f, 14f, 0.8f);
        kit.frameRound(c, 11f, 4f, 25f, 14f, 0.8f,
                AnimationMath.withAlpha(frame.style.accent, 0x88), 0.5f);

        // Framed picture.
        kit.fillRound(c, 3f, 5f, 9f, 11f, 0.5f, 0xFF12181C);
        kit.frameRound(c, 3f, 5f, 9f, 11f, 0.5f,
                AnimationMath.withAlpha(frame.style.accentDeep, 0x66), 0.4f);

        // Sofa.
        kit.fillRound(c, 27f, 13f, 47f, 18f, 2f, 0xFF3E4A42);
        kit.fillRound(c, 28f, 17f, 46f, 22f, 1.5f, 0xFF4C5A50);
        kit.fillRound(c, 25f, 16f, 29f, 23f, 1.2f, 0xFF3E4A42);
        kit.fillRound(c, 45f, 16f, 49f, 23f, 1.2f, 0xFF3E4A42);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.4f);
        paint.setColor(AnimationMath.withAlpha(frame.style.accent, 0x33));
        c.drawLine(33f, 14f, 33f, 17f, paint);
        c.drawLine(41f, 14f, 41f, 17f, paint);

        // Coffee table and plant.
        kit.fillRound(c, 33f, 24f, 43f, 26f, 0.8f, 0xFF262D33);
        kit.fillRound(c, 35f, 26f, 41f, 27f, 0.5f, 0xFF1C2227);
        kit.fillRound(c, 51f, 21f, 55f, 25f, 0.8f, 0xFF7A5A3A);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.6f);
        paint.setColor(0xFF3C6B4A);
        c.drawLine(53f, 21f, 53f, 17f, paint);
        c.drawLine(53f, 19f, 50.5f, 17f, paint);
        c.drawLine(53f, 19f, 55.5f, 17f, paint);
        kit.dot(c, 51.2f, 17.5f, 1.1f, 0xFF3C6B4A);
        kit.dot(c, 54.8f, 18f, 1f, 0xFF4E7C58);
        kit.dot(c, 53f, 16.5f, 1.5f, 0xFF4E7C58);

        // Walker crossing the room, wrapping fully off both edges.
        float walk = (frame.timeMs % WALK_PERIOD_MS) / (float) WALK_PERIOD_MS;
        Walkers.lounge(kit, c, -4f + 78f * walk, 28f, walk);

        // CCTV scanlines and recording dot.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.3f);
        paint.setColor(0x0CFFFFFF);
        for (float y = 0f; y < DESIGN_H; y += 2f) {
            c.drawLine(0f, y, DESIGN_W, y, paint);
        }
        kit.dot(c, 2f, 2f, 1.1f,
                AnimationMath.withAlpha(0xFFD9564A, (int) (55f + 200f * frame.pulse(3f))));
    }
}
