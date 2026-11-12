package com.fadcam.ui.animations.scenes;

import android.graphics.Canvas;
import android.graphics.Paint;

import com.fadcam.ui.animations.AnimationMath;
import com.fadcam.ui.animations.CanvasKit;
import com.fadcam.ui.animations.Scene;
import com.fadcam.ui.animations.SceneFrame;
import com.fadcam.ui.animations.SceneStyle;
import com.fadcam.ui.animations.parts.LoungeRoom;
import com.fadcam.ui.animations.parts.PhoneShell;

/**
 * Remote CCTV: a phone streaming the lounge, a signal link and a dashboard with
 * torch, D-pad and record controls. The phone and dashboard share the exact
 * same {@link LoungeRoom} scene.
 */
public final class RemoteScene implements Scene {

    private final LoungeRoom lounge = new LoungeRoom();

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

        // Phone front, portrait crop of the shared room.
        PhoneShell.draw(kit, canvas, 12f, 22f, 46f, 70f, 1.2f, 3f,
                0xFF121416, style.line, 0.6f, 0xFF0A0C0D);
        lounge.draw(kit, canvas, frame, 12f, 22f, 46f, 70f, false, 3f);
        kit.frameRound(canvas, 12f, 22f, 46f, 70f, 3f,
                AnimationMath.withAlpha(style.accentDeep, 50), 0.5f);
        kit.dot(canvas, 29f, 24.5f, 1.3f, 0xFF050708);
        kit.frameRound(canvas, 27.7f, 23.2f, 30.3f, 25.8f, 1.2f,
                AnimationMath.withAlpha(style.accentDeep, 102), 0.4f);
        kit.dot(canvas, 29f, 24.5f, 0.5f, 0xFF3A4044);

        // Signal arcs and a travelling dot.
        float signal = AnimationMath.seg(frame.progress, 0.2f, 0.55f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.8f);
        paint.setColor(AnimationMath.withAlpha(style.accent, (int) (200f * signal)));
        for (int i = 1; i <= 3; i++) {
            float r = 4f + i * 4f;
            kit.rect.set(56f - r, 46f - r, 56f + r, 46f + r);
            canvas.drawArc(kit.rect, -52f, 104f, false, paint);
        }
        paint.setPathEffect(DASH);
        paint.setColor(AnimationMath.withAlpha(style.accentDeep, (int) (120f * signal)));
        canvas.drawLine(54f, 46f, 80f, 46f, paint);
        paint.setPathEffect(null);

        float travel = frame.ambient ? frame.phase : AnimationMath.seg(frame.progress, 0.4f, 0.8f);
        int travelAlpha = (int) (220f * signal);
        if (frame.ambient) {
            float edge = Math.min(frame.phase, 1f - frame.phase);
            travelAlpha = (int) (220f * Math.min(1f, edge * 8f));
        }
        kit.dot(canvas, 54f + 26f * travel, 46f, 1.4f,
                AnimationMath.withAlpha(style.accent, travelAlpha));

        // Dashboard shell.
        kit.fillRound(canvas, 82f, 8f, 158f, 74f, 4f, 0xFF121416);
        kit.frameRound(canvas, 82f, 8f, 158f, 74f, 4f, style.line, 0.8f);
        kit.dot(canvas, 87f, 13f, 1f, style.dim);
        kit.dot(canvas, 91f, 13f, 1f, style.dim);
        kit.dot(canvas, 95f, 13f, 1f, style.dim);
        paint.setColor(style.line);
        paint.setStrokeWidth(0.4f);
        canvas.drawLine(100f, 13f, 152f, 13f, paint);

        // Upper grid: the shared CCTV scene.
        lounge.draw(kit, canvas, frame, 85f, 17f, 155f, 47f, true, 2f);
        kit.frameRound(canvas, 85f, 17f, 155f, 47f, 2f, style.line, 0.5f);

        // Lower grid: torch, D-pad, record.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.4f);
        paint.setColor(0x22FFFFFF);
        canvas.drawLine(85f, 50f, 155f, 50f, paint);

        paint.setColor(AnimationMath.withAlpha(style.accentDeep, 0x99));
        paint.setStrokeWidth(0.7f);
        canvas.drawCircle(97f, 61f, 5f, paint);
        kit.icon(canvas, "flashlight_on", 97f, 61f, 7.5f, style.accent);

        paint.setColor(AnimationMath.withAlpha(style.accentDeep, 0x66));
        canvas.drawCircle(120f, 61f, 7.5f, paint);
        kit.dot(canvas, 120f, 61f, 2f, style.accent);
        drawArrow(kit, canvas, 120f, 52.5f, 118.4f, 55.2f, 121.6f, 55.2f, style.accent);
        drawArrow(kit, canvas, 120f, 69.5f, 118.4f, 66.8f, 121.6f, 66.8f, style.accent);
        drawArrow(kit, canvas, 111.5f, 61f, 114.2f, 59.4f, 114.2f, 62.6f, style.accent);
        drawArrow(kit, canvas, 128.5f, 61f, 125.8f, 59.4f, 125.8f, 62.6f, style.accent);

        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0xCCE8E4D8);
        paint.setStrokeWidth(0.7f);
        canvas.drawCircle(143f, 61f, 5f, paint);
        kit.dot(canvas, 143f, 61f, 2f, style.alert);
    }

    private void drawArrow(CanvasKit kit, Canvas c, float tipX, float tipY,
                           float x1, float y1, float x2, float y2, int color) {
        kit.path.reset();
        kit.path.moveTo(tipX, tipY);
        kit.path.lineTo(x1, y1);
        kit.path.lineTo(x2, y2);
        kit.path.close();
        kit.paint.setStyle(Paint.Style.FILL);
        kit.paint.setColor(color);
        c.drawPath(kit.path, kit.paint);
    }

    private static final android.graphics.DashPathEffect DASH =
            new android.graphics.DashPathEffect(new float[]{2f, 2f}, 0f);
}
