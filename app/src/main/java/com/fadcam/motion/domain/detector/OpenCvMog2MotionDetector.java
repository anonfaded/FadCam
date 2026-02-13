package com.fadcam.motion.domain.detector;

import android.media.Image;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;
import org.opencv.video.BackgroundSubtractorMOG2;
import org.opencv.video.Video;

import java.nio.ByteBuffer;

/**
 * OpenCV-backed motion detector using MOG2 foreground segmentation.
 * Optimized for low-resolution grayscale analysis frames.
 */
public class OpenCvMog2MotionDetector implements MotionDetector, MotionDebugInfoProvider {
    private static final int SAMPLE_W = 320;
    private static final int SAMPLE_H = 180;
    private static final int STRONG_DELTA_THRESHOLD = 14;
    private static final float MIN_ACTIVE_AREA_RATIO = 0.003f;
    private static final float MIN_ACTIVE_MEAN_DELTA = 0.004f;
    private static final float MIN_ACTIVE_ENERGY = 0.012f;
    private static final float QUIET_SCENE_AREA_RATIO = 0.75f;
    private static final float GLOBAL_AREA_RATIO_THRESHOLD = 0.70f;
    private static final float GLOBAL_STRONG_AREA_RATIO_THRESHOLD = 0.45f;
    private static final float GLOBAL_MEAN_DELTA_THRESHOLD = 0.07f;

    private final BackgroundSubtractorMOG2 mog2;
    private final Mat grayMat;
    private final Mat fgMask;
    private final byte[] current;
    private byte[] previous;

    private float lastChangedAreaRatio = 0f;
    private float lastStrongAreaRatio = 0f;
    private float lastMeanDelta = 0f;
    private float lastBackgroundDelta = 0f;
    private float lastMaxDelta = 0f;
    private boolean lastGlobalMotionSuppressed = false;

    public OpenCvMog2MotionDetector() {
        if (!OpenCVLoader.initDebug()) {
            throw new IllegalStateException("OpenCV native runtime not available");
        }
        mog2 = Video.createBackgroundSubtractorMOG2(500, 16.0, false);
        mog2.setDetectShadows(false);
        grayMat = new Mat(SAMPLE_H, SAMPLE_W, CvType.CV_8UC1);
        fgMask = new Mat(SAMPLE_H, SAMPLE_W, CvType.CV_8UC1);
        current = new byte[SAMPLE_W * SAMPLE_H];
    }

