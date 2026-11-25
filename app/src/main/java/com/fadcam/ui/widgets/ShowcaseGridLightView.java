package com.fadcam.ui.widgets;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.ui.animations.AnimationView;

/**
 * The Pro bundle card's surface: one lighting system for the whole sheet.
 *
 * <p>Drawing order is background → glow → base grid → illuminated grid → registration marks.
 * The base grid is barely-there white hairlines; the same lines are drawn a second time through
 * a radial gold shader that peaks around the phone, so only the grid near the device is revealed
 * as warm light. Because both passes come from one cached line list and one light centre, the
 * highlight can never drift out of alignment, and it follows the phone slot in LTR, RTL and
 * through layout changes.</p>
 *
 * <p>All geometry, shaders and paints are built when the size or the light moves; {@link #onDraw}
 * only issues draw calls. There are no blurs, no offscreen bitmaps and no per-frame allocations,
 * so the card stays cheap on older devices.</p>
 */
public class ShowcaseGridLightView extends View {

    /**
     * Reference step at the bundle card's width. The rendered step is this scaled by the view's
     * own width, so a small preview stage gets proportionally smaller boxes instead of the same
     * 24dp cells filling it with a handful of oversized squares.
     */
    private static final float GRID_STEP_DP = 24f;
    private static final float GRID_REFERENCE_DP = 330f;
    private static final float GRID_MIN_STEP_DP = 9f;
    private static final float GRID_STROKE_DP = 0.5f;

    /** White graph paper, deliberately faint. */
    private static final int BASE_GRID = 0x0AFFFFFF; // ~4%
    /** Warm champagne used for both the light and the revealed grid. */
    private static final int LIGHT = 0xFFD4AF5A;

    /** Light ellipse, as multiples of the phone slot. */
    private static final float LIT_RX = 1.05f;
    private static final float LIT_RY = 0.82f;
    private static final float GLOW_RX = 0.86f;
    private static final float GLOW_RY = 0.64f;

    /**
     * Light ellipse around an animation's artwork. The scene reports its real art bounds,
     * so a compact phone demo gets a compact halo while a wide editor/road demo gets a
     * wide one — instead of every stage lighting the same fixed 62% of its card. The
     * radius stays outside the art so the halo reads as light around the animation,
     * not a fill tucked underneath it.
     */
    private static final float FOCUS_RX = 0.84f;
    private static final float FOCUS_RY = 0.90f;

    private final Paint baseGridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint litGridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bracketPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix shaderMatrix = new Matrix();
    private final float density;

    private float[] columns = new float[0];
    private float[] rows = new float[0];
    private float inset;
    private float arm;

    private float lightX;
    private float lightY;
    private float lightRx;
    private float lightRy;
    private boolean hasLight;
    private Shader litShader;
    private Shader glowShader;

    /** Optional sibling whose bounds drive the light; set through {@code app:lightSubject}. */
    private final int subjectId;
    private View subject;
    private final Rect subjectRect = new Rect();
    /** Corner registration marks. Off for preview stages that already sit inside a card. */
    private final boolean cornerMarks;

    public ShowcaseGridLightView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;

