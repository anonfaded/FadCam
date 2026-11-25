package com.fadcam.ui.animations.parts;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;

import com.fadcam.ui.animations.AnimationMath;
import com.fadcam.ui.animations.CanvasKit;
import com.fadcam.ui.animations.SceneFrame;

/**
 * The road ahead: twilight-blue sky, hand-drawn clouds, a mountain range, a
 * receding road and a distant car. Reused as the windshield view and inside the
 * dash phone.
 *
 * <p>Local design space is 120x60. The sky/horizon are deliberately low so a
 * cover-crop still leaves the sky visible on wide and tall hosts.</p>
 */
public final class RoadView {

    public static final float DESIGN_W = 120f;
    public static final float DESIGN_H = 60f;
    private static final float HORIZON_Y = 20f;
    private static final long FLOW_PERIOD_MS = 1800L;

    private static final int CLOUD = 0x3DDCE9F2;
    private static final int MOUNTAIN = 0xFF2C485B;
    private static final int SNOW = 0x77DCE9F2;
    private static final int HILL = 0xFF22333F;

    private final Shader sky = new LinearGradient(0f, 0f, 0f, HORIZON_Y,
            new int[]{0xFF14324B, 0xFF2E648C, 0xFF88B4CF},
            new float[]{0f, 0.58f, 1f}, Shader.TileMode.CLAMP);
    private final Shader asphalt = new LinearGradient(0f, HORIZON_Y, 0f, DESIGN_H,
            new int[]{0xFF252C33, 0xFF343B42}, null, Shader.TileMode.CLAMP);

    /**
     * @param fit     true letterboxes the whole scene, false centre-crops it
     * @param corner  clip radius for a phone screen; 0 for the windshield
     * @param anchorY 0 anchors the crop to the top (keeps the sky), 0.5 centres it
     */
    public void draw(CanvasKit kit, Canvas c, SceneFrame frame,
                     float l, float t, float r, float b,
                     boolean fit, float corner, float anchorY) {
        float scale = fit
                ? Math.min((r - l) / DESIGN_W, (b - t) / DESIGN_H)
                : Math.max((r - l) / DESIGN_W, (b - t) / DESIGN_H);
        float drawW = DESIGN_W * scale;
        float drawH = DESIGN_H * scale;
        float ox = l + (r - l - drawW) * 0.5f;
        float oy = t + (b - t - drawH) * anchorY;

        c.save();
        kit.clipRound(c, l, t, r, b, corner);
        c.translate(ox, oy);
        c.scale(scale, scale);

        paintRoad(kit, c, frame);

        c.restore();
    }

    /** Draws the scene in its own 120x60 space for custom compositions. */
    public void drawLocal(CanvasKit kit, Canvas c, SceneFrame frame) {
        paintRoad(kit, c, frame);
    }

