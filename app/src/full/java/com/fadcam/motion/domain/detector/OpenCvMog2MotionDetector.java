package com.fadcam.motion.domain.detector;

import android.media.Image;

import androidx.annotation.Nullable;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.geometry.Geometry;
import org.opencv.video.BackgroundSubtractorMOG2;
import org.opencv.video.Video;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Production-style motion detector: MOG2 background subtraction + morphology +
 * connected components, with a per-target score.
 *
 * <p>Why this shape:
 * <ul>
 *   <li><b>Full field of view</b> — the source frame is only downscaled, never
 *       cropped, so everything the preview shows is analysed.</li>
 *   <li><b>Area-averaged downscale</b> — a small/far target keeps its energy
 *       instead of being skipped by nearest-neighbour point sampling.</li>
 *   <li><b>Morphology</b> — removes single-pixel noise and joins nearby blobs.</li>
 *   <li><b>Connected components</b> — every moving target is scored on its own
 *       area and contrast, so a small target in a dark corner is not diluted by
 *       the rest of the frame (the old global-mean score did exactly that).</li>
 * </ul>
 *
 * <p>This mirrors how professional CCTV detectors work: sensitivity controls the
 * smallest accepted target, while a separate total-change condition rejects
 * lighting noise.
 */
public class OpenCvMog2MotionDetector implements MotionDetector, MotionDebugInfoProvider {
    /**
     * Working width cap. The actual width comes from the user's analyzer-resolution
     * setting (via the analysis ImageReader size), so 480p/720p/1080p really change
     * the sampling grid. This cap only protects against absurd surface sizes.
     * Height follows the source aspect ratio (no stretching/cropping).
     */
    private static final int MAX_TARGET_W = 1920;
    /** Minimum blob area floor; scaled by the user's sensitivity at runtime. */
    private float minBlobAreaRatio = 0.0022f;
    private int sensitivity = -1;
    /** Total changed area required before a frame can trigger. */
    private static final float MIN_TOTAL_CHANGE_RATIO = 0.0012f;
    /** A single blob this large is enough on its own. */
    private static final float STRONG_BLOB_AREA_RATIO = 0.010f;
    private static final int STRONG_DELTA_THRESHOLD = 14;
    /** Reject a frame that is almost entirely different (light switch, auto-exposure). */
    private static final float GLOBAL_CHANGE_RATIO = 0.72f;

    private final BackgroundSubtractorMOG2 mog2;
    private Mat grayMat;
    private Mat fgMask;
    /** Gamma curve as a lookup table, applied in one pass over the byte array. */
    private int[] gammaLutValues;
    private Mat openKernel;
    private Mat closeKernel;
    private Mat hierarchy;
    private final List<MatOfPoint> contours = new ArrayList<>();
    private int outW = 0;
    private int outH = 0;
    private final java.util.List<android.graphics.Rect> lastBlobRects =
            new java.util.ArrayList<>();
    private byte[] current;
    private byte[] previous;

    private float lastChangedAreaRatio = 0f;
    private float lastStrongAreaRatio = 0f;
    private float lastMeanDelta = 0f;
    private float lastBackgroundDelta = 0f;
    private float lastMaxDelta = 0f;
    private float lastMotionCenterX = 0.5f;
    private float lastMotionCenterY = 0.5f;
    private boolean lastGlobalMotionSuppressed = false;
    private int lastBlobCount = 0;

    public OpenCvMog2MotionDetector() {
        this(null);
    }

