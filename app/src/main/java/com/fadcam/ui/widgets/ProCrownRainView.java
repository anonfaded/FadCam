package com.fadcam.ui.widgets;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;

import com.fadcam.R;

import java.util.Random;

/**
 * Ambient backdrop: gold crowns falling straight down behind the Pro mascot.
 *
 * <p>This is the Pro screen's one bold element, so everything about it is deliberately
 * restrained: a single gold tint, low alpha, no glow, no particles that react to touch. Each
 * crown falls on its own column with its own speed and size, which reads as depth rather
 * than as a repeating pattern. Motion is frame-synced and allocation-free; it is disabled when
 * the system asks for reduced motion.
 */
public class ProCrownRainView extends View {

    /**
     * Glyphs fall straight down — no rotation and no drift. Any tilt reads as a diagonal slide
     * once the crown is moving, which is not the calm vertical rain this screen wants.
     */
    private static final float BASE_TILT_DEG = 0f;

    private static final int SPRITE_SIZES_DP = 0; // sizes are derived per density below

    private final Paint spritePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix spriteMatrix = new Matrix();
    private final Random random = new Random(0xC0FFEE);

    private Bitmap[] sprites;
    private int[] spriteSizes;

    private float[] columnX;
    private float[] columnY;
    private float[] columnSpeed;      // px per second
    private float[] columnAlpha;
    private float[] columnTilt;
    private int[] columnSprite;
    private float[] columnPhase;      // sway phase offset

    private float density;
    private boolean animating;
    private long lastFrameNanos;

    public ProCrownRainView(Context context) {
        this(context, null);
    }

    public ProCrownRainView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ProCrownRainView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!animating) {
                return;
            }
            if (lastFrameNanos == 0L) {
                lastFrameNanos = frameTimeNanos;
            }
            float dtSeconds = (frameTimeNanos - lastFrameNanos) / 1_000_000_000f;
            lastFrameNanos = frameTimeNanos;
            // Clamp so a stalled frame cannot teleport the crowns.
            dtSeconds = Math.min(dtSeconds, 0.05f);
            advance(dtSeconds);
            invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        buildSprites();
        buildColumns(w, h);
        // Sprites only exist after the first measure, and onAttachedToWindow runs earlier:
        // kick the loop here so the rain actually falls.
        startRain();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startRain();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopRain();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) {
            startRain();
        } else {
            stopRain();
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) startRain();
        else stopRain();
    }

    private void startRain() {
        if (animating || sprites == null || !isShown() || getWindowVisibility() != VISIBLE || !motionAllowed()) {
            invalidate();
            return;
        }
        animating = true;
        lastFrameNanos = 0L;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private void stopRain() {
        animating = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
    }

    /** Respects the system "remove animations" accessibility setting. */
    private boolean motionAllowed() {
        try {
            return Settings.Global.getFloat(getContext().getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
        } catch (Exception e) {
            return true;
        }
    }

    /** Pre-renders the crown at a few sizes — the vector is never decoded per frame. */
    private void buildSprites() {
        int[] sizeDp = {46, 30, 19};
        sprites = new Bitmap[sizeDp.length];
        spriteSizes = new int[sizeDp.length];
        Drawable crown = AppCompatResources.getDrawable(getContext(), R.drawable.ic_crown);
        for (int i = 0; i < sizeDp.length; i++) {
            int px = Math.max(8, Math.round(sizeDp[i] * density));
            spriteSizes[i] = px;
            Bitmap bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            if (crown != null) {
                Drawable copy = crown.getConstantState() != null
                        ? crown.getConstantState().newDrawable().mutate() : crown.mutate();
                copy.setBounds(0, 0, px, px);
                copy.setColorFilter(new PorterDuffColorFilter(0xFFFFEFBE, PorterDuff.Mode.SRC_IN));
                copy.draw(canvas);
            }
            sprites[i] = bitmap;
        }
    }

    /**
     * Lays out the rain as columns. Spacing, speed and alpha are randomised once per column (not
     * per frame), so the pattern never repeats visibly and costs nothing to advance.
     */
    private void buildColumns(int width, int height) {
        if (width <= 0) {
            return;
        }
        float spacing = 46f * density;
        int columns = Math.max(6, Math.round(width / spacing));
        int count = columns * 2;
        columnX = new float[count];
        columnY = new float[count];
        columnSpeed = new float[count];
        columnAlpha = new float[count];
        columnTilt = new float[count];
        columnSprite = new int[count];
        columnPhase = new float[count];

        float step = width / (float) columns;
        for (int i = 0; i < count; i++) {
            int column = i % columns;
            float halfSprite = spriteSizes[1] / 2f;
            columnX[i] = Math.min(width - halfSprite,
                    Math.max(halfSprite, step * column + step * 0.5f));
            // Second drop in a column starts staggered so the fall never looks like a grid.
            float phase = (i < columns) ? 0f : Math.max(0.28f, random.nextFloat() * 0.42f);
            columnY[i] = (random.nextFloat() + phase) % 1f * height;
            columnSpeed[i] = (13f + random.nextFloat() * 24f) * density;   // px/s: 13-37 dp/s
            columnAlpha[i] = 0.36f + random.nextFloat() * 0.38f;
            columnTilt[i] = 0f;
            columnSprite[i] = random.nextInt(3);
            // phase unused after removing sideways drift; kept for array symmetry
        }
    }

    private void advance(float dtSeconds) {
        if (columnY == null) {
            return;
        }
        float limit = getHeight() + 140f * density;
        for (int i = 0; i < columnY.length; i++) {
            columnY[i] += columnSpeed[i] * dtSeconds;
            if (columnY[i] > limit) {
                // Recycle to the top with fresh character so the rain never loops visibly.
                columnY[i] = -60f * density - random.nextFloat() * 160f * density;
                columnSpeed[i] = (13f + random.nextFloat() * 24f) * density;
                columnAlpha[i] = 0.34f + random.nextFloat() * 0.38f;
                columnSprite[i] = random.nextInt(3);
                columnTilt[i] = 0f;
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (sprites == null || columnY == null) {
            return;
        }
        float height = getHeight();
        for (int i = 0; i < columnY.length; i++) {
            Bitmap sprite = sprites[columnSprite[i]];
            if (sprite == null || sprite.isRecycled()) {
                continue;
            }
            int size = spriteSizes[columnSprite[i]];
            float x = columnX[i];
            float y = columnY[i];
            // Fade in at the top and out at the bottom so drops appear and leave cleanly.
            float edge = Math.min(1f, Math.min(y + 20f * density, height - y) / (90f * density));
            if (edge <= 0f) {
                continue;
            }
            spritePaint.setAlpha((int) (255 * columnAlpha[i] * Math.max(0f, Math.min(1f, edge))));
            spriteMatrix.reset();
            spriteMatrix.postTranslate(x - size / 2f, y - size / 2f);
            canvas.drawBitmap(sprite, spriteMatrix, spritePaint);
        }
    }
}
