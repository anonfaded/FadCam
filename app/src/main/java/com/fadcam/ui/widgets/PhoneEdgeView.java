package com.fadcam.ui.widgets;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * One 3D side plane of the showcase phone. The buttons are modelled here (not painted on the
 * front/back faces), so the device's edges carry real, raised hardware while it rotates.
 */
public class PhoneEdgeView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RectF volUp;
    private RectF volDown;
    private RectF power;

    public PhoneEdgeView(Context context) {
        super(context);
        fadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        setClickable(false);
        setFocusable(false);
    }

    /** Button rects in the parent container's coordinates (same y space as this view). */
    public void setButtonRects(RectF volUp, RectF volDown, RectF power) {
        this.volUp = volUp != null ? new RectF(volUp) : null;
        this.volDown = volDown != null ? new RectF(volDown) : null;
        this.power = power != null ? new RectF(power) : null;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) return;

        int layer = canvas.saveLayer(0f, 0f, w, h, null);
        float r = Math.min(w * 0.42f, dp(10));
        paint.setShader(new LinearGradient(0f, 0f, w, 0f,
                0xFF9AA3B0, 0xFF3B3F48, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(new RectF(0f, 0f, w, h), r, r, paint);
        // Bright outer rim so the edge reads against a dark page.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.5f, w * 0.09f));
        paint.setColor(0x99FFFFFF);
        canvas.drawLine(w * 0.14f, r, w * 0.14f, h - r, paint);
        // Inner seam where the edge meets the glass: reads as a real crease.
        paint.setStrokeWidth(Math.max(2f, w * 0.12f));
        paint.setColor(0x66000000);
        canvas.drawLine(w * 0.93f, r, w * 0.93f, h - r, paint);
        paint.setStyle(Paint.Style.FILL);

        drawButton(canvas, volUp, w);
        drawButton(canvas, volDown, w);
        drawButton(canvas, power, w);

        // Fade the bottom exactly like the surfaces do.
        float fadeTop = h - dp(34);
        fadePaint.setShader(new LinearGradient(0f, fadeTop, 0f, h,
                0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0f, fadeTop, w, h, fadePaint);
        canvas.restoreToCount(layer);
    }

    /** A raised hardware button: body, top light, bottom shadow — reads as real depth. */
    private void drawButton(Canvas canvas, RectF src, float w) {
        if (src == null) return;
        float left = w * 0.16f;
        float right = w * 0.84f;
        float top = src.top;
        float bottom = src.bottom;
        float br = w * 0.30f;
        RectF body = new RectF(left, top, right, bottom);
        paint.setShader(new LinearGradient(0f, top, 0f, bottom,
                0xFFA7B0BE, 0xFF565B65, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(body, br, br, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, w * 0.06f));
        paint.setColor(0x59FFFFFF);
        canvas.drawLine(left + br * 0.6f, top + w * 0.10f, right - br * 0.6f, top + w * 0.10f, paint);
        paint.setColor(0x66000000);
        canvas.drawLine(left + br * 0.6f, bottom - w * 0.10f, right - br * 0.6f, bottom - w * 0.10f, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
