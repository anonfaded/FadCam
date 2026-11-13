package com.fadcam.ui.animations.scenes;

import android.graphics.Canvas;
import android.graphics.Paint;

import com.fadcam.ui.animations.AnimationMath;
import com.fadcam.ui.animations.CanvasKit;
import com.fadcam.ui.animations.Scene;
import com.fadcam.ui.animations.SceneFrame;
import com.fadcam.ui.animations.SceneStyle;

/**
 * Faditor mini: preview and timeline on the left, tool column on the right.
 * The tool order mirrors the real editor: trim, split, crop, duplicate, mute.
 */
public final class EditorScene implements Scene {

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

        // Preview.
        kit.fillRound(canvas, 10f, 8f, 112f, 42f, 3f, 0xFF101214);
        kit.frameRound(canvas, 10f, 8f, 112f, 42f, 3f, style.line, 0.8f);
        kit.fillRound(canvas, 14f, 12f, 108f, 38f, 2f, 0xFF1B1F22);
        kit.path.reset();
        kit.path.moveTo(56f, 19f);
        kit.path.lineTo(68f, 26f);
        kit.path.lineTo(56f, 33f);
        kit.path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(AnimationMath.withAlpha(style.accent, 0xCC));
        canvas.drawPath(kit.path, paint);

        float playhead = frame.ambient
                ? 0.5f + 0.5f * (float) Math.sin(frame.phase * Math.PI * 2.0)
                : AnimationMath.seg(frame.progress, 0.08f, 0.9f);
        kit.fillRound(canvas, 14f, 39.5f, 108f, 41f, 0.5f, 0xFF2A2C2E);
        kit.fillRound(canvas, 14f, 39.5f, 14f + 94f * playhead, 41f, 0.5f, style.accent);

        // Timeline.
        kit.fillRound(canvas, 10f, 46f, 112f, 78f, 3f, 0xFF101214);
        kit.frameRound(canvas, 10f, 46f, 112f, 78f, 3f, style.line, 0.8f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.4f);
        paint.setColor(0x22FFFFFF);
        for (float x = 14f; x < 108f; x += 8f) canvas.drawLine(x, 49f, x, 53f, paint);

        clipStrip(kit, canvas, 14f, 46f, 0xFF1A1D1F, style.line, false, style);
        clipStrip(kit, canvas, 48f, 70f, 0xFF1A1D1F, style.line, false, style);
        clipStrip(kit, canvas, 72f, 106f, 0xFF2A2415, style.accent, true, style);

        // Trim handles on the selected clip.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(style.accent);
        canvas.drawLine(72f, 53f, 72f, 73f, paint);
        canvas.drawLine(106f, 53f, 106f, 73f, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);

        // Playhead.
        float head = 76f + 28f * playhead;
        paint.setColor(0xFFEFECE4);
        paint.setStrokeWidth(0.8f);
        canvas.drawLine(head, 51f, head, 76f, paint);
        kit.path.reset();
        kit.path.moveTo(head - 2.4f, 49f);
        kit.path.lineTo(head + 2.4f, 49f);
        kit.path.lineTo(head, 53f);
        kit.path.close();
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(kit.path, paint);

        // Tool column.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(0.4f);
        paint.setColor(0x22FFFFFF);
        canvas.drawLine(115f, 8f, 115f, 84f, paint);
        toolTile(kit, canvas, frame, 118f, 8f, true, "content_cut");
        toolTile(kit, canvas, frame, 118f, 23f, false, "carpenter");
        toolTile(kit, canvas, frame, 118f, 38f, false, "crop");
        toolTile(kit, canvas, frame, 118f, 53f, false, "content_copy");
        toolTile(kit, canvas, frame, 118f, 68f, false, "volume_off");
    }

    private void toolTile(CanvasKit kit, Canvas c, SceneFrame frame,
                          float x, float y, boolean active, String glyph) {
        SceneStyle style = frame.style;
        kit.fillRound(c, x, y, x + 38f, y + 13f, 2.5f,
                active ? AnimationMath.withAlpha(style.accent, 0x1F) : 0xFF121416);
        kit.frameRound(c, x, y, x + 38f, y + 13f, 2.5f,
                active ? style.accent : style.line, active ? 0.9f : 0.5f);
        kit.icon(c, glyph, x + 19f, y + 6.5f, 10f,
                active ? style.accent : style.muted);
    }

    private void clipStrip(CanvasKit kit, Canvas c, float l, float r,
                           int fill, int border, boolean selected, SceneStyle style) {
        kit.fillRound(c, l, 56f, r, 72f, 1.5f, fill);
        kit.frameRound(c, l, 56f, r, 72f, 1.5f, border, selected ? 1f : 0.6f);
        if (selected) {
            kit.paint.setStyle(Paint.Style.STROKE);
            kit.paint.setStrokeWidth(0.35f);
            kit.paint.setColor(AnimationMath.withAlpha(style.accent, 0x44));
            for (float x = l + 8f; x < r - 3f; x += 7f) {
                c.drawLine(x, 59f, x, 69f, kit.paint);
            }
        }
    }
}
