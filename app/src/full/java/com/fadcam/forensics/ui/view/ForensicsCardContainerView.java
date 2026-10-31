package com.fadcam.forensics.ui.view;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.AttributeSet;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;

import com.fadcam.R;

/**
 * Torn-edge container for full forensic cards (image + metadata).
 */
public class ForensicsCardContainerView extends LinearLayout {

    public static final int CARD_FILL_DOSSIER = 0;

    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgeShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final PaintFlagsDrawFilter drawFilter = new PaintFlagsDrawFilter(
            0,
            Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG
    );

    @Nullable
    private Path tornPath;
    private int pathWidth = -1;
    private int pathHeight = -1;
    private int seedHash;
    private float amplitudeDp = 6.2f;
    private float frequency = 1.8f;
    private boolean reducedFxMode;
    private int cardFillStyle = CARD_FILL_DOSSIER;
    @Nullable
    private Drawable fillDrawable;

    public ForensicsCardContainerView(@NonNull Context context) {
        super(context);
        init();
    }

    public ForensicsCardContainerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ForensicsCardContainerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        setClipToPadding(false);
        setClipChildren(false);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Keeps clipPath edges smooth on legacy renderers.
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(dp(0.85f));
        edgePaint.setColor(Color.argb(94, 132, 142, 154));
        edgePaint.setDither(true);
        edgePaint.setFilterBitmap(true);

        edgeShadowPaint.setStyle(Paint.Style.STROKE);
        edgeShadowPaint.setStrokeWidth(dp(1.45f));
        edgeShadowPaint.setColor(Color.argb(92, 0, 0, 0));
        edgeShadowPaint.setMaskFilter(new BlurMaskFilter(dp(1.1f), BlurMaskFilter.Blur.NORMAL));
        edgeShadowPaint.setDither(true);
        edgeShadowPaint.setFilterBitmap(true);

        setCardFillStyle(CARD_FILL_DOSSIER);
    }

    public void setTearSeed(@Nullable String seed) {
        int newSeed = seed == null ? 0 : seed.hashCode();
        if (newSeed == seedHash) {
            return;
        }
        seedHash = newSeed;
        tornPath = null;
        invalidate();
    }

    public void setTearStyle(float amplitudeDp, float frequency) {
        float clampedAmp = clamp(amplitudeDp, 2f, 16f);
        float clampedFreq = clamp(frequency, 0.6f, 8f);
        if (this.amplitudeDp == clampedAmp && this.frequency == clampedFreq) {
            return;
        }
        this.amplitudeDp = clampedAmp;
        this.frequency = clampedFreq;
        tornPath = null;
        invalidate();
    }

    public void setRealismMode(boolean reducedFx) {
        if (this.reducedFxMode == reducedFx) {
            return;
        }
        this.reducedFxMode = reducedFx;
        invalidate();
    }

    public void setCardFillStyle(int styleMode) {
        if (this.cardFillStyle == styleMode && fillDrawable != null) {
            return;
        }
        this.cardFillStyle = styleMode;
        fillDrawable = AppCompatResources.getDrawable(getContext(), R.drawable.forensics_card_dossier_bg);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw || h != oldh) {
            tornPath = null;
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        ensurePath();
        if (tornPath == null) {
            super.dispatchDraw(canvas);
            return;
        }

        canvas.setDrawFilter(drawFilter);
        int save = canvas.save();
        canvas.clipPath(tornPath);
        drawCardFill(canvas);
        super.dispatchDraw(canvas);
        canvas.restoreToCount(save);

        if (!reducedFxMode) {
            canvas.drawPath(tornPath, edgeShadowPaint);
        }
        canvas.drawPath(tornPath, edgePaint);
    }

    private void drawCardFill(@NonNull Canvas canvas) {
        if (fillDrawable == null) {
            return;
        }
        fillDrawable.setBounds(0, 0, getWidth(), getHeight());
        fillDrawable.draw(canvas);
    }

    private void ensurePath() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            tornPath = null;
            return;
        }
        if (tornPath != null && pathWidth == w && pathHeight == h) {
            return;
        }
        pathWidth = w;
        pathHeight = h;

        float bleed = dp(6f);
        int innerW = Math.max(1, Math.round(w - (bleed * 2f)));
        int innerH = Math.max(1, Math.round(h - (bleed * 2f)));
        float safeAmplitude = Math.min(dp(amplitudeDp), bleed * 0.9f);
        tornPath = TornEdgePathFactory.getOrCreate(innerW, innerH, seedHash, safeAmplitude, frequency);
        tornPath.offset(bleed, bleed);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
