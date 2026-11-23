package com.fadcam.ui.widgets;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.fadcam.R;

/**
 * The bundle card's light: the phone mockup sits in a champagne aura the way the onboarding
 * payoff's icon sits in its glow.
 *
 * <p>Four layers, back to front: a paper-coloured core that softly clears the dossier grid
 * under the device, a broad warm haze, a blurred rim hugging the device's actual outline, and
 * a pool of light on the sheet beneath it. The rim is pre-blurred into an offscreen bitmap the
 * way the payoff does it, because {@link BlurMaskFilter} is a software-canvas operation; the
 * bitmap is rebuilt only when the slot changes, so drawing stays allocation-free.</p>
 *
 * <p>It is a sibling underneath the card's content, so the copy and the phone always paint on
 * top. {@link ProBundleShowcaseView} hands it the phone slot on every layout pass.</p>
 */
public class ShowcaseAuraView extends View {

    private static final int GOLD = 0xFFE8C77B;
    private static final int LIGHT = 0xFFF0D08A;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF phone = new RectF();

    private Shader coreClear;
    private Shader atmosphere;
    private Shader pool;
    private Bitmap halo;
    private int haloLeft;
    private int haloTop;
    private final int paperColor;
    private final float density;
    private boolean clearsGrid = true;

    public ShowcaseAuraView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        density = getResources().getDisplayMetrics().density;
        paperColor = getResources().getColor(R.color.dossier_paper, null);
        haloPaint.setStyle(Paint.Style.STROKE);
    }

    /** The phone slot in this view's coordinates; safe to call on every layout pass. */
    public void setPhoneBounds(float left, float top, float width, float height) {
        if (width <= 0f || height <= 0f) return;
        if (Math.abs(phone.left - left) < 0.5f
                && Math.abs(phone.top - top) < 0.5f
                && Math.abs(phone.width() - width) < 0.5f
                && Math.abs(phone.height() - height) < 0.5f) {
            return;
        }
        phone.set(left, top, left + width, top + height);
        rebuild();
        invalidate();
    }

    /**
     * The card's paper owns the backdrop, so the core may erase the grid there. A host that
     * draws its own backdrop (the phone-only variant) turns that pass off and keeps the glow.
     */
    public void setClearsGrid(boolean clears) {
        if (clearsGrid == clears) return;
        clearsGrid = clears;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        rebuild();
    }

    private void rebuild() {
        if (phone.isEmpty() || getWidth() <= 0 || getHeight() <= 0) return;
        float cx = phone.centerX();
        float cy = phone.centerY();
        float pw = phone.width();
        float ph = phone.height();

        // Clears the grid under and just around the device. Stops well before the card's
        // border so the paper never looks patched.
        coreClear = ellipse(
                new int[]{paperColor, paperColor, withAlpha(paperColor, 0x78), withAlpha(paperColor, 0)},
                new float[]{0f, 0.52f, 0.82f, 1f},
                pw * 0.74f, ph * 0.59f, cx, cy);

        // Broad ambient warmth that lifts the sheet around the device.
        atmosphere = ellipse(
                new int[]{withAlpha(GOLD, 0x18), withAlpha(GOLD, 0x09), withAlpha(GOLD, 0)},
                new float[]{0f, 0.55f, 1f},
                pw * 1.75f, ph * 0.85f, cx, cy);

        // Light pooling on the sheet below the device.
        pool = ellipse(
                new int[]{withAlpha(LIGHT, 0x38), withAlpha(LIGHT, 0x14), withAlpha(LIGHT, 0)},
                new float[]{0f, 0.5f, 1f},
                pw * 1.0f, pw * 0.26f, cx, phone.bottom + dp(1f));

        buildHalo();
    }

    /**
     * A radial gradient stretched into an ellipse. Radius 1 keeps the stop positions readable;
     * the local matrix does the shaping and the placement in one step.
     */
    private Shader ellipse(int[] colors, float[] stops, float rx, float ry, float cx, float cy) {
        RadialGradient gradient = new RadialGradient(
                0f, 0f, 1f, colors, stops, Shader.TileMode.CLAMP);
        matrix.setScale(Math.max(1f, rx), Math.max(1f, ry));
        matrix.postTranslate(cx, cy);
        gradient.setLocalMatrix(matrix);
        return gradient;
    }

    /**
     * The rim light, blurred in software so the edges melt into the paper instead of reading as
     * a drawn outline. Follows the device's own rounded rectangle, so the light hugs the frame
     * at the sides and the corners alike (an ellipse would only catch the mid-edges).
     */
    private void buildHalo() {
        if (halo != null) {
            halo.recycle();
            halo = null;
        }
        float blur = dp(22f);
        float extra = blur + dp(10f);
        RectF area = new RectF(phone);
        area.inset(-extra, -extra);
        if (!area.intersect(0f, 0f, getWidth(), getHeight())) return;
        if (area.width() < 1f || area.height() < 1f) return;

        Bitmap bitmap = Bitmap.createBitmap(
                Math.round(area.width()), Math.round(area.height()), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.translate(-area.left, -area.top);

        float radius = phone.width() * 0.17f;
        RectF outline = new RectF(phone);

        haloPaint.setStrokeWidth(dp(15f));
        haloPaint.setMaskFilter(new BlurMaskFilter(dp(22f), BlurMaskFilter.Blur.NORMAL));
        haloPaint.setColor(0x26F0D08A);
        canvas.drawRoundRect(outline, radius, radius, haloPaint);

        haloPaint.setStrokeWidth(dp(7f));
        haloPaint.setMaskFilter(new BlurMaskFilter(dp(11f), BlurMaskFilter.Blur.NORMAL));
        haloPaint.setColor(0x33F0D08A);
        canvas.drawRoundRect(outline, radius, radius, haloPaint);

        haloPaint.setStrokeWidth(dp(2f));
        haloPaint.setMaskFilter(new BlurMaskFilter(dp(4.5f), BlurMaskFilter.Blur.NORMAL));
        haloPaint.setColor(0x40F0D08A);
        canvas.drawRoundRect(outline, radius, radius, haloPaint);

        haloPaint.setMaskFilter(null);
        halo = bitmap;
        haloLeft = Math.round(area.left);
        haloTop = Math.round(area.top);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (phone.isEmpty()) return;
        if (clearsGrid) draw(canvas, coreClear);
        draw(canvas, atmosphere);
        if (halo != null && !halo.isRecycled()) {
            canvas.drawBitmap(halo, haloLeft, haloTop, null);
        }
        draw(canvas, pool);
    }

    private void draw(Canvas canvas, Shader shader) {
        if (shader == null) return;
        paint.setShader(shader);
        canvas.drawRect(0f, 0f, getWidth(), getHeight(), paint);
        paint.setShader(null);
    }

    private float dp(float value) {
        return value * density;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }
}
