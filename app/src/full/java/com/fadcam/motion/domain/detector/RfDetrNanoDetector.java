package com.fadcam.motion.domain.detector;

import android.content.Context;

import androidx.annotation.Nullable;

import com.fadcam.FLog;
import com.google.ai.edge.litert.Accelerator;
import com.google.ai.edge.litert.CompiledModel;
import com.google.ai.edge.litert.LiteRtException;
import com.google.ai.edge.litert.TensorBuffer;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * On-device detector: RF-DETR Nano (Apache-2.0) executed through LiteRT on the GPU.
 *
 * <p>Why this model/runtime pair:
 * <ul>
 *   <li>Accuracy: 48.4 COCO mAP@0.5:0.95 against 30.6 for the previous EfficientDet-Lite1.</li>
 *   <li>It is a transformer (DETR) detector, so it returns whole objects with a score per
 *       class instead of thousands of anchor hits. There is no manual label mapping and
 *       no hand-tuned post-filter: the model's own class probabilities decide.</li>
 *   <li>The published phone build is split into two graphs around the two-stage query
 *       selection, which has no mobile GPU kernel. Both graphs run on the GPU; the split
 *       point is run on the host.</li>
 * </ul>
 *
 * <p>Hardware requirement: the LiteRT GPU accelerator (ML Drift / OpenCL-OpenGL). The
 * legacy TFLite GPU delegate cannot execute this graph, and the CPU path is far too slow
 * for continuous analysis. When the GPU cannot initialise, this detector reports itself
 * unavailable with the real reason instead of silently degrading — {@link #unavailableReason()}.
 *
 * <p>Pipeline:
 * <pre>
 *   YUV frame -> RGB -> 384x384 NCHW, ImageNet mean/std
 *     -> Graph A -> enc_class[576,91], enc_coord[576,4], memory[576,256]
 *     -> host: top-300 queries by max class score, gather coords -> refpoint[300,4]
 *     -> Graph B -> boxes[300,4] (cxcywh, 0..1), logits[300,91]
 *     -> sigmoid -> threshold -> cxcywh->xyxy -> per-class NMS
 * </pre>
 */
public final class RfDetrNanoDetector implements AiObjectDetector {

    private static final String TAG = "RfDetrNano";

    private static final int INPUT_SIZE = 384;
    private static final int NUM_QUERIES = 576;
    private static final int TOP_K = 300;
    private static final int NUM_CLASSES = 91;
    private static final int EMBED_DIM = 256;

    /** Class 0 is the COCO "unused" slot; a detection must have a real category. */
    private static final int BACKGROUND_CLASS = 0;

    /**
     * Score threshold. 0.45 matches the reference configuration for this model family and
     * is in line with the 0.5 default used by production on-device detectors. The old
     * 0.20 threshold was the main reason for mislabelled objects.
     */
    private static final float SCORE_THRESHOLD = 0.45f;
    /** Per-class NMS overlap, as recommended by the model authors. */
    private static final float NMS_IOU_THRESHOLD = 0.60f;
    private static final int MAX_DETECTIONS = 12;
    private static final float MIN_BOX_SIDE = 0.003f;

    private static final float[] IMAGENET_MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] IMAGENET_STD = {0.229f, 0.224f, 0.225f};

    /**
     * COCO category names indexed by category id (91 slots, id 0 unused).
     * These are the model's own labels; nothing here is invented or remapped.
     */
    private static final String[] COCO_LABELS = {
            "__unused__", "person", "bicycle", "car", "motorcycle", "airplane", "bus",
            "train", "truck", "boat", "traffic light", "fire hydrant", "__unused__",
            "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep",
            "cow", "elephant", "bear", "zebra", "giraffe", "__unused__", "backpack",
            "umbrella", "__unused__", "__unused__", "handbag", "tie", "suitcase", "frisbee",
            "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove",
            "skateboard", "surfboard", "tennis racket", "bottle", "__unused__", "wine glass",
            "cup", "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
            "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
            "potted plant", "bed", "__unused__", "dining table", "__unused__", "__unused__",
            "toilet", "__unused__", "tv", "laptop", "mouse", "remote", "keyboard",
            "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator",
            "__unused__", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
            "toothbrush"
    };

    /** Classes that count as "person" for security purposes. */
    private static final String COARSE_PERSON = "PERSON";
    /** Classes that count as vehicles. */
    private static final List<String> VEHICLE_LABELS = Arrays.asList(
            "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat");
    /** Classes that count as pets. The forensics categories use PET, not ANIMAL. */
    private static final List<String> PET_LABELS = Arrays.asList(
            "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra",
            "giraffe");

    @Nullable
    private final CompiledModel graphA;
    @Nullable
    private final CompiledModel graphB;

    /** Non-null when this detector could not be created; shown to the user. */
    @Nullable
    private final String unavailableReason;

    /** True after {@link #close()} released the native graphs. */
    private volatile boolean closed;
    /** Rate limits the per-stage timing line to one every few seconds. */
    private volatile long lastStageLogMs;

    // Slot indices resolved by tensor size, because the converter's slot order is arbitrary.
    private int aIn = -1;
    private int aOutClass = -1;
    private int aOutCoord = -1;
    private int aOutMemory = -1;
    private int bInMemory = -1;
    private int bInRefpoint = -1;
    private int bOutBoxes = -1;
    private int bOutLogits = -1;

    // Persistent tensor buffers. Reallocating these for every inference churns GPU
    // memory and was a measurable source of jank during long recordings.
    @Nullable
    private List<TensorBuffer> aInputs;
    @Nullable
    private List<TensorBuffer> aOutputs;
    @Nullable
    private List<TensorBuffer> bInputs;
    @Nullable
    private List<TensorBuffer> bOutputs;

    // Reusable buffers to avoid per-frame allocation.
    private final float[] inputChw = new float[3 * INPUT_SIZE * INPUT_SIZE];
    private final float[] classScores = new float[NUM_QUERIES * NUM_CLASSES];
    private final float[] coords = new float[NUM_QUERIES * 4];
    private final float[] refpoint = new float[TOP_K * 4];
    private final float[] memory = new float[NUM_QUERIES * EMBED_DIM];
    private final float[] boxes = new float[TOP_K * 4];
    private final float[] logits = new float[TOP_K * NUM_CLASSES];
    private final float[] queryBest = new float[NUM_QUERIES];
    private final boolean[] queryUsed = new boolean[NUM_QUERIES];

    public RfDetrNanoDetector(Context context) {
        CompiledModel a = null;
        CompiledModel b = null;
        String reason = null;
        List<TensorBuffer> aIn = null;
        List<TensorBuffer> aOut = null;
        List<TensorBuffer> bIn = null;
        List<TensorBuffer> bOut = null;
        try {
            // The graphs are downloadable assets. When they ship inside the APK this
            // resolves to the bundled copies; when they have been downloaded it resolves
            // to files in app storage. CompiledModel.create takes an absolute path, which
            // is a documented overload, so no AssetManager indirection is needed.
            java.io.File graphAFile = com.fadcam.assets.AssetModels.graphFile(
                    context, com.fadcam.assets.AssetModels.GRAPH_A);
            java.io.File graphBFile = com.fadcam.assets.AssetModels.graphFile(
                    context, com.fadcam.assets.AssetModels.GRAPH_B);
            if (graphAFile == null || graphBFile == null) {
                // Missing models are a normal, recoverable state, not a crash. The caller
                // renders a download banner from this reason.
                throw new com.fadcam.assets.AssetException(
                        com.fadcam.assets.Asset.MODELS,
                        "detection models are not downloaded yet");
            }
            a = CompiledModel.Companion.create(graphAFile.getAbsolutePath(),
                    gpuOptions(context, "rfdetr_nano_graph_a_1"));
            b = CompiledModel.Companion.create(graphBFile.getAbsolutePath(),
                    gpuOptions(context, "rfdetr_nano_graph_b_1"));
            resolveSlots(a, b);
            aIn = a.createInputBuffers();
            aOut = a.createOutputBuffers();
            bIn = b.createInputBuffers();
            bOut = b.createOutputBuffers();
        } catch (Throwable t) {
            // No silent downgrade. The caller surfaces this to the user.
            FLog.e(TAG, "RF-DETR Nano could not initialise on the GPU", t);
            reason = describeFailure(t);
            closeAll(aIn);
            closeAll(aOut);
            closeAll(bIn);
            closeAll(bOut);
            releaseQuietly(a);
            releaseQuietly(b);
            a = null;
            b = null;
            aIn = null;
            aOut = null;
            bIn = null;
            bOut = null;
        }
        this.graphA = a;
        this.graphB = b;
        this.aInputs = aIn;
        this.aOutputs = aOut;
        this.bInputs = bIn;
        this.bOutputs = bOut;
        this.unavailableReason = reason;
    }

    /**
     * GPU options tuned for a camera app:
     *
     * <ul>
     *   <li><b>Program cache</b> — the compiled GPU program is persisted, so a later
     *       process start restores it instead of recompiling. Requires the directory,
     *       a stable per-model key, and the toggle together; any one missing disables
     *       caching silently. {@code codeCacheDir} is the documented location and is
     *       excluded from backups.</li>
     *   <li><b>Priority</b> — left at the engine default. LOW was measurably
     *       slower here (224 ms per inference against ~100 ms): it lowers the GPU
     *       clock for the whole context instead of merely yielding, which hurt the
     *       camera pipeline it was meant to protect.</li>
     *   <li><b>FP16</b> — the model ships as fp16 weights, and FP16 is what the
     *       accelerator picks by default on capable hardware. Setting it explicitly
     *       keeps behaviour identical across devices.</li>
     * </ul>
     *
     * <p>All other parameters stay null, which means "engine default": LiteRT omits
     * null entries from the option map rather than treating them as false.
     */
    private static CompiledModel.Options gpuOptions(Context context, String modelCacheKey) {
        CompiledModel.Options options = new CompiledModel.Options(Accelerator.GPU);
        File dir = context.getCodeCacheDir();
        if (dir == null || (!dir.exists() && !dir.mkdirs())) {
            // Caching is an optimisation. Without a writable directory the model still
            // runs, it just recompiles on each process start.
            FLog.w(TAG, "GPU program cache directory unavailable; running without cache");
            return options;
        }
        options.setGpuOptions(new CompiledModel.GpuOptions(
                /* constantTensorSharing               */ null,
                /* infiniteFloatCapping                */ null,
                /* allowSrcQuantizedFcConvOps          */ null,
                /* precision                           */ CompiledModel.GpuOptions.Precision.FP16,
                /* bufferStorageType                   */ null,
                /* preferTextureWeights                */ null,
                /* serializationDir                    */ dir.getAbsolutePath(),
                /* modelCacheKey                       */ modelCacheKey,
                /* serializeProgramCache               */ Boolean.TRUE,
                /* serializeExternalTensors            */ Boolean.TRUE,
                /* externalTensorsMode                 */ null,
                /* externalTensorPattern               */ null,
                /* backend                             */ null,
                /* priority                            */ null,
                /* numStepsOfCommandBufferPreparations */ null));
        return options;
    }

    /** True when the GPU could not execute the model; the message is user-facing. */
    @Override
    @Nullable
    public String unavailableReason() {
        return unavailableReason;
    }

    private static String describeFailure(Throwable t) {
        String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        return "Object detection needs a GPU this device cannot provide for the "
                + "RF-DETR model (" + msg + "). Motion detection still works, but objects "
                + "and people cannot be recognised on this phone.";
    }

    private static void releaseQuietly(@Nullable CompiledModel m) {
        if (m == null) {
            return;
        }
        try {
            m.close();
        } catch (Throwable ignored) {
        }
    }

    /** Maps the arbitrary converter slot order onto named logical slots by float count. */
    private void resolveSlots(CompiledModel a, CompiledModel b) throws LiteRtException {
        List<TensorBuffer> tmpAIn = a.createInputBuffers();
        List<TensorBuffer> tmpAOut = a.createOutputBuffers();
        List<TensorBuffer> tmpBIn = b.createInputBuffers();
        List<TensorBuffer> tmpBOut = b.createOutputBuffers();
        List<Integer> aInSizes = sizesOf(tmpAIn);
        List<Integer> aOutSizes = sizesOf(tmpAOut);
        List<Integer> bInSizes = sizesOf(tmpBIn);
        List<Integer> bOutSizes = sizesOf(tmpBOut);
        closeAll(tmpAIn);
        closeAll(tmpAOut);
        closeAll(tmpBIn);
        closeAll(tmpBOut);

        aIn = indexOfSize(aInSizes, 3 * INPUT_SIZE * INPUT_SIZE);
        aOutClass = indexOfSize(aOutSizes, NUM_QUERIES * NUM_CLASSES);
        aOutCoord = indexOfSize(aOutSizes, NUM_QUERIES * 4);
        aOutMemory = indexOfSize(aOutSizes, NUM_QUERIES * EMBED_DIM);

        bInMemory = indexOfSize(bInSizes, NUM_QUERIES * EMBED_DIM);
        bInRefpoint = indexOfSize(bInSizes, TOP_K * 4);
        bOutBoxes = indexOfSize(bOutSizes, TOP_K * 4);
        bOutLogits = indexOfSize(bOutSizes, TOP_K * NUM_CLASSES);

        if (aIn < 0 || aOutClass < 0 || aOutCoord < 0 || aOutMemory < 0
                || bInMemory < 0 || bInRefpoint < 0 || bOutBoxes < 0 || bOutLogits < 0) {
            throw new IllegalStateException("Unexpected RF-DETR tensor layout"
                    + " A_in=" + aInSizes + " A_out=" + aOutSizes
                    + " B_in=" + bInSizes + " B_out=" + bOutSizes);
        }
        FLog.i(TAG, "RF-DETR Nano ready on GPU: A_in=" + aIn
                + " A_out=" + aOutSizes + " B_in=" + bInSizes + " B_out=" + bOutSizes);
    }

    /**
     * Element count of each buffer. Every tensor in this model is FP32, and the buffer is
     * allocated at creation, so reading it back yields the exact element count. This is
     * only done once at init.
     */
    private static List<Integer> sizesOf(List<TensorBuffer> buffers) throws LiteRtException {
        List<Integer> out = new ArrayList<>(buffers.size());
        for (TensorBuffer buf : buffers) {
            try {
                out.add(buf.readFloat().length);
            } catch (Throwable t) {
                out.add(-1);
            }
        }
        return out;
    }

    private static int indexOfSize(List<Integer> sizes, int expected) {
        for (int i = 0; i < sizes.size(); i++) {
            if (sizes.get(i) != null && sizes.get(i) == expected) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean isAvailable() {
        return !closed
                && graphA != null && graphB != null
                && aInputs != null && aOutputs != null
                && bInputs != null && bOutputs != null;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeAll(aInputs);
        closeAll(aOutputs);
        closeAll(bInputs);
        closeAll(bOutputs);
        aInputs = null;
        aOutputs = null;
        bInputs = null;
        bOutputs = null;
        releaseQuietly(graphA);
        releaseQuietly(graphB);
    }

    @Override
    public List<DetectionResult> detect(FramePacket packet) {
        if (!isAvailable() || packet == null) {
            return Collections.emptyList();
        }
        long startedMs = android.os.SystemClock.elapsedRealtime();
        try {
            long t0 = android.os.SystemClock.elapsedRealtime();
            preprocess(packet);
            long t1 = android.os.SystemClock.elapsedRealtime();
            runGraphA();
            long t2 = android.os.SystemClock.elapsedRealtime();
            selectTopK();
            long t3 = android.os.SystemClock.elapsedRealtime();
            runGraphB();
            long t4 = android.os.SystemClock.elapsedRealtime();
            List<DetectionResult> results = decode(packet.rotationDegrees);
            long t5 = android.os.SystemClock.elapsedRealtime();
            long totalMs = t5 - startedMs;
            if (totalMs > 400L) {
                FLog.w(TAG, "Slow inference: " + totalMs + "ms (GPU may be under load)");
            } else if (stageLogDue()) {
                FLog.i(TAG, "Inference " + totalMs + "ms (" + results.size() + " dets)"
                        + " prep=" + (t1 - t0) + " graphA=" + (t2 - t1)
                        + " select=" + (t3 - t2) + " graphB=" + (t4 - t3)
                        + " decode=" + (t5 - t4));
            }
            return results;
        } catch (Throwable t) {
            FLog.e(TAG, "RF-DETR inference failed", t);
            return Collections.emptyList();
        }
    }

    // ── Preprocessing ────────────────────────────────────────────────────────

    /**
     * YUV_420_888 -> RGB -> 384x384 NCHW float with ImageNet normalisation.
     *
     * <p>The frame is sampled through a rotation that makes it upright. The model was
     * trained on upright photographs; feeding it a sideways sensor frame makes a
     * person look like an arbitrary texture, which is why a face could come back as
     * "skateboard". Sampling the rotation directly avoids a separate rotate pass.
     */
    private void preprocess(FramePacket p) {
        final int srcW = p.width;
        final int srcH = p.height;
        final int plane = INPUT_SIZE * INPUT_SIZE;
        final int rotation = p.rotationDegrees;

        for (int y = 0; y < INPUT_SIZE; y++) {
            for (int x = 0; x < INPUT_SIZE; x++) {
                // Destination is the upright image. Map each destination pixel back
                // into the source (sensor) frame. These are the exact inverses of
                // the box mapping in GLWatermarkRenderer#mapDetectionBoxToContent.
                int ux;
                int uy;
                switch (rotation) {
                    case 90:
                        ux = y;
                        uy = INPUT_SIZE - 1 - x;
                        break;
                    case 180:
                        ux = INPUT_SIZE - 1 - x;
                        uy = INPUT_SIZE - 1 - y;
                        break;
                    case 270:
                        ux = INPUT_SIZE - 1 - y;
                        uy = x;
                        break;
                    default:
                        ux = x;
                        uy = y;
                        break;
                }
                int srcX = Math.min(srcW - 1, ux * srcW / INPUT_SIZE);
                int srcY = Math.min(srcH - 1, uy * srcH / INPUT_SIZE);

                int yRow = srcY * p.yRowStride;
                int yIdx = yRow + srcX * p.yPixelStride;
                int uvRow = (srcY >> 1) * p.uvRowStride;
                int uvCol = (srcX >> 1) * p.uvPixelStride;

                int yv = p.y[yIdx] & 0xFF;
                int uv = p.u[Math.min(p.u.length - 1, uvRow + uvCol)] & 0xFF;
                int vv = p.v[Math.min(p.v.length - 1, uvRow + uvCol)] & 0xFF;

                // BT.601 full-range YUV -> RGB
                int c = yv;
                int d = uv - 128;
                int e = vv - 128;
                int r = clamp255((298 * c + 409 * e + 128) >> 8);
                int g = clamp255((298 * c - 100 * d - 208 * e + 128) >> 8);
                int b = clamp255((298 * c + 516 * d + 128) >> 8);

                int dst = y * INPUT_SIZE + x;
                inputChw[dst] = (r / 255f - IMAGENET_MEAN[0]) / IMAGENET_STD[0];
                inputChw[plane + dst] = (g / 255f - IMAGENET_MEAN[1]) / IMAGENET_STD[1];
                inputChw[2 * plane + dst] = (b / 255f - IMAGENET_MEAN[2]) / IMAGENET_STD[2];
            }
        }
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    // ── Graph A + host step ──────────────────────────────────────────────────

    private void runGraphA() throws LiteRtException {
        aInputs.get(aIn).writeFloat(inputChw);
        graphA.run(aInputs, aOutputs);
        System.arraycopy(aOutputs.get(aOutClass).readFloat(), 0, classScores, 0, classScores.length);
        System.arraycopy(aOutputs.get(aOutCoord).readFloat(), 0, coords, 0, coords.length);
        System.arraycopy(aOutputs.get(aOutMemory).readFloat(), 0, memory, 0, memory.length);
    }

    /**
     * Top-K queries by their best class score; gather their reference boxes.
     * Primitive selection sort: no boxing and no per-frame allocation. 576x300
     * comparisons is well under a millisecond.
     */
    private void selectTopK() {
        for (int q = 0; q < NUM_QUERIES; q++) {
            float max = -Float.MAX_VALUE;
            int base = q * NUM_CLASSES;
            for (int c = 1; c < NUM_CLASSES; c++) {
                float v = classScores[base + c];
                if (v > max) {
                    max = v;
                }
            }
            queryBest[q] = max;
            queryUsed[q] = false;
        }
        for (int i = 0; i < TOP_K; i++) {
            int bestQ = -1;
            float bestScore = -Float.MAX_VALUE;
            for (int q = 0; q < NUM_QUERIES; q++) {
                if (queryUsed[q]) {
                    continue;
                }
                if (queryBest[q] > bestScore) {
                    bestScore = queryBest[q];
                    bestQ = q;
                }
            }
            if (bestQ < 0) {
                bestQ = 0;
            }
            queryUsed[bestQ] = true;
            System.arraycopy(coords, bestQ * 4, refpoint, i * 4, 4);
        }
    }

    // ── Graph B + decode ─────────────────────────────────────────────────────

    private void runGraphB() throws LiteRtException {
        bInputs.get(bInMemory).writeFloat(memory);
        bInputs.get(bInRefpoint).writeFloat(refpoint);
        graphB.run(bInputs, bOutputs);
        System.arraycopy(bOutputs.get(bOutBoxes).readFloat(), 0, boxes, 0, boxes.length);
        System.arraycopy(bOutputs.get(bOutLogits).readFloat(), 0, logits, 0, logits.length);
    }

    private List<DetectionResult> decode(int rotationDegrees) {
        List<DetectionResult> candidates = new ArrayList<>();
        for (int q = 0; q < TOP_K; q++) {
            int base = q * NUM_CLASSES;
            int bestClass = BACKGROUND_CLASS;
            float bestLogit = -Float.MAX_VALUE;
            for (int c = 1; c < NUM_CLASSES; c++) {
                float v = logits[base + c];
                if (v > bestLogit) {
                    bestLogit = v;
                    bestClass = c;
                }
            }
            if (bestClass == BACKGROUND_CLASS) {
                continue;
            }
            float score = sigmoid(bestLogit);
            if (score < SCORE_THRESHOLD) {
                continue;
            }
            float cx = boxes[q * 4];
            float cy = boxes[q * 4 + 1];
            float w = boxes[q * 4 + 2];
            float h = boxes[q * 4 + 3];
            if (w <= MIN_BOX_SIDE || h <= MIN_BOX_SIDE) {
                continue;
            }
            String label = labelFor(bestClass);
            if (label == null) {
                continue;
            }
            // Boxes are reported in the UPRIGHT frame: exactly the orientation the
            // model analysed, which is the orientation of the recorded video and of
            // the evidence JPEG. Nothing downstream rotates them again, so there is no
            // second transform that can disagree with this one.
            candidates.add(new DetectionResult(
                    bestClass,
                    label,
                    coarseTypeFor(label),
                    score,
                    clamp01(cx),
                    clamp01(cy),
                    clamp01(w),
                    clamp01(h)));
        }
        return nmsPerClass(candidates);
    }

    /** DETR proposes one box per query; NMS removes duplicates of the same class. */
    private boolean stageLogDue() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastStageLogMs < 3000L) {
            return false;
        }
        lastStageLogMs = now;
        return true;
    }

    private static float sigmoid(float x) {
        return (float) (1.0 / (1.0 + Math.exp(-x)));
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    @Nullable
    private static String labelFor(int classId) {
        if (classId <= 0 || classId >= COCO_LABELS.length) {
            return null;
        }
        String label = COCO_LABELS[classId];
        return "__unused__".equals(label) ? null : label;
    }

    private static String coarseTypeFor(String label) {
        if ("person".equals(label)) {
            return COARSE_PERSON;
        }
        if (VEHICLE_LABELS.contains(label)) {
            return "VEHICLE";
        }
        if (PET_LABELS.contains(label)) {
            return "PET";
        }
        return "OBJECT";
    }

    private static List<DetectionResult> nmsPerClass(List<DetectionResult> in) {
        List<DetectionResult> sorted = new ArrayList<>(in);
        sorted.sort((l, r) -> Float.compare(r.confidence, l.confidence));
        List<DetectionResult> kept = new ArrayList<>();
        for (DetectionResult candidate : sorted) {
            boolean suppressed = false;
            for (DetectionResult chosen : kept) {
                if (chosen.classId != candidate.classId) {
                    continue;
                }
                if (iou(candidate, chosen) > NMS_IOU_THRESHOLD) {
                    suppressed = true;
                    break;
                }
            }
            if (!suppressed) {
                kept.add(candidate);
                if (kept.size() >= MAX_DETECTIONS) {
                    break;
                }
            }
        }
        return kept;
    }

    private static float iou(DetectionResult a, DetectionResult b) {
        float ax0 = a.centerX - a.width / 2f;
        float ax1 = a.centerX + a.width / 2f;
        float ay0 = a.centerY - a.height / 2f;
        float ay1 = a.centerY + a.height / 2f;
        float bx0 = b.centerX - b.width / 2f;
        float bx1 = b.centerX + b.width / 2f;
        float by0 = b.centerY - b.height / 2f;
        float by1 = b.centerY + b.height / 2f;
        float ix = Math.max(0f, Math.min(ax1, bx1) - Math.max(ax0, bx0));
        float iy = Math.max(0f, Math.min(ay1, by1) - Math.max(ay0, by0));
        float inter = ix * iy;
        if (inter <= 0f) {
            return 0f;
        }
        float union = a.width * a.height + b.width * b.height - inter;
        return union <= 0f ? 0f : inter / union;
    }

    private static void closeAll(List<TensorBuffer> buffers) {
        // Called from the constructor's failure path, where any of these is still null
        // because the failure happened before it was created. Iterating a null list here
        // replaced the real error with a NullPointerException and hid the actual cause.
        if (buffers == null) {
            return;
        }
        for (TensorBuffer buf : buffers) {
            if (buf == null) {
                continue;
            }
            try {
                buf.close();
            } catch (Throwable ignored) {
            }
        }
    }

    // ── AiObjectDetector contract ────────────────────────────────────────────

    @Override
    public float bestPersonConfidence(List<DetectionResult> detections) {
        float best = 0f;
        if (detections == null) {
            return best;
        }
        for (DetectionResult d : detections) {
            if (d != null && COARSE_PERSON.equals(d.coarseType)) {
                best = Math.max(best, d.confidence);
            }
        }
        return best;
    }

    @Override
    public boolean hasPerson(List<DetectionResult> detections) {
        return bestPersonConfidence(detections) > 0f;
    }

    @Override
    public DetectionResult choosePrimary(List<DetectionResult> detections) {
        if (detections == null || detections.isEmpty()) {
            return null;
        }
        DetectionResult best = null;
        for (DetectionResult d : detections) {
            if (d == null) {
                continue;
            }
            if (best == null || d.confidence > best.confidence) {
                best = d;
            }
        }
        return best;
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "RfDetrNanoDetector(available=%s)", isAvailable());
    }
}
