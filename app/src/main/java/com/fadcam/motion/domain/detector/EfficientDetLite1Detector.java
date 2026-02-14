package com.fadcam.motion.domain.detector;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.Image;
import android.util.Log;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Single-source detector for person/vehicle/pet/object using EfficientDet-Lite1.
 */
public class EfficientDetLite1Detector {
    private static final String TAG = "EfficientDetLite1";
    private static final String MODEL_PATH = "models/efficientdet_lite1.tflite";

    private static final float MIN_CONFIDENCE = 0.20f;
    private static final float MIN_PERSON_CONFIDENCE = 0.55f;

    private final Interpreter interpreter;
    private final int inputWidth;
    private final int inputHeight;
    private final int inputChannels;
    private final DataType inputDataType;
    private boolean tensorLayoutLogged = false;

    public static final class Detection {
        public final int classId;
        public final String eventType;
        public final float confidence;
        public final float centerX;
        public final float centerY;
        public final float width;
        public final float height;

        public Detection(
                int classId,
                String eventType,
                float confidence,
                float centerX,
                float centerY,
                float width,
                float height
        ) {
            this.classId = classId;
            this.eventType = eventType;
            this.confidence = confidence;
            this.centerX = centerX;
            this.centerY = centerY;
            this.width = width;
            this.height = height;
        }
    }

