package com.fadcam.ui.faditor.util;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Utility to extract audio from a video file and generate waveform data.
 *
 * <p>Extraction produces an AAC (.m4a) file in the app's cache directory.
 * Waveform generation decodes the audio to PCM and downsamples to an amplitude array.</p>
 */
public class AudioExtractor {

    private static final String TAG = "AudioExtractor";
    private static final int WAVEFORM_SAMPLES = 200;

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    /**
     * Callback for extraction operations.
     */
    public interface ExtractionCallback {
        /** Called on success with the output file and its duration in ms. */
        void onSuccess(@NonNull File outputFile, long durationMs);
        /** Called on failure. */
        void onError(@NonNull Exception error);
    }

    /**
     * Callback for waveform generation.
     */
    public interface WaveformCallback {
        /** Called with the downsampled amplitude array (0–255 per sample). */
        void onWaveformReady(@NonNull int[] waveform);
        /** Called on failure. */
        void onError(@NonNull Exception error);
    }

    public AudioExtractor(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Extract the audio track from a video/audio file to a standalone .m4a file.
     * Runs on a background thread.
     *
     * @param sourceUri the video/audio URI
     * @param callback  result callback (called on main thread)
     */
    public void extractAudio(@NonNull Uri sourceUri, @NonNull ExtractionCallback callback) {
        executor.execute(() -> {
            try {
                File output = doExtract(sourceUri);
                long durationMs = getAudioDuration(output);
                runOnMain(() -> callback.onSuccess(output, durationMs));
            } catch (Exception e) {
                Log.e(TAG, "Audio extraction failed", e);
                runOnMain(() -> callback.onError(e));
            }
        });
    }

    /**
     * Generate waveform amplitude data from an audio file.
     * Runs on a background thread.
     *
     * @param audioUri  the audio file URI
     * @param callback  result callback (called on main thread)
     */
    public void generateWaveform(@NonNull Uri audioUri, @NonNull WaveformCallback callback) {
        executor.execute(() -> {
            try {
                int[] waveform = doGenerateWaveform(audioUri);
                runOnMain(() -> callback.onWaveformReady(waveform));
            } catch (Exception e) {
                Log.e(TAG, "Waveform generation failed", e);
                runOnMain(() -> callback.onError(e));
            }
        });
    }

    /**
     * Shutdown the background executor. Call when no longer needed.
     */
    public void shutdown() {
        executor.shutdownNow();
    }

    // ── Internal extraction ──────────────────────────────────────────

    @NonNull
    private File doExtract(@NonNull Uri sourceUri) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, sourceUri, null);

            // Find audio track
            int audioTrackIndex = -1;
            MediaFormat audioFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIndex = i;
                    audioFormat = format;
                    break;
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null) {
                throw new IllegalStateException("No audio track found in source");
            }

            extractor.selectTrack(audioTrackIndex);

            // Create output file
            File cacheDir = new File(context.getCacheDir(), "faditor_audio");
            if (!cacheDir.exists()) cacheDir.mkdirs();
            File outputFile = new File(cacheDir,
                    "audio_" + System.currentTimeMillis() + ".m4a");

            // Mux audio track to output
            MediaMuxer muxer = new MediaMuxer(
                    outputFile.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            int dstTrackIndex = muxer.addTrack(audioFormat);
            muxer.start();

            ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024); // 1MB buffer
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            while (true) {
                int sampleSize = extractor.readSampleData(buffer, 0);
                if (sampleSize < 0) break;

                bufferInfo.offset = 0;
                bufferInfo.size = sampleSize;
                bufferInfo.presentationTimeUs = extractor.getSampleTime();
                bufferInfo.flags = extractor.getSampleFlags();

                muxer.writeSampleData(dstTrackIndex, buffer, bufferInfo);
                extractor.advance();
            }

            muxer.stop();
            muxer.release();

            Log.d(TAG, "Audio extracted to: " + outputFile.getAbsolutePath()
                    + " (" + outputFile.length() + " bytes)");
            return outputFile;

        } finally {
            extractor.release();
        }
    }

    /**
     * Get audio duration from a file using MediaExtractor.
     */
    private long getAudioDuration(@NonNull File file) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        return format.getLong(MediaFormat.KEY_DURATION) / 1000; // µs → ms
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get audio duration", e);
        } finally {
            extractor.release();
        }
        return 0;
    }

    // ── Internal waveform generation ─────────────────────────────────

    @NonNull
    private int[] doGenerateWaveform(@NonNull Uri audioUri) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(context, audioUri, null);

            // Find audio track
            int trackIndex = -1;
            MediaFormat inputFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    trackIndex = i;
                    inputFormat = format;
                    break;
                }
            }
            if (trackIndex < 0 || inputFormat == null) {
                // No audio — return flat waveform
                return new int[WAVEFORM_SAMPLES];
            }

            extractor.selectTrack(trackIndex);
            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            if (mime == null) return new int[WAVEFORM_SAMPLES];

            // Decode to PCM
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(inputFormat, null, null, 0);
            codec.start();

            // Collect all PCM samples
            List<Short> allSamples = new ArrayList<>();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            long timeoutUs = 10_000; // 10ms

            while (!outputDone) {
                // Feed input
                if (!inputDone) {
                    int inputBufIndex = codec.dequeueInputBuffer(timeoutUs);
                    if (inputBufIndex >= 0) {
                        ByteBuffer inputBuf = codec.getInputBuffer(inputBufIndex);
                        if (inputBuf != null) {
                            int sampleSize = extractor.readSampleData(inputBuf, 0);
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputBufIndex, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                long pts = extractor.getSampleTime();
                                codec.queueInputBuffer(inputBufIndex, 0, sampleSize, pts, 0);
                                extractor.advance();
                            }
                        }
                    }
                }

                // Drain output
                int outputBufIndex = codec.dequeueOutputBuffer(info, timeoutUs);
                if (outputBufIndex >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                    ByteBuffer outputBuf = codec.getOutputBuffer(outputBufIndex);
                    if (outputBuf != null && info.size > 0) {
                        // Read 16-bit PCM samples
                        outputBuf.position(info.offset);
                        outputBuf.limit(info.offset + info.size);
                        ShortBuffer shortBuf = outputBuf.asShortBuffer();
                        while (shortBuf.hasRemaining()) {
                            allSamples.add(shortBuf.get());
                        }
                    }
                    codec.releaseOutputBuffer(outputBufIndex, false);
                }
            }

            // Downsample to WAVEFORM_SAMPLES amplitude values
            return downsample(allSamples);

        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                try { codec.release(); } catch (Exception ignored) {}
            }
            extractor.release();
        }
    }

    /**
     * Downsample PCM samples to a fixed-size amplitude array.
     * Each output value is the peak absolute amplitude (0–255) in that window.
     */
    @NonNull
    private int[] downsample(@NonNull List<Short> samples) {
        int[] result = new int[WAVEFORM_SAMPLES];
        if (samples.isEmpty()) return result;

        int samplesPerBin = Math.max(1, samples.size() / WAVEFORM_SAMPLES);

        for (int i = 0; i < WAVEFORM_SAMPLES; i++) {
            int start = i * samples.size() / WAVEFORM_SAMPLES;
            int end = Math.min(start + samplesPerBin, samples.size());

            int peak = 0;
            for (int j = start; j < end; j++) {
                int abs = Math.abs(samples.get(j));
                if (abs > peak) peak = abs;
            }

            // Normalise from Short.MAX_VALUE range to 0–255
            result[i] = Math.min(255, (peak * 255) / 32768);
        }
        return result;
    }

    private void runOnMain(@NonNull Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }
}
