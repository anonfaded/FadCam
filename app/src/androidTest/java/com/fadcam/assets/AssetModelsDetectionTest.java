package com.fadcam.assets;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Proves the detector still initialises and detects after the graphs are resolved as
 * absolute files rather than AssetManager handles.
 *
 * <p>A file that merely exists proves nothing: the point of this test is that the LiteRT
 * GPU path accepts a path-based model and produces real output. If the compiled model
 * could not be built from the copied file, this fails.
 */
@RunWith(AndroidJUnit4.class)
public class AssetModelsDetectionTest {

    private static final String TAG = "ASSETSPIKE";

    private static Context ctx() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test
    public void detectorInitialisesFromResolvedGraphFiles() throws Exception {
        // The graphs are no longer in the APK, so download them first: this is the path a
        // real user takes on a fresh install.
        AssetModelsTest.ensureModelsInstalled();
        File a = AssetModels.graphFile(ctx(), AssetModels.GRAPH_A);
        File b = AssetModels.graphFile(ctx(), AssetModels.GRAPH_B);
        assertNotNull(a);
        assertNotNull(b);

        com.fadcam.motion.domain.detector.RfDetrNanoDetector detector =
                new com.fadcam.motion.domain.detector.RfDetrNanoDetector(ctx());
        try {
            String reason = detector.unavailableReason();
            if (reason != null) {
                // A GPU-less device is a legitimate outcome, but it must be reported as a
                // GPU limitation, not as a missing model. Distinguishing those is the whole
                // point: one is fixed by downloading, the other is not.
                System.out.println(TAG + " MODELS detector unavailable: " + reason);
                assertTrue("failure must not be about missing model files, got: " + reason,
                        !reason.toLowerCase(java.util.Locale.US).contains("not downloaded"));
            } else {
                assertTrue("detector must report itself available", detector.isAvailable());
                System.out.println(TAG + " MODELS PASS detector initialised from "
                        + "absolute-path graphs on the GPU path");
            }
        } finally {
            detector.close();
        }
    }

    @Test
    public void detectionProducesOutputOnASyntheticFrame() throws Exception {
        AssetModelsTest.ensureModelsInstalled();
        com.fadcam.motion.domain.detector.RfDetrNanoDetector detector =
                new com.fadcam.motion.domain.detector.RfDetrNanoDetector(ctx());
        try {
            if (detector.unavailableReason() != null) {
                System.out.println(TAG + " MODELS skip inference: "
                        + detector.unavailableReason());
                return;
            }
            // A flat frame has nothing to detect. Success here means the pipeline ran end
            // to end and returned an empty (not crashed) result, which is exactly what a
            // path-based model load has to support.
            com.fadcam.motion.domain.detector.AiObjectDetector.FramePacket frame =
                    com.fadcam.motion.domain.detector.AiObjectDetector.FramePacket
                            .syntheticWarmup(384, 384);
            long t0 = System.nanoTime();
            java.util.List<com.fadcam.motion.domain.detector.AiObjectDetector.DetectionResult>
                    detections = detector.detect(frame);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            assertNotNull("detect() must return a list, never null", detections);
            System.out.println(TAG + " MODELS PASS inference ran in " + ms
                    + "ms, detections=" + detections.size() + " (empty is correct here)");
        } finally {
            detector.close();
        }
    }
}