    /**
     * @param context used to resolve a downloaded OpenCV runtime. Pass null on builds where
     *                the libraries are still bundled, or from tests that have already
     *                initialised the runtime.
     * @throws com.fadcam.assets.AssetException when the runtime is not installed, so the
     *         caller can show a download banner instead of crashing
     */
    public OpenCvMog2MotionDetector(@Nullable android.content.Context context) {
        if (context != null) {
            // Resolves a downloaded runtime, or falls through to OpenCV's own loader when
            // the libraries still ship in the APK. Throws AssetException when neither is
            // available, which the UI renders as a one-time download prompt.
            com.fadcam.assets.AssetOpenCv.ensureLoaded(context);
        }
        boolean inited = OpenCVLoader.initLocal();
        if (!inited) {
            throw new IllegalStateException("OpenCV native runtime not available");
        }
        mog2 = Video.createBackgroundSubtractorMOG2(500, 16.0, false);
        mog2.setDetectShadows(false);
    }

    @Override
    public void setSensitivity(int sensitivity) {
        int clamped = Math.max(0, Math.min(100, sensitivity));
        if (clamped == this.sensitivity) {
            return;
        }
        this.sensitivity = clamped;
        // Sensitivity controls the smallest accepted target, like a CCTV
        // "sensitivity" control: high = tiny/far targets, low = only larger
        // targets (and no eye/face micro-motion).
        float t = 1f - (clamped / 100f);
        minBlobAreaRatio = 0.0004f + (float) Math.pow(t, 1.5) * 0.020f;
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

        int srcW = image.getWidth();
        int srcH = image.getHeight();
        int rowStride = yPlane.getRowStride();
        int pixelStride = yPlane.getPixelStride();

        int targetW = Math.min(MAX_TARGET_W, srcW);
        int targetH = Math.max(2, Math.round(targetW * srcH / (float) Math.max(1, srcW)));
        if ((targetH & 1) != 0) {
            targetH--;
        }
        ensureBuffers(targetW, targetH);

        sampleLumaBox(yBuffer, srcW, srcH, rowStride, pixelStride, targetW, targetH, current);

        // The gamma curve is part of the detector's sensitivity: it lifts shadows so
        // dim motion is not missed. Keep the LUT, but apply it in place on the byte
        // array instead of copying the frame into the Mat and back out again.
        applyGamma(current, gammaLutValues);
        grayMat.put(0, 0, current);

        if (previous == null) {
            previous = current.clone();
            mog2.apply(grayMat, fgMask, 1.0);
            resetDebug();
            return 0f;
        }

        // ── Per-pixel luma change (secondary signal + noise floor) ───────────
        final byte[] prevFrame = previous;
        long deltaSum = 0L;
        int strongPixels = 0;
        int maxDelta = 0;
        for (int i = 0; i < current.length; i++) {
            int cur = current[i] & 0xFF;
            int prev = prevFrame[i] & 0xFF;
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

        // ── Background subtraction + morphology ──────────────────────────────
        mog2.apply(grayMat, fgMask, 0.01);
        Imgproc.threshold(fgMask, fgMask, 200.0, 255.0, Imgproc.THRESH_BINARY);
        Imgproc.morphologyEx(fgMask, fgMask, Imgproc.MORPH_OPEN, openKernel);
        Imgproc.morphologyEx(fgMask, fgMask, Imgproc.MORPH_CLOSE, closeKernel);

        int totalPixels = current.length;
        int changedPixels = Core.countNonZero(fgMask);
        lastChangedAreaRatio = changedPixels / (float) totalPixels;
        lastBackgroundDelta = (float) Core.mean(fgMask).val[0] / 255f;

        // ── Connected components: score each target individually ─────────────
        contours.clear();
        lastBlobRects.clear();
        Imgproc.findContours(fgMask, contours, hierarchy, Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE);
        lastBlobCount = 0;

        float bestBlobScore = 0f;
        float bestBlobArea = 0f;
        float bestBlobMaxDelta = 0f;
        float bestCenterX = 0.5f;
        float bestCenterY = 0.5f;
        int significantBlobs = 0;

        for (MatOfPoint contour : contours) {
            try {
                Rect box = Geometry.boundingRect(contour);
                int x0 = Math.max(0, box.x);
                int y0 = Math.max(0, box.y);
                int x1 = Math.min(targetW, box.x + box.width);
                int y1 = Math.min(targetH, box.y + box.height);
                if (x1 <= x0 || y1 <= y0) {
                    continue;
                }
                // Actual foreground pixels, not polygon area: a sparse/noisy
                // contour can have a large polygon but almost no real motion.
                Mat roi = fgMask.submat(y0, y1, x0, x1);
                int fgPixels;
                try {
                    fgPixels = Core.countNonZero(roi);
                } finally {
                    roi.release();
                }
                float areaRatio = fgPixels / (float) totalPixels;
                if (areaRatio < minBlobAreaRatio) {
                    continue;
                }
                lastBlobRects.add(new android.graphics.Rect(x0, y0, x1, y1));

                long blobSum = 0L;
                int blobMax = 0;
                int blobPixels = 0;
                for (int y = y0; y < y1; y++) {
                    int row = y * targetW;
                    for (int x = x0; x < x1; x++) {
                        int i = row + x;
                        int delta = Math.abs((current[i] & 0xFF) - (prevFrame[i] & 0xFF));
                        blobSum += delta;
                        if (delta > blobMax) {
                            blobMax = delta;
                        }
                        blobPixels++;
                    }
                }
                if (blobPixels == 0) {
                    continue;
                }
                float blobMean = (blobSum / (float) blobPixels) / 255f;
                float blobMaxNorm = blobMax / 255f;
                if (blobMean < 0.010f && blobMaxNorm < 0.15f) {
                    // Low-energy noise floor.
                    continue;
                }

                // Sensitivity = smallest target that can trigger.
                float sizeStrength = Math.min(1f, areaRatio / STRONG_BLOB_AREA_RATIO);
                float contrastStrength = Math.min(1f,
                        Math.max(0f, (blobMaxNorm - 0.05f) / 0.45f));
                float meanStrength = Math.min(1f,
                        Math.max(0f, (blobMean - 0.010f) / 0.080f));
                float blobScore = (0.45f * sizeStrength)
                        + (0.35f * contrastStrength)
                        + (0.20f * meanStrength);
                significantBlobs++;
                lastBlobCount = significantBlobs;

                if (blobScore > bestBlobScore) {
                    bestBlobScore = blobScore;
                    bestBlobArea = areaRatio;
                    bestBlobMaxDelta = blobMaxNorm;
                    org.opencv.geometry.Moments m = Geometry.moments(contour);
                    if (m.m00 > 0d) {
                        bestCenterX = clamp01((float) (m.m10 / m.m00) / targetW);
                        bestCenterY = clamp01((float) (m.m01 / m.m00) / targetH);
                    }
                }
            } finally {
                contour.release();
            }
        }

        if (significantBlobs > 0) {
            lastMotionCenterX = bestCenterX;
            lastMotionCenterY = bestCenterY;
        } else {
            lastMotionCenterX = 0.5f;
            lastMotionCenterY = 0.5f;
        }

        // Global illumination change (light switch, auto-exposure) — suppress.
        if (lastChangedAreaRatio >= GLOBAL_CHANGE_RATIO
                && significantBlobs <= 1
                && bestBlobMaxDelta < 0.60f) {
            mog2.apply(grayMat, fgMask, 0.30);
            lastGlobalMotionSuppressed = true;
            return 0f;
        }
        lastGlobalMotionSuppressed = false;

        if (significantBlobs == 0) {
            return 0f;
        }

        boolean totalChangeMet = lastChangedAreaRatio >= MIN_TOTAL_CHANGE_RATIO;
        boolean strongTargetMet = bestBlobArea >= STRONG_BLOB_AREA_RATIO
                && bestBlobMaxDelta >= 0.20f;
        if (!totalChangeMet && !strongTargetMet) {
            return 0f;
        }

        // A small-but-strong target is enough on its own; a large global change
        // needs some local contrast so a flat lighting change cannot trigger.
        float score = bestBlobScore;
        if (totalChangeMet) {
            score = Math.max(score,
                    Math.min(1f, lastChangedAreaRatio * 1.2f + lastMaxDelta * 0.35f));
        }
        return Math.max(0f, Math.min(1f, score));
    }

    /** Applies the gamma LUT in place; one pass, no Mat round trip. */
    private static void applyGamma(byte[] pixels, int[] lut) {
        if (pixels == null || lut == null) {
            return;
        }
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (byte) lut[pixels[i] & 0xFF];
        }
    }

