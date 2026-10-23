package com.fadcam.ui.widgets;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;

import com.fadcam.R;

/**
 * Decorative privacy schematic for the bundle card: three nodes beside the phone converge
 * into one line that reaches a dark metallic shield. The whole illustration is derived from
 * one scale (the shield height), so strokes, nodes, wire length and spacing grow and shrink
 * together. Geometry and shaders are built in {@link #onSizeChanged}; {@link #onDraw} only
 * draws. The label beneath it is a real TextView.
 */
public class PrivacyConvergenceView extends View {

    private static final int SHIELD_FILL_TOP = 0xFF514026;
    private static final int SHIELD_FILL_MID = 0xFF241E15;
    private static final int SHIELD_FILL_BOTTOM = 0xFF141311;
    private static final int SHIELD_LIGHT = 0xFFF3D591;
    private static final int SHIELD_WARM = 0xFFD0A85C;
    private static final int SHIELD_DEEP = 0xFFA47B38;
    private static final int CHECK_GOLD = 0xFFDBBB78;
    private static final int GLOW_START = 0x14F2C14E;
    private static final int GLOW_END = 0x00F2C14E;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shieldPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shieldFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nodePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint checkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path shieldPath = new Path();
    private final Path checkPath = new Path();
    private final Path[] wirePaths = {new Path(), new Path(), new Path()};
    private final Path trunkPath = new Path();
    private final float[] nodeX = new float[3];
    private final float[] nodeY = new float[3];

    private float nodeRadius;
    private float shieldCenterX;
    private float shieldCenterY;
    private float shieldHeight;

    public PrivacyConvergenceView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        int gold = getResources().getColor(R.color.dossier_olive_bright, null);

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setColor((gold & 0x00FFFFFF) | 0x70000000);

        shieldPaint.setStyle(Paint.Style.STROKE);
        shieldPaint.setStrokeCap(Paint.Cap.ROUND);
        shieldPaint.setStrokeJoin(Paint.Join.ROUND);

        shieldFillPaint.setStyle(Paint.Style.FILL);

        nodePaint.setStyle(Paint.Style.FILL);
        nodePaint.setColor((gold & 0x00FFFFFF) | 0xD9000000);

        checkPaint.setStyle(Paint.Style.STROKE);
        checkPaint.setStrokeCap(Paint.Cap.ROUND);
        checkPaint.setStrokeJoin(Paint.Join.ROUND);
        checkPaint.setColor(CHECK_GOLD);

        glowPaint.setStyle(Paint.Style.FILL);

        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;
        float pad = 2f * getResources().getDisplayMetrics().density;

        // Everything below is expressed in shield heights: one scale for the whole mark.
        shieldHeight = Math.min(h - pad * 2f, 65f * getResources().getDisplayMetrics().density);
        float shieldW = shieldHeight * 0.82f;
        float cx = w * 0.5f;
        float cy = h * 0.5f;
        shieldCenterX = cx;
        shieldCenterY = cy;
        float left = cx - shieldW * 0.5f;
        float right = cx + shieldW * 0.5f;
        float top = cy - shieldHeight * 0.5f;
        float bottom = cy + shieldHeight * 0.5f;

        shieldPaint.setStrokeWidth(shieldHeight * 0.021f);
        linePaint.setStrokeWidth(shieldHeight * 0.017f);
        checkPaint.setStrokeWidth(shieldHeight * 0.018f);
        nodeRadius = shieldHeight * 0.042f;

        shieldPaint.setShader(new LinearGradient(left, top, right, bottom,
                new int[]{SHIELD_LIGHT, SHIELD_WARM, SHIELD_DEEP},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        shieldFillPaint.setShader(new LinearGradient(left, top, right, bottom,
                new int[]{SHIELD_FILL_TOP, SHIELD_FILL_MID, SHIELD_FILL_BOTTOM},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        glowPaint.setShader(new RadialGradient(cx, cy, shieldHeight * 1.05f,
                new int[]{GLOW_START, GLOW_END}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));

        shieldPath.reset();
        shieldPath.moveTo(left, top + shieldHeight * 0.08f);
        shieldPath.cubicTo(left + shieldW * 0.16f, top,
                right - shieldW * 0.16f, top, right, top + shieldHeight * 0.08f);
        shieldPath.lineTo(right, top + shieldHeight * 0.42f);
        shieldPath.cubicTo(right, top + shieldHeight * 0.68f,
                cx + shieldW * 0.30f, top + shieldHeight * 0.86f, cx, bottom);
        shieldPath.cubicTo(cx - shieldW * 0.30f, top + shieldHeight * 0.86f,
                left, top + shieldHeight * 0.68f, left, top + shieldHeight * 0.42f);
        shieldPath.close();

        checkPath.reset();
        checkPath.moveTo(cx - shieldW * 0.19f, cy + shieldHeight * 0.01f);
        checkPath.lineTo(cx - shieldW * 0.03f, cy + shieldHeight * 0.14f);
        checkPath.lineTo(cx + shieldW * 0.21f, cy - shieldHeight * 0.11f);

        float nodeRight = Math.min(w - pad, cx + shieldHeight * 1.55f);
        float junctionX = Math.min(right + shieldW * 0.08f, nodeRight - shieldHeight * 0.45f);
        float junctionY = cy;
        float trunkStartX = right + shieldW * 0.03f;
        float spread = Math.min(shieldHeight * 0.40f, nodeRight - junctionX);

        float[] offsets = {-spread, 0f, spread};
        for (int i = 0; i < wirePaths.length; i++) {
            nodeX[i] = nodeRight;
            nodeY[i] = Math.max(pad, Math.min(h - pad, cy + offsets[i]));
            float dx = nodeX[i] - junctionX;
            wirePaths[i].reset();
            wirePaths[i].moveTo(nodeX[i], nodeY[i]);
            wirePaths[i].cubicTo(nodeX[i] - dx * 0.34f, nodeY[i],
                    junctionX + dx * 0.28f, junctionY, junctionX, junctionY);
        }

        trunkPath.reset();
        trunkPath.moveTo(junctionX, junctionY);
        trunkPath.lineTo(trunkStartX, junctionY);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // Barely-there amber pool, only enough to lift the shield off the grid.
        canvas.drawCircle(shieldCenterX, shieldCenterY, shieldHeight * 1.05f, glowPaint);
        for (int i = 0; i < wirePaths.length; i++) {
            canvas.drawPath(wirePaths[i], linePaint);
            canvas.drawCircle(nodeX[i], nodeY[i], nodeRadius, nodePaint);
        }
        canvas.drawPath(trunkPath, shieldPaint);
        canvas.drawPath(shieldPath, shieldFillPaint);
        canvas.drawPath(shieldPath, shieldPaint);
        canvas.drawPath(checkPath, checkPaint);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // One quiet entrance with the card; no looping motion.
        setAlpha(0f);
        animate().alpha(1f)
                .setStartDelay(160)
                .setDuration(480)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .start();
    }

    @Override
    protected void onDetachedFromWindow() {
        animate().cancel();
        super.onDetachedFromWindow();
    }
}
