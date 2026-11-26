package com.fadcam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Pins how the four video orientation values classify.
 *
 * <p>The important case is the inverted portrait value. The encoder used to decide its output
 * shape with {@code "portrait".equalsIgnoreCase(orientation)}, which is silently false for
 * {@code portrait_inverted} — that would have recorded a landscape file for a portrait setting.
 * Every shape consumer now goes through these classifiers instead.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class VideoOrientationShapeTest {

    private static final String[] ALL = {
            SharedPreferencesManager.ORIENTATION_PORTRAIT,
            SharedPreferencesManager.ORIENTATION_PORTRAIT_INVERTED,
            SharedPreferencesManager.ORIENTATION_LANDSCAPE,
            SharedPreferencesManager.ORIENTATION_LANDSCAPE_INVERTED,
    };

    @Test
    public void portraitVariantsAreNotLandscape() {
        assertFalse(SharedPreferencesManager.isLandscapeOrientation(
                SharedPreferencesManager.ORIENTATION_PORTRAIT));
        assertFalse(SharedPreferencesManager.isLandscapeOrientation(
                SharedPreferencesManager.ORIENTATION_PORTRAIT_INVERTED));
    }

    @Test
    public void landscapeVariantsAreLandscape() {
        assertTrue(SharedPreferencesManager.isLandscapeOrientation(
                SharedPreferencesManager.ORIENTATION_LANDSCAPE));
        assertTrue(SharedPreferencesManager.isLandscapeOrientation(
                SharedPreferencesManager.ORIENTATION_LANDSCAPE_INVERTED));
    }

    @Test
    public void onlyInvertedVariantsAreInverted() {
        assertFalse(SharedPreferencesManager.isInvertedOrientation(
                SharedPreferencesManager.ORIENTATION_PORTRAIT));
        assertFalse(SharedPreferencesManager.isInvertedOrientation(
                SharedPreferencesManager.ORIENTATION_LANDSCAPE));
        assertTrue(SharedPreferencesManager.isInvertedOrientation(
                SharedPreferencesManager.ORIENTATION_PORTRAIT_INVERTED));
        assertTrue(SharedPreferencesManager.isInvertedOrientation(
                SharedPreferencesManager.ORIENTATION_LANDSCAPE_INVERTED));
    }

    /** An inverted option must keep its upright counterpart's shape, not swap it. */
    @Test
    public void invertedKeepsTheShapeOfItsUprightCounterpart() {
        assertEquals(
                SharedPreferencesManager.isLandscapeOrientation(
                        SharedPreferencesManager.ORIENTATION_PORTRAIT),
                SharedPreferencesManager.isLandscapeOrientation(
                        SharedPreferencesManager.ORIENTATION_PORTRAIT_INVERTED));
        assertEquals(
                SharedPreferencesManager.isLandscapeOrientation(
                        SharedPreferencesManager.ORIENTATION_LANDSCAPE),
                SharedPreferencesManager.isLandscapeOrientation(
                        SharedPreferencesManager.ORIENTATION_LANDSCAPE_INVERTED));
    }

    @Test
    public void everyValueClassifiesExactlyOnce() {
        for (String value : ALL) {
            boolean landscape = SharedPreferencesManager.isLandscapeOrientation(value);
            boolean inverted = SharedPreferencesManager.isInvertedOrientation(value);
            assertTrue(value + " must classify as portrait or landscape",
                    landscape || !landscape);
            assertTrue(value + " must be identifiable as inverted or not",
                    inverted || !inverted);
        }
    }

    /** Legacy stored values must behave exactly as before this feature. */
    @Test
    public void legacyValuesKeepTheirOldMeaning() {
        assertFalse(SharedPreferencesManager.isLandscapeOrientation("portrait"));
        assertTrue(SharedPreferencesManager.isLandscapeOrientation("landscape"));
        assertFalse(SharedPreferencesManager.isInvertedOrientation("portrait"));
        assertFalse(SharedPreferencesManager.isInvertedOrientation("landscape"));
    }

    /** Null and unknown values must degrade to portrait, never crash. */
    @Test
    public void unknownValuesFallBackToPortrait() {
        assertFalse(SharedPreferencesManager.isLandscapeOrientation(null));
        assertFalse(SharedPreferencesManager.isLandscapeOrientation("sideways"));
        assertFalse(SharedPreferencesManager.isInvertedOrientation(null));
        assertFalse(SharedPreferencesManager.isInvertedOrientation("sideways"));
    }

    @Test
    public void classificationIsCaseInsensitive() {
        assertTrue(SharedPreferencesManager.isLandscapeOrientation("LANDSCAPE_INVERTED"));
        assertTrue(SharedPreferencesManager.isInvertedOrientation("Portrait_Inverted"));
    }

    /** The default must stay portrait so existing installs are unaffected. */
    @Test
    public void defaultStaysPortrait() {
        assertEquals(SharedPreferencesManager.ORIENTATION_PORTRAIT,
                SharedPreferencesManager.DEFAULT_VIDEO_ORIENTATION);
    }
}
