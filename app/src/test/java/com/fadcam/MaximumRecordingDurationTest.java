package com.fadcam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MaximumRecordingDurationTest {
    @Test
    public void resolvesEveryPresetInMinutes() {
        assertEquals(0, MaximumRecordingDuration.resolveMinutes(
                MaximumRecordingDuration.OPTION_NO_LIMIT, 30));
        assertEquals(5, MaximumRecordingDuration.resolveMinutes(
                MaximumRecordingDuration.OPTION_5_MINUTES, 30));
        assertEquals(10, MaximumRecordingDuration.resolveMinutes(
                MaximumRecordingDuration.OPTION_10_MINUTES, 30));
        assertEquals(30, MaximumRecordingDuration.resolveMinutes(
                MaximumRecordingDuration.OPTION_30_MINUTES, 30));
        assertEquals(60, MaximumRecordingDuration.resolveMinutes(
                MaximumRecordingDuration.OPTION_1_HOUR, 30));
    }

    @Test
    public void acceptsCustomRangeBoundaries() {
        assertTrue(MaximumRecordingDuration.isValidCustomMinutes(1));
        assertTrue(MaximumRecordingDuration.isValidCustomMinutes(24 * 60));
        assertEquals(77, MaximumRecordingDuration.resolveMinutes(
                MaximumRecordingDuration.OPTION_CUSTOM, 77));
        assertEquals(77L * 60_000L, MaximumRecordingDuration.resolveDurationMillis(
                MaximumRecordingDuration.OPTION_CUSTOM, 77));
    }

    @Test
    public void rejectsInvalidCustomDurationsWithoutOverflowing() {
        assertFalse(MaximumRecordingDuration.isValidCustomMinutes(0));
        assertFalse(MaximumRecordingDuration.isValidCustomMinutes(-1));
        assertFalse(MaximumRecordingDuration.isValidCustomMinutes(24 * 60 + 1));
        assertEquals(0L, MaximumRecordingDuration.resolveDurationMillis(
                MaximumRecordingDuration.OPTION_CUSTOM, Integer.MAX_VALUE));
    }

    @Test
    public void rejectsUnknownStoredOptionSafely() {
        assertFalse(MaximumRecordingDuration.isSupportedOption("unexpected"));
        assertEquals(0L, MaximumRecordingDuration.resolveDurationMillis("unexpected", 30));
    }
}
