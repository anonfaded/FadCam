package com.fadcam.motion.domain.detector;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.media.Image;
import android.util.Log;

import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.label.Category;
import org.tensorflow.lite.task.core.BaseOptions;
import org.tensorflow.lite.task.vision.detector.Detection;
import org.tensorflow.lite.task.vision.detector.ObjectDetector;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Single-source detector for person/vehicle/pet/object using EfficientDet-Lite1 metadata.
 */
public class EfficientDetLite1Detector {
    private static final String TAG = "EfficientDetLite1";
    private static final String MODEL_PATH = "models/efficientdet_lite1.tflite";

    // Hard-cut single policy block (no compat thresholds).
    private static final float MIN_CONFIDENCE = 0.20f;
    private static final float MIN_PERSON_CONFIDENCE = 0.24f;
    private static final float MIN_BOX_SIDE = 0.003f;
    private static final int MAX_RESULTS = 40;

    private final ObjectDetector detector;
    private long lastInferenceWarnMs = 0L;

    public static final class FramePacket {
        public final int width;
        public final int height;
        public final int yRowStride;
        public final int yPixelStride;
        public final int uvRowStride;
        public final int uvPixelStride;
        public final byte[] y;
        public final byte[] u;
        public final byte[] v;

        private FramePacket(
                int width,
                int height,
                int yRowStride,
                int yPixelStride,
                int uvRowStride,
                int uvPixelStride,
                byte[] y,
                byte[] u,
                byte[] v
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
        }

