package com.fadcam.motion.domain.detector;

import android.media.Image;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * AI object detection seam. The data types (FramePacket/DetectionResult) are pure
 * Android and live in shared code; the TFLite implementation lives in src/full/
 * (EfficientDetLite1Detector). Shared code (RecordingService, forensics) never
 * references TensorFlow classes.
 */
public interface AiObjectDetector {

    boolean isAvailable();

    /**
     * User-facing explanation when {@link #isAvailable()} is false, or null when the
     * detector is usable. Implementations must state the real cause (for example a
     * missing GPU capability) rather than degrade silently.
     */
    @androidx.annotation.Nullable
    default String unavailableReason() {
        return null;
    }

    List<DetectionResult> detect(FramePacket packet);

    float bestPersonConfidence(List<DetectionResult> detections);

    boolean hasPerson(List<DetectionResult> detections);

    DetectionResult choosePrimary(List<DetectionResult> detections);

    /**
     * Releases the native model and its buffers. Called when the feature that
     * needs detection is switched off, so the 56 MB model does not stay resident
     * for a feature the user disabled.
     */
    default void close() {
    }

    final class FramePacket {
        public final int width;
        public final int height;
        public final int yRowStride;
        public final int yPixelStride;
        public final int uvRowStride;
        public final int uvPixelStride;
        public final byte[] y;
        public final byte[] u;
        public final byte[] v;
        /**
         * Clockwise rotation that turns this sensor-oriented frame into the upright
         * content orientation the model was trained on. Detections are mapped back
         * to sensor coordinates, so every downstream consumer keeps using one space.
         */
        public final int rotationDegrees;
        /**
         * True for the front camera. Its analysis stream runs bottom-to-top relative to
         * the content orientation, so both the overlay and the evidence snapshot apply
         * the same vertical flip.
         */
        public final boolean frontFacing;

        private FramePacket(
                int width,
                int height,
                int yRowStride,
                int yPixelStride,
                int uvRowStride,
                int uvPixelStride,
                byte[] y,
                byte[] u,
                byte[] v,
                int rotationDegrees,
                boolean frontFacing
        ) {
            this.width = width;
            this.height = height;
            this.yRowStride = yRowStride;
            this.yPixelStride = yPixelStride;
            this.uvRowStride = uvRowStride;
            this.uvPixelStride = uvPixelStride;
            this.y = y;
            this.u = u;
            this.v = v;
            this.rotationDegrees = ((rotationDegrees % 360) + 360) % 360;
            this.frontFacing = frontFacing;
        }

        /**
         * A neutral frame for measuring model cost at load time. It carries no
         * scene data; the numbers give a timing baseline, not a detection.
         */
        public static FramePacket syntheticWarmup(int width, int height) {
            int ySize = width * height;
            return new FramePacket(
                    width, height,
                    width, 1,
                    width / 2, 1,
                    new byte[ySize],
                    new byte[ySize / 4],
                    new byte[ySize / 4],
                    0,
                    false);
        }

        public static FramePacket copyFrom(Image image, int rotationDegrees, boolean frontFacing) {
            if (image == null || image.getPlanes() == null || image.getPlanes().length < 3) {
                return null;
            }
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer yBuffer = planes[0].getBuffer();
            ByteBuffer uBuffer = planes[1].getBuffer();
            ByteBuffer vBuffer = planes[2].getBuffer();
            if (yBuffer == null || uBuffer == null || vBuffer == null) {
                return null;
            }

            ByteBuffer yDup = yBuffer.duplicate();
            ByteBuffer uDup = uBuffer.duplicate();
            ByteBuffer vDup = vBuffer.duplicate();
            yDup.rewind();
            uDup.rewind();
            vDup.rewind();

            byte[] y = new byte[yDup.remaining()];
            byte[] u = new byte[uDup.remaining()];
            byte[] v = new byte[vDup.remaining()];
            yDup.get(y);
            uDup.get(u);
            vDup.get(v);

            return new FramePacket(
                    image.getWidth(),
                    image.getHeight(),
                    planes[0].getRowStride(),
                    planes[0].getPixelStride(),
                    planes[1].getRowStride(),
                    planes[1].getPixelStride(),
                    y,
                    u,
                    v,
                    rotationDegrees,
                    frontFacing
            );
        }
    }

    final class DetectionResult {
        public final int classId;
        public final String className;
        public final String coarseType;
        public final float confidence;
        public final float centerX;
        public final float centerY;
        public final float width;
        public final float height;

        public DetectionResult(
                int classId,
                String className,
                String coarseType,
                float confidence,
                float centerX,
                float centerY,
                float width,
                float height
        ) {
            this.classId = classId;
            this.className = className;
            this.coarseType = coarseType;
            this.confidence = confidence;
            this.centerX = centerX;
            this.centerY = centerY;
            this.width = width;
            this.height = height;
        }
    }
}
