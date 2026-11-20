package com.fadcam.motion;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.fadcam.motion.domain.detector.FrameRotation;

import org.junit.Test;

/**
 * The detection overlay depends entirely on one property: rotating a box out of the
 * sensor frame and back must return the original box, and the two directions must
 * agree on which corner ends up where.
 *
 * <p>These tests exist because a mismatch here is invisible in code review and very
 * visible on screen: boxes drawn away from the object they describe.
 */
public class FrameRotationTest {

    private static final float EPS = 1e-5f;

    private static void assertPoint(float[] expected, float[] actual) {
        assertEquals("x", expected[0], actual[0], EPS);
        assertEquals("y", expected[1], actual[1], EPS);
    }

    @Test
    public void zeroRotationIsIdentity() {
        assertPoint(new float[]{0.25f, 0.75f}, FrameRotation.sensorToUpright(0.25f, 0.75f, 0));
    }

    @Test
    public void normalizationSnapsToRightAngles() {
        assertEquals(0, FrameRotation.normalize(0));
        assertEquals(90, FrameRotation.normalize(90));
        assertEquals(180, FrameRotation.normalize(180));
        assertEquals(270, FrameRotation.normalize(270));
        assertEquals(90, FrameRotation.normalize(450));
        assertEquals(270, FrameRotation.normalize(-90));
        assertEquals(0, FrameRotation.normalize(360));
        // Near-right-angle values snap rather than producing a non-right angle.
        assertEquals(0, FrameRotation.normalize(5));
        assertEquals(90, FrameRotation.normalize(88));
        assertEquals(180, FrameRotation.normalize(182));
        assertEquals(270, FrameRotation.normalize(271));
    }

    @Test
    public void sensorTopLeftMovesToExpectedUprightCorner() {
        // Rotating clockwise by 90 sends the sensor's top-left corner to top-right.
        assertPoint(new float[]{1f, 0f}, FrameRotation.sensorToUpright(0f, 0f, 90));
        // By 180 it goes to bottom-right.
        assertPoint(new float[]{1f, 1f}, FrameRotation.sensorToUpright(0f, 0f, 180));
        // By 270 it goes to bottom-left.
        assertPoint(new float[]{0f, 1f}, FrameRotation.sensorToUpright(0f, 0f, 270));
    }

    @Test
    public void pointRoundTripsForEveryRotation() {
        float[][] points = {
                {0f, 0f}, {1f, 0f}, {0f, 1f}, {1f, 1f},
                {0.5f, 0.5f}, {0.13f, 0.87f}, {0.72f, 0.28f}, {0.99f, 0.01f}
        };
        for (int rotation : new int[]{0, 90, 180, 270}) {
            for (float[] p : points) {
                float[] upright = FrameRotation.sensorToUpright(p[0], p[1], rotation);
                float[] back = FrameRotation.uprightToSensor(upright[0], upright[1], rotation);
                assertEquals("rotation " + rotation + " x", p[0], back[0], EPS);
                assertEquals("rotation " + rotation + " y", p[1], back[1], EPS);
            }
        }
    }

    @Test
    public void boxSwapsWidthAndHeightOnlyForQuarterTurns() {
        float[] none = FrameRotation.boxSensorToUpright(0.5f, 0.5f, 0.4f, 0.2f, 0);
        assertEquals(0.4f, none[2], EPS);
        assertEquals(0.2f, none[3], EPS);

        float[] quarter = FrameRotation.boxSensorToUpright(0.5f, 0.5f, 0.4f, 0.2f, 90);
        assertEquals(0.2f, quarter[2], EPS);
        assertEquals(0.4f, quarter[3], EPS);

        float[] half = FrameRotation.boxSensorToUpright(0.5f, 0.5f, 0.4f, 0.2f, 180);
        assertEquals(0.4f, half[2], EPS);
        assertEquals(0.2f, half[3], EPS);
    }

    @Test
    public void boxRoundTripsForEveryRotation() {
        float cx = 0.31f;
        float cy = 0.62f;
        float w = 0.18f;
        float h = 0.44f;
        for (int rotation : new int[]{0, 90, 180, 270}) {
            float[] upright = FrameRotation.boxSensorToUpright(cx, cy, w, h, rotation);
            float[] back = FrameRotation.boxUprightToSensor(
                    upright[0], upright[1], upright[2], upright[3], rotation);
            assertEquals("rotation " + rotation + " cx", cx, back[0], EPS);
            assertEquals("rotation " + rotation + " cy", cy, back[1], EPS);
            assertEquals("rotation " + rotation + " w", w, back[2], EPS);
            assertEquals("rotation " + rotation + " h", h, back[3], EPS);
        }
    }

    /**
     * The sampling grid and the box maths must describe the SAME rotation. This
     * compares a corner mapped as a point against the same corner located through
     * the pixel sampler.
     */
    @Test
    public void pixelSamplerAgreesWithBoxMaths() {
        final int size = 100; // last index 99
        for (int rotation : new int[]{0, 90, 180, 270}) {
            for (float[] corner : new float[][]{
                    {0f, 0f}, {1f, 0f}, {0f, 1f}, {1f, 1f}}) {
                int dx = Math.round(corner[0] * (size - 1));
                int dy = Math.round(corner[1] * (size - 1));
                int[] src = FrameRotation.uprightPixelToSensorPixel(dx, dy, size, rotation);
                float[] expected = FrameRotation.uprightToSensor(corner[0], corner[1], rotation);
                assertEquals("rotation " + rotation + " corner x",
                        expected[0] * (size - 1), src[0], 1.0f);
                assertEquals("rotation " + rotation + " corner y",
                        expected[1] * (size - 1), src[1], 1.0f);
            }
        }
    }

    @Test
    public void swapsAxesMatchesTheQuarterTurns() {
        assertTrue(FrameRotation.swapsAxes(90));
        assertTrue(FrameRotation.swapsAxes(270));
        assertEquals(false, FrameRotation.swapsAxes(0));
        assertEquals(false, FrameRotation.swapsAxes(180));
    }

    @Test
    public void centreStaysCentredForEveryRotation() {
        for (int rotation : new int[]{0, 90, 180, 270}) {
            float[] c = FrameRotation.sensorToUpright(0.5f, 0.5f, rotation);
            assertArrayEquals(new float[]{0.5f, 0.5f}, c, EPS);
        }
    }
}
