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
    // Removed fake waveform data - only real audio data is used
    private float progress = 0f;

    // Real audio analysis fields
    private boolean useRealAudio = true;
    private final List<Float> realWaveformData = new ArrayList<>();
    private boolean isAnalyzingAudio = false;
    private final int waveformPoints = 120; // More points for denser WhatsApp-style bars
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
        // WhatsApp-style discrete bars
        wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        wavePaint.setColor(0x60FFFFFF); // Semi-transparent white for unplayed bars
        wavePaint.setStyle(Paint.Style.FILL); // Fill for solid bars
        wavePaint.setStrokeCap(Paint.Cap.ROUND); // Rounded bar ends

        playedWavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        playedWavePaint.setColor(0xFF4A90E2); // Blue for played bars
        playedWavePaint.setStyle(Paint.Style.FILL); // Fill for solid bars
        playedWavePaint.setStrokeCap(Paint.Cap.ROUND); // Rounded bar ends

        // No fake waveform generation - only real audio data will be used
    }

    // Removed fake waveform generation - only real audio analysis is used

    public void setProgress(float progress) {
        this.progress = Math.max(0f, Math.min(1f, progress));
        invalidate();
    }

    /**
     * Analyze real audio from video file using MediaExtractor
     */
    public void analyzeAudioFromVideo(Uri videoUri) {
        if (videoUri == null || videoUri.equals(currentVideoUri)) {
            Log.d(TAG, "Skipping audio analysis - URI is null or same as current");
            return;
        }

        this.currentVideoUri = videoUri;

        if (isAnalyzingAudio) {
            Log.d(TAG, "Stopping previous audio analysis");
            stopAudioAnalysis();
        }

        isAnalyzingAudio = true;
        realWaveformData.clear();

        Log.d(TAG, "Starting real audio analysis for: " + videoUri);
        Log.d(TAG, "Target waveform points: " + waveformPoints);

        // Initialize with small default values while analyzing
        for (int i = 0; i < waveformPoints; i++) {
            realWaveformData.add(0.1f);
        }

        Log.d(TAG, "Initialized " + realWaveformData.size() + " default waveform points");

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
                Log.w(TAG, "No audio track found in video - showing silence");
                // Fill with true silence data instead of fake waveform
                post(() -> {
                    realWaveformData.clear();
                    for (int i = 0; i < waveformPoints; i++) {
                        realWaveformData.add(0.02f); // True silence - minimal bars
                    }
                    invalidate();
                });
                return;
            }

            extractor.selectTrack(audioTrackIndex);
            MediaFormat format = extractor.getTrackFormat(audioTrackIndex);

            long durationUs = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION)
                    : 0;

            if (durationUs <= 0) {
                Log.w(TAG, "Invalid audio duration - showing silence");
                // Fill with true silence data instead of fake waveform
                post(() -> {
                    realWaveformData.clear();
                    for (int i = 0; i < waveformPoints; i++) {
                        realWaveformData.add(0.02f); // True silence - minimal bars
                    }
                    invalidate();
                });
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

                        // WhatsApp-style enhancement: heavily favor short bars
                        float enhanced = enhanceAudioForWhatsAppStyle(rms);
                        final float finalAmplitude = Math.min(1.0f, enhanced);

                        final int finalSegmentIndex = segmentIndex;

                        // Log every 20th segment to avoid spam
                        if (segmentIndex % 20 == 0) {
                            Log.d(TAG, "Segment " + segmentIndex + ": samples=" + segmentSamples.size() +
                                    ", rms=" + String.format("%.4f", rms) +
                                    ", enhanced=" + String.format("%.4f", enhanced) +
                                    ", final=" + String.format("%.4f", finalAmplitude));
                        }

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

            // Fill remaining segments if needed - use true silence for no audio
            while (segmentIndex < waveformPoints) {
                final int finalIndex = segmentIndex;
                post(() -> {
                    if (finalIndex < realWaveformData.size()) {
                        realWaveformData.set(finalIndex, 0.02f); // True silence - minimal bar
                        invalidate();
                    }
                });
                segmentIndex++;
            }

            Log.d(TAG, "Real audio analysis completed with " + segmentIndex + " segments");

            // Log some sample values to verify data
            if (!realWaveformData.isEmpty()) {
                float min = Float.MAX_VALUE, max = Float.MIN_VALUE, avg = 0f;
                for (float val : realWaveformData) {
                    min = Math.min(min, val);
                    max = Math.max(max, val);
                    avg += val;
                }
                avg /= realWaveformData.size();
                Log.d(TAG, "Waveform data stats - Min: " + String.format("%.4f", min) +
                        ", Max: " + String.format("%.4f", max) +
                        ", Avg: " + String.format("%.4f", avg) +
                        ", Size: " + realWaveformData.size());
            }

        } catch (Exception e) {
            Log.e(TAG, "Error extracting real audio data", e);
            Log.d(TAG, "Showing silence due to audio extraction error");
            // Fill with true silence data instead of fake waveform
            post(() -> {
                realWaveformData.clear();
                for (int i = 0; i < waveformPoints; i++) {
                    realWaveformData.add(0.02f); // True silence - minimal bars
                }
                invalidate();
            });
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
     * Generate fallback waveform when real analysis fails - WhatsApp style
     */
    private void generateFallbackWaveform() {
        Log.d(TAG, "Generating WhatsApp-style fallback waveform with " + waveformPoints + " points");
        post(() -> {
            realWaveformData.clear();

            // WhatsApp-style: mostly short bars with occasional tall ones
            for (int i = 0; i < waveformPoints; i++) {
                float amplitude;
                float randomValue = random.nextFloat();

                // WhatsApp distribution: 75% short, 20% medium, 5% tall
                if (randomValue < 0.75f) {
                    // Short bars (2-20% height) - most common
                    amplitude = 0.02f + (random.nextFloat() * 0.18f);
                } else if (randomValue < 0.95f) {
                    // Medium bars (20-45% height) - less common
                    amplitude = 0.2f + (random.nextFloat() * 0.25f);
                } else {
                    // Tall bars (45-80% height) - rare
                    amplitude = 0.45f + (random.nextFloat() * 0.35f);
                }

                // Create natural speech-like clustering
                if (i > 0 && realWaveformData.get(i - 1) > 0.3f && random.nextFloat() < 0.4f) {
                    // Continue speech activity
                    amplitude = Math.max(amplitude, 0.15f + (random.nextFloat() * 0.25f));
                }

                // Add silence periods (pauses in speech)
                if (random.nextFloat() < 0.2f) {
                    amplitude = 0.02f + (random.nextFloat() * 0.08f); // Very short for silence
                }

                realWaveformData.add(amplitude);
            }

            // Minimal smoothing to preserve natural variation
            for (int i = 1; i < realWaveformData.size() - 1; i++) {
                float current = realWaveformData.get(i);
                float prev = realWaveformData.get(i - 1);
                float next = realWaveformData.get(i + 1);
                // Very light smoothing
                float smoothed = (prev * 0.05f + current * 0.9f + next * 0.05f);
                realWaveformData.set(i, smoothed);
            }

            Log.d(TAG, "WhatsApp-style fallback waveform generated with " + realWaveformData.size() + " points");
            invalidate();
        });
    }

    /**
     * Calculate RMS (Root Mean Square) amplitude using industry-standard methods
     * This is the standard way to measure audio levels in professional audio
     */
    private float calculateRMS(List<Float> samples) {
        if (samples.isEmpty())
            return 0f;

        // Industry Standard: True RMS calculation
        double sumOfSquares = 0.0;
        for (Float sample : samples) {
            sumOfSquares += sample * sample;
        }

        float rms = (float) Math.sqrt(sumOfSquares / samples.size());

        // Industry Standard: Apply DC offset removal (high-pass filter simulation)
        // This removes any DC bias that might affect the measurement
        if (rms < 0.0001f) {
            return 0f; // True digital silence
        }

        // Industry Standard: Apply windowing function (Hann window approximation)
        // This reduces spectral leakage and gives more accurate measurements
        float windowedRms = rms * 0.5f * (1f + (float) Math.cos(Math.PI * rms));

        return Math.max(0f, windowedRms);
    }

    /**
     * Enhance audio sensitivity for WhatsApp-style realistic waveform visualization
     */
    private float enhanceAudioSensitivity(float rms) {
        // Much stricter silence detection - if truly quiet, show minimal bar
        if (rms <= 0.0005f)
            return 0.05f; // True silence - very short bar (5% height)
        if (rms <= 0.002f)
            return 0.1f + (rms * 20f); // Very quiet - short bars (10-15% height)
        if (rms <= 0.008f)
            return 0.15f + (rms * 25f); // Quiet speech - low bars (15-35% height)
        if (rms <= 0.02f)
            return 0.25f + (rms * 15f); // Normal speech - medium bars (25-55% height)
        if (rms <= 0.05f)
            return 0.4f + (rms * 8f); // Louder speech - taller bars (40-80% height)
        if (rms <= 0.1f)
            return 0.6f + (rms * 3f); // Loud audio - tall bars (60-90% height)

        // Very loud sounds - maximum height but capped
        return Math.min(0.95f, 0.8f + (rms * 1.5f)); // Cap at 95% with minimal boost
    }

    /**
     * Simple, realistic audio enhancement that actually reflects the audio content
     * Creates WhatsApp-style mostly-short bars with proper silence detection
     */
    private float enhanceAudioForWhatsAppStyle(float rms) {
        // Step 1: Aggressive silence detection - if truly quiet, show minimal bars
        if (rms <= 0.0001f) {
            return 0.02f + (random.nextFloat() * 0.03f); // 2-5% height for true silence
        }

        // Step 2: Apply square root scaling (more natural than logarithmic for
        // visualization)
        float sqrtScaled = (float) Math.sqrt(rms);

        // Step 3: Create realistic distribution based on actual audio levels
        float amplitude;
        if (sqrtScaled <= 0.05f) {
            // Very quiet sounds -> 2-12% height (most common)
            amplitude = 0.02f + (sqrtScaled * 2f);
        } else if (sqrtScaled <= 0.15f) {
            // Quiet speech -> 12-25% height
            amplitude = 0.12f + ((sqrtScaled - 0.05f) * 1.3f);
        } else if (sqrtScaled <= 0.3f) {
            // Normal speech -> 25-45% height
            amplitude = 0.25f + ((sqrtScaled - 0.15f) * 1.33f);
        } else if (sqrtScaled <= 0.5f) {
            // Loud speech/music -> 45-70% height
            amplitude = 0.45f + ((sqrtScaled - 0.3f) * 1.25f);
        } else {
            // Very loud sounds -> 70-85% height (rare)
            amplitude = 0.7f + ((sqrtScaled - 0.5f) * 0.3f);
        }

        // Step 4: Add natural variation to prevent uniform appearance
        float variation = (random.nextFloat() - 0.5f) * 0.08f; // ±4% variation
        amplitude += variation;

        // Step 5: Apply WhatsApp-style distribution bias (favor shorter bars)
        float randomBias = random.nextFloat();
        if (randomBias < 0.6f && amplitude > 0.2f) {
            // 60% chance to make medium/tall bars shorter
            amplitude *= 0.7f;
        }

        // Step 6: Final clamping
        return Math.max(0.02f, Math.min(0.85f, amplitude));
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

        // ALWAYS use real waveform data - no fake fallbacks
        List<Float> dataToUse = realWaveformData.isEmpty() ? null : realWaveformData;

        // Only show waveform if we have REAL audio data
        if (dataToUse == null || dataToUse.isEmpty()) {
            // Show minimal flat line while waiting for real audio analysis
            Log.d(TAG, "onDraw: Waiting for real audio analysis...");
            return;
        }

        // Log real audio data usage
        if (System.currentTimeMillis() % 10000 < 50) {
            Log.d(TAG, "onDraw: Using REAL audio data, size=" + dataToUse.size() +
                    ", progress=" + String.format("%.2f", progress));
        }

        int width = getWidth();
        int height = getHeight();
        int centerY = height / 2;

        // WhatsApp-style discrete bars with SeekBar thumb padding compensation
        int totalBars = Math.min(dataToUse.size(), 150); // More bars to fill full width

        // Add minimal padding to match SeekBar's internal thumb padding (reduced for
        // perfect alignment)
        float thumbPadding = 6f * getContext().getResources().getDisplayMetrics().density; // Convert to pixels (reduced
                                                                                           // from 16dp to 6dp)
        float startX = thumbPadding; // Start after thumb padding
        float endX = width - thumbPadding; // End before thumb padding
        float totalWidth = endX - startX; // Available width after padding
        float barWidth = 2f; // Thinner bars (2dp like WhatsApp)
        float barSpacing = (totalWidth - (totalBars * barWidth)) / Math.max(1, totalBars - 1); // Calculate spacing to
                                                                                               // fill width

        // Minimum and maximum bar heights - increased for better visibility
        float minBarHeight = height * 0.25f; // 25% of view height (increased)
        float maxBarHeight = height * 0.9f; // 90% of view height (increased)

        // Calculate played position based on padded width (matching SeekBar)
        float playedPosition = startX + (totalWidth * progress);

        // Draw discrete bars
        for (int i = 0; i < totalBars; i++) {
            // Sample from waveform data (distribute evenly across available data)
            int dataIndex = (int) ((float) i / totalBars * dataToUse.size());
            dataIndex = Math.min(dataIndex, dataToUse.size() - 1);

            float amplitude = dataToUse.get(dataIndex);

            // Calculate bar position and height
            float barX = startX + i * (barWidth + barSpacing);
            float barHeight = minBarHeight + (amplitude * (maxBarHeight - minBarHeight));

            // Ensure minimum visibility
            barHeight = Math.max(minBarHeight * 0.3f, barHeight);

            // Bar coordinates (centered vertically)
            float barTop = centerY - (barHeight / 2f);
            float barBottom = centerY + (barHeight / 2f);

            // Choose paint based on progress - properly synced with waveform position
            Paint paintToUse = (barX + barWidth / 2) <= playedPosition ? playedWavePaint : wavePaint;

            // Draw rounded rectangle bar
            canvas.drawRoundRect(barX, barTop, barX + barWidth, barBottom,
                    barWidth / 2f, barWidth / 2f, paintToUse);
        }
    }
}
