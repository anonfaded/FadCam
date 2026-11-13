package com.fadcam.ui.animations;

import android.graphics.Canvas;

/**
 * A self-contained Canvas animation drawn in its own design space.
 *
 * <p>A scene never looks at the view size. The host scales the design space to
 * whatever bounds it has, which is what makes the same component reusable in a
 * card, a dialog, a full-screen page or a video thumbnail.</p>
 */
public interface Scene {

    /** Width of the local coordinate space. */
    float designWidth();

    /** Height of the local coordinate space. */
    float designHeight();

    /**
     * Draws one frame. The canvas is already scaled into the design space.
     * Implementations must not allocate per-frame objects.
     */
    void draw(Canvas canvas, SceneFrame frame, CanvasKit kit);

    /**
     * Clears any state that must restart with a new reveal (timers, counters).
     * Called by the host from {@code reset()}.
     */
    default void reset() {
    }
}
