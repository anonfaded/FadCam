package com.fadcam.ui.widgets;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.fadcam.R;

/**
 * A quiet, static dossier texture: olive graph paper, a faded agency watermark and corner
 * registration marks. Drawn once per size; no animation and no per-frame allocations.
 */
public class DossierPaperView extends View {

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bracketPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint watermarkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final float gridStep;

    public DossierPaperView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        gridStep = 22f * density;
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f);
        gridPaint.setColor(getResources().getColor(R.color.dossier_grid, null));
        bracketPaint.setStyle(Paint.Style.STROKE);
        bracketPaint.setStrokeWidth(1.1f * density);
        bracketPaint.setColor(getResources().getColor(R.color.dossier_olive, null));
        bracketPaint.setAlpha(150);
        watermarkPaint.setStyle(Paint.Style.FILL);
        watermarkPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        watermarkPaint.setTextSize(42f * density);
        watermarkPaint.setLetterSpacing(0.12f);
        watermarkPaint.setColor(getResources().getColor(R.color.dossier_ink, null));
        watermarkPaint.setAlpha(12);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        for (float x = gridStep; x < w; x += gridStep) {
            canvas.drawLine(x, 0, x, h, gridPaint);
        }
        for (float y = gridStep; y < h; y += gridStep) {
            canvas.drawLine(0, y, w, y, gridPaint);
        }

        canvas.save();
        canvas.rotate(-18f, w * 0.5f, h * 0.62f);
        canvas.drawText("FADSEC LAB // DOSSIER", w * 0.05f, h * 0.64f, watermarkPaint);
        canvas.restore();

        float inset = 10f * density;
        float arm = 17f * density;
        // Top-left
        canvas.drawLine(inset, inset, inset + arm, inset, bracketPaint);
        canvas.drawLine(inset, inset, inset, inset + arm, bracketPaint);
        // Top-right
        canvas.drawLine(w - inset, inset, w - inset - arm, inset, bracketPaint);
        canvas.drawLine(w - inset, inset, w - inset, inset + arm, bracketPaint);
        // Bottom-left
        canvas.drawLine(inset, h - inset, inset + arm, h - inset, bracketPaint);
        canvas.drawLine(inset, h - inset, inset, h - inset - arm, bracketPaint);
        // Bottom-right
        canvas.drawLine(w - inset, h - inset, w - inset - arm, h - inset, bracketPaint);
        canvas.drawLine(w - inset, h - inset, w - inset, h - inset - arm, bracketPaint);
    }
}
