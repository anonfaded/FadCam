package com.fadcam.motion.domain.detector;

import android.media.Image;

import java.nio.ByteBuffer;

public class FrameDiffMotionDetector implements MotionDetector, MotionDebugInfoProvider {

    private static final int SAMPLE_GRID_W = 160;
    private static final int SAMPLE_GRID_H = 90;
    private static final int PIXEL_DELTA_THRESHOLD = 8;
    private static final int STRONG_PIXEL_DELTA_THRESHOLD = 18;
    private static final int BACKGROUND_FREEZE_DELTA = 22;
    private static final float BACKGROUND_LEARNING_RATE = 0.03f;
    private static final float MIN_CHANGED_AREA_RATIO = 0.0012f;
    private static final float MIN_ACTIVE_AREA_RATIO = 0.02f;
    private static final float MIN_ACTIVE_MEAN_DELTA = 0.015f;
    private static final float MIN_ACTIVE_BG_DELTA = 0.012f;
    private static final float STRONG_SPIKE_AREA_RATIO = 0.04f;
    private static final float GLOBAL_MOTION_RATIO_THRESHOLD = 0.72f;
    private static final float GLOBAL_MOTION_MEAN_THRESHOLD = 0.10f;
    private static final float GLOBAL_MOTION_HARD_AREA_THRESHOLD = 0.85f;
    private static final float GLOBAL_MOTION_STRONG_AREA_THRESHOLD = 0.45f;
    private static final float GLOBAL_ADAPT_LEARNING_RATE = 0.22f;
    private byte[] previous;
    private float[] background;

    private volatile float lastChangedAreaRatio = 0f;
    private volatile float lastMeanDelta = 0f;
    private volatile float lastBackgroundDelta = 0f;
    private volatile float lastMaxDelta = 0f;
    private volatile float lastStrongAreaRatio = 0f;
    private volatile float lastMotionCenterX = 0.5f;
    private volatile float lastMotionCenterY = 0.5f;
    private volatile boolean lastGlobalMotionSuppressed = false;

    public float getLastChangedAreaRatio() {
        return lastChangedAreaRatio;
    }

    public float getLastMeanDelta() {
        return lastMeanDelta;
    }

    public float getLastBackgroundDelta() {
        return lastBackgroundDelta;
    }

    public float getLastMaxDelta() {
        return lastMaxDelta;
    }

    public float getLastStrongAreaRatio() {
        return lastStrongAreaRatio;
    }

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

