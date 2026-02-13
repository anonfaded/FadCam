package com.fadcam.motion.domain.detector;

import android.media.Image;

import java.nio.ByteBuffer;

public class FrameDiffMotionDetector implements MotionDetector {

    private static final int SAMPLE_GRID_W = 96;
    private static final int SAMPLE_GRID_H = 54;
    private static final int PIXEL_DELTA_THRESHOLD = 10;
    private static final float MIN_CHANGED_AREA_RATIO = 0.005f;
    private byte[] previous;

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
            return 0f;
        }

        long sum = 0L;
        int changedPixels = 0;
        for (int i = 0; i < current.length; i++) {
            int a = current[i] & 0xFF;
            int b = previous[i] & 0xFF;
            int delta = Math.abs(a - b);
            sum += delta;
            if (delta >= PIXEL_DELTA_THRESHOLD) {
                changedPixels++;
            }
        }
        previous = current;

        float changedAreaRatio = changedPixels / (float) current.length;
        if (changedAreaRatio < MIN_CHANGED_AREA_RATIO) {
            return 0f;
        }

        // Normalize to 0..1
        float meanDelta = (sum / (float) current.length) / 255f;
        float weighted = meanDelta * (0.6f + (changedAreaRatio * 1.5f));
        return Math.min(1f, weighted);
    }
}
