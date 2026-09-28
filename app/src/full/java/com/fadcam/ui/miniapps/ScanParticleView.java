package com.fadcam.ui.miniapps;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * Custom View that draws animated glowing crimson particles drifting inside the QR scan frame.
 * Uses software rendering layer so Paint.setShadowLayer glow effects work on drawCircle.
 */
public class ScanParticleView extends View {

    private static final int N = 16;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rng = new Random();

    private final float[] px    = new float[N];
    private final float[] py    = new float[N];
    private final float[] vx    = new float[N];
    private final float[] vy    = new float[N];
    private final float[] alpha = new float[N];
    private final float[] size  = new float[N];

    private boolean running;
    private boolean initialized;

    public ScanParticleView(Context ctx) {
        super(ctx);
        setup();
    }

    public ScanParticleView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        setup();
    }

    public ScanParticleView(Context ctx, AttributeSet attrs, int defStyle) {
        super(ctx, attrs, defStyle);
        setup();
    }

    private void setup() {
        // Required: software layer so setShadowLayer works with drawCircle
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    private void initParticles() {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        float dp = getContext().getResources().getDisplayMetrics().density;

        for (int i = 0; i < N; i++) {
            px[i] = rng.nextFloat() * w;
            py[i] = rng.nextFloat() * h;

            float speed = (0.4f + rng.nextFloat() * 1.1f) * dp;
            double angle = rng.nextDouble() * Math.PI * 2.0;
            vx[i] = (float) (Math.cos(angle) * speed);
            vy[i] = (float) (Math.sin(angle) * speed);

            alpha[i] = 0.25f + rng.nextFloat() * 0.75f;
            size[i]  = (1.8f + rng.nextFloat() * 3.2f) * dp;
        }
        initialized = true;
    }

    /** Call to start the particle animation and make the view visible. */
    public void startAnimation() {
        running     = true;
        initialized = false;
        setVisibility(VISIBLE);
        postInvalidateOnAnimation();
    }

    /** Call to stop the particle animation and hide the view. */
    public void stopAnimation() {
        running = false;
        setVisibility(GONE);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!running) return;

        if (!initialized) {
            initParticles();
            if (!initialized) {
                postInvalidateOnAnimation();
                return;
            }
        }

        int w = getWidth();
        int h = getHeight();

        for (int i = 0; i < N; i++) {
            // Move
            px[i] += vx[i];
            py[i] += vy[i];

            // Bounce off edges
            if (px[i] < 0)  { px[i] = 0;  vx[i] =  Math.abs(vx[i]); }
            if (px[i] > w)  { px[i] = w;  vx[i] = -Math.abs(vx[i]); }
            if (py[i] < 0)  { py[i] = 0;  vy[i] =  Math.abs(vy[i]); }
            if (py[i] > h)  { py[i] = h;  vy[i] = -Math.abs(vy[i]); }

            // Pulse alpha randomly
            alpha[i] += (rng.nextFloat() - 0.45f) * 0.06f;
            if (alpha[i] < 0.12f) alpha[i] = 0.12f;
            if (alpha[i] > 1.0f)  alpha[i] = 1.0f;

            int a = (int) (alpha[i] * 255f);

            // Outer glow: semi-transparent crimson shadow
            int glowAlpha = (a / 2) & 0xFF;
            paint.setShadowLayer(size[i] * 2.8f, 0f, 0f, (glowAlpha << 24) | 0x00D32F2F);

            // Core: bright warm-red, fully opaque at max alpha
            paint.setARGB(a, 255, 70, 70);

            canvas.drawCircle(px[i], py[i], size[i], paint);
        }

        // Schedule next frame at display refresh rate
        postInvalidateOnAnimation();
    }
}
