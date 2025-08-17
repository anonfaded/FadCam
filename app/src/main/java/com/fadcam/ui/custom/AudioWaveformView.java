package com.fadcam.ui.custom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class AudioWaveformView extends View {
    private Paint wavePaint;
    private Paint playedWavePaint;
    private List<Float> waveformData;
    private float progress = 0f;
    private Random random = new Random();

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

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        if (waveformData == null || waveformData.isEmpty()) return;

        int width = getWidth();
        int height = getHeight();
        int centerY = height / 2;
        
        float barWidth = width / (float) waveformData.size();
        float playedWidth = width * progress;

        Path wavePath = new Path();
        Path playedPath = new Path();
        
        for (int i = 0; i < waveformData.size(); i++) {
            float x = i * barWidth;
            float amplitude = waveformData.get(i) * (height * 0.35f); // Slightly better amplitude scaling
            
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