    @Override
    public float detectScore(Image image) {
        if (image == null || image.getPlanes() == null || image.getPlanes().length == 0) {
            return 0f;
        }
        Image.Plane yPlane = image.getPlanes()[0];
        ByteBuffer buffer = yPlane.getBuffer();
        if (buffer == null || !buffer.hasRemaining()) {
            return 0f;
        }

        int width = image.getWidth();
        int height = image.getHeight();
        int rowStride = yPlane.getRowStride();
        int pixelStride = yPlane.getPixelStride();

        int gridW = Math.min(SAMPLE_GRID_W, Math.max(8, width));
        int gridH = Math.min(SAMPLE_GRID_H, Math.max(8, height));
        int sampleCount = gridW * gridH;
        byte[] current = new byte[sampleCount];

        int index = 0;
        for (int gy = 0; gy < gridH; gy++) {
            int y = gy * (height - 1) / Math.max(1, gridH - 1);
            int rowStart = y * rowStride;
            for (int gx = 0; gx < gridW; gx++) {
                int x = gx * (width - 1) / Math.max(1, gridW - 1);
                int offset = rowStart + x * pixelStride;
                if (offset >= 0 && offset < buffer.limit()) {
                    current[index++] = buffer.get(offset);
                } else {
                    current[index++] = 0;
                }
            }
        }

        if (previous == null || previous.length != current.length) {
            previous = current;
            background = new float[current.length];
            for (int i = 0; i < current.length; i++) {
                background[i] = current[i] & 0xFF;
            }
            lastChangedAreaRatio = 0f;
            lastMeanDelta = 0f;
            lastBackgroundDelta = 0f;
            lastMaxDelta = 0f;
            lastStrongAreaRatio = 0f;
            lastGlobalMotionSuppressed = false;
            return 0f;
        }

        long sum = 0L;
        long sumBg = 0L;
        long sumCur = 0L;
        long sumPrev = 0L;
        long sumBgLevel = 0L;
        for (int i = 0; i < current.length; i++) {
            int cur = current[i] & 0xFF;
            int prev = previous[i] & 0xFF;
            int bg = Math.round(background[i]);
            sumCur += cur;
            sumPrev += prev;
            sumBgLevel += bg;
        }
        float meanCur = sumCur / (float) current.length;
        float meanPrev = sumPrev / (float) current.length;
        float meanBg = sumBgLevel / (float) current.length;
        float globalShiftPrev = meanCur - meanPrev;
        float globalShiftBg = meanCur - meanBg;

        int changedPixels = 0;
        int strongPixels = 0;
        int maxDelta = 0;
        long weightedX = 0L;
        long weightedY = 0L;
        long weightSum = 0L;
        for (int i = 0; i < current.length; i++) {
            int cur = current[i] & 0xFF;
            int prev = previous[i] & 0xFF;
            int bg = Math.round(background[i]);

            int frameDelta = Math.abs(Math.round((cur - prev) - globalShiftPrev));
            int bgDelta = Math.abs(Math.round((cur - bg) - globalShiftBg));
            frameDelta = Math.min(255, frameDelta);
            bgDelta = Math.min(255, bgDelta);
            int delta = Math.max(frameDelta, bgDelta);
            sum += delta;
            sumBg += bgDelta;
            if (delta > maxDelta) {
                maxDelta = delta;
            }
            if (delta >= PIXEL_DELTA_THRESHOLD) {
                changedPixels++;
                int gx = i % gridW;
                int gy = i / gridW;
                int w = Math.max(1, delta);
                weightedX += (long) gx * w;
                weightedY += (long) gy * w;
                weightSum += w;
            }
            if (delta >= STRONG_PIXEL_DELTA_THRESHOLD) {
                strongPixels++;
            }

            if (bgDelta <= BACKGROUND_FREEZE_DELTA) {
                background[i] = (background[i] * (1f - BACKGROUND_LEARNING_RATE))
                    + (cur * BACKGROUND_LEARNING_RATE);
            }
        }
        previous = current;

        float changedAreaRatio = changedPixels / (float) current.length;
        float strongAreaRatio = strongPixels / (float) current.length;
        float meanDelta = (sum / (float) current.length) / 255f;
        float meanBgDelta = (sumBg / (float) current.length) / 255f;
        float maxDeltaNorm = maxDelta / 255f;
        if (weightSum > 0L) {
            lastMotionCenterX = Math.max(0f, Math.min(1f, (weightedX / (float) weightSum) / Math.max(1f, gridW - 1f)));
            lastMotionCenterY = Math.max(0f, Math.min(1f, (weightedY / (float) weightSum) / Math.max(1f, gridH - 1f)));
        } else {
            lastMotionCenterX = 0.5f;
            lastMotionCenterY = 0.5f;
        }

        lastChangedAreaRatio = changedAreaRatio;
        lastStrongAreaRatio = strongAreaRatio;
        lastMeanDelta = meanDelta;
        lastBackgroundDelta = meanBgDelta;
        lastMaxDelta = maxDeltaNorm;

        if (changedAreaRatio < MIN_CHANGED_AREA_RATIO && strongPixels < 3) {
            lastGlobalMotionSuppressed = false;
            return 0f;
        }

        // Reject low-area low-energy noise floor so recording can pause correctly in quiet scenes.
        if (changedAreaRatio < MIN_ACTIVE_AREA_RATIO
                && meanDelta < MIN_ACTIVE_MEAN_DELTA
                && meanBgDelta < MIN_ACTIVE_BG_DELTA) {
            lastGlobalMotionSuppressed = false;
            return 0f;
        }

        float ratioWeight = Math.min(1f, changedAreaRatio * 2.2f);
        float score = (meanDelta * 0.45f) + (meanBgDelta * 0.35f) + (maxDeltaNorm * 0.20f);
        score *= ratioWeight;

        if (strongAreaRatio >= STRONG_SPIKE_AREA_RATIO && maxDeltaNorm >= 0.22f) {
            score = Math.max(score, 0.08f + (maxDeltaNorm * 0.25f));
        }

        boolean globalMotionLikely =
            changedAreaRatio >= GLOBAL_MOTION_HARD_AREA_THRESHOLD
                || strongAreaRatio >= GLOBAL_MOTION_STRONG_AREA_THRESHOLD
                || (changedAreaRatio >= GLOBAL_MOTION_RATIO_THRESHOLD
                    && meanDelta >= GLOBAL_MOTION_MEAN_THRESHOLD);
        if (globalMotionLikely) {
            // Fast background convergence on global-frame disturbances so detector
            // recovers instead of staying "active" for long periods.
            for (int i = 0; i < current.length; i++) {
                int cur = current[i] & 0xFF;
                background[i] = (background[i] * (1f - GLOBAL_ADAPT_LEARNING_RATE))
                    + (cur * GLOBAL_ADAPT_LEARNING_RATE);
            }
            lastGlobalMotionSuppressed = true;
            // Treat near-full-frame motion (camera shake/tilt) as non-event motion signal.
            return 0f;
        }
        lastGlobalMotionSuppressed = false;
        return Math.min(1f, Math.max(0f, score));
    }
}