    public EfficientDetLite1Detector(Context context) {
        Interpreter localInterpreter = null;
        int width = 320;
        int height = 320;
        int channels = 3;
        DataType type = DataType.UINT8;
        try {
            MappedByteBuffer model = loadModelFile(context, MODEL_PATH);
            if (model != null) {
                Interpreter.Options options = new Interpreter.Options();
                options.setNumThreads(4);
                localInterpreter = new Interpreter(model, options);
                Tensor inputTensor = localInterpreter.getInputTensor(0);
                int[] shape = inputTensor.shape();
                if (shape.length >= 4) {
                    height = shape[1];
                    width = shape[2];
                    channels = shape[3];
                }
                type = inputTensor.dataType();
                Log.i(TAG, "Loaded model: " + MODEL_PATH
                        + " input=" + width + "x" + height + "x" + channels
                        + " type=" + type);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialize EfficientDet-Lite1 detector", t);
        }
        this.interpreter = localInterpreter;
        this.inputWidth = width;
        this.inputHeight = height;
        this.inputChannels = channels;
        this.inputDataType = type;
    }

    public boolean isAvailable() {
        return interpreter != null;
    }

    public List<Detection> detect(Image image) {
        List<Detection> detections = new ArrayList<>();
        if (interpreter == null || image == null) {
            return detections;
        }
        try {
            ByteBuffer input = toInputBuffer(image);
            if (input == null) {
                return detections;
            }

            int outputCount = interpreter.getOutputTensorCount();
            if (outputCount < 3) {
                return detections;
            }

            Map<Integer, Object> outputs = new HashMap<>();
            ByteBuffer[] outputBuffers = new ByteBuffer[outputCount];
            DataType[] outputTypes = new DataType[outputCount];
            float[] outputScales = new float[outputCount];
            int[] outputZeroPoints = new int[outputCount];
            int[][] outputShapes = new int[outputCount][];

            for (int i = 0; i < outputCount; i++) {
                Tensor tensor = interpreter.getOutputTensor(i);
                outputShapes[i] = tensor.shape();
                outputTypes[i] = tensor.dataType();
                outputScales[i] = tensor.quantizationParams().getScale();
                outputZeroPoints[i] = tensor.quantizationParams().getZeroPoint();
                if (!tensorLayoutLogged) {
                    Log.d(TAG, "Output[" + i + "] name=" + tensor.name()
                            + " shape=" + java.util.Arrays.toString(outputShapes[i])
                            + " type=" + outputTypes[i]);
                }
                ByteBuffer outputBuffer = ByteBuffer.allocateDirect(Math.max(1, tensor.numBytes()));
                outputBuffer.order(ByteOrder.nativeOrder());
                outputBuffers[i] = outputBuffer;
                outputs.put(i, outputBuffer);
            }

            interpreter.runForMultipleInputsOutputs(new Object[]{input}, outputs);

            float[][] boxes = null;
            float[] classes = null;
            float[] scores = null;
            float[] num = null;

            for (int i = 0; i < outputCount; i++) {
                float[] raw = readOutput(outputBuffers[i], outputTypes[i], outputScales[i], outputZeroPoints[i]);
                int[] shape = outputShapes[i];
                String name = interpreter.getOutputTensor(i).name();
                String lowerName = name == null ? "" : name.toLowerCase(Locale.US);
                if (shape == null || raw.length == 0) {
                    continue;
                }
                if (looksLikeBoxesTensor(lowerName, shape)) {
                    int n = shape[1];
                    boxes = reshapeBoxes(raw, n);
                    continue;
                }
                if (looksLikeNumTensor(lowerName, shape)) {
                    num = raw;
                    continue;
                }
                if (looksLikeScoresTensor(lowerName, shape, raw)) {
                    scores = raw;
                    continue;
                }
                if (looksLikeClassesTensor(lowerName, shape, raw)) {
                    classes = raw;
                    continue;
                }
            }

            // Fallback to shape-based heuristics if model export uses opaque tensor names.
            if (boxes == null || classes == null || scores == null) {
                for (int i = 0; i < outputCount; i++) {
                    if (boxes != null && classes != null && scores != null) {
                        break;
                    }
                    float[] raw = readOutput(outputBuffers[i], outputTypes[i], outputScales[i], outputZeroPoints[i]);
                    int[] shape = outputShapes[i];
                    if (shape == null || raw.length == 0) {
                        continue;
                    }
                    if (boxes == null && shape.length == 3 && shape[shape.length - 1] == 4) {
                        boxes = reshapeBoxes(raw, shape[1]);
                        continue;
                    }
                    if (shape.length == 2 && shape[0] == 1) {
                        if (num == null && shape[1] == 1) {
                            num = raw;
                            continue;
                        }
                        if (scores == null && isMostlyProbability(raw)) {
                            scores = raw;
                            continue;
                        }
                        if (classes == null && isMostlyClassId(raw)) {
                            classes = raw;
                        }
                    }
                }
            }

            if (boxes == null || classes == null || scores == null) {
                return detections;
            }
            tensorLayoutLogged = true;

            int candidateCount = Math.min(Math.min(boxes.length, classes.length), scores.length);
            if (num != null && num.length > 0) {
                candidateCount = Math.min(candidateCount, Math.max(0, Math.round(num[0])));
            }

            for (int i = 0; i < candidateCount; i++) {
                float score = scores[i];
                if (score < MIN_CONFIDENCE) {
                    continue;
                }
                int classId = Math.max(0, Math.round(classes[i]));
                float[] box = boxes[i];
                float top = clamp01(box[0]);
                float left = clamp01(box[1]);
                float bottom = clamp01(box[2]);
                float right = clamp01(box[3]);
                float width = Math.max(0f, right - left);
                float height = Math.max(0f, bottom - top);
                if (width <= 0.002f || height <= 0.002f) {
                    continue;
                }
                float centerX = clamp01((left + right) * 0.5f);
                float centerY = clamp01((top + bottom) * 0.5f);
                String eventType = mapEventType(classId);
                detections.add(new Detection(classId, eventType, score, centerX, centerY, width, height));
            }
        } catch (Throwable t) {
            Log.w(TAG, "EfficientDet inference failed", t);
        }
        return detections;
    }

    public float bestPersonConfidence(List<Detection> detections) {
        float best = 0f;
        for (Detection d : detections) {
            if ("PERSON".equals(d.eventType)) {
                best = Math.max(best, d.confidence);
            }
        }
        return best;
    }

    public boolean hasPerson(List<Detection> detections) {
        return bestPersonConfidence(detections) >= MIN_PERSON_CONFIDENCE;
    }

    public Detection choosePrimary(List<Detection> detections) {
        if (detections == null || detections.isEmpty()) {
            return null;
        }
        Detection best = null;
        for (Detection d : detections) {
            if (best == null) {
                best = d;
                continue;
            }
            int pBest = priority(best.eventType);
            int pCurrent = priority(d.eventType);
            if (pCurrent > pBest || (pCurrent == pBest && d.confidence > best.confidence)) {
                best = d;
            }
        }
        return best;
    }

    private int priority(String eventType) {
        if ("PERSON".equals(eventType)) {
            return 4;
        }
        if ("VEHICLE".equals(eventType)) {
            return 3;
        }
        if ("PET".equals(eventType)) {
            return 2;
        }
        return 1;
    }

    private String mapEventType(int classId) {
        // COCO ids used by EfficientDet-Lite family.
        if (classId == 0) {
            return "PERSON";
        }
        if (classId == 1 || classId == 2 || classId == 3 || classId == 5 || classId == 6 || classId == 7 || classId == 8) {
            return "VEHICLE";
        }
        if (classId == 14 || classId == 15 || classId == 16 || classId == 17 || classId == 18) {
            return "PET";
        }
        return "OBJECT";
    }

    private ByteBuffer toInputBuffer(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length == 0) {
            return null;
        }
        Image.Plane yPlane = planes[0];
        ByteBuffer yBuffer = yPlane.getBuffer();
        if (yBuffer == null) {
            return null;
        }
        int srcW = image.getWidth();
        int srcH = image.getHeight();
        int rowStride = yPlane.getRowStride();
        int pixelStride = yPlane.getPixelStride();
        int channels = Math.max(1, inputChannels);
        int bytesPerElement = inputDataType == DataType.UINT8 ? 1 : 4;
        ByteBuffer input = ByteBuffer.allocateDirect(inputWidth * inputHeight * channels * bytesPerElement);
        input.order(ByteOrder.nativeOrder());

        for (int y = 0; y < inputHeight; y++) {
            int srcY = y * Math.max(1, srcH - 1) / Math.max(1, inputHeight - 1);
            int rowStart = srcY * rowStride;
            for (int x = 0; x < inputWidth; x++) {
                int srcX = x * Math.max(1, srcW - 1) / Math.max(1, inputWidth - 1);
                int offset = rowStart + srcX * pixelStride;
                int luma = 0;
                if (offset >= 0 && offset < yBuffer.limit()) {
                    luma = yBuffer.get(offset) & 0xFF;
                }
                for (int c = 0; c < channels; c++) {
                    if (inputDataType == DataType.UINT8) {
                        input.put((byte) (luma & 0xFF));
                    } else {
                        input.putFloat(luma / 255f);
                    }
                }
            }
        }
        input.rewind();
        return input;
    }

