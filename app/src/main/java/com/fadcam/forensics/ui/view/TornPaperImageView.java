package com.fadcam.forensics.ui.view;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

import com.fadcam.R;

import java.util.Random;

/**
 * Forensics gallery image view with deterministic torn top/bottom edges.
 */
public class TornPaperImageView extends AppCompatImageView {

    private static final String TAG = "TornPaperImageView";
    private static final int DEFAULT_PAPER_TINT = 0x1FFFFFFF;

    private static BitmapShader sharedGrainShader;

    private final Paint paperTintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgeShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgeHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Path tornPath;
    private int pathWidth = -1;
    private int pathHeight = -1;

    private float tearAmplitudeDp = 6f;
    private float tearFrequency = 2.15f;
    private int paperTint = DEFAULT_PAPER_TINT;
    private float grainAlpha = 0.10f;
    private float edgeShadowAlpha = 0.22f;
    private String seedKey;
    private int seedHash;
    private boolean forensicsStyleEnabled = true;

    public TornPaperImageView(@NonNull Context context) {
        super(context);
        init(context, null);
    }

    public TornPaperImageView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public TornPaperImageView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    private void init(@NonNull Context context, @Nullable AttributeSet attrs) {
        setScaleType(ScaleType.CENTER_CROP);
        setWillNotDraw(false);

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.TornPaperImageView);
            tearAmplitudeDp = a.getDimension(R.styleable.TornPaperImageView_tearAmplitude, dpToPx(6f)) / context.getResources().getDisplayMetrics().density;
            tearFrequency = a.getFloat(R.styleable.TornPaperImageView_tearFrequency, tearFrequency);
            paperTint = a.getColor(R.styleable.TornPaperImageView_paperTint, paperTint);
            grainAlpha = a.getFloat(R.styleable.TornPaperImageView_grainAlpha, grainAlpha);
            edgeShadowAlpha = a.getFloat(R.styleable.TornPaperImageView_edgeShadowAlpha, edgeShadowAlpha);
            seedKey = a.getString(R.styleable.TornPaperImageView_seedKey);
            a.recycle();
        }

        if (seedKey == null) {
            seedKey = "default";
        }
        seedHash = seedKey.hashCode();

        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        boolean lowRam = am != null && am.isLowRamDevice();
        if (lowRam) {
            grainAlpha = Math.min(grainAlpha, 0.04f);
        }

        paperTintPaint.setStyle(Paint.Style.FILL);
        paperTintPaint.setColor(paperTint);

        edgeShadowPaint.setStyle(Paint.Style.STROKE);
        edgeShadowPaint.setColor(Color.argb(Math.round(255f * edgeShadowAlpha), 0, 0, 0));
        edgeShadowPaint.setStrokeWidth(dpToPx(1.35f));

        edgeHighlightPaint.setStyle(Paint.Style.STROKE);
        edgeHighlightPaint.setColor(Color.argb(Math.round(255f * Math.max(0.08f, edgeShadowAlpha * 0.55f)), 255, 255, 255));
        edgeHighlightPaint.setStrokeWidth(dpToPx(0.8f));

        grainPaint.setStyle(Paint.Style.FILL);
        grainPaint.setAlpha(Math.round(255f * grainAlpha));
        ensureGrainShader();
        if (sharedGrainShader != null) {
            grainPaint.setShader(sharedGrainShader);
        }
    }

    public void setTearSeed(@Nullable String seed) {
        String newSeed = seed == null ? "" : seed;
        if (newSeed.equals(seedKey)) {
            return;
        }
        seedKey = newSeed;
        seedHash = seedKey.hashCode();
        tornPath = null;
        invalidate();
    }

    public void setTearStyle(float amplitudeDp, float frequency) {
        float clampedAmplitude = clamp(amplitudeDp, 2f, 14f);
        float clampedFrequency = clamp(frequency, 0.6f, 8f);
        if (this.tearAmplitudeDp == clampedAmplitude && this.tearFrequency == clampedFrequency) {
            return;
        }
        this.tearAmplitudeDp = clampedAmplitude;
        this.tearFrequency = clampedFrequency;
        tornPath = null;
        invalidate();
    }

    public void setForensicsStyle(boolean enabled) {
        if (this.forensicsStyleEnabled == enabled) {
            return;
        }
        this.forensicsStyleEnabled = enabled;
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
    protected void onDraw(@NonNull Canvas canvas) {
        if (!forensicsStyleEnabled) {
            super.onDraw(canvas);
            return;
        }

        ensurePath();
        if (tornPath == null) {
            super.onDraw(canvas);
            return;
        }

        int save = canvas.save();
        canvas.clipPath(tornPath);
        super.onDraw(canvas);

        if (paperTintPaint.getColor() != Color.TRANSPARENT) {
            canvas.drawPath(tornPath, paperTintPaint);
        }

        if (grainPaint.getShader() != null && grainPaint.getAlpha() > 0) {
            canvas.drawPath(tornPath, grainPaint);
        }
        canvas.restoreToCount(save);

        // Draw edge polish outside clip for depth.
        canvas.drawPath(tornPath, edgeShadowPaint);
        canvas.save();
        canvas.translate(0f, dpToPx(0.65f));
        canvas.drawPath(tornPath, edgeHighlightPaint);
        canvas.restore();
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
        float amplitudePx = Math.max(dpToPx(1.5f), dpToPx(tearAmplitudeDp));
        tornPath = TornEdgePathFactory.getOrCreate(w, h, seedHash, amplitudePx, tearFrequency);
    }

    private void ensureGrainShader() {
        if (sharedGrainShader != null) {
            return;
        }
        try {
            Bitmap bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
            Random random = new Random(0x4FADE123L);
            for (int y = 0; y < bitmap.getHeight(); y++) {
                for (int x = 0; x < bitmap.getWidth(); x++) {
                    int base = 140 + random.nextInt(90);
                    int alpha = 8 + random.nextInt(28);
                    int color = Color.argb(alpha, base, base - 4, base - 8);
                    bitmap.setPixel(x, y, color);
                }
            }
            sharedGrainShader = new BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to prepare grain shader", t);
        }
    }

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
