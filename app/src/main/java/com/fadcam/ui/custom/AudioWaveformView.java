package com.fadcam.ui.custom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.AudioFormat;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import com.google.android.exoplayer2.ExoPlayer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AudioWaveformView extends View {
    private static final String TAG = "AudioWaveformView";
    private Paint wavePaint;
    private Paint playedWavePaint;
    private List<Float> waveformData;
    private float progress = 0f;

    // Real audio analysis fields
    private boolean useRealAudio = true;
    private final List<Float> realWaveformData = new ArrayList<>();
    private boolean isAnalyzingAudio = false;
    private final int waveformPoints = 200; // Number of points in waveform
    private final ExecutorService audioAnalysisExecutor = Executors.newSingleThreadExecutor();
    private Uri currentVideoUri;
    private java.util.Random random = new java.util.Random(); // For fallback generation

    public AudioWaveformView(Context context) {
        super(context);
        init();
    }

    public AudioWaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        wavePaint.setColor(0x50FFFFFF); // Better semi-transparent white
        wavePaint.setStrokeWidth(1.5f);
        wavePaint.setStyle(Paint.Style.STROKE);

        playedWavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        playedWavePaint.setColor(0xFF4A90E2); // Nice blue for played portion - distinct from red progress bar
        playedWavePaint.setStrokeWidth(1.5f);
        playedWavePaint.setStyle(Paint.Style.STROKE);

        // Generate more realistic waveform data
        generateRealisticWaveform();
    }

    private void generateRealisticWaveform() {
        waveformData = new ArrayList<>();

        // Create much more realistic audio waveform patterns
        float[] basePattern = new float[200];

        // Generate base noise pattern
        for (int i = 0; i < 200; i++) {
            basePattern[i] = random.nextFloat();
        }

        // Apply multiple passes for more realistic patterns
        for (int i = 0; i < 200; i++) {
            float amplitude = 0f;

            // Layer 1: Base random variation (speech-like)
            amplitude += basePattern[i] * 0.3f;

            // Layer 2: Breathing/rhythm pattern
            amplitude += (float) Math.sin(i * 0.1f) * 0.2f;

            // Layer 3: Word/phrase groupings
            float phrasePos = (i % 40) / 40f;
            if (phrasePos < 0.7f) {
                amplitude += (float) Math.sin(phrasePos * Math.PI) * 0.4f;
            } else {
                amplitude *= 0.1f; // Pauses between phrases
            }

            // Layer 4: Sentence-level variations
            float sentencePos = (i % 80) / 80f;
            amplitude *= 0.5f + 0.5f * (float) Math.sin(sentencePos * Math.PI);

            // Layer 5: Overall volume envelope
            float overallPos = i / 200f;
            if (overallPos < 0.1f || overallPos > 0.9f) {
                amplitude *= overallPos < 0.1f ? overallPos * 10f : (1f - overallPos) * 10f;
            }

            // Add natural variation and clamp
            amplitude += (random.nextFloat() - 0.5f) * 0.1f;
            amplitude = Math.max(0.01f, Math.min(0.8f, amplitude));

            waveformData.add(amplitude);
        }

        // Apply smoothing pass to remove harsh transitions
        for (int i = 1; i < waveformData.size() - 1; i++) {
            float current = waveformData.get(i);
            float prev = waveformData.get(i - 1);
            float next = waveformData.get(i + 1);
            float smoothed = (prev + current * 2 + next) / 4f;
            waveformData.set(i, smoothed);
        }
    }

    public void setProgress(float progress) {
        this.progress = Math.max(0f, Math.min(1f, progress));
        invalidate();
    }

    /**
     * Analyze real audio from video file using MediaExtractor
     */
    public void analyzeAudioFromVideo(Uri videoUri) {
        if (videoUri == null || videoUri.equals(currentVideoUri))
            return;

        this.currentVideoUri = videoUri;

        if (isAnalyzingAudio) {
            stopAudioAnalysis();
        }

        isAnalyzingAudio = true;
        realWaveformData.clear();

        Log.d(TAG, "Starting real audio analysis for: " + videoUri);

        // Initialize with small default values while analyzing
        for (int i = 0; i < waveformPoints; i++) {
            realWaveformData.add(0.1f);
        }

        // Start audio analysis in background
        audioAnalysisExecutor.submit(() -> extractRealAudioData(videoUri));
    }

    /**
     * Extract real audio data using MediaExtractor
     */
    private void extractRealAudioData(Uri videoUri) {
        MediaExtractor extractor = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(getContext(), videoUri, null);

            // Find audio track
            int audioTrackIndex = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIndex = i;
                    break;
                }
            }

            if (audioTrackIndex == -1) {
                Log.w(TAG, "No audio track found in video");
                generateFallbackWaveform();
                return;
            }

            extractor.selectTrack(audioTrackIndex);
            MediaFormat format = extractor.getTrackFormat(audioTrackIndex);

            long durationUs = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION)
                    : 0;

            if (durationUs <= 0) {
                Log.w(TAG, "Invalid audio duration, using fallback");
                generateFallbackWaveform();
                return;
            }

            Log.d(TAG, "Audio track found - Duration: " + (durationUs / 1000) + "ms");

            // Calculate segment duration for waveform points
            long segmentDurationUs = durationUs / waveformPoints;

            ByteBuffer buffer = ByteBuffer.allocate(8192);
            long currentTimeUs = 0;
            int segmentIndex = 0;
            List<Float> segmentSamples = new ArrayList<>();

            while (isAnalyzingAudio && segmentIndex < waveformPoints) {
                int sampleSize = extractor.readSampleData(buffer, 0);
                if (sampleSize < 0)
                    break;

                long sampleTimeUs = extractor.getSampleTime();

                // Process audio samples in this buffer
                buffer.rewind();
                buffer.order(ByteOrder.LITTLE_ENDIAN);

                // Extract 16-bit PCM samples with better precision
                while (buffer.remaining() >= 2 && segmentIndex < waveformPoints) {
                    short sample = buffer.getShort();
                    // More conservative amplitude calculation
                    float amplitude = Math.abs(sample) / 32768.0f;

                    // Apply gentle logarithmic scaling for better dynamic range
                    if (amplitude > 0.002f) {
                        amplitude = (float) (Math.log10(amplitude * 5 + 1) / Math.log10(6));
                    }

                    segmentSamples.add(amplitude);
                }

                // Check if we've filled current segment
                if (sampleTimeUs >= (segmentIndex + 1) * segmentDurationUs) {
                    if (!segmentSamples.isEmpty()) {
                        float rms = calculateRMS(segmentSamples);

                        // Enhanced sensitivity with dynamic scaling
                        float enhanced = enhanceAudioSensitivity(rms);
                        final float finalAmplitude = Math.min(1.0f, enhanced);

                        final int finalSegmentIndex = segmentIndex;

                        post(() -> {
                            if (finalSegmentIndex < realWaveformData.size()) {
                                realWaveformData.set(finalSegmentIndex, finalAmplitude);
                                invalidate();
                            }
                        });

                        segmentSamples.clear();
                        segmentIndex++;
                    }
                }

                buffer.clear();
                extractor.advance();
            }

            // Fill remaining segments if needed
            while (segmentIndex < waveformPoints) {
                final int finalIndex = segmentIndex;
                post(() -> {
                    if (finalIndex < realWaveformData.size()) {
                        realWaveformData.set(finalIndex, 0.05f); // Silent ending
                        invalidate();
                    }
                });
                segmentIndex++;
            }

            Log.d(TAG, "Real audio analysis completed with " + segmentIndex + " segments");

        } catch (Exception e) {
            Log.e(TAG, "Error extracting real audio data", e);
            generateFallbackWaveform();
        } finally {
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing MediaExtractor", e);
                }
            }
            isAnalyzingAudio = false;
        }
    }

    /**
     * Generate fallback waveform when real analysis fails
     */
    private void generateFallbackWaveform() {
        post(() -> {
            realWaveformData.clear();
            // Generate much more realistic speech-like pattern
            float[] baseNoise = new float[waveformPoints];
            for (int i = 0; i < waveformPoints; i++) {
                baseNoise[i] = random.nextFloat();
            }

            for (int i = 0; i < waveformPoints; i++) {
                float t = i / (float) waveformPoints;
                float amplitude = 0f;

                // Layer 1: Natural speech rhythm (breathing, pauses)
                float breathingCycle = (float) Math.sin(t * Math.PI * 3.2) * 0.3f + 0.7f;

                // Layer 2: Word-level variations
                float wordPattern = 0f;
                float wordPos = (t * 50) % 1f; // ~50 "words" across the timeline
                if (wordPos < 0.6f) {
                    wordPattern = (float) Math.sin(wordPos * Math.PI) * 0.8f;
                } else {
                    wordPattern = 0.05f; // Pause between words
                }

                // Layer 3: Syllable-level variations
                float syllablePattern = (float) Math.sin(t * Math.PI * 120 + baseNoise[i] * 6) * 0.4f + 0.6f;

                // Layer 4: Natural volume envelope
                float envelope = 1.0f;
                if (t < 0.05f)
                    envelope = t * 20f; // Fade in
                if (t > 0.95f)
                    envelope = (1f - t) * 20f; // Fade out

                // Layer 5: Random natural variation
                float naturalVariation = baseNoise[i] * 0.3f + 0.7f;

                // Combine all layers
                amplitude = wordPattern * syllablePattern * breathingCycle * envelope * naturalVariation;

                // Add some random spikes for consonants
                if (random.nextFloat() < 0.15f) {
                    amplitude *= 1.5f + random.nextFloat() * 0.8f;
                }

                // Clamp and add to data
                amplitude = Math.max(0.005f, Math.min(0.75f, amplitude * 0.6f));
                realWaveformData.add(amplitude);
            }

            // Apply smoothing to make it more natural
            for (int i = 1; i < realWaveformData.size() - 1; i++) {
                float current = realWaveformData.get(i);
                float prev = realWaveformData.get(i - 1);
                float next = realWaveformData.get(i + 1);
                float smoothed = (prev + current * 3 + next) / 5f;
                realWaveformData.set(i, smoothed);
            }

            invalidate();
        });
    }

    /**
     * Calculate RMS (Root Mean Square) amplitude from audio samples
     */
    private float calculateRMS(List<Float> samples) {
        if (samples.isEmpty())
            return 0f;

        double sum = 0.0;
        for (Float sample : samples) {
            sum += sample * sample;
        }

        return (float) Math.sqrt(sum / samples.size());
    }

    /**
     * Enhance audio sensitivity for better waveform visualization
     */
    private float enhanceAudioSensitivity(float rms) {
        if (rms <= 0.0001f)
            return 0.005f; // Very quiet - barely visible
        if (rms <= 0.001f)
            return rms * 8.0f; // Quiet speech - high amplification
        if (rms <= 0.005f)
            return rms * 6.0f; // Quiet speech - good amplification
        if (rms <= 0.01f)
            return rms * 4.0f; // Normal speech - moderate amplification
        if (rms <= 0.03f)
            return rms * 3.0f; // Louder speech - some amplification
        if (rms <= 0.08f)
            return rms * 2.0f; // Loud audio - slight amplification
        if (rms <= 0.2f)
            return rms * 1.5f; // Very loud - minimal amplification
        return Math.min(0.85f, rms * 1.2f); // Cap at 85% with slight boost
    }

    /**
     * Stop audio analysis when player is released
     */
    public void stopAudioAnalysis() {
        isAnalyzingAudio = false;
        Log.d(TAG, "Audio analysis stopped");
    }

    /**
     * Clean up resources
     */
    public void cleanup() {
        stopAudioAnalysis();
        audioAnalysisExecutor.shutdown();
        realWaveformData.clear();
        currentVideoUri = null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Use real waveform data if available, otherwise fall back to fake data
        List<Float> dataToUse = useRealAudio && !realWaveformData.isEmpty() ? realWaveformData : waveformData;

        if (dataToUse == null || dataToUse.isEmpty())
            return;

        int width = getWidth();
        int height = getHeight();
        int centerY = height / 2;

        float barWidth = width / (float) dataToUse.size();
        float playedWidth = width * progress;

        Path wavePath = new Path();
        Path playedPath = new Path();

        for (int i = 0; i < dataToUse.size(); i++) {
            float x = i * barWidth;
            float amplitude = dataToUse.get(i) * (height * 0.35f); // Slightly better amplitude scaling

            if (i == 0) {
                wavePath.moveTo(x, centerY - amplitude);
                if (x < playedWidth) {
                    playedPath.moveTo(x, centerY - amplitude);
                }
            } else {
                wavePath.lineTo(x, centerY - amplitude);
                wavePath.lineTo(x, centerY + amplitude);

                if (x < playedWidth) {
                    playedPath.lineTo(x, centerY - amplitude);
                    playedPath.lineTo(x, centerY + amplitude);
                }
            }
        }

        // Draw unplayed waveform first (background)
        canvas.drawPath(wavePath, wavePaint);

        // Draw played portion on top
        canvas.drawPath(playedPath, playedWavePaint);
    }
}
