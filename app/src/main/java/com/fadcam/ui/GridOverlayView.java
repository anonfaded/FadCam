package com.fadcam.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * GridOverlayView
 * Draws a rule-of-thirds grid (2 vertical + 2 horizontal lines) over the
 * camera preview. Visibility is controlled by the Grid Lines setting; the
 * view is only shown while the live preview is visible.
 */
public class GridOverlayView extends View {

    private final Paint linePaint;

    public GridOverlayView(@NonNull Context context) {
        this(context, null);
    }

    public GridOverlayView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public GridOverlayView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(0x59FFFFFF); // white, ~35% alpha
        linePaint.setStrokeWidth(dp(1f));
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float thirdW = w / 3f;
        float thirdH = h / 3f;

        // Vertical thirds
        canvas.drawLine(thirdW, 0f, thirdW, h, linePaint);
        canvas.drawLine(2f * thirdW, 0f, 2f * thirdW, h, linePaint);
        // Horizontal thirds
        canvas.drawLine(0f, thirdH, w, thirdH, linePaint);
        canvas.drawLine(0f, 2f * thirdH, w, 2f * thirdH, linePaint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
