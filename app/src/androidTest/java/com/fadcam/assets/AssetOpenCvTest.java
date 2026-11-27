package com.fadcam.assets;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * OpenCV must be downloadable and usable after the download.
 *
 * <p>OpenCV is no longer in the APK, so these tests fail if the resolver, install or load
 * order is wrong — there is no bundled copy to mask a mistake.
 */
@RunWith(AndroidJUnit4.class)
public class AssetOpenCvTest {

    private static final String TAG = "ASSETSPIKE";

    private static Context ctx() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    /** Downloads and installs OpenCV if it is not already present. */
    static void ensureOpenCvInstalled() throws Exception {
        AssetRegistry reg = AssetRegistry.get(ctx());
        List<AssetManifest.Entry> entries = reg.entriesFor(Asset.OPENCV);
        assertNotNull(entries);
        if (reg.store().hasAllFiles(Asset.OPENCV, entries)) {
            return;
        }
        List<File> pending = new java.util.ArrayList<>();
        for (AssetManifest.Entry e : entries) {
            File partial = reg.store().partialFileFor(e);
            new AssetDownloader(new okhttp3.OkHttpClient()).download(
                    reg.manifest().baseUrl() + e.file, partial, (done, total) -> { });
            pending.add(partial);
        }
        AssetInstaller.installAll(Asset.OPENCV, reg.store(), entries, pending);
    }

    @Test
    public void openCvIsNotBundledInTheApk() {
        // The point of this PR: a fresh install must not carry 31 MB of OpenCV.
        AssetRegistry reg = AssetRegistry.get(ctx());
        assertFalse("OpenCV must not report BUNDLED; it was removed from the APK",
                reg.isBundled(Asset.OPENCV));
        System.out.println(TAG + " OPENCV bundled=" + reg.isBundled(Asset.OPENCV)
                + " state=" + reg.state(Asset.OPENCV));
    }

    @Test
    public void openCvDownloadsInstallsAndInitialises() throws Exception {
        ensureOpenCvInstalled();
        AssetRegistry reg = AssetRegistry.get(ctx());
        List<AssetManifest.Entry> entries = reg.entriesFor(Asset.OPENCV);

        // Both members must be on disk, including libc++_shared which nothing else provides.
        for (AssetManifest.Entry e : entries) {
            for (String member : e.members) {
                File f = reg.store().fileFor(Asset.OPENCV, member);
                assertTrue("missing " + member + " after install", f.isFile());
                System.out.println(TAG + " OPENCV installed " + member + " "
                        + f.length() + " bytes");
            }
        }

        AssetOpenCv.ensureLoaded(ctx());
        assertEquals("a runtime that initialised must report its real version",
                "5.1.0", org.opencv.core.Core.VERSION);
        System.out.println(TAG + " OPENCV PASS initialised from storage, version="
                + org.opencv.core.Core.VERSION);
    }

    @Test
    public void motionDetectionRunsFromTheDownloadedRuntime() throws Exception {
        ensureOpenCvInstalled();
        AssetOpenCv.ensureLoaded(ctx());

        // Proves the JNI bridge, not just that a class loaded: create a MOG2 subtractor and
        // push two frames through it. This is the same OpenCV surface the detector uses.
        org.opencv.video.BackgroundSubtractorMOG2 mog2 =
                org.opencv.video.Video.createBackgroundSubtractorMOG2(500, 16.0, false);
        mog2.setDetectShadows(false);
        org.opencv.core.Mat frame =
                new org.opencv.core.Mat(480, 640, org.opencv.core.CvType.CV_8UC1);
        org.opencv.core.Mat mask = new org.opencv.core.Mat();
        try {
            frame.setTo(new org.opencv.core.Scalar(20));
            mog2.apply(frame, mask);
            // Move a block so the subtractor has a real difference to report.
            org.opencv.core.Rect block =
                    new org.opencv.core.Rect(100, 100, 200, 160);
            frame.submat(block).setTo(new org.opencv.core.Scalar(220));
            long t0 = System.nanoTime();
            mog2.apply(frame, mask);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            assertEquals("MOG2 must return a mask the same size as the frame",
                    480, mask.rows());
            assertEquals(640, mask.cols());
            System.out.println(TAG + " OPENCV PASS MOG2 ran two 640x480 frames, last in "
                    + ms + "ms");
        } finally {
            mog2.clear();
            frame.release();
            mask.release();
        }

        // And the production detector constructs against the same runtime.
        com.fadcam.motion.domain.detector.OpenCvMog2MotionDetector detector =
                new com.fadcam.motion.domain.detector.OpenCvMog2MotionDetector(ctx());
        detector.setSensitivity(60);
        System.out.println(TAG + " OPENCV PASS production detector constructed");
    }

    @Test
    public void isReadyReflectsInstallState() throws Exception {
        AssetRegistry reg = AssetRegistry.get(ctx());
        reg.delete(Asset.OPENCV);
        assertFalse("with nothing installed, OpenCV must not be ready",
                AssetOpenCv.isReady(ctx()));

        ensureOpenCvInstalled();
        assertTrue("after install, OpenCV must be ready", AssetOpenCv.isReady(ctx()));
        assertTrue("installed size must be reported", AssetOpenCv.installedSize(ctx()) > 0);
        System.out.println(TAG + " OPENCV installedSize="
                + (AssetOpenCv.installedSize(ctx()) / 1048576) + " MB");
    }

    @Test
    public void missingRuntimeRaisesAssetExceptionNotACrash() {
        AssetRegistry.get(ctx()).delete(Asset.OPENCV);
        try {
            AssetOpenCv.ensureLoaded(ctx());
            throw new AssertionError("ensureLoaded must throw when OpenCV is absent");
        } catch (AssetException expected) {
            assertTrue("the exception must name OpenCV",
                    expected.asset() == Asset.OPENCV);
            System.out.println(TAG + " OPENCV PASS absent runtime reported as: "
                    + expected.getMessage());
        }
    }
}
