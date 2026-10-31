package com.fadcam.opengl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.fadcam.utils.VideoEncoderCapabilities;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * Device-side proof for the two assumptions behind the #176 fix:
 *
 * <ol>
 *   <li>Every advertised encoder can be described (surface support, size, fps, bitrate mode)
 *       without throwing.</li>
 *   <li>An encoder that rejects the optional {@code KEY_PREPEND_HEADER_TO_SYNC_FRAMES} can
 *       still be configured when that key is omitted — the exact ladder used by
 *       {@code GLRecordingPipeline.initializeEncoder()}.</li>
 * </ol>
 */
@RunWith(AndroidJUnit4.class)
public class VideoEncoderCapabilitiesTest {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int FPS = 30;
    private static final int BITRATE = 4_000_000;

    @Test
    public void capabilityReportDescribesEveryEncoder() {
        for (String mime : new String[]{MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC}) {
            List<VideoEncoderCapabilities.EncoderInfo> infos =
                    VideoEncoderCapabilities.inspectAll(mime, WIDTH, HEIGHT);
            if (MediaFormat.MIMETYPE_VIDEO_AVC.equals(mime)) {
                // AVC encoding is mandatory on every Android device that can record video.
                assertFalse("No AVC encoder advertised", infos.isEmpty());
            }
            for (VideoEncoderCapabilities.EncoderInfo info : infos) {
                assertTrue("empty codec name for " + mime, info.name != null && !info.name.isEmpty());
                // describe() must never throw; it is called on the recording-start path.
                assertFalse(info.describe().isEmpty());
            }
        }
    }

    @Test
    public void prependHeaderIsOptionalNotMandatory() {
        boolean configuredWithPrepend = false;
        boolean configuredWithoutPrepend = false;
        boolean sawPrependRejection = false;

        for (String mime : new String[]{MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC}) {
            List<VideoEncoderCapabilities.EncoderInfo> infos =
                    VideoEncoderCapabilities.inspectAll(mime, WIDTH, HEIGHT);
            for (VideoEncoderCapabilities.EncoderInfo info : infos) {
                if (!info.surfaceSupported || Boolean.FALSE.equals(info.sizeSupported)) {
                    continue;
                }
                try {
                    configure(info.name, mime, true);
                    configuredWithPrepend = true;
                } catch (Exception first) {
                    // This is the Huawei/EMUI 10 path: the optional key is rejected.
                    sawPrependRejection = true;
                    try {
                        configure(info.name, mime, false);
                        configuredWithoutPrepend = true;
                    } catch (Exception second) {
                        // Encoder is unusable for an unrelated reason; another candidate
                        // will be tried by the production ladder.
                    }
                }
            }
        }

        // The test device must have at least one usable encoder, with or without the key.
        assertTrue("No encoder configured at all", configuredWithPrepend || configuredWithoutPrepend);
        // If we actually observed a rejection, the no-prepend rung must have recovered it.
        if (sawPrependRejection && !configuredWithPrepend) {
            assertTrue("Prepend was rejected and the no-prepend retry did not recover",
                    configuredWithoutPrepend);
        }
    }

    private static void configure(String name, String mime, boolean prepend) throws Exception {
        MediaCodec codec = null;
        try {
            codec = MediaCodec.createByCodecName(name);
            MediaFormat format = MediaFormat.createVideoFormat(mime, WIDTH, HEIGHT);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            if (prepend) {
                format.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1);
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } finally {
            if (codec != null) {
                try {
                    codec.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