        int id = View.NO_ID;
        boolean marks = true;
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(
                    attrs, R.styleable.ShowcaseGridLightView);
            id = a.getResourceId(R.styleable.ShowcaseGridLightView_lightSubject, View.NO_ID);
            marks = a.getBoolean(R.styleable.ShowcaseGridLightView_cornerMarks, true);
            a.recycle();
        }
        subjectId = id;
        cornerMarks = marks;

        baseGridPaint.setStyle(Paint.Style.STROKE);
        baseGridPaint.setStrokeWidth(GRID_STROKE_DP * density);
        baseGridPaint.setColor(BASE_GRID);

        litGridPaint.setStyle(Paint.Style.STROKE);
        litGridPaint.setStrokeWidth(GRID_STROKE_DP * density);

        bracketPaint.setStyle(Paint.Style.STROKE);
        bracketPaint.setStrokeWidth(1.1f * density);
        bracketPaint.setColor(getResources().getColor(R.color.dossier_olive, null));
        bracketPaint.setAlpha(150);

        inset = 10f * density;
        arm = 17f * density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /**
     * Moves the card's single light source onto the phone slot. Safe to call from every layout
     * pass: identical bounds are a no-op, so nothing re-renders while the card is static.
     */
    public void setLight(float centerX, float centerY, float phoneWidth, float phoneHeight) {
        if (phoneWidth <= 0f || phoneHeight <= 0f) return;
        applyLight(centerX, centerY, phoneWidth * LIT_RX, phoneHeight * LIT_RY);
    }

    private void setFocusedLight(float centerX, float centerY, float width, float height) {
        if (width <= 0f || height <= 0f) return;
        applyLight(centerX, centerY, width * FOCUS_RX, height * FOCUS_RY);
    }

    private void applyLight(float centerX, float centerY, float rx, float ry) {
        if (hasLight
                && Math.abs(lightX - centerX) < 0.5f
                && Math.abs(lightY - centerY) < 0.5f
                && Math.abs(lightRx - rx) < 0.5f
                && Math.abs(lightRy - ry) < 0.5f) {
            return;
        }
        lightX = centerX;
        lightY = centerY;
        lightRx = rx;
        lightRy = ry;
        hasLight = true;
        buildShaders();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        // Proportional cells: the reference card shows 24dp boxes; a 150dp preview shows
        // about 11dp boxes, which reads as the same grid instead of four huge squares.
        float step = GRID_STEP_DP * density
                * Math.max(GRID_MIN_STEP_DP / GRID_STEP_DP,
                        Math.min(1f, w / (GRID_REFERENCE_DP * density)));
        if (step <= 0f) return;
        int count = (int) (w / step);
        columns = new float[count];
        for (int i = 0; i < count; i++) columns[i] = (i + 1) * step;
        count = (int) (h / step);
        rows = new float[count];
        for (int i = 0; i < count; i++) rows[i] = (i + 1) * step;
        // A surface without an explicit subject lights its own centre, so a preview stage
        // gets the same soft reveal behind its animation as the card gets behind the phone.
        // If the stage hosts an AnimationView, the light waits for that view's real art
        // bounds instead of briefly painting the old full-size centre glow.
        if (subjectId == View.NO_ID && !hasAnimationSibling()) {
            setLight(w * 0.5f, h * 0.5f, w * 0.62f, h * 0.62f);
        }
        buildShaders();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        bindSubject();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (subject != null) subject.removeOnLayoutChangeListener(subjectLayoutListener);
        subject = null;
        super.onDetachedFromWindow();
    }

    /** Resolves the declared subject once the whole host layout exists. */
    private void bindSubject() {
        if (!(getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) getParent();
        View found = subjectId != View.NO_ID
                ? parent.findViewById(subjectId)
                : findAnimation(parent);
        if (found == null || found == subject) return;
        if (subject != null) subject.removeOnLayoutChangeListener(subjectLayoutListener);
        subject = found;
        subject.addOnLayoutChangeListener(subjectLayoutListener);
        aimAtSubject();
    }

    /** The stage's animation is the default subject: its art, not the card, gets the light. */
    private boolean hasAnimationSibling() {
        return getParent() instanceof ViewGroup && findAnimation((ViewGroup) getParent()) != null;
    }

    private static View findAnimation(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof AnimationView) return child;
            if (child instanceof ViewGroup) {
                View nested = findAnimation((ViewGroup) child);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private final View.OnLayoutChangeListener subjectLayoutListener =
            (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> aimAtSubject();

    private void aimAtSubject() {
        if (subject == null || !(getParent() instanceof ViewGroup)) return;
        if (subject.getWidth() <= 0 || subject.getHeight() <= 0) return;
        Rect rect = subjectRect;
        boolean focused = subject instanceof AnimationView
                && ((AnimationView) subject).getFocusInView(rect);
        if (!focused) {
            rect.set(0, 0, subject.getWidth(), subject.getHeight());
        }
        ((ViewGroup) getParent()).offsetDescendantRectToMyCoords(subject, rect);
        if (focused) {
            setFocusedLight(rect.centerX() - getLeft(), rect.centerY() - getTop(),
                    rect.width(), rect.height());
        } else {
            setLight(rect.centerX() - getLeft(), rect.centerY() - getTop(),
                    rect.width(), rect.height());
        }
    }

    private void buildShaders() {
        if (!hasLight || getWidth() <= 0 || getHeight() <= 0) return;

        // The revealed grid: strongest hugging the device, gone before the card's corners. The
        // transparent tail starts well inside the ellipse so no circular edge is ever visible.
        litShader = ellipse(
                new int[]{withAlpha(LIGHT, 0x26), withAlpha(LIGHT, 0x20),
                        withAlpha(LIGHT, 0x0F), withAlpha(LIGHT, 0x00)},
                new float[]{0f, 0.5f, 0.78f, 1f},
                lightRx, lightRy);

        // The light itself: barely perceptible on the sheet, it mostly exists to explain the
        // grid highlight.
        glowShader = ellipse(
                new int[]{withAlpha(LIGHT, 0x14), withAlpha(LIGHT, 0x09), withAlpha(LIGHT, 0x00)},
                new float[]{0f, 0.55f, 1f},
                lightRx * (GLOW_RX / LIT_RX), lightRy * (GLOW_RY / LIT_RY));
    }

    private Shader ellipse(int[] colors, float[] stops, float rx, float ry) {
        RadialGradient gradient = new RadialGradient(
                0f, 0f, 1f, colors, stops, Shader.TileMode.CLAMP);
        shaderMatrix.setScale(Math.max(1f, rx), Math.max(1f, ry));
        shaderMatrix.postTranslate(lightX, lightY);
        gradient.setLocalMatrix(shaderMatrix);
        return gradient;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) return;

        if (glowShader != null) {
            glowPaint.setShader(glowShader);
            canvas.drawRect(0f, 0f, w, h, glowPaint);
            glowPaint.setShader(null);
        }

        // Base grid and illuminated grid share these exact line positions, so the highlight is
        // always the same lines getting brighter — never a second pattern.
        for (float x : columns) canvas.drawLine(x, 0f, x, h, baseGridPaint);
        for (float y : rows) canvas.drawLine(0f, y, w, y, baseGridPaint);

        if (litShader != null) {
            litGridPaint.setShader(litShader);
            for (float x : columns) canvas.drawLine(x, 0f, x, h, litGridPaint);
            for (float y : rows) canvas.drawLine(0f, y, w, y, litGridPaint);
            litGridPaint.setShader(null);
        }

        if (cornerMarks) drawRegistrationMarks(canvas, w, h);
    }

    private void drawRegistrationMarks(Canvas canvas, float w, float h) {
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

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }
}
