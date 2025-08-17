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
        playedWavePaint.setColor(0xFFFF4444); // Nice red for played portion - original was better
        playedWavePaint.setStrokeWidth(1.5f);
        playedWavePaint.setStyle(Paint.Style.STROKE);

        // Generate more realistic waveform data
        generateRealisticWaveform();
    }

    private void generateRealisticWaveform() {
        waveformData = new ArrayList<>();
        
        // Create more realistic audio waveform patterns
        for (int i = 0; i < 200; i++) {
            float amplitude;
            
            // Create realistic patterns: quiet parts, loud parts, speech-like patterns
            if (i < 20 || i > 180) {
                // Quiet intro/outro
                amplitude = 0.1f + random.nextFloat() * 0.2f;
            } else if (i > 60 && i < 80) {
                // Loud section 
                amplitude = 0.6f + random.nextFloat() * 0.3f;
            } else if (i > 120 && i < 140) {
                // Another loud section
                amplitude = 0.5f + random.nextFloat() * 0.4f;
            } else {
                // Normal speech/music pattern
                amplitude = 0.2f + random.nextFloat() * 0.4f;
            }
            
            // Add some smoothing to avoid totally random spikes
            if (i > 0) {
                float prev = waveformData.get(i - 1);
                float diff = Math.abs(amplitude - prev);
                if (diff > 0.3f) {
                    // Smooth out big jumps
                    amplitude = prev + (amplitude - prev) * 0.7f;
                }
            }
            
            waveformData.add(Math.min(amplitude, 0.9f));
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
        if (videoUri == null || videoUri.equals(currentVideoUri)) return;
        
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
            
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION) ? 
                format.getLong(MediaFormat.KEY_DURATION) : 0;
            
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
                if (sampleSize < 0) break;
                
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
            // Generate more varied and realistic speech-like pattern
            for (int i = 0; i < waveformPoints; i++) {
                float t = i / (float) waveformPoints;
                
                // Create speech-like patterns with more variation
                float basePattern = (float) Math.sin(t * Math.PI * 15 + Math.sin(t * Math.PI * 4) * 3);
                float envelope = (float) (0.2 + 0.5 * Math.sin(t * Math.PI * 2.3));
                float noise = (random.nextFloat() - 0.5f) * 0.4f;
                
                // Add more realistic pauses and variations
                float pauseFactor = 1.0f;
                if (t % 0.25f > 0.18f) pauseFactor = 0.1f; // Longer pauses
                if (t % 0.12f > 0.08f) pauseFactor *= 0.6f; // Short pauses
                
                float amplitude = Math.abs(basePattern * envelope + noise) * pauseFactor;
                amplitude = Math.max(0.01f, Math.min(0.6f, amplitude * 0.4f)); // Lower max amplitude
                
                realWaveformData.add(amplitude);
            }
            invalidate();
        });
    }
    
    /**
     * Calculate RMS (Root Mean Square) amplitude from audio samples
     */
    private float calculateRMS(List<Float> samples) {
        if (samples.isEmpty()) return 0f;
        
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
        if (rms <= 0.001f) return 0.01f; // Very quiet - almost invisible
        if (rms <= 0.005f) return rms * 3.0f; // Quiet speech - moderate amplification
        if (rms <= 0.02f) return rms * 2.5f; // Normal speech - good amplification
        if (rms <= 0.05f) return rms * 2.0f; // Louder speech - some amplification
        if (rms <= 0.1f) return rms * 1.5f; // Loud audio - slight amplification
        if (rms <= 0.3f) return rms * 1.2f; // Very loud - minimal amplification
        return Math.min(0.9f, rms); // Cap at 90% to prevent full bars everywhere
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
        
        if (dataToUse == null || dataToUse.isEmpty()) return;

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
