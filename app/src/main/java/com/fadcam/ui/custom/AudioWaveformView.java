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
    private final int waveformPoints = 20; // Ultra-reduced for sub-second analysis + lower visual density
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
     * Extract real audio amplitude data using seek-based chunk sampling.
     *
     * <p>Instead of decoding the entire audio track (slow for large files),
     * this seeks to evenly-spaced positions and decodes small ~50ms chunks.
     * Total decode time is constant (~6s of audio for 120 bins) regardless
     * of video duration, making waveform generation nearly instant.</p>
     *
     * <p>Falls back to streaming accumulation if duration is unavailable.</p>
     */
    private void extractRealAudioData(Uri videoUri) {
        long startTime = System.currentTimeMillis();
        Log.i(TAG, "═══ WAVEFORM ANALYSIS START ═══");
        Log.d(TAG, "Analyzing waveform for URI: " + videoUri);

        MediaExtractor extractor = null;
        MediaCodec codec = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(getContext(), videoUri, null);

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

            if (audioTrackIndex == -1 || audioFormat == null) {
                Log.w(TAG, "No audio track found — showing silence");
                postSilence();
                return;
            }

            String mime = audioFormat.getString(MediaFormat.KEY_MIME);
            if (mime == null) { postSilence(); return; }

            extractor.selectTrack(audioTrackIndex);

            long durationUs = audioFormat.containsKey(MediaFormat.KEY_DURATION)
                    ? audioFormat.getLong(MediaFormat.KEY_DURATION) : 0;
            int sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;

            Log.d(TAG, "Audio: " + mime + ", " + sampleRate + "Hz, " +
                    channels + "ch, duration=" + durationUs + "us");

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(audioFormat, null, null, 0);
            codec.start();

            float[] rawBins;
            if (durationUs > 0) {
                // ── FAST PATH: Seek-based chunk sampling ─────────────
                rawBins = extractSeekBased(extractor, codec, durationUs,
                        sampleRate, channels);
            } else {
                // ── FALLBACK: Stream accumulation (no ArrayList) ─────
                rawBins = extractStreamBased(extractor, codec,
                        sampleRate, channels);
            }

            codec.stop();
            codec.release();
            codec = null;

            if (rawBins == null) { postSilence(); return; }

            // Normalise so the loudest bin fills ~95% of bar height.
            float peak = 0;
            for (float v : rawBins) if (v > peak) peak = v;
            if (peak > 1e-6f) {
                float scale = 0.95f / peak;
                for (int i = 0; i < waveformPoints; i++) rawBins[i] *= scale;
            }

            long elapsedMs = System.currentTimeMillis() - startTime;
            Log.i(TAG, "═══ WAVEFORM ANALYSIS COMPLETE in " + elapsedMs + "ms ═══");

            final float[] finalBins = rawBins;
            post(() -> {
                for (int i = 0; i < waveformPoints && i < realWaveformData.size(); i++) {
                    realWaveformData.set(i, finalBins[i]);
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
     * Lightning-fast waveform extraction: analyze ONLY first 10 seconds.
     * 
     * <p>Strategy: Decode only the first 10 seconds of audio (enough for visual
     * representation), use 20 bins total, massive skip ratio. This gives sub-second
     * performance on ANY video length.</p>
     * 
     * <p>Expected: ~300-500ms regardless of video length.</p>
     */
    private float[] extractSeekBased(
            MediaExtractor extractor, MediaCodec codec,
            long durationUs, int sampleRate, int channels) {

        float[] bins = new float[waveformPoints];
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        
        // ONLY analyze first 10 seconds (or full video if shorter)
        long maxAnalysisDurationUs = Math.min(durationUs, 10_000_000L); // 10 seconds
        
        // Ultra-minimal samples per bin
        int targetSamplesPerBin = 100;
        
        for (int bin = 0; bin < waveformPoints && isAnalyzingAudio; bin++) {
            long targetUs = (long) ((double) bin / waveformPoints * maxAnalysisDurationUs);
            
            extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            codec.flush();
            
            long sumAbs = 0;
            int sampleCount = 0;
            boolean binDone = false;
            boolean inputEos = false;
            
            while (!binDone && isAnalyzingAudio) {
                // Feed input
                if (!inputEos) {
                    int inIdx = codec.dequeueInputBuffer(300); // Ultra-aggressive timeout
                    if (inIdx >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(inIdx);
                        if (buf == null) { inputEos = true; continue; }
                        int size = extractor.readSampleData(buf, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEos = true;
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                
                // Drain output
                int outIdx = codec.dequeueOutputBuffer(info, 300);
                if (outIdx >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        binDone = true;
                    }
                    ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                    if (outBuf != null && info.size > 0) {
                        outBuf.position(info.offset);
                        outBuf.limit(info.offset + info.size);
                        ShortBuffer shorts = outBuf.order(ByteOrder.LITTLE_ENDIAN)
                                .asShortBuffer();
                        
                        // MASSIVE SKIP: Read 1 sample, skip 100 samples
                        while (shorts.hasRemaining() && sampleCount < targetSamplesPerBin) {
                            sumAbs += Math.abs(shorts.get());
                            sampleCount++;
                            
                            // Skip 100 samples
                            int skip = Math.min(100, shorts.remaining());
                            shorts.position(shorts.position() + skip);
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if (sampleCount >= targetSamplesPerBin) binDone = true;
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inputEos) {
                    binDone = true;
                }
            }
            
            bins[bin] = sampleCount > 0
                    ? Math.min(1.0f, (float) (sumAbs / sampleCount) / 32768.0f)
                    : 0f;
        }
        
        return bins;
    }

    /**
     * Streaming fallback when audio duration is unknown. Decodes entire audio
     * but accumulates directly into amplitude chunks without storing individual
     * samples, then downsamples to {@link #waveformPoints} bins.
     */
    private float[] extractStreamBased(
            MediaExtractor extractor, MediaCodec codec,
            int sampleRate, int channels) {

        int chunkSize = Math.max(1, sampleRate * channels / 20); // ~50ms per chunk
        List<Float> chunks = new ArrayList<>();
        long chunkSum = 0;
        int chunkCount = 0;

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        boolean outputDone = false;

        while (!outputDone && isAnalyzingAudio) {
            if (!inputDone) {
                int inIdx = codec.dequeueInputBuffer(10_000);
                if (inIdx >= 0) {
                    ByteBuffer buf = codec.getInputBuffer(inIdx);
                    if (buf == null) { inputDone = true; continue; }
                    int size = extractor.readSampleData(buf, 0);
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size,
                                extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }

            int outIdx = codec.dequeueOutputBuffer(info, 10_000);
            if (outIdx >= 0) {
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }
                ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                if (outBuf != null) {
                    outBuf.position(info.offset);
                    outBuf.limit(info.offset + info.size);
                    ShortBuffer shorts = outBuf.order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer();
                    while (shorts.hasRemaining()) {
                        chunkSum += Math.abs(shorts.get());
                        chunkCount++;
                        if (chunkCount >= chunkSize) {
                            chunks.add(Math.min(1.0f,
                                    (float) (chunkSum / chunkCount) / 32768.0f));
                            chunkSum = 0;
                            chunkCount = 0;
                        }
                    }
                }
                codec.releaseOutputBuffer(outIdx, false);
            } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                outputDone = true;
            }
        }
        // Flush last partial chunk
        if (chunkCount > 0) {
            chunks.add(Math.min(1.0f,
                    (float) (chunkSum / chunkCount) / 32768.0f));
        }

        if (chunks.isEmpty()) return null;

        // Downsample collected chunks to waveformPoints bins
        float[] bins = new float[waveformPoints];
        for (int i = 0; i < waveformPoints; i++) {
            float pos = (float) i / waveformPoints * chunks.size();
            int idx = Math.min((int) pos, chunks.size() - 1);
            bins[i] = chunks.get(idx);
        }
        return bins;
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
