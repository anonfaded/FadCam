package com.fadcam.ui.animations.scenes;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;

import com.fadcam.R;
import com.fadcam.ui.animations.AnimationMath;
import com.fadcam.ui.animations.CanvasKit;
import com.fadcam.ui.animations.Scene;
import com.fadcam.ui.animations.SceneFrame;
import com.fadcam.ui.animations.SceneStyle;

/**
 * Mini QR scanner: a deterministic (non-sensitive) code, a sweeping scan line
 * and a result card with the decoded value and a saved check.
 */
public final class QrScene implements Scene {

    private final boolean[][] qr = new boolean[21][21];
    private final String value;

    public QrScene(Context context) {
        value = context.getString(R.string.onboarding_demo_qr_value);
        buildMatrix();
    }

    @Override
    public float designWidth() {
        return 120f;
    }

    @Override
    public float designHeight() {
        return 64f;
    }

    private void buildMatrix() {
        // Finder squares plus a stable fill; never a real, scannable payload.
        for (int x = 0; x < 21; x++) {
            for (int y = 0; y < 21; y++) {
                boolean finder = (x < 7 && y < 7) || (x > 13 && y < 7) || (x < 7 && y > 13);
                if (finder) {
                    qr[x][y] = false;
                } else {
                    int v = (x * 31 + y * 17 + x * y * 7) % 11;
                    qr[x][y] = v < 5;
                }
            }
        }
    }

    @Override
    public void draw(Canvas canvas, SceneFrame frame, CanvasKit kit) {
        SceneStyle style = frame.style;
        Paint paint = kit.paint;

        float left = 14f;
        float top = 8f;
        float size = 46f;
        float cell = size / 21f;

        kit.fillRound(canvas, left - 2f, top - 2f, left + size + 2f, top + size + 2f,
                2f, 0xFF0C0E0F);

        finder(canvas, kit, left, top, cell);
        finder(canvas, kit, left + 14f * cell, top, cell);
        finder(canvas, kit, left, top + 14f * cell, cell);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFE7E4D9);
        for (int x = 0; x < 21; x++) {
            for (int y = 0; y < 21; y++) {
                if (!qr[x][y]) continue;
                float mx = left + x * cell;
                float my = top + y * cell;
                canvas.drawRect(mx, my, mx + cell - 0.25f, my + cell - 0.25f, paint);
            }
        }

        float sweep = frame.ambient ? frame.phase : AnimationMath.seg(frame.progress, 0.12f, 0.6f);
        if (sweep > 0f && sweep < 1f) {
            float y = top + size * sweep;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(AnimationMath.withAlpha(style.accent, 0x22));
            canvas.drawRect(left, y - 3f, left + size, y, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(0.9f);
            paint.setColor(style.accent);
            canvas.drawLine(left, y, left + size, y, paint);
        }

        // Result card: the scanned value, then a saved check.
        float saved = AnimationMath.seg(frame.progress, 0.72f, 0.92f);
        if (saved > 0f) {
            int alpha = (int) (255f * saved);
            kit.fillRound(canvas, 64f, 16f, 116f, 44f, 2f,
                    AnimationMath.withAlpha(0xFF121416, alpha));
            kit.frameRound(canvas, 64f, 16f, 116f, 44f, 2f,
                    AnimationMath.withAlpha(style.accent, alpha), 0.8f);
            kit.label(canvas, value, 67f, 29f, 5.4f,
                    AnimationMath.withAlpha(style.ink, alpha), Paint.Align.LEFT, false);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.2f);
            paint.setColor(AnimationMath.withAlpha(style.accent, alpha));
            canvas.drawLine(106f, 36f, 109f, 39f, paint);
            canvas.drawLine(109f, 39f, 113f, 33f, paint);
        }
    }

    private void finder(Canvas c, CanvasKit kit, float x, float y, float cell) {
        kit.paint.setStyle(Paint.Style.FILL);
        kit.paint.setColor(0xFFE7E4D9);
        c.drawRect(x, y, x + 7f * cell, y + 7f * cell, kit.paint);
        kit.paint.setColor(0xFF0C0E0F);
        c.drawRect(x + cell, y + cell, x + 6f * cell, y + 6f * cell, kit.paint);
        kit.paint.setColor(0xFFE7E4D9);
        c.drawRect(x + 2f * cell, y + 2f * cell, x + 5f * cell, y + 5f * cell, kit.paint);
    }
}
