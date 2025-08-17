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
        wavePaint.setColor(0x40FFFFFF); // Semi-transparent white
        wavePaint.setStrokeWidth(2f);
        wavePaint.setStyle(Paint.Style.STROKE);

        playedWavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        playedWavePaint.setColor(0xFFFF4444); // Red for played portion
        playedWavePaint.setStrokeWidth(2f);
        playedWavePaint.setStyle(Paint.Style.STROKE);

        // Generate mock waveform data
        generateMockWaveform();
    }

    private void generateMockWaveform() {
        waveformData = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            waveformData.add(random.nextFloat() * 0.8f + 0.1f);
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
            float amplitude = waveformData.get(i) * (height * 0.3f);
            
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

        // Draw unplayed waveform
        canvas.drawPath(wavePath, wavePaint);
        
        // Draw played portion
        canvas.drawPath(playedPath, playedWavePaint);
    }
}
