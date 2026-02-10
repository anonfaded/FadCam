package com.fadcam.ui.custom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Audio waveform visualization for the video player progress bar.
 *
 * <p>Renders mirror-style waveform bars (upper + lower, split by a centerline),
 * matching the same visual style used in the Faditor Mini editor timeline.</p>
 *
 * <ul>
 *   <li>Upper bars use the primary wave colour, lower bars a dimmed version.</li>
 *   <li>Bars before the current playback position are drawn in the "played" colour.</li>
 *   <li>A thin centerline separates upper and lower halves.</li>
 *   <li>Amplitude data comes from real audio analysis via {@link MediaExtractor}.</li>
 * </ul>
 */
public class AudioWaveformView extends View {

    private static final String TAG = "AudioWaveformView";

    // ── Colours (matching Faditor Mini editor timeline) ──────────────
    /** Primary upper-bar colour (green, matching editor). */
    private static final int COLOR_WAVE         = 0xFF4CAF50;
    /** Dimmed lower-bar colour (alpha-reduced green). */
    private static final int COLOR_WAVE_DIM     = 0x404CAF50;
    /** Played upper-bar colour (brighter accent). */
    private static final int COLOR_WAVE_PLAYED      = 0xFFFFFFFF;
    /** Played lower-bar colour (semi-transparent white). */
    private static final int COLOR_WAVE_PLAYED_DIM  = 0x60FFFFFF;
    /** Centerline colour (subtle green). */
    private static final int COLOR_CENTERLINE   = 0x334CAF50;

    // ── Layout constants ─────────────────────────────────────────────
    /** Bar width in dp (matches editor's 3dp). */
    private static final float BAR_WIDTH_DP = 3f;
    /** Gap between bars in dp (matches editor's 0.5dp). */
    private static final float BAR_GAP_DP   = 0.5f;
    /** Power curve applied to amplitude for better dynamics. */
    private static final float AMPLITUDE_POWER = 0.7f;

    // ── Paints ───────────────────────────────────────────────────────
    private final Paint wavePaint        = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint waveMirrorPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playedWavePaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playedWaveMirrorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerlinePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float progress = 0f;

    // ── Audio analysis fields ────────────────────────────────────────
    private final List<Float> realWaveformData = new ArrayList<>();
    private volatile boolean isAnalyzingAudio = false;
    private final int waveformPoints = 120;
    private final ExecutorService audioAnalysisExecutor = Executors.newSingleThreadExecutor();
    private Uri currentVideoUri;

    // ── Constructors ─────────────────────────────────────────────────

    public AudioWaveformView(Context context) {
        super(context);
        init();
    }

    public AudioWaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        float density = getContext().getResources().getDisplayMetrics().density;

        // Upper bars (unplayed)
        wavePaint.setColor(COLOR_WAVE);
        wavePaint.setStyle(Paint.Style.FILL);
        wavePaint.setStrokeCap(Paint.Cap.ROUND);

        // Lower / mirror bars (unplayed)
        waveMirrorPaint.setColor(COLOR_WAVE_DIM);
        waveMirrorPaint.setStyle(Paint.Style.FILL);
        waveMirrorPaint.setStrokeCap(Paint.Cap.ROUND);

        // Upper bars (played)
        playedWavePaint.setColor(COLOR_WAVE_PLAYED);
        playedWavePaint.setStyle(Paint.Style.FILL);
        playedWavePaint.setStrokeCap(Paint.Cap.ROUND);

        // Lower / mirror bars (played)
        playedWaveMirrorPaint.setColor(COLOR_WAVE_PLAYED_DIM);
        playedWaveMirrorPaint.setStyle(Paint.Style.FILL);
        playedWaveMirrorPaint.setStrokeCap(Paint.Cap.ROUND);

