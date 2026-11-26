package com.fadcam.watermark;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.Surface;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The inverted orientations are a static install-time choice, like the "Rotate video 180°"
 * option every dashcam has. No sensor is involved, so the behaviour must be completely
 * predictable: selecting it rotates, and that is all.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class DevicePostureTest {

    private static final String PORTRAIT = com.fadcam.SharedPreferencesManager.ORIENTATION_PORTRAIT;
    private static final String LANDSCAPE = com.fadcam.SharedPreferencesManager.ORIENTATION_LANDSCAPE;
    private static final String PORTRAIT_INV =
            com.fadcam.SharedPreferencesManager.ORIENTATION_PORTRAIT_INVERTED;
    private static final String LANDSCAPE_INV =
            com.fadcam.SharedPreferencesManager.ORIENTATION_LANDSCAPE_INVERTED;
    private static final String AUTO = com.fadcam.SharedPreferencesManager.ORIENTATION_AUTO;

    @Test
    public void uprightBandsMapToRotationZero() {
        assertEquals(Surface.ROTATION_0, DevicePosture.toSurfaceRotation(0));
        assertEquals(Surface.ROTATION_0, DevicePosture.toSurfaceRotation(359));
        assertEquals(Surface.ROTATION_0, DevicePosture.toSurfaceRotation(44));
        assertEquals(Surface.ROTATION_0, DevicePosture.toSurfaceRotation(315));
    }

    @Test
    public void upsideDownBandMapsTo180() {
        assertEquals(Surface.ROTATION_180, DevicePosture.toSurfaceRotation(135));
        assertEquals(Surface.ROTATION_180, DevicePosture.toSurfaceRotation(180));
        assertEquals(Surface.ROTATION_180, DevicePosture.toSurfaceRotation(224));
    }

    @Test
    public void sidewaysBandsMapToQuarterTurns() {
        // Left side up is ROTATION_270 per the CameraX guide, not ROTATION_90.
        assertEquals(Surface.ROTATION_270, DevicePosture.toSurfaceRotation(45));
        assertEquals(Surface.ROTATION_270, DevicePosture.toSurfaceRotation(90));
        assertEquals(Surface.ROTATION_270, DevicePosture.toSurfaceRotation(134));
        assertEquals(Surface.ROTATION_90, DevicePosture.toSurfaceRotation(225));
        assertEquals(Surface.ROTATION_90, DevicePosture.toSurfaceRotation(270));
        assertEquals(Surface.ROTATION_90, DevicePosture.toSurfaceRotation(314));
    }

    /** Every possible accelerometer angle must land in one of the four bands. */
    @Test
    public void everyAngleMapsToAKnownRotation() {
        for (int deg = 0; deg < 360; deg++) {
            int rot = DevicePosture.toSurfaceRotation(deg);
            assertTrue(deg + " mapped to " + rot,
                    rot == Surface.ROTATION_0 || rot == Surface.ROTATION_90
                            || rot == Surface.ROTATION_180 || rot == Surface.ROTATION_270);
        }
    }

    /**
     * The regression that made every orientation record identically: inversion used to be gated
     * on a sensor reporting upside-down, which never happens on most phones. It must now be a
     * pure function of the setting.
     */
    @Test
    public void inversionIsStaticAndAlwaysApplies() {
        assertTrue(com.fadcam.SharedPreferencesManager.shouldInvertOutput(PORTRAIT_INV));
        assertTrue(com.fadcam.SharedPreferencesManager.shouldInvertOutput(LANDSCAPE_INV));
        assertFalse(com.fadcam.SharedPreferencesManager.shouldInvertOutput(PORTRAIT));
        assertFalse(com.fadcam.SharedPreferencesManager.shouldInvertOutput(LANDSCAPE));
        assertFalse(com.fadcam.SharedPreferencesManager.shouldInvertOutput(AUTO));
        assertFalse(com.fadcam.SharedPreferencesManager.shouldInvertOutput(null));
    }

    /** Auto is a shape choice, never a rotation. */
    @Test
    public void autoNeverRotates() {
        assertFalse(com.fadcam.SharedPreferencesManager.shouldInvertOutput(AUTO));
    }

    /** Inverted must keep its upright counterpart's shape. */
    @Test
    public void invertedKeepsTheShapeOfItsUprightCounterpart() {
        assertTrue(com.fadcam.SharedPreferencesManager.isLandscapeOrientation(LANDSCAPE));
        assertTrue(com.fadcam.SharedPreferencesManager.isLandscapeOrientation(LANDSCAPE_INV));
        assertFalse(com.fadcam.SharedPreferencesManager.isLandscapeOrientation(PORTRAIT));
        assertFalse(com.fadcam.SharedPreferencesManager.isLandscapeOrientation(PORTRAIT_INV));
    }
}
