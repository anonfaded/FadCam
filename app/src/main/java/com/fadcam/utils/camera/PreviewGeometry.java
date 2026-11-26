package com.fadcam.utils.camera;

import android.content.Context;
import android.util.Size;

import com.fadcam.SharedPreferencesManager;

/**
 * Maps a touch point on the preview view to normalized coordinates of the camera
 * image actually drawn inside it.
 *
 * <p>{@link com.fadcam.opengl.GLWatermarkRenderer} renders the camera frame into a
 * centered viewport that preserves the content aspect ratio (letterbox/pillarbox),
 * and fills the leftover bars with a dimmed zoomed copy. Touch coordinates are
 * relative to the whole view, so normalizing them over the view size would be wrong
 * whenever the view aspect differs from the video aspect — the AF region would land
 * off the point the user tapped.
 */
public final class PreviewGeometry {

    /** Normalized tap inside the content viewport, or null when the tap is on a bar. */
    public static final class Tap {
        public final float nx;
        public final float ny;

        Tap(float nx, float ny) {
            this.nx = nx;
            this.ny = ny;
        }
    }

    private PreviewGeometry() {
    }

    /**
     * @return normalized content coordinates, or {@code null} if the tap landed in the
     *         letterbox/pillarbox area outside the sharp camera image.
     */
    public static Tap mapTap(Context context, float x, float y, int viewWidth, int viewHeight) {
        if (viewWidth <= 0 || viewHeight <= 0) {
            return null;
        }
        float contentAR = contentAspectRatio(context);
        if (contentAR <= 0f) {
            return new Tap(clamp01(x / viewWidth), clamp01(y / viewHeight));
        }

        int vpX = 0;
        int vpY = 0;
        int vpW = viewWidth;
        int vpH = viewHeight;
        float surfaceAR = viewWidth / (float) viewHeight;
        if (surfaceAR > contentAR) {
            vpH = viewHeight;
            vpW = Math.round(viewHeight * contentAR);
            vpX = (viewWidth - vpW) / 2;
        } else if (surfaceAR < contentAR) {
            vpW = viewWidth;
            vpH = Math.round(viewWidth / contentAR);
            vpY = (viewHeight - vpH) / 2;
        }

        float cx = x - vpX;
        float cy = y - vpY;
        if (cx < 0f || cy < 0f || cx > vpW || cy > vpH) {
            return null;
        }
        return new Tap(clamp01(cx / vpW), clamp01(cy / vpH));
    }

    /**
     * Aspect ratio of the frame the GL renderer draws: the encoder output after the
     * app's orientation swap, matching {@code initializeEncoder()}.
     */
    private static float contentAspectRatio(Context context) {
        try {
            SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(context);
            Size res = prefs.getCameraResolution();
            if (res.getWidth() <= 0 || res.getHeight() <= 0) {
                return 0f;
            }
            boolean appWantsPortrait = !SharedPreferencesManager.isLandscapeOrientation(
                    prefs.getVideoOrientation());
            boolean isSensorPortrait = res.getHeight() > res.getWidth();
            boolean needsSwap = appWantsPortrait != isSensorPortrait;
            int w = needsSwap ? res.getHeight() : res.getWidth();
            int h = needsSwap ? res.getWidth() : res.getHeight();
            return w / (float) h;
        } catch (Exception e) {
            return 0f;
        }
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
