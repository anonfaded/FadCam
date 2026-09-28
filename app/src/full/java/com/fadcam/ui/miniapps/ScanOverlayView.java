package com.fadcam.ui.miniapps;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Draws a semi-transparent dark overlay with a transparent rectangular cutout
 * in the center, creating the "scan window" effect.
 */
public class ScanOverlayView extends View {

    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF scanRect = new RectF();

    public ScanOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dimPaint.setColor(0xCC000000); // ~80% dark
        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        borderPaint.setColor(0x80FFFFFF);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(4f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        
        // Match the 260dp from layout
        float density = getResources().getDisplayMetrics().density;
        float scanSize = 260f * density;
        
        // Ensure it doesn't exceed screen size
        float maxAllowed = Math.min(w, h) * 0.9f;
        if (scanSize > maxAllowed) scanSize = maxAllowed;
        
        float left = (w - scanSize) / 2f;
        float top = (h - scanSize) / 2f;
        scanRect.set(left, top, left + scanSize, top + scanSize);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int save = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
        // Draw dim overlay
        canvas.drawRect(0, 0, getWidth(), getHeight(), dimPaint);
        // Cut out scan window
        canvas.drawRoundRect(scanRect, 24f, 24f, clearPaint);
        // Draw border
        canvas.drawRoundRect(scanRect, 24f, 24f, borderPaint);
        canvas.restoreToCount(save);
    }
}
