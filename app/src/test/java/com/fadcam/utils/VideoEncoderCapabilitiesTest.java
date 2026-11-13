package com.fadcam.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.util.Range;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** JVM coverage for the capability clamps used by the #176 fallback ladder. */
@RunWith(RobolectricTestRunner.class)
public class VideoEncoderCapabilitiesTest {

    @Test
    public void frameRateIsClampedIntoTheEncoderRange() {
        Range<Double> range = Range.create(15.0, 30.0);
        assertEquals(30, VideoEncoderCapabilities.clampFps(range, 60));
        assertEquals(15, VideoEncoderCapabilities.clampFps(range, 10));
        assertEquals(24, VideoEncoderCapabilities.clampFps(range, 24));
        assertEquals(60, VideoEncoderCapabilities.clampFps(null, 60));
    }

    @Test
    public void fractionalFrameRateRangeStillYieldsAWholeRate() {
        Range<Double> range = Range.create(29.97, 29.97);
        assertEquals(30, VideoEncoderCapabilities.clampFps(range, 60));
        assertTrue(VideoEncoderCapabilities.supportsFrameRate(Range.create(1.0, 59.94), 60));
        assertFalse(VideoEncoderCapabilities.supportsFrameRate(Range.create(1.0, 30.0), 60));
    }

    @Test
    public void bitrateIsClampedIntoTheEncoderRange() {
        Range<Integer> range = Range.create(1_000_000, 10_000_000);
        assertEquals(10_000_000, VideoEncoderCapabilities.clampBitrate(range, 62_000_000));
        assertEquals(1_000_000, VideoEncoderCapabilities.clampBitrate(range, 100_000));
        assertEquals(4_000_000, VideoEncoderCapabilities.clampBitrate(range, 4_000_000));
        assertEquals(62_000_000, VideoEncoderCapabilities.clampBitrate(null, 62_000_000));
    }
}
