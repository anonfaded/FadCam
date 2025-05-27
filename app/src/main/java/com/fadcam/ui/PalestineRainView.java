package com.fadcam.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.fadcam.R;

import java.util.Random;

/**
 * PalestineRainView: Animated falling icons for human verification slide.
 * Uses palestine.png as the icon.
 */
public class PalestineRainView extends View {
    private static class IconDrop {
        float y, speed, offset;
        float tilt;
    }

    private IconDrop[] drops;
    private int numColumns;
    private int iconSizePx;
    private int gapPx;
    private int viewWidth, viewHeight;
    private Bitmap iconBitmap;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler();
    private static final int FRAME_DELAY = 24; // ms - slightly slower animation
    private static final float SPEED_MIN = 2.0f; // slower minimum speed
    private static final float SPEED_MAX = 4.5f; // slower maximum speed
    private final Random random = new Random();

    public PalestineRainView(Context context) {
        super(context);
        init(context);
    }
    public PalestineRainView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }
    public PalestineRainView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        // Set this view to not intercept touch events
        setClickable(false);
        setFocusable(false);
        
        iconSizePx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 28, context.getResources().getDisplayMetrics());
        gapPx = 0; // denser, no gap
        iconBitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.palestine_main);
        paint.setAlpha(255); // Full opacity
        startAnimation();
    }

    private void startAnimation() {
        handler.post(frameRunnable);
    }

    private final Runnable frameRunnable = new Runnable() {
        @Override
        public void run() {
            updateDrops();
            invalidate();
            handler.postDelayed(this, FRAME_DELAY);
        }
    };

    private void updateDrops() {
        if (drops == null) return;
        for (int i = 0; i < numColumns; i++) {
            IconDrop drop = drops[i];
            drop.y += drop.speed;
            if (drop.y > viewHeight) {
                drop.y = -iconSizePx - drop.offset;
                drop.speed = SPEED_MIN + random.nextFloat() * 0.7f;
                drop.offset = random.nextInt(iconSizePx * 2);
                if (random.nextBoolean()) {
                    drop.tilt = -32f + random.nextFloat() * 20f;
                } else {
                    drop.tilt = 12f + random.nextFloat() * 20f;
                }
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (iconBitmap == null || drops == null) return;
        Paint iconPaint = new Paint(paint);
        android.graphics.LinearGradient iconGradient = new android.graphics.LinearGradient(
            0, 0, 0, iconSizePx,
            0xFFFFFFFF, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP
        );
        iconPaint.setShader(iconGradient);
        if (numColumns == 1) {
            float x = (viewWidth - iconSizePx) / 2f;
            IconDrop drop = drops[0];
            Rect dest = new Rect((int)x, (int)drop.y, (int)x + iconSizePx, (int)drop.y + iconSizePx);
            canvas.save();
            canvas.rotate(drop.tilt, x + iconSizePx / 2f, drop.y + iconSizePx / 2f);
            canvas.drawBitmap(iconBitmap, null, dest, iconPaint);
            canvas.restore();
        } else {
            for (int i = 0; i < numColumns; i++) {
                float x = i * (iconSizePx + gapPx);
                IconDrop drop = drops[i];
                float y = drop.y;
                Rect dest = new Rect((int)x, (int)y, (int)x + iconSizePx, (int)y + iconSizePx);
                canvas.save();
                canvas.rotate(drop.tilt, x + iconSizePx / 2f, y + iconSizePx / 2f);
                canvas.drawBitmap(iconBitmap, null, dest, iconPaint);
                canvas.restore();
            }
        }
        int gradientHeight = (int) (viewHeight * 0.4f);
        Paint gradPaint = new Paint();
        LinearGradient grad = new LinearGradient(
            0, viewHeight,
            0, viewHeight - gradientHeight,
            0xCC000000, 0x00000000,
            Shader.TileMode.CLAMP
        );
        gradPaint.setShader(grad);
        canvas.drawRect(0, viewHeight - gradientHeight, viewWidth, viewHeight, gradPaint);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        viewWidth = w;
        viewHeight = h;
        numColumns = Math.max(2, w / (iconSizePx + gapPx) * 2);
        drops = new IconDrop[numColumns];
        for (int i = 0; i < numColumns; i++) {
            drops[i] = new IconDrop();
            drops[i].y = -random.nextInt(h + iconSizePx);
            drops[i].speed = SPEED_MIN + random.nextFloat() * 0.7f;
            drops[i].offset = random.nextInt(iconSizePx * 2);
            if (random.nextBoolean()) {
                drops[i].tilt = -32f + random.nextFloat() * 20f;
            } else {
                drops[i].tilt = 12f + random.nextFloat() * 20f;
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Don't intercept touch events, pass them to parent
        return false;
    }
} 