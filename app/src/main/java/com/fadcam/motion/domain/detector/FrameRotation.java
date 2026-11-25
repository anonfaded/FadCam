package com.fadcam.motion.domain.detector;

/**
 * Rotation between the camera sensor frame and the upright frame.
 *
 * <p>The sensor delivers a landscape buffer; the video and the preview are rotated
 * to the app orientation. The detector is trained on upright images, so it is fed
 * the rotated frame, and its boxes come back in upright space — the same space the
 * overlay draws in.
 *
 * <p>This class is the single definition of that transform. It is pure Java with no
 * Android or model dependency so both directions can be unit-tested; the detector
 * and the overlay must never carry their own copies of this maths, because two
 * copies that drift apart produce boxes that sit away from their object.
 *
 * <p>An image rotated clockwise by {@code rotation} degrees maps a source pixel
 * {@code (sx, sy)} in {@code [0,1]} to the upright pixel:
 *
 * <pre>
 *   90 : (1 - sy, sx)
 *  180 : (1 - sx, 1 - sy)
 *  270 : (sy, 1 - sx)
 *    0 : (sx, sy)
 * </pre>
 */
public final class FrameRotation {

    private FrameRotation() {
    }

    /** Normalises any angle into one of 0, 90, 180, 270. */
    public static int normalize(int degrees) {
        int d = ((degrees % 360) + 360) % 360;
        // Only right angles are meaningful for a camera buffer.
        if (d < 45 || d >= 315) {
            return 0;
        }
        if (d < 135) {
            return 90;
        }
        if (d < 225) {
            return 180;
        }
        return 270;
    }

    /** True when a rotation swaps the frame's width and height. */
    public static boolean swapsAxes(int rotation) {
        int r = normalize(rotation);
        return r == 90 || r == 270;
    }

    /**
     * Maps a normalised point from the sensor frame into the upright frame.
     *
     * @return {@code {x, y}} in the upright frame
     */
    public static float[] sensorToUpright(float sx, float sy, int rotation) {
        switch (normalize(rotation)) {
            case 90:
                return new float[]{1f - sy, sx};
            case 180:
                return new float[]{1f - sx, 1f - sy};
            case 270:
                return new float[]{sy, 1f - sx};
            default:
                return new float[]{sx, sy};
        }
    }

    /**
     * Maps a normalised point from the upright frame back to the sensor frame.
     * Exact inverse of {@link #sensorToUpright}.
     */
    public static float[] uprightToSensor(float ux, float uy, int rotation) {
        switch (normalize(rotation)) {
            case 90:
                return new float[]{uy, 1f - ux};
            case 180:
                return new float[]{1f - ux, 1f - uy};
            case 270:
                return new float[]{1f - uy, ux};
            default:
                return new float[]{ux, uy};
        }
    }

    /**
     * Maps a normalised centre+size box from sensor space into upright space.
     * Width and height swap for 90 and 270.
     *
     * @return {@code {cx, cy, w, h}}
     */
    public static float[] boxSensorToUpright(float cx, float cy, float w, float h, int rotation) {
        float[] p = sensorToUpright(cx, cy, rotation);
        boolean swap = swapsAxes(rotation);
        return new float[]{p[0], p[1], swap ? h : w, swap ? w : h};
    }

    /**
     * Maps a normalised centre+size box from upright space back into sensor space.
     * Exact inverse of {@link #boxSensorToUpright}.
     */
    public static float[] boxUprightToSensor(float cx, float cy, float w, float h, int rotation) {
        float[] p = uprightToSensor(cx, cy, rotation);
        boolean swap = swapsAxes(rotation);
        return new float[]{p[0], p[1], swap ? h : w, swap ? w : h};
    }

    /**
     * Source sample coordinate, in {@code [0, size)} of the sensor frame, for a
     * destination pixel of an {@code size × size} upright sample.
     *
     * <p>Used to sample the frame through the rotation in one pass instead of
     * rotating a full-resolution buffer first.
     *
     * @param dx   destination column, {@code 0..size-1}
     * @param dy   destination row, {@code 0..size-1}
     * @param size destination edge length
     * @return {@code {x, y}} in destination units, to be scaled onto the source
     */
    public static int[] uprightPixelToSensorPixel(int dx, int dy, int size, int rotation) {
        int last = size - 1;
        switch (normalize(rotation)) {
            case 90:
                return new int[]{dy, last - dx};
            case 180:
                return new int[]{last - dx, last - dy};
            case 270:
                return new int[]{last - dy, dx};
            default:
                return new int[]{dx, dy};
        }
    }
}