        public static FramePacket copyFrom(Image image) {
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
                    v
            );
        }
    }

    public static final class DetectionResult {
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

    public EfficientDetLite1Detector(Context context) {
        ObjectDetector local = null;
        try {
            BaseOptions baseOptions = BaseOptions.builder()
                    .setNumThreads(4)
                    .build();
            ObjectDetector.ObjectDetectorOptions options =
                    ObjectDetector.ObjectDetectorOptions.builder()
                            .setBaseOptions(baseOptions)
                            .setScoreThreshold(MIN_CONFIDENCE)
                            .setMaxResults(MAX_RESULTS)
                            .build();
            local = ObjectDetector.createFromFileAndOptions(context, MODEL_PATH, options);
            Log.i(TAG, "Loaded model via Task Vision API: " + MODEL_PATH);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialize EfficientDet Task Vision detector", t);
        }
        this.detector = local;
    }

    public boolean isAvailable() {
        return detector != null;
    }

    public List<DetectionResult> detect(Image image) {
        FramePacket packet = FramePacket.copyFrom(image);
        if (packet == null) {
            return new ArrayList<>();
        }
        return detect(packet);
    }

    public List<DetectionResult> detect(FramePacket packet) {
        List<DetectionResult> out = new ArrayList<>();
        if (detector == null || packet == null || packet.width <= 0 || packet.height <= 0) {
            return out;
        }
        try {
            Bitmap bitmap = yuv420ToBitmap(packet);
            if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
                return out;
            }
            TensorImage tensorImage = TensorImage.fromBitmap(bitmap);
            List<Detection> detections = detector.detect(tensorImage);
            int frameWidth = bitmap.getWidth();
            int frameHeight = bitmap.getHeight();
            for (Detection detection : detections) {
                if (detection == null || detection.getCategories() == null || detection.getCategories().isEmpty()) {
                    continue;
                }
                Category best = bestCategory(detection.getCategories());
                if (best == null) {
                    continue;
                }
                float score = best.getScore();
                if (score < MIN_CONFIDENCE) {
                    continue;
                }
                RectF box = detection.getBoundingBox();
                if (box == null) {
                    continue;
                }
                float left = clamp01(box.left / Math.max(1f, frameWidth));
                float top = clamp01(box.top / Math.max(1f, frameHeight));
                float right = clamp01(box.right / Math.max(1f, frameWidth));
                float bottom = clamp01(box.bottom / Math.max(1f, frameHeight));
                float width = Math.max(0f, right - left);
                float height = Math.max(0f, bottom - top);
                if (width <= MIN_BOX_SIDE || height <= MIN_BOX_SIDE) {
                    continue;
                }
                float centerX = clamp01((left + right) * 0.5f);
                float centerY = clamp01((top + bottom) * 0.5f);
                String label = normalizeLabel(best);
                String coarse = mapCoarseType(label);
                out.add(new DetectionResult(
                        best.getIndex(),
                        label,
                        coarse,
                        score,
                        centerX,
                        centerY,
                        width,
                        height
                ));
            }
        } catch (Throwable t) {
            long now = android.os.SystemClock.elapsedRealtime();
            if ((now - lastInferenceWarnMs) > 5000L) {
                lastInferenceWarnMs = now;
                Log.w(TAG, "EfficientDet inference skipped for one frame: " + t.getClass().getSimpleName());
            }
        }
        return out;
    }

    public float bestPersonConfidence(List<DetectionResult> detections) {
        float best = 0f;
        if (detections == null) {
            return 0f;
        }
        for (DetectionResult d : detections) {
            if ("PERSON".equals(d.coarseType)) {
                best = Math.max(best, d.confidence);
            }
        }
        return best;
    }

    public boolean hasPerson(List<DetectionResult> detections) {
        return bestPersonConfidence(detections) >= MIN_PERSON_CONFIDENCE;
    }

    public DetectionResult choosePrimary(List<DetectionResult> detections) {
        if (detections == null || detections.isEmpty()) {
            return null;
        }
        DetectionResult best = null;
        for (DetectionResult d : detections) {
            if (best == null) {
                best = d;
                continue;
            }
            if (d.confidence > best.confidence) {
                best = d;
            } else if (Math.abs(d.confidence - best.confidence) < 0.001f) {
                float areaCurrent = d.width * d.height;
                float areaBest = best.width * best.height;
                if (areaCurrent > areaBest) {
                    best = d;
                }
            }
        }
        return best;
    }

    private Category bestCategory(List<Category> categories) {
        Category best = null;
        for (Category category : categories) {
            if (category == null) {
                continue;
            }
            if (best == null || category.getScore() > best.getScore()) {
                best = category;
            }
        }
        return best;
    }

    private String normalizeLabel(Category category) {
        String label = category.getLabel();
        if (label == null || label.trim().isEmpty()) {
            label = category.getDisplayName();
        }
        if (label == null || label.trim().isEmpty()) {
            return "object";
        }
        return label.trim().toLowerCase(Locale.US);
    }

    private String mapCoarseType(String classNameRaw) {
        String className = classNameRaw == null ? "" : classNameRaw.toLowerCase(Locale.US);
        if ("person".equals(className)) {
            return "PERSON";
        }
        if ("cat".equals(className) || "dog".equals(className) || "bird".equals(className)
                || "horse".equals(className) || "sheep".equals(className)
                || "cow".equals(className)) {
            return "PET";
        }
        if ("bicycle".equals(className) || "car".equals(className) || "motorcycle".equals(className)
                || "airplane".equals(className) || "bus".equals(className)
                || "train".equals(className) || "truck".equals(className)
                || "boat".equals(className)) {
            return "VEHICLE";
        }
        return "OBJECT";
    }

    private Bitmap yuv420ToBitmap(FramePacket packet) {
        int width = packet.width;
        int height = packet.height;
        int[] pixels = new int[width * height];
        int index = 0;
        for (int y = 0; y < height; y++) {
            int yRowStart = y * packet.yRowStride;
            int uvRowStart = (y / 2) * packet.uvRowStride;
            for (int x = 0; x < width; x++) {
                int yIndex = yRowStart + (x * packet.yPixelStride);
                int uvIndex = uvRowStart + ((x / 2) * packet.uvPixelStride);
                int yValue = (yIndex >= 0 && yIndex < packet.y.length) ? (packet.y[yIndex] & 0xFF) : 0;
                int uValue = (uvIndex >= 0 && uvIndex < packet.u.length) ? (packet.u[uvIndex] & 0xFF) : 128;
                int vValue = (uvIndex >= 0 && uvIndex < packet.v.length) ? (packet.v[uvIndex] & 0xFF) : 128;

                int r = clampByte(yValue + (int) (1.402f * (vValue - 128)));
                int g = clampByte(yValue - (int) (0.344136f * (uValue - 128)) - (int) (0.714136f * (vValue - 128)));
                int b = clampByte(yValue + (int) (1.772f * (uValue - 128)));
                pixels[index++] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    private int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
