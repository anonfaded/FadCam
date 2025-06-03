// ----- Fix Start: Create IconRainView.java for onboarding animated icons (clone of CoffeeRainView, independent) -----
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
import android.view.View;

import androidx.annotation.Nullable;

import com.fadcam.R;

import java.util.Random;

/**
 * IconRainView: Animated falling icons for onboarding slides.
 * This is independent from CoffeeRainView and uses unknown_icon3.png.
 */
public class IconRainView extends View {
    private static class IconDrop {
        float y, speed, offset;
        float tilt;
        int iconType; // 0-2 for different icons
    }

    private IconDrop[] drops;
    private int numColumns;
    private int iconSizePx;
    private int gapPx;
    private int viewWidth, viewHeight;
    private Bitmap[] iconBitmaps; // Array of different app icons
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler();
    private static final int FRAME_DELAY = 20; // ms
    private static final float SPEED_MIN = 1.0f;
    private static final float SPEED_MAX = 2.2f;
    private final Random random = new Random();

    public IconRainView(Context context) {
        super(context);
        init(context);
    }
    public IconRainView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }
    public IconRainView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        iconSizePx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24, context.getResources().getDisplayMetrics());
        gapPx = 0; // denser, no gap
        
        // Load different app icons
        iconBitmaps = new Bitmap[6];
        iconBitmaps[0] = BitmapFactory.decodeResource(context.getResources(), R.mipmap.ic_launcher);
        iconBitmaps[1] = BitmapFactory.decodeResource(context.getResources(), R.mipmap.ic_launcher_2);
        iconBitmaps[2] = BitmapFactory.decodeResource(context.getResources(), R.mipmap.ic_launcher_faded);
        iconBitmaps[3] = BitmapFactory.decodeResource(context.getResources(), R.mipmap.ic_launcher_noor);
        iconBitmaps[4] = BitmapFactory.decodeResource(context.getResources(), R.mipmap.ic_launcher_bat);
        iconBitmaps[5] = BitmapFactory.decodeResource(context.getResources(), R.mipmap.ic_launcher_redbinary);
        
        paint.setAlpha((int) (0.45f * 255)); // More transparent than coffee
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
                drop.speed = SPEED_MIN + random.nextFloat() * (SPEED_MAX - SPEED_MIN);
                drop.offset = random.nextInt(iconSizePx * 2);
                drop.iconType = random.nextInt(iconBitmaps.length);
                
                // Vary the tilt for more natural look
                if (random.nextBoolean()) {
                    drop.tilt = -25f + random.nextFloat() * 15f; // -25 to -10
                } else {
                    drop.tilt = 10f + random.nextFloat() * 15f; // +10 to +25
                }
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (iconBitmaps == null || drops == null) return;
        
        for (int i = 0; i < numColumns; i++) {
            // Evenly distribute columns from left to right
            float x = i * (iconSizePx + gapPx);
            IconDrop drop = drops[i];
            float y = drop.y;
            
            // Choose icon based on drop's iconType
            Bitmap iconBitmap = iconBitmaps[drop.iconType];
            if (iconBitmap != null) {
                Rect dest = new Rect((int)x, (int)y, (int)x + iconSizePx, (int)y + iconSizePx);
                canvas.save();
                canvas.rotate(drop.tilt, x + iconSizePx / 2f, y + iconSizePx / 2f);
                canvas.drawBitmap(iconBitmap, null, dest, paint);
                canvas.restore();
            }
        }
        
        // Draw a gradient at the bottom (fade out)
        int gradientHeight = (int) (viewHeight * 0.35f);
        Paint gradPaint = new Paint();
        LinearGradient grad = new LinearGradient(
            0, viewHeight, // start at bottom
            0, viewHeight - gradientHeight, // end at top of gradient
            0xBB000000, 0x00000000, // Black to transparent
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
        
        // Fill the width with columns - matrix style
        numColumns = Math.max(1, w / (iconSizePx + gapPx));
        drops = new IconDrop[numColumns];
        
        for (int i = 0; i < numColumns; i++) {
            drops[i] = new IconDrop();
            drops[i].y = -random.nextInt(h + iconSizePx); // randomize initial y position
            drops[i].speed = SPEED_MIN + random.nextFloat() * (SPEED_MAX - SPEED_MIN);
            drops[i].offset = random.nextInt(iconSizePx * 2);
            drops[i].iconType = random.nextInt(iconBitmaps.length); // Random icon for variety
            
            // Random tilt but always tilted
            if (random.nextBoolean()) {
                drops[i].tilt = -25f + random.nextFloat() * 15f; // -25 to -10
            } else {
                drops[i].tilt = 10f + random.nextFloat() * 15f; // +10 to +25
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacksAndMessages(null);
    }
}
// ----- Fix End: Create IconRainView.java for onboarding animated icons (clone of CoffeeRainView, independent) ----- 