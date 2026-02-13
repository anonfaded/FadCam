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

/**
 * Optional TFLite person detector.
 * Loads a model from assets/models/person_detector.tflite when present.
 * If missing or incompatible, detector gracefully reports unavailable.
 */
public class TflitePersonDetector implements PersonDetector {
    private static final String TAG = "TflitePersonDetector";
    private static final String MODEL_PATH = "models/person_detector.tflite";

    private final Interpreter interpreter;
    private final int inputWidth;
    private final int inputHeight;
    private final int inputChannels;
    private final DataType inputDataType;
    private final float threshold;
    private volatile float lastConfidence = 0f;

    public TflitePersonDetector(Context context, float threshold) {
        this.threshold = threshold;
        Interpreter localInterpreter = null;
        int width = 96;
        int height = 96;
        int channels = 3;
        DataType dataType = DataType.FLOAT32;
        try {
            MappedByteBuffer model = loadModelFile(context, MODEL_PATH);
            if (model != null) {
                Interpreter.Options options = new Interpreter.Options();
                options.setNumThreads(2);
                localInterpreter = new Interpreter(model, options);
                Tensor input = localInterpreter.getInputTensor(0);
                int[] shape = input.shape();
                if (shape.length >= 4) {
                    height = shape[1];
                    width = shape[2];
                    channels = shape[3];
                }
                dataType = input.dataType();
                Log.i(TAG, "Loaded model: " + MODEL_PATH + " input=" + width + "x" + height + "x" + channels
                    + " type=" + dataType);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to initialize TFLite person detector", t);
        }
        interpreter = localInterpreter;
        inputWidth = width;
        inputHeight = height;
        inputChannels = channels;
        inputDataType = dataType;
    }

    @Override
    public boolean detectPerson(Image image) {
        if (interpreter == null || image == null) {
            return false;
        }
        try {
            ByteBuffer input = toInputBuffer(image);
            if (input == null) {
                return false;
            }

            int outputCount = interpreter.getOutputTensorCount();
            if (outputCount <= 0) {
                return false;
            }

            Object[] inputs = new Object[]{input};
            java.util.Map<Integer, Object> outputs = new java.util.HashMap<>();
            float[][] outputArrays = new float[outputCount][];
            int[][] outputShapes = new int[outputCount][];
            String[] outputNames = new String[outputCount];
            DataType[] outputTypes = new DataType[outputCount];
            ByteBuffer[] outputBuffers = new ByteBuffer[outputCount];
            float[] outputScales = new float[outputCount];
            int[] outputZeroPoints = new int[outputCount];
            for (int i = 0; i < outputCount; i++) {
                Tensor tensor = interpreter.getOutputTensor(i);
                int[] shape = tensor.shape();
                outputShapes[i] = shape;
                outputNames[i] = tensor.name() == null ? "" : tensor.name().toLowerCase();
                outputTypes[i] = tensor.dataType();
                outputScales[i] = tensor.quantizationParams().getScale();
                outputZeroPoints[i] = tensor.quantizationParams().getZeroPoint();

                ByteBuffer outputBuffer = ByteBuffer.allocateDirect(Math.max(1, tensor.numBytes()));
                outputBuffer.order(ByteOrder.nativeOrder());
                outputBuffers[i] = outputBuffer;
                outputs.put(i, outputBuffer);
            }
            interpreter.runForMultipleInputsOutputs(inputs, outputs);

            for (int i = 0; i < outputCount; i++) {
                outputArrays[i] = readOutput(outputBuffers[i], outputTypes[i], outputScales[i], outputZeroPoints[i]);
            }

            float maxConfidence = 0f;
            boolean foundScoreTensor = false;
            for (int i = 0; i < outputCount; i++) {
                float[] output = outputArrays[i];
                if (output == null) {
                    continue;
                }
                if (!isConfidenceCandidate(outputNames[i], outputShapes[i], output)) {
                    continue;
                }
                foundScoreTensor = true;
                for (float v : output) {
                    if (v >= 0f && v <= 1f && v > maxConfidence) {
                        maxConfidence = v;
                    }
                }
            }
            if (!foundScoreTensor) {
                // Fallback to robust bounded scan when tensor metadata/names are nonstandard.
                for (int i = 0; i < outputCount; i++) {
                    float[] output = outputArrays[i];
                    if (output == null) {
                        continue;
                    }
                    for (float v : output) {
                        if (v >= 0f && v <= 1f && v > maxConfidence) {
                            maxConfidence = v;
                        }
                    }
                }
            }
            lastConfidence = maxConfidence;
            return maxConfidence >= threshold;
        } catch (Throwable t) {
            Log.w(TAG, "Person inference failed", t);
            lastConfidence = 0f;
            return false;
        }
    }

    @Override
    public boolean isAvailable() {
        return interpreter != null;
    }

    @Override
    public float getLastConfidence() {
        return lastConfidence;
    }

    private boolean isConfidenceCandidate(String name, int[] shape, float[] values) {
        if (values.length <= 1) {
            return false;
        }
        if (shape != null && shape.length >= 1) {
            int last = shape[shape.length - 1];
            if (last == 4) {
                // Usually boxes [N,4]
                return false;
            }
        }
        if (name != null && (name.contains("class") || name.contains("label"))) {
            return false;
        }
        if (name != null && (name.contains("score") || name.contains("confidence") || name.contains("prob"))) {
            return true;
        }
        // Heuristic fallback: >80% values in [0..1]
        int bounded = 0;
        for (float v : values) {
            if (v >= 0f && v <= 1f) {
                bounded++;
            }
        }
        return (bounded / (float) values.length) > 0.8f;
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
            int srcY = y * Math.max(1, (srcH - 1)) / Math.max(1, (inputHeight - 1));
            int rowStart = srcY * rowStride;
            for (int x = 0; x < inputWidth; x++) {
                int srcX = x * Math.max(1, (srcW - 1)) / Math.max(1, (inputWidth - 1));
                int offset = rowStart + srcX * pixelStride;
                int luma = 0;
                if (offset >= 0 && offset < yBuffer.limit()) {
                    luma = yBuffer.get(offset) & 0xFF;
                }
                for (int c = 0; c < channels; c++) {
                    if (inputDataType == DataType.UINT8) {
                        input.put((byte) (luma & 0xFF));
                    } else {
                        float normalized = luma / 255f;
                        input.putFloat(normalized);
                    }
                }
            }
        }
        input.rewind();
        return input;
    }

    private static MappedByteBuffer loadModelFile(Context context, String path) {
        try (AssetFileDescriptor fileDescriptor = context.getAssets().openFd(path);
             FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
             FileChannel fileChannel = inputStream.getChannel()) {
            long startOffset = fileDescriptor.getStartOffset();
            long declaredLength = fileDescriptor.getDeclaredLength();
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
        } catch (Exception e) {
            Log.i(TAG, "Model not found in assets: " + path);
            return null;
        }
    }

    private float[] readOutput(ByteBuffer buffer, DataType dataType, float scale, int zeroPoint) {
        if (buffer == null) {
            return new float[0];
        }
        buffer.rewind();

        if (dataType == DataType.FLOAT32) {
            FloatBuffer floatBuffer = buffer.order(ByteOrder.nativeOrder()).asFloatBuffer();
            float[] values = new float[floatBuffer.remaining()];
            floatBuffer.get(values);
            return values;
        }

        if (dataType == DataType.UINT8) {
            float[] values = new float[buffer.remaining()];
            float effectiveScale = scale > 0f ? scale : (1f / 255f);
            for (int i = 0; i < values.length; i++) {
                int unsigned = buffer.get() & 0xFF;
                values[i] = (unsigned - zeroPoint) * effectiveScale;
            }
            return values;
        }

        // Fallback for uncommon tensor types: parse raw bytes as unsigned values.
        float[] values = new float[buffer.remaining()];
        for (int i = 0; i < values.length; i++) {
            values[i] = buffer.get() & 0xFF;
        }
        return values;
    }
}
