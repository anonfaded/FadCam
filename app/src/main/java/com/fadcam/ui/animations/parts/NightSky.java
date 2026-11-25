package com.fadcam.ui.animations.parts;

import android.graphics.Canvas;

import com.fadcam.ui.animations.AnimationMath;
import com.fadcam.ui.animations.CanvasKit;
import com.fadcam.ui.animations.SceneFrame;

/**
 * Night sky with twinkling stars and a crescent moon.
 *
 * <p>Used by the lounge window and by the motion scene's opening door. Taking a
 * rectangle means the same component works at any size; star and moon sizes
 * scale from the shorter edge so a tall doorway does not get a giant moon.</p>
 */
public final class NightSky {

    /** Normalised star positions; stable so the sky never flickers randomly. */
    private static final float[] STAR_U = {
            0.18f, 0.32f, 0.72f, 0.82f, 0.44f, 0.62f, 0.26f, 0.9f
    };
    private static final float[] STAR_V = {
            0.16f, 0.42f, 0.19f, 0.55f, 0.74f, 0.82f, 0.88f, 0.34f
    };

    public void draw(CanvasKit kit, Canvas c, SceneFrame frame,
                     float l, float t, float r, float b, float corner) {
        float w = r - l;
        float h = b - t;
        float unit = Math.min(w, h);

        c.save();
        kit.clipRound(c, l, t, r, b, corner);
        kit.fillRound(c, l, t, r, b, corner, 0xFF0C141D);

        for (int i = 0; i < STAR_U.length; i++) {
            float x = l + STAR_U[i] * w;
            float y = t + STAR_V[i] * h;
            float twinkle = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(
                    frame.timeMs / 620f + i * 1.9f));
            kit.dot(c, x, y, unit * 0.05f,
                    AnimationMath.withAlpha(0xFFDCE8F2, (int) (255f * twinkle)));
            if (twinkle > 0.82f) {
                kit.dot(c, x, y, unit * 0.14f, 0x28DCE8F2);
            }
        }

        // Crescent: a pale disc with a sky-coloured disc biting into it.
        float moonR = unit * 0.22f;
        float moonX = l + w * 0.63f;
        float moonY = t + h * 0.30f;
        kit.dot(c, moonX, moonY, moonR, 0xFFE9E5D8);
        kit.dot(c, moonX + moonR * 0.58f, moonY - moonR * 0.36f,
                moonR * 0.98f, 0xFF0C141D);

        c.restore();
    }
}