    private static float[][] reshapeBoxes(float[] raw, int rows) {
        float[][] boxes = new float[rows][4];
        int offset = 0;
        for (int r = 0; r < rows; r++) {
            if (offset + 3 >= raw.length) {
                break;
            }
            boxes[r][0] = raw[offset];
            boxes[r][1] = raw[offset + 1];
            boxes[r][2] = raw[offset + 2];
            boxes[r][3] = raw[offset + 3];
            offset += 4;
        }
        return boxes;
    }

    private static MappedByteBuffer loadModelFile(Context context, String path) {
        try (AssetFileDescriptor fd = context.getAssets().openFd(path);
             FileInputStream stream = new FileInputStream(fd.getFileDescriptor());
             FileChannel channel = stream.getChannel()) {
            return channel.map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
        } catch (Exception e) {
            Log.e(TAG, String.format(Locale.US, "Model not found in assets: %s", path), e);
            return null;
        }
    }

    private static float[] readOutput(ByteBuffer buffer, DataType type, float scale, int zeroPoint) {
        if (buffer == null) {
            return new float[0];
        }
        buffer.rewind();
        if (type == DataType.FLOAT32) {
            FloatBuffer floatBuffer = buffer.order(ByteOrder.nativeOrder()).asFloatBuffer();
            float[] values = new float[floatBuffer.remaining()];
            floatBuffer.get(values);
            return values;
        }
        if (type == DataType.UINT8) {
            float[] values = new float[buffer.remaining()];
            float effectiveScale = scale > 0f ? scale : (1f / 255f);
            for (int i = 0; i < values.length; i++) {
                int unsigned = buffer.get() & 0xFF;
                values[i] = (unsigned - zeroPoint) * effectiveScale;
            }
            return values;
        }
        float[] values = new float[buffer.remaining()];
        for (int i = 0; i < values.length; i++) {
            values[i] = buffer.get() & 0xFF;
        }
        return values;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private boolean looksLikeBoxesTensor(String name, int[] shape) {
        if (shape.length == 3 && shape[shape.length - 1] == 4) {
            return true;
        }
        return name.contains("box") || name.contains("bbox");
    }

    private boolean looksLikeNumTensor(String name, int[] shape) {
        return (shape.length == 2 && shape[0] == 1 && shape[1] == 1)
                || name.contains("num");
    }

    private boolean looksLikeScoresTensor(String name, int[] shape, float[] values) {
        if (name.contains("score") || name.contains("confidence")) {
            return true;
        }
        return shape.length == 2 && shape[0] == 1 && isMostlyProbability(values);
    }

    private boolean looksLikeClassesTensor(String name, int[] shape, float[] values) {
        if (name.contains("class")) {
            return true;
        }
        return shape.length == 2 && shape[0] == 1 && isMostlyClassId(values);
    }

    private boolean isMostlyProbability(float[] values) {
        int ok = 0;
        for (float v : values) {
            if (v >= 0f && v <= 1.0001f) {
                ok++;
            }
        }
        return values.length > 0 && (ok / (float) values.length) > 0.80f;
    }

    private boolean isMostlyClassId(float[] values) {
        int ok = 0;
        for (float v : values) {
            int iv = Math.round(v);
            if (Math.abs(v - iv) <= 0.05f && iv >= 0 && iv <= 120) {
                ok++;
            }
        }
        return values.length > 0 && (ok / (float) values.length) > 0.70f;
    }
}
