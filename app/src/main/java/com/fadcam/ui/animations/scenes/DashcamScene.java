package com.fadcam.ui.animations.scenes;

import android.graphics.Canvas;
import android.graphics.Paint;

import com.fadcam.ui.animations.AnimationMath;
import com.fadcam.ui.animations.CanvasKit;
import com.fadcam.ui.animations.Scene;
import com.fadcam.ui.animations.SceneFrame;
import com.fadcam.ui.animations.SceneStyle;
import com.fadcam.ui.animations.parts.PhoneShell;
import com.fadcam.ui.animations.parts.RoadView;

/**
 * Dashcam: the road ahead seen through the windshield, a dashboard, a landscape
 * phone on the left and a steering wheel whose centre sits on the dash line.
 * The windshield and the phone both reuse {@link RoadView}.
 */
public final class DashcamScene implements Scene {

    private final RoadView road = new RoadView();

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

        // Windshield first, so the dashboard and phone sit in front of it.
        road.draw(kit, canvas, frame, 6f, 14f, 162f, 72f, false, 0f, 0f);

        canvas.save();
        canvas.translate(84f, 46f);
        canvas.scale(1.25f, 1.25f);
        canvas.translate(-84f, -46f);

        // Dashboard.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF1C2227);
        kit.path.reset();
        kit.path.moveTo(12f, 84f);
        kit.path.lineTo(156f, 84f);
        kit.path.lineTo(150f, 64f);
        kit.path.cubicTo(120f, 58f, 48f, 58f, 18f, 64f);
        kit.path.close();
        canvas.drawPath(kit.path, paint);

        // Leather top edge: the line the steering wheel centre rides on.
        paint.setColor(0xFF323B42);
        kit.path.reset();
        kit.path.moveTo(18f, 64f);
        kit.path.cubicTo(48f, 58f, 120f, 58f, 150f, 64f);
        kit.path.lineTo(150f, 65.5f);
        kit.path.cubicTo(120f, 59.5f, 48f, 59.5f, 18f, 65.5f);
        kit.path.close();
        canvas.drawPath(kit.path, paint);

        // Side vents.
        kit.fillRound(canvas, 18f, 69f, 28f, 74f, 1.2f, 0xFF0B0E10);
        kit.fillRound(canvas, 140f, 69f, 150f, 74f, 1.2f, 0xFF0B0E10);
        paint.setStrokeWidth(0.35f);
        paint.setColor(0x44FFFFFF);
        for (float y = 70.2f; y < 73.5f; y += 1.1f) {
            canvas.drawLine(19f, y, 27f, y, paint);
            canvas.drawLine(141f, y, 149f, y, paint);
        }

        // Centre AC vents.
        kit.fillRound(canvas, 61f, 68f, 72f, 74f, 1.2f, 0xFF0B0E10);
        kit.fillRound(canvas, 80f, 68f, 91f, 74f, 1.2f, 0xFF0B0E10);
        paint.setColor(0x44FFFFFF);
        for (float y = 69.5f; y < 73.5f; y += 1.2f) {
            canvas.drawLine(62f, y, 71f, y, paint);
            canvas.drawLine(81f, y, 90f, y, paint);
        }

        // Larger hazard switch centred between the vents.
        kit.fillRound(canvas, 72.8f, 68.8f, 79.2f, 74f, 1f, 0xFF0B0E10);
        kit.path.reset();
        kit.path.moveTo(76f, 69.6f);
        kit.path.lineTo(73.6f, 73f);
        kit.path.lineTo(78.4f, 73f);
        kit.path.close();
        paint.setColor(0xFFC0392B);
        canvas.drawPath(kit.path, paint);

        // Button row centred under the vents.
        for (int i = 0; i < 3; i++) {
            kit.fillRound(canvas, 68f + i * 6f, 76f, 72f + i * 6f, 78.2f, 0.6f,
                    0xFF2A3238);
        }

        // Landscape phone on the left, so the road and car stay visible.
        PhoneShell.draw(kit, canvas, 24f, 31f, 62f, 53f, 1f, 2f,
                0xFF101214, style.accent, 0.7f, 0xFF0E1317);
        road.draw(kit, canvas, frame, 24f, 31f, 62f, 53f, false, 2f, 0.5f);
        kit.dot(canvas, 26.2f, 33.2f, 0.9f,
                AnimationMath.withAlpha(style.alert,
                        (int) (255f * (0.22f + 0.78f * frame.pulse(3f)))));

        // Phone stand.
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(AnimationMath.withAlpha(style.muted, 180));
        paint.setStrokeWidth(0.9f);
        canvas.drawLine(43f, 53f, 43f, 64f, paint);
        canvas.drawLine(33f, 64f, 53f, 64f, paint);

        // Steering wheel: centre on the leather line, top half over the glass.
        float steer = frame.ambient
                ? (float) Math.sin(frame.phase * Math.PI * 2.0) * 14f : 0f;
        canvas.save();
        canvas.rotate(steer, 112f, 64f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f);
        paint.setColor(style.accentDeep);
        canvas.drawCircle(112f, 64f, 16f, paint);
        paint.setStrokeWidth(1f);
        paint.setColor(style.muted);
        canvas.drawCircle(112f, 64f, 4.5f, paint);
        canvas.drawLine(112f, 64f, 112f, 48.5f, paint);
        canvas.drawLine(112f, 64f, 98f, 74f, paint);
        canvas.drawLine(112f, 64f, 126f, 74f, paint);
        kit.dot(canvas, 112f, 64f, 2f, style.accent);
        canvas.restore();

        canvas.restore();
    }
}
