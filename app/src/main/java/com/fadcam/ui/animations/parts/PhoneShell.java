package com.fadcam.ui.animations.parts;

import android.graphics.Canvas;

import com.fadcam.ui.animations.AnimationMath;
import com.fadcam.ui.animations.CanvasKit;

/**
 * The shared phone body used by every preview.
 *
 * <p>Scenes pass the screen rectangle; the shell grows a thin bezel around it
 * and paints body, accent edge and screen. One definition keeps the bezel
 * weight identical across background, motion, bodycam, remote and dashcam.</p>
 */
public final class PhoneShell {

    private PhoneShell() {
    }

    /**
     * @param screen screen rectangle in the scene's design space
     * @param bezel  rim thickness in design units; 1.2–2.0 reads as a modern phone
     * @param corner screen corner radius; the body uses a slightly larger one
     */
    public static void draw(CanvasKit kit, Canvas c,
                            float sl, float st, float sr, float sb,
                            float bezel, float corner,
                            int bodyColor, int edgeColor, float edgeWidth,
                            int screenColor) {
        float outerCorner = corner + bezel * 0.8f;
        kit.fillRound(c, sl - bezel, st - bezel, sr + bezel, sb + bezel,
                outerCorner, bodyColor);
        kit.frameRound(c, sl - bezel, st - bezel, sr + bezel, sb + bezel,
                outerCorner, edgeColor, edgeWidth);
        kit.fillRound(c, sl, st, sr, sb, corner, screenColor);
    }
}
