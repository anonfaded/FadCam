package com.fadcam.ui.animations.scenes;

import android.graphics.Canvas;
import android.graphics.Paint;

import com.fadcam.ui.animations.AnimationMath;
import com.fadcam.ui.animations.CanvasKit;
import com.fadcam.ui.animations.Scene;
import com.fadcam.ui.animations.SceneFrame;
import com.fadcam.ui.animations.SceneStyle;
import com.fadcam.ui.animations.parts.NightSky;
import com.fadcam.ui.animations.parts.PhoneShell;
import com.fadcam.ui.animations.parts.Walkers;

/**
 * Motion detection: a mounted phone watches a room, a person walks in and out,
 * and a soft beam leaves the lens while motion is being recorded. The CCTV
 * timestamp accumulates only while motion is active, so it freezes on pause.
 */
public final class MotionScene implements Scene {

    private static final long CLOCK_BASE_MS = 80778000L; // 10:26:18.000 PM
    private static final long MAX_TICK_MS = 100L;

    private final NightSky nightSky = new NightSky();
    private final StringBuilder stamp = new StringBuilder(20);
    private long cctvElapsedMs;
    private long lastTickMs;

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
        cctvElapsedMs = 0L;
        lastTickMs = 0L;
    }

    @Override
    public void draw(Canvas canvas, SceneFrame frame, CanvasKit kit) {
        SceneStyle style = frame.style;
        Paint paint = kit.paint;

        kit.fillRound(canvas, 10f, 14f, 158f, 78f, 3f, 0xFF101214);
        kit.frameRound(canvas, 10f, 14f, 158f, 78f, 3f, style.line, 0.8f);

        canvas.save();
        canvas.clipRect(11f, 15f, 157f, 77f);

        // Wall and floor.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.3f);
        paint.setColor(0x12FFFFFF);
        for (float x = 26f; x < 158f; x += 16f) canvas.drawLine(x, 16f, x, 66f, paint);
        canvas.drawLine(10f, 66f, 158f, 66f, paint);

        // Computer desk: monitor, stand, keyboard.
        paint.setColor(AnimationMath.withAlpha(style.accentDeep, 0x66));
        paint.setStrokeWidth(0.8f);
        canvas.drawLine(54f, 54f, 92f, 54f, paint);
        canvas.drawLine(56f, 54f, 56f, 66f, paint);
        canvas.drawLine(90f, 54f, 90f, 66f, paint);
        kit.fillRound(canvas, 62f, 32f, 84f, 48f, 1.2f, 0xFF181B1D);
        kit.frameRound(canvas, 62f, 32f, 84f, 48f, 1.2f,
                AnimationMath.withAlpha(style.accent, 0x99), 0.6f);
        kit.fillRound(canvas, 64f, 34f, 82f, 46f, 0.8f, 0xFF23282C);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0x66C6A66A);
        canvas.drawLine(73f, 48f, 73f, 54f, paint);
        canvas.drawLine(69f, 54f, 77f, 54f, paint);
        kit.fillRound(canvas, 58f, 55f, 86f, 57.5f, 0.7f, 0xFF1B1F22);
        kit.frameRound(canvas, 58f, 55f, 86f, 57.5f, 0.7f, style.line, 0.3f);

        // Doorway: the shared night sky shows through as the leaf swings open.
        nightSky.draw(kit, canvas, frame, 128f, 30f, 150f, 66f, 1f);
        kit.frameRound(canvas, 126f, 28f, 152f, 66f, 1f, style.line, 0.6f);

        // Walk in, pause, then leave through the door.
        float t = frame.ambient ? frame.phase : frame.progress;
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
        float fade = 1f - AnimationMath.seg(t, 0.86f, 0.93f);
        int walkerAlpha = (int) (255f * fade);

        // Mounted phone with a thin rim, matching the other previews.
        kit.fillRound(canvas, 12f, 30f, 17f, 44f, 1f, 0xFF23282C);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        paint.setColor(0xFF3A3F43);
        canvas.drawLine(17f, 37f, 20f, 37f, paint);
        PhoneShell.draw(kit, canvas, 22f, 28f, 32f, 48f, 2f, 2f,
                0xFF202529, AnimationMath.withAlpha(style.accent, 0xAA), 0.7f, 0xFF2B3237);

        // Soft white flash converging exactly on the lens centre (27, 31).
        if (active) {
            fillCone(kit, canvas, 27f, 31f, 27f, 31f, 152f, 66f, 38f, 66f, 10);
            fillCone(kit, canvas, 27f, 31f, 27f, 31f, 146f, 66f, 50f, 66f, 14);
            fillCone(kit, canvas, 27f, 31f, 27f, 31f, 138f, 66f, 64f, 66f, 18);
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(255);
            paint.setShader(FLASH_POOL);
            canvas.save();
            canvas.translate(95f, 66f);
            canvas.scale(2.1f, 0.24f);
            canvas.drawCircle(0f, 0f, 26f, paint);
            canvas.restore();
            paint.setShader(null);
            paint.setAlpha(255);
        }

        // Lens dot on top so the beam visibly starts at its centre.
        if (active) {
            kit.dot(canvas, 27f, 31f, 3.4f, 0x33FFFFFF);
            kit.dot(canvas, 27f, 31f, 1.7f, 0xFFFFFFFF);
        } else {
            kit.dot(canvas, 27f, 31f, 1.2f, 0xFF3A4044);
        }

        if (fade > 0.02f) {
            Walkers.motion(kit, canvas, px, t, active, walkerAlpha);
        }

        if (active && fade > 0.02f) {
            drawDetectionFrame(canvas, kit, px, fade, style);
        }

        // Door leaf on top, swinging toward the hinge.
        float open = AnimationMath.seg(t, 0.72f, 0.84f) * (1f - AnimationMath.seg(t, 0.965f, 1f));
        float leafW = 21f * (1f - 0.82f * open);
        float leafL = 150f - leafW;
        kit.fillRound(canvas, leafL, 30f, 150f, 66f, 0.8f, 0xFF202427);
        kit.frameRound(canvas, leafL, 30f, 150f, 66f, 0.8f,
                AnimationMath.withAlpha(style.accent, 0xCC), 0.6f);
        kit.dot(canvas, leafL + 3f, 49f, 0.8f, style.accent);

        // Human-readable CCTV clock; freezes whenever motion stops.
        updateClock(active, frame.timeMs);
        float stampW = kit.measure(stamp, 5.8f, false);
        kit.fillRound(canvas, 20.5f, 16.2f, 22f + stampW + 1.5f, 22.5f, 1f, 0xA60B0C0D);
        kit.label(canvas, stamp, 22f, 21.5f, 5.8f, style.ink, Paint.Align.LEFT, false);
        drawPlayPauseBadge(kit, canvas, 146f, 22f, active, style);
        kit.dot(canvas, 16f, 19f, 1.4f,
                AnimationMath.withAlpha(style.alert, (int) (55f + 200f * frame.pulse(3f))));
        canvas.restore();
    }

    private void drawDetectionFrame(Canvas c, CanvasKit kit, float px, float fade,
                                    SceneStyle style) {
        float l = px - 7f;
        float r = px + 7f;
        float top = 33f;
        float bottom = 65f;
        Paint paint = kit.paint;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(AnimationMath.withAlpha(style.accent, (int) (18f * fade)));
        kit.rect.set(l, top, r, bottom);
        c.drawRect(kit.rect, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.8f);
        paint.setColor(AnimationMath.withAlpha(style.accent, (int) (225f * fade)));
        kit.rect.set(l, top, r, bottom);
        c.drawRect(kit.rect, paint);
        paint.setStrokeWidth(1.4f);
        c.drawLine(l, top, l + 4f, top, paint);
        c.drawLine(l, top, l, top + 4f, paint);
        c.drawLine(r, top, r - 4f, top, paint);
        c.drawLine(r, top, r, top + 4f, paint);
        c.drawLine(l, bottom, l + 4f, bottom, paint);
        c.drawLine(l, bottom, l, bottom - 4f, paint);
        c.drawLine(r, bottom, r - 4f, bottom, paint);
        c.drawLine(r, bottom, r, bottom - 4f, paint);
    }

    private static final android.graphics.Shader FLASH_POOL =
            new android.graphics.RadialGradient(0f, 0f, 26f,
                    new int[]{0x3DFFFFFF, 0x00FFFFFF}, null,
                    android.graphics.Shader.TileMode.CLAMP);

    private void fillCone(CanvasKit kit, Canvas c,
                          float x1, float y1, float x2, float y2,
                          float x3, float y3, float x4, float y4, int alpha) {
        kit.path.reset();
        kit.path.moveTo(x1, y1);
        kit.path.lineTo(x3, y3);
        kit.path.lineTo(x4, y4);
        kit.path.lineTo(x2, y2);
        kit.path.close();
        kit.paint.setStyle(Paint.Style.FILL);
        kit.paint.setColor(alpha << 24 | 0x00FFFFFF);
        c.drawPath(kit.path, kit.paint);
    }

    /** Gold play while recording motion, muted pause between events. */
    private void drawPlayPauseBadge(CanvasKit kit, Canvas c, float x, float y,
                                    boolean playing, SceneStyle style) {
        int accent = playing ? style.accent : style.muted;
        kit.fillRound(c, x - 6f, y - 6f, x + 6f, y + 6f, 6f, 0xE60B0C0D);
        kit.frameRound(c, x - 6f, y - 6f, x + 6f, y + 6f, 6f, accent, 0.8f);
        kit.paint.setStyle(Paint.Style.FILL);
        kit.paint.setColor(accent);
        if (playing) {
            kit.path.reset();
            kit.path.moveTo(x - 1.6f, y - 3f);
            kit.path.lineTo(x + 3.4f, y);
            kit.path.lineTo(x - 1.6f, y + 3f);
            kit.path.close();
            c.drawPath(kit.path, kit.paint);
        } else {
            c.drawRect(x - 3f, y - 3f, x - 0.9f, y + 3f, kit.paint);
            c.drawRect(x + 0.9f, y - 3f, x + 3f, y + 3f, kit.paint);
        }
    }

    /** Accumulate CCTV time only while motion is being recorded. */
    private void updateClock(boolean active, long nowMs) {
        if (lastTickMs == 0L) lastTickMs = nowMs;
        if (active) {
            long delta = Math.min(nowMs - lastTickMs, MAX_TICK_MS);
            if (delta > 0L) cctvElapsedMs += delta;
        }
        lastTickMs = nowMs;
        formatStamp(CLOCK_BASE_MS + cctvElapsedMs);
    }

    private void formatStamp(long totalMs) {
        int ms = (int) (totalMs % 1000L);
        long secs = totalMs / 1000L;
        int s = (int) (secs % 60L);
        int m = (int) ((secs / 60L) % 60L);
        int h24 = (int) ((secs / 3600L) % 24L);
        int h = h24 % 12;
        if (h == 0) h = 12;
        stamp.setLength(0);
        appendTwo(stamp, h);
        stamp.append(':');
        appendTwo(stamp, m);
        stamp.append(':');
        appendTwo(stamp, s);
        stamp.append('.');
        if (ms < 100) stamp.append('0');
        if (ms < 10) stamp.append('0');
        stamp.append(ms);
        stamp.append(h24 < 12 ? " AM" : " PM");
    }

    private static void appendTwo(StringBuilder out, int value) {
        if (value < 10) out.append('0');
        out.append(value);
    }
}