    private void paintRoad(CanvasKit kit, Canvas c, SceneFrame frame) {
        Paint paint = kit.paint;
        float flow = (frame.timeMs % FLOW_PERIOD_MS) / (float) FLOW_PERIOD_MS;

        // Sky. The paint may still carry the stage's corner-tick alpha, so reset
        // it explicitly: colour alpha also modulates a shader.
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
        paint.setShader(sky);
        c.drawRect(0f, 0f, DESIGN_W, HORIZON_Y, paint);
        paint.setShader(null);
        paint.setAlpha(255);

        // Clouds are drawn behind the ridge, so the mountains crop their bases.
        float drift = 1.2f * (float) Math.sin(frame.timeMs / 9000f);
        cloud(kit, c, 19f + drift, 8.2f, 1f);
        cloud(kit, c, 88f + drift * 0.7f, 6.6f, 0.72f);

        drawMountains(kit, c);
        drawHills(kit, c);

        // Horizon haze.
        paint.setColor(AnimationMath.withAlpha(frame.style.accent, 0x33));
        c.drawRect(0f, HORIZON_Y - 0.5f, DESIGN_W, HORIZON_Y + 0.4f, paint);

        // Ground either side of the road.
        paint.setColor(0xFF1E2A25);
        c.drawRect(0f, HORIZON_Y, DESIGN_W, DESIGN_H, paint);

        // Road, widened so the tarmac uses the full view.
        kit.path.reset();
        kit.path.moveTo(54f, HORIZON_Y);
        kit.path.lineTo(66f, HORIZON_Y);
        kit.path.lineTo(114f, DESIGN_H);
        kit.path.lineTo(6f, DESIGN_H);
        kit.path.close();
        paint.setAlpha(255);
        paint.setShader(asphalt);
        c.drawPath(kit.path, paint);
        paint.setShader(null);
        paint.setAlpha(255);

        // Edge lines.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.5f);
        paint.setColor(0x55FFFFFF);
        c.drawLine(55.8f, HORIZON_Y, 8f, DESIGN_H, paint);
        c.drawLine(64.2f, HORIZON_Y, 112f, DESIGN_H, paint);

        // Lane dashes flowing toward the viewer.
        for (int i = 0; i < 4; i++) {
            float f = (i / 4f + flow) % 1f;
            float y = HORIZON_Y + 40f * f;
            float len = 0.8f + 5.2f * f;
            paint.setStrokeWidth(0.3f + 1.5f * f);
            paint.setColor(AnimationMath.withAlpha(frame.style.accent, (int) (105f + 145f * f)));
            c.drawLine(60f, y, 60f, y + len, paint);
        }

        // Trees on the shoulder: three per side so they emerge at the horizon
        // and grow continuously toward the viewer instead of popping in halfway.
        for (int i = 0; i < 3; i++) {
            float f = (i / 3f + flow) % 1f;
            float y = HORIZON_Y + 40f * f;
            float size = 0.7f + 2.4f * f;
            float spread = 8f + 50f * f;
            // Distant trees sit back in the haze; near ones are fully opaque.
            int alpha = (int) (130f + 90f * f);
            tree(kit, c, 60f - spread, y, size, alpha);
            tree(kit, c, 60f + spread, y, size, alpha);
        }

        // Distant car with red tail lights; sits in the gap between phone and wheel.
        float carY = 31f;
        kit.fillRound(c, 56f, carY, 64f, carY + 4.4f, 1.2f, 0xFF1A1E22);
        kit.fillRound(c, 57f, carY - 2.4f, 63f, carY + 1.4f, 1f, 0xFF22282D);
        kit.dot(c, 57.2f, carY + 2.5f, 0.7f, 0xFFD9564A);
        kit.dot(c, 62.8f, carY + 2.5f, 0.7f, 0xFFD9564A);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x33000000);
        kit.rect.set(56.4f, carY + 4.6f, 63.6f, carY + 5.6f);
        c.drawRect(kit.rect, paint);
    }

    /** A single puffy silhouette, drawn as one closed path. */
    private void cloud(CanvasKit kit, Canvas c, float x, float y, float s) {
        kit.path.reset();
        kit.path.moveTo(x - 7.2f * s, y + 2.4f * s);
        kit.path.cubicTo(x - 9.6f * s, y + 2.1f * s, x - 9.6f * s, y - 1.3f * s,
                x - 6.4f * s, y - 1.7f * s);
        kit.path.cubicTo(x - 6.9f * s, y - 4.9f * s, x - 2.6f * s, y - 5.7f * s,
                x - 1.0f * s, y - 3.3f * s);
        kit.path.cubicTo(x + 0.5f * s, y - 6.1f * s, x + 5.1f * s, y - 4.9f * s,
                x + 4.5f * s, y - 1.9f * s);
        kit.path.cubicTo(x + 7.5f * s, y - 2.1f * s, x + 8.8f * s, y + 1.1f * s,
                x + 6.1f * s, y + 2.4f * s);
        kit.path.cubicTo(x + 3.0f * s, y + 3.2f * s, x - 4.0f * s, y + 3.2f * s,
                x - 7.2f * s, y + 2.4f * s);
        kit.path.close();
        kit.paint.setStyle(Paint.Style.FILL);
        kit.paint.setColor(CLOUD);
        c.drawPath(kit.path, kit.paint);
    }

    /** Far ridge with three peaks and small snow caps. */
    private void drawMountains(CanvasKit kit, Canvas c) {
        kit.paint.setStyle(Paint.Style.FILL);
        kit.paint.setColor(MOUNTAIN);
        kit.path.reset();
        kit.path.moveTo(0f, HORIZON_Y);
        kit.path.lineTo(0f, 17.4f);
        kit.path.lineTo(9f, 14.6f);
        kit.path.lineTo(18f, 16.8f);
        kit.path.lineTo(27f, 9.2f);
        kit.path.lineTo(36f, 15.6f);
        kit.path.lineTo(44f, 13.4f);
        kit.path.lineTo(52f, 16.2f);
        kit.path.lineTo(61f, 7.8f);
        kit.path.lineTo(70f, 14.8f);
        kit.path.lineTo(80f, 12.4f);
        kit.path.lineTo(89f, 16f);
        kit.path.lineTo(97f, 10.2f);
        kit.path.lineTo(106f, 15.4f);
        kit.path.lineTo(114f, 13f);
        kit.path.lineTo(DESIGN_W, 16.4f);
        kit.path.lineTo(DESIGN_W, HORIZON_Y);
        kit.path.close();
        c.drawPath(kit.path, kit.paint);

        snowCap(kit, c, 27f, 9.2f);
        snowCap(kit, c, 61f, 7.8f);
        snowCap(kit, c, 97f, 10.2f);
    }

    private void snowCap(CanvasKit kit, Canvas c, float px, float py) {
        kit.paint.setColor(SNOW);
        kit.path.reset();
        kit.path.moveTo(px, py);
        kit.path.lineTo(px - 2.7f, py + 2.5f);
        kit.path.lineTo(px - 1.2f, py + 1.85f);
        kit.path.lineTo(px, py + 2.35f);
        kit.path.lineTo(px + 1.4f, py + 1.8f);
        kit.path.lineTo(px + 2.7f, py + 2.5f);
        kit.path.close();
        c.drawPath(kit.path, kit.paint);
    }

    /** Near rolling hills, in front of the ridge. */
    private void drawHills(CanvasKit kit, Canvas c) {
        kit.paint.setStyle(Paint.Style.FILL);
        kit.paint.setColor(HILL);
        kit.path.reset();
        kit.path.moveTo(0f, HORIZON_Y);
        kit.path.lineTo(0f, 18.9f);
        kit.path.quadTo(14f, 16.4f, 28f, 18.6f);
        kit.path.quadTo(42f, 20.1f, 56f, 17.6f);
        kit.path.quadTo(70f, 15.6f, 84f, 18.4f);
        kit.path.quadTo(98f, 20.2f, DESIGN_W, 17.4f);
        kit.path.lineTo(DESIGN_W, HORIZON_Y);
        kit.path.close();
        c.drawPath(kit.path, kit.paint);
    }

    private void tree(CanvasKit kit, Canvas c, float x, float groundY,
                      float size, int alpha) {
        Paint paint = kit.paint;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(AnimationMath.withAlpha(0xFF3A2E22, alpha));
        c.drawRect(x - size * 0.16f, groundY - size * 1.8f,
                x + size * 0.16f, groundY, paint);
        paint.setColor(AnimationMath.withAlpha(0xFF3C5A44, alpha));
        c.drawCircle(x, groundY - size * 1.9f, size, paint);
    }
}