        // Centerline
        centerlinePaint.setColor(COLOR_CENTERLINE);
        centerlinePaint.setStyle(Paint.Style.STROKE);
        centerlinePaint.setStrokeWidth(1f * density);
    }

    /**
     * Set the current playback progress (0.0 – 1.0).
     */
    public void setProgress(float progress) {
        this.progress = Math.max(0f, Math.min(1f, progress));
        invalidate();
    }

    /**
     * Analyze real audio from video file using MediaExtractor.
     */
    public void analyzeAudioFromVideo(Uri videoUri) {
        if (videoUri == null || videoUri.equals(currentVideoUri)) {
            return;
        }

        this.currentVideoUri = videoUri;

        if (isAnalyzingAudio) {
            stopAudioAnalysis();
        }

        isAnalyzingAudio = true;
        realWaveformData.clear();

        // Initialize with minimal values while analysing
        for (int i = 0; i < waveformPoints; i++) {
            realWaveformData.add(0.02f);
        }

        audioAnalysisExecutor.submit(() -> extractRealAudioData(videoUri));
    }

    /**
     * Extract real audio amplitude data by decoding audio via {@link MediaCodec}.
     *
     * <p>Matches FaditorEditorActivity's approach:
     * <ol>
     *   <li>Decode full audio track to PCM using MediaCodec.</li>
     *   <li>Downsample to {@link #waveformPoints} bins (average of abs(sample)).</li>
     *   <li>Normalise each bin to 0–1 range (avg / 32768).</li>
     * </ol>
     * The resulting values are drawn with a 0.7 power curve in {@link #onDraw}.
     */
    @SuppressWarnings("deprecation")
    private void extractRealAudioData(Uri videoUri) {
        Log.i(TAG, "═══ WAVEFORM ANALYSIS START ═══");
        Log.i(TAG, "URI: " + videoUri);
        Log.i(TAG, "URI scheme: " + videoUri.getScheme());

        MediaExtractor extractor = null;
        MediaCodec codec = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(getContext(), videoUri, null);
            Log.d(TAG, "MediaExtractor: track count = " + extractor.getTrackCount());

            // Find audio track
            int audioTrackIndex = -1;
            MediaFormat audioFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                Log.d(TAG, "  Track " + i + ": mime=" + mime);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIndex = i;
                    audioFormat = format;
                    break;
                }
            }

            if (audioTrackIndex == -1 || audioFormat == null) {
                Log.w(TAG, "No audio track found — showing silence");
                postSilence();
                return;
            }

            String mime = audioFormat.getString(MediaFormat.KEY_MIME);
            if (mime == null) {
                Log.w(TAG, "Audio mime is null — showing silence");
                postSilence();
                return;
            }

            Log.d(TAG, "Selected audio track " + audioTrackIndex + ": " + mime);
            extractor.selectTrack(audioTrackIndex);

            // ─── Decode audio to PCM using MediaCodec ────────────────
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(audioFormat, null, null, 0);
            codec.start();

            ByteBuffer[] inputBuffers = codec.getInputBuffers();
            ByteBuffer[] outputBuffers = codec.getOutputBuffers();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            List<Short> allSamples = new ArrayList<>();
            boolean inputDone = false;
            boolean outputDone = false;

            while (!outputDone && isAnalyzingAudio) {
                // Feed encoded input
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(10_000);
                    if (inIdx >= 0) {
                        ByteBuffer buf = inputBuffers[inIdx];
                        int sampleSize = extractor.readSampleData(buf, 0);
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                // Drain decoded PCM output
                int outIdx = codec.dequeueOutputBuffer(info, 10_000);
                if (outIdx >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                    ByteBuffer outBuf = outputBuffers[outIdx];
                    outBuf.position(info.offset);
                    outBuf.limit(info.offset + info.size);
                    ShortBuffer shorts = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                    while (shorts.hasRemaining()) {
                        allSamples.add(shorts.get());
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                } else if (outIdx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outputBuffers = codec.getOutputBuffers();
                }
            }

            codec.stop();
            codec.release();
            codec = null;

            Log.d(TAG, "Decoded " + allSamples.size() + " PCM samples");

            if (allSamples.isEmpty()) {
                Log.w(TAG, "No PCM samples decoded — showing silence");
                postSilence();
                return;
            }

            // ─── Downsample to waveformPoints bins ───────────────────
            int totalSamples = allSamples.size();
            int samplesPerBin = Math.max(1, totalSamples / waveformPoints);
            int binCount = Math.min(waveformPoints, totalSamples);

            // Compute raw amplitude for every bin into a local array FIRST.
            // We must NOT post values progressively because normalisation
            // needs to see all bins before we publish them to the UI.
            float[] rawBins = new float[waveformPoints];

            for (int i = 0; i < binCount; i++) {
                long sum = 0;
                int start = i * samplesPerBin;
                int end = Math.min(start + samplesPerBin, totalSamples);
                for (int j = start; j < end; j++) {
                    sum += Math.abs(allSamples.get(j));
                }
                long avg = sum / (end - start);
                rawBins[i] = Math.min(1.0f, avg / 32768.0f);
            }
            // Remaining bins (short file) stay 0.0f by default.

            Log.i(TAG, "═══ WAVEFORM ANALYSIS COMPLETE ═══");
            Log.i(TAG, "  PCM samples: " + totalSamples + ", bins: " + binCount +
                  ", samplesPerBin: " + samplesPerBin);

            // Log pre-normalization range for debugging
            float rawMin = Float.MAX_VALUE, rawMax = Float.MIN_VALUE;
            for (int i = 0; i < binCount; i++) {
                if (rawBins[i] < rawMin) rawMin = rawBins[i];
                if (rawBins[i] > rawMax) rawMax = rawBins[i];
            }
            Log.i(TAG, "  Pre-norm amplitude range: [" + rawMin + " .. " + rawMax + "]");

            // Normalise so the loudest bin fills ~95% of bar height.
            float peak = rawMax;
            if (peak > 1e-6f) {
                float scale = 0.95f / peak;
                for (int i = 0; i < waveformPoints; i++) {
                    rawBins[i] = rawBins[i] * scale;
                }
                Log.i(TAG, "  normalizeWaveformData: peak=" + peak + ", scale=" + scale);
            } else {
                Log.d(TAG, "  normalizeWaveformData: peak ≈ 0 → all bins set to 0");
            }

            // Publish all bins to the UI in ONE post.
            post(() -> {
                for (int i = 0; i < waveformPoints && i < realWaveformData.size(); i++) {
                    realWaveformData.set(i, rawBins[i]);
                }
                invalidate();
            });

        } catch (Exception e) {
            Log.e(TAG, "Error extracting audio data", e);
            postSilence();
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) { }
                try { codec.release(); } catch (Exception ignored) { }
            }
            if (extractor != null) {
                try { extractor.release(); } catch (Exception ignored) { }
            }
            isAnalyzingAudio = false;
        }
    }

    /**
     * Fill wave data with silence values on the UI thread.
     */
    private void postSilence() {
        post(() -> {
            realWaveformData.clear();
            for (int i = 0; i < waveformPoints; i++) {
                realWaveformData.add(0.02f);
            }
            invalidate();
        });
    }

    /**
     * Attempts to reconstruct a real file system path from a SAF
     * {@code content://} URI on primary storage.
     *
     * <p>SAF URIs on primary storage often contain a colon-separated relative
     * path, e.g.
     * {@code content://...document/primary:Download/FadCam/file.mp4}
     * → {@code /storage/emulated/0/Download/FadCam/file.mp4}.
     *
     * @return the reconstructed absolute path if the file exists and is
     *         readable, or {@code null} otherwise.
     */
    @androidx.annotation.Nullable
    private String tryReconstructFilePath(Uri uri) {
        String path = uri.getPath();
        if (path == null || !path.contains(":")) return null;

        int lastColonIndex = path.lastIndexOf(':');
        if (lastColonIndex < 0 || lastColonIndex >= path.length() - 1) return null;

        String relativePath = path.substring(lastColonIndex + 1);
        String reconstructed = "/storage/emulated/0/" + relativePath;
        java.io.File file = new java.io.File(reconstructed);
        if (file.exists() && file.canRead()) {
            return reconstructed;
        }
        return null;
    }

    /**
     * Stop any in-progress audio analysis.
     */
    public void stopAudioAnalysis() {
        isAnalyzingAudio = false;
    }

    /**
     * Release all resources.
     */
    public void cleanup() {
        stopAudioAnalysis();
        audioAnalysisExecutor.shutdown();
        realWaveformData.clear();
        currentVideoUri = null;
    }

    // ── Drawing ──────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (realWaveformData.isEmpty()) return;

        int width  = getWidth();
        int height = getHeight();
        float density = getContext().getResources().getDisplayMetrics().density;

        float centerY = height / 2f;
        float topHalf  = centerY - 1f * density;   // available height above centre
        float botHalf  = centerY - 1f * density;   // available height below centre (slightly smaller mirror)

        // Bar geometry (dp → px)
        float barW = BAR_WIDTH_DP * density;
        float gap  = BAR_GAP_DP * density;

        // Slider thumb padding compensation
        float thumbPadding = 6f * density;
        float startX = thumbPadding;
        float endX   = width - thumbPadding;
        float totalWidth = endX - startX;

        int barCount = Math.max(1, (int) (totalWidth / (barW + gap)));
        float step   = totalWidth / barCount;

        // Draw centerline across the full width
        canvas.drawLine(startX, centerY, endX, centerY, centerlinePaint);

        // Played-position boundary
        float playedX = startX + totalWidth * progress;

        // Minimum bar height (always visible)
        float minBar = 1f * density;

        RectF barRect = new RectF();
        float barRadius = barW * 0.4f;

        for (int j = 0; j < barCount; j++) {
            // Map bar index to waveform data index
            float samplePos = (j / (float) barCount) * realWaveformData.size();
            int si  = Math.min((int) samplePos, realWaveformData.size() - 1);
            int si2 = Math.min(si + 1, realWaveformData.size() - 1);
            float frac = samplePos - si;

            float amplitude = (realWaveformData.get(si) * (1f - frac))
                    + (realWaveformData.get(si2) * frac);

            // Apply power curve for better dynamic range (same as editor)
            amplitude = (float) Math.pow(amplitude, AMPLITUDE_POWER);

            float topH = Math.max(minBar, amplitude * topHalf);
            float botH = Math.max(minBar, amplitude * botHalf * 0.85f);

            float barX = startX + j * step;
            boolean played = (barX + barW / 2f) <= playedX;

            // Upper bar
            barRect.set(barX, centerY - topH, barX + barW, centerY);
            canvas.drawRoundRect(barRect, barRadius, barRadius,
                    played ? playedWavePaint : wavePaint);

            // Mirror (lower) bar
            barRect.set(barX, centerY, barX + barW, centerY + botH);
            canvas.drawRoundRect(barRect, barRadius, barRadius,
                    played ? playedWaveMirrorPaint : waveMirrorPaint);
        }
    }
}