    @Override
    public float detectScore(Image image) {
        if (image == null || image.getPlanes() == null || image.getPlanes().length == 0) {
            return 0f;
        }
        Image.Plane yPlane = image.getPlanes()[0];
        ByteBuffer yBuffer = yPlane.getBuffer();
        if (yBuffer == null || !yBuffer.hasRemaining()) {
            return 0f;
        }

        int width = image.getWidth();
        int height = image.getHeight();
        int rowStride = yPlane.getRowStride();
        int pixelStride = yPlane.getPixelStride();

        sampleLuma(yBuffer, width, height, rowStride, pixelStride, current);

        if (previous == null || previous.length != current.length) {
            previous = current.clone();
            grayMat.put(0, 0, current);
            mog2.apply(grayMat, fgMask, 1.0);
            resetDebug();
            return 0f;
        }

        long deltaSum = 0L;
        int strongPixels = 0;
        int maxDelta = 0;
        for (int i = 0; i < current.length; i++) {
            int cur = current[i] & 0xFF;
            int prev = previous[i] & 0xFF;
            int delta = Math.abs(cur - prev);
            deltaSum += delta;
            if (delta >= STRONG_DELTA_THRESHOLD) {
                strongPixels++;
            }
            if (delta > maxDelta) {
                maxDelta = delta;
            }
        }
        previous = current.clone();

        lastMeanDelta = (deltaSum / (float) current.length) / 255f;
        lastStrongAreaRatio = strongPixels / (float) current.length;
        lastMaxDelta = maxDelta / 255f;

        grayMat.put(0, 0, current);
        mog2.apply(grayMat, fgMask, 0.01);
        Imgproc.threshold(fgMask, fgMask, 160.0, 255.0, Imgproc.THRESH_BINARY);
        int changedPixels = org.opencv.core.Core.countNonZero(fgMask);
        lastChangedAreaRatio = changedPixels / (float) current.length;
        lastBackgroundDelta = (float) org.opencv.core.Core.mean(fgMask).val[0] / 255f;

        float activeEnergy = (lastMeanDelta * 0.65f) + (lastBackgroundDelta * 0.35f);
        if (lastChangedAreaRatio < MIN_ACTIVE_AREA_RATIO && lastMeanDelta < MIN_ACTIVE_MEAN_DELTA) {
            lastGlobalMotionSuppressed = false;
            return 0f;
        }
        if (activeEnergy < MIN_ACTIVE_ENERGY && lastChangedAreaRatio < QUIET_SCENE_AREA_RATIO) {
            // Keep tiny-far motion if it has enough edge energy; otherwise suppress noise.
            boolean subtleButReal = lastStrongAreaRatio >= 0.015f || lastMaxDelta >= 0.18f;
            if (!subtleButReal) {
                lastGlobalMotionSuppressed = false;
                return 0f;
            }
        }

        boolean globalLikely = lastChangedAreaRatio >= GLOBAL_AREA_RATIO_THRESHOLD
                || (lastStrongAreaRatio >= GLOBAL_STRONG_AREA_RATIO_THRESHOLD && lastMeanDelta >= GLOBAL_MEAN_DELTA_THRESHOLD);
        boolean likelyFarSubjectMotion = lastChangedAreaRatio <= 0.08f
                && lastStrongAreaRatio >= 0.008f
                && lastMeanDelta >= 0.004f
                && lastMaxDelta >= 0.18f;
        if (globalLikely) {
            if (likelyFarSubjectMotion) {
                lastGlobalMotionSuppressed = false;
                return Math.max(0.05f, Math.min(0.25f, activeEnergy + (lastStrongAreaRatio * 0.35f)));
            }
            // Fast background adaptation when global disturbance happens.
            mog2.apply(grayMat, fgMask, 0.25);
            lastGlobalMotionSuppressed = true;
            return 0f;
        }
        lastGlobalMotionSuppressed = false;

        float areaFactor = 0.90f + Math.min(0.60f, lastChangedAreaRatio * 0.60f);
        float detailBoost = Math.min(0.18f, (lastStrongAreaRatio * 0.45f) + (lastMaxDelta * 0.12f));
        float areaBoost = Math.min(0.20f, lastChangedAreaRatio * 0.22f);
        float score = (activeEnergy * areaFactor) + detailBoost + areaBoost;
        return Math.max(0f, Math.min(1f, score));
    }

    @Override
    public float getLastChangedAreaRatio() {
        return lastChangedAreaRatio;
    }

    @Override
    public float getLastStrongAreaRatio() {
        return lastStrongAreaRatio;
    }

    @Override
    public float getLastMeanDelta() {
        return lastMeanDelta;
    }

    @Override
    public float getLastBackgroundDelta() {
        return lastBackgroundDelta;
    }

    @Override
    public float getLastMaxDelta() {
        return lastMaxDelta;
    }

    @Override
    public boolean isLastGlobalMotionSuppressed() {
        return lastGlobalMotionSuppressed;
    }

    private void resetDebug() {
        lastChangedAreaRatio = 0f;
        lastStrongAreaRatio = 0f;
        lastMeanDelta = 0f;
        lastBackgroundDelta = 0f;
        lastMaxDelta = 0f;
        lastGlobalMotionSuppressed = false;
    }

    private static void sampleLuma(ByteBuffer buffer, int width, int height, int rowStride, int pixelStride, byte[] out) {
        int index = 0;
        for (int y = 0; y < SAMPLE_H; y++) {
            int srcY = y * Math.max(1, (height - 1)) / Math.max(1, (SAMPLE_H - 1));
            int rowOffset = srcY * rowStride;
            for (int x = 0; x < SAMPLE_W; x++) {
                int srcX = x * Math.max(1, (width - 1)) / Math.max(1, (SAMPLE_W - 1));
                int offset = rowOffset + srcX * pixelStride;
                out[index++] = (offset >= 0 && offset < buffer.limit()) ? buffer.get(offset) : 0;
            }
        }
    }
}