    private void ensureBuffers(int targetW, int targetH) {
        if (targetW == outW && targetH == outH && grayMat != null) {
            return;
        }
        outW = targetW;
        outH = targetH;
        current = new byte[targetW * targetH];
        previous = null;
        grayMat = new Mat(targetH, targetW, CvType.CV_8UC1);
        fgMask = new Mat(targetH, targetW, CvType.CV_8UC1);
        if (hierarchy == null) {
            hierarchy = new Mat();
        }
        gammaLutValues = new int[256];
        for (int i = 0; i < 256; i++) {
            gammaLutValues[i] = Math.min(255,
                    (int) Math.round(255.0 * Math.pow(i / 255.0, 0.72)));
        }
        openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(3, 3));
        closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(5, 5));
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

    public int getLastBlobCount() {
        return lastBlobCount;
    }

    @Override
    public java.util.List<android.graphics.Rect> getLastBlobRects() {
        return lastBlobRects;
    }

    @Override
    public int getWorkingWidth() {
        return outW;
    }

    @Override
    public int getWorkingHeight() {
        return outH;
    }

    @Override
    public float getMinBlobAreaRatio() {
        return minBlobAreaRatio;
    }

    @Override
    public boolean isLastGlobalMotionSuppressed() {
        return lastGlobalMotionSuppressed;
    }

    @Override
    public float getLastMotionCenterX() {
        return lastMotionCenterX;
    }

    @Override
    public float getLastMotionCenterY() {
        return lastMotionCenterY;
    }

    private void resetDebug() {
        lastChangedAreaRatio = 0f;
        lastStrongAreaRatio = 0f;
        lastMeanDelta = 0f;
        lastBackgroundDelta = 0f;
        lastMaxDelta = 0f;
        lastMotionCenterX = 0.5f;
        lastMotionCenterY = 0.5f;
        lastGlobalMotionSuppressed = false;
        lastBlobCount = 0;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    /**
     * Box-filter downscale. Each output pixel is the average of the source block
     * it covers, so thin/small/far targets contribute instead of being skipped.
     */
    private static void sampleLumaBox(ByteBuffer buffer, int srcW, int srcH,
                                      int rowStride, int pixelStride,
                                      int dstW, int dstH, byte[] out) {
        int index = 0;
        for (int dy = 0; dy < dstH; dy++) {
            int sy0 = dy * srcH / dstH;
            int sy1 = Math.max(sy0 + 1, (dy + 1) * srcH / dstH);
            if (sy1 > srcH) {
                sy1 = srcH;
            }
            for (int dx = 0; dx < dstW; dx++) {
                int sx0 = dx * srcW / dstW;
                int sx1 = Math.max(sx0 + 1, (dx + 1) * srcW / dstW);
                if (sx1 > srcW) {
                    sx1 = srcW;
                }
                long sum = 0L;
                int count = 0;
                for (int sy = sy0; sy < sy1; sy++) {
                    int row = sy * rowStride;
                    for (int sx = sx0; sx < sx1; sx++) {
                        int offset = row + sx * pixelStride;
                        if (offset >= 0 && offset < buffer.limit()) {
                            sum += buffer.get(offset) & 0xFF;
                            count++;
                        }
                    }
                }
                out[index++] = (byte) (count > 0 ? (sum / count) : 0);
            }
        }
    }
}
