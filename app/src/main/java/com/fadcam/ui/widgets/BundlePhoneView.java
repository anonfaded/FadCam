package com.fadcam.ui.widgets;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.fadcam.R;

/**
 * Programmatic phone mockup for the Pro bundle card. Only the upper half of a stylised
 * premium phone is shown, dissolving into the card at an angle that matches the device tilt.
 *
 * <p>The screen boots like a real device: black glass, a brief power-on glow, the FadSec Lab
 * flag as the boot emblem, then the home screen appears — status bar (time, signal, Wi-Fi,
 * battery) and the three Pro apps popping in on a pure-black wallpaper.
 *
 * <p>Pure Canvas drawing: crisp at any size, no bitmaps, no nested views. Motion is limited
 * to a one-time entrance settle, the boot sequence, and a very slow, tiny float.
 */
public class BundlePhoneView extends View {
    private static final float ROTATION = -5f;   // gentle tilt, like a product shot
    private static final float ASPECT = 2.05f;   // height / width of the phone body
    private static final float BOOT_MS = 3100f;  // boot sequence length

    private static final int[] ICON_RES = {
            R.mipmap.ic_launcher_notes,
            R.mipmap.ic_launcher_calculator,
            R.mipmap.ic_launcher_weather
    };
    private static final int[] LABEL_RES = {
            R.string.pro_screen_notes,
            R.string.pro_screen_calculator,
            R.string.pro_screen_weather
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF phone = new RectF();
    private final Drawable[] icons = new Drawable[3];
    private final String[] labels = new String[3];
    private final Drawable bootFlag;
    private final Typeface labelTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private float bootProgress = 1f; // 1 = fully booted (also the state when motion is off)
    private ValueAnimator bootAnimator;
    private ValueAnimator floatAnimator;

    public BundlePhoneView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        fadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        bootFlag = ContextCompat.getDrawable(context, R.drawable.fadseclab_flag);
        for (int i = 0; i < 3; i++) {
            icons[i] = ContextCompat.getDrawable(context, ICON_RES[i]);
            labels[i] = context.getString(LABEL_RES[i]);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;
        // The phone is deliberately taller than the view: only its upper part is shown.
        float pw = Math.min(w * 0.52f, dp(158));
        float ph = pw * ASPECT;
        float cx = w / 2f;
        float top = Math.max(dp(10), h * 0.05f);
        phone.set(cx - pw / 2f, top, cx + pw / 2f, top + ph);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (phone.width() <= 0f) return;
        int layer = canvas.saveLayer(0f, 0f, getWidth(), getHeight(), null);
        canvas.rotate(ROTATION, getWidth() / 2f, getHeight() / 2f);
        drawPhone(canvas);
        drawFade(canvas);
        canvas.restoreToCount(layer);
    }

    /** Dissolve the lower part of the device, aligned with the phone's tilt (never the screen). */
    private void drawFade(Canvas canvas) {
        float cy = getHeight() / 2f;
        float cos = (float) Math.cos(Math.toRadians(ROTATION));
        float fadeEnd = cy + (getHeight() - cy) / cos;
        float fadeStart = fadeEnd - dp(44);
        fadePaint.setShader(new LinearGradient(0f, fadeStart, 0f, fadeEnd,
                0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
        canvas.drawRect(-getWidth(), fadeStart, getWidth() * 2f, fadeEnd + phone.height(), fadePaint);
    }

    private void drawPhone(Canvas canvas) {
        float pw = phone.width();
        float ph = phone.height();
        float r = pw * 0.135f;

        // Ambient shadow (stacked rounded rects — cheap and hardware safe).
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setXfermode(null);
        for (int i = 0; i < 3; i++) {
            float grow = pw * (0.02f + i * 0.03f);
            paint.setColor(i == 0 ? 0x1C000000 : (i == 1 ? 0x12000000 : 0x0A000000));
            RectF shadow = new RectF(phone);
            shadow.inset(-grow, -grow * 0.5f);
            shadow.offset(0f, ph * 0.012f + grow * 0.35f);
            canvas.drawRoundRect(shadow, r + grow, r + grow, paint);
        }

        // Physical buttons, drawn before the body so they read as attached to the rails.
        float bw = Math.max(2.5f, pw * 0.022f);
        float br = bw * 0.55f;
        paint.setColor(0xFF4A4D56);
        float powerTop = phone.top + ph * 0.235f;
        float powerH = ph * 0.085f;
        canvas.drawRoundRect(new RectF(phone.right - bw * 0.35f, powerTop,
                phone.right + bw * 0.65f, powerTop + powerH), br, br, paint);
        float volTop = phone.top + ph * 0.185f;
        float volH = ph * 0.060f;
        float volGap = ph * 0.013f;
        canvas.drawRoundRect(new RectF(phone.left - bw * 0.65f, volTop,
                phone.left + bw * 0.35f, volTop + volH), br, br, paint);
        canvas.drawRoundRect(new RectF(phone.left - bw * 0.65f, volTop + volH + volGap,
                phone.left + bw * 0.35f, volTop + volH + volGap + volH), br, br, paint);

        // Metal frame: the outer body is the side rail.
        paint.setShader(new LinearGradient(0f, phone.top, 0f, phone.bottom,
                0xFF3A3D45, 0xFF1F2126, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(phone, r, r, paint);
        // Front face inset leaves a visible rail around the glass.
        float rail = Math.max(2f, pw * 0.022f);
        RectF face = new RectF(phone);
        face.inset(rail, rail);
        float faceR = r - rail;
        paint.setShader(new LinearGradient(0f, face.top, 0f, face.bottom,
                0xFF1E2026, 0xFF101216, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(face, faceR, faceR, paint);
        // Rail highlight + crease.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, pw * 0.006f));
        paint.setShader(new LinearGradient(0f, phone.top, 0f, phone.bottom,
                0x3AFFFFFF, 0x08FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(phone, r, r, paint);
        paint.setShader(null);
        paint.setColor(0x30000000);
        canvas.drawRoundRect(face, faceR, faceR, paint);

        // Screen: thin bezel, pure black wallpaper (OLED style), subtle inset.
        float bezel = Math.max(2f, pw * 0.030f);
        RectF screen = new RectF(face);
        screen.inset(bezel, bezel);
        float screenR = faceR - bezel;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF000000);
        canvas.drawRoundRect(screen, screenR, screenR, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, pw * 0.006f));
        paint.setColor(0x30000000);
        canvas.drawRoundRect(screen, screenR, screenR, paint);
        paint.setStyle(Paint.Style.FILL);

        drawBootSequence(canvas, screen, pw, screenR);
    }

    /** Screen contents: power-on, boot emblem, then the home screen assembling itself. */
    private void drawBootSequence(Canvas canvas, RectF screen, float pw, float screenR) {
        float p = bootProgress;
        float flagIn = easeOut(seg(p, 0.06f, 0.30f));
        float flagOut = easeOut(seg(p, 0.44f, 0.58f));
        float flagA = flagIn * (1f - flagOut);
        float flagS = 0.90f + 0.10f * flagIn + 0.03f * flagOut;
        float powerFlash = 0.09f * (1f - easeOut(seg(p, 0.02f, 0.12f)));
        float statusA = easeOut(seg(p, 0.52f, 0.68f));

        // Glass reflection — always on; it is what makes the screen read as glass.
        paint.setShader(new LinearGradient(0f, screen.top, 0f, screen.top + screen.height() * 0.35f,
                0x16FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(screen, screenR, screenR, paint);
        paint.setShader(null);

        // Power-on flash.
        if (powerFlash > 0.001f) {
            paint.setColor(withAlpha(0xFFFFFFFF, powerFlash));
            canvas.drawRoundRect(screen, screenR, screenR, paint);
        }

        // Boot emblem: the FadSec Lab flag, with a soft glow so its dark silhouette reads
        // against the black glass.
        if (flagA > 0.001f && bootFlag != null) {
            // Centred in the visible (uncropped) part of the screen, not the full panel.
            float flagSize = screen.width() * 0.50f;
            float cx = screen.centerX();
            float cy = screen.top + screen.height() * 0.26f;
            paint.setShader(new RadialGradient(cx, cy, flagSize * 0.95f,
                    new int[]{withAlpha(0xFF23252C, flagA), withAlpha(0xFF0B0C10, flagA * 0.6f), 0x00000000},
                    new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, flagSize * 0.95f, paint);
            paint.setShader(null);
            int layer = canvas.saveLayer(screen, null);
            float half = flagSize * flagS / 2f;
            bootFlag.setBounds(Math.round(cx - half), Math.round(cy - half),
                    Math.round(cx + half), Math.round(cy + half));
            bootFlag.setAlpha(Math.round(255 * flagA));
            bootFlag.draw(canvas);
            canvas.restoreToCount(layer);
        }

        // Status bar.
        if (statusA > 0.001f) drawStatusBar(canvas, screen, pw, statusA);

        // The three Pro apps, popping in one after another on a black home screen.
        float iconSize = pw * 0.225f;
        float gap = pw * 0.065f;
        float totalW = iconSize * 3 + gap * 2;
        float startX = screen.centerX() - totalW / 2f;
        float iconTop = screen.top + screen.height() * 0.17f;
        for (int i = 0; i < 3; i++) {
            float start = 0.56f + i * 0.09f;
            float a = easeOut(seg(p, start, start + 0.20f));
            if (a <= 0.001f) continue;
            float s = 0.55f + 0.45f * a;
            float left = startX + i * (iconSize + gap);
            float centerX = left + iconSize / 2f;
            float centerY = iconTop + iconSize / 2f;
            canvas.save();
            canvas.scale(s, s, centerX, centerY);
            paint.setColor(withAlpha(0xFF222329, a));
            canvas.drawRoundRect(new RectF(left, iconTop, left + iconSize, iconTop + iconSize),
                    iconSize * 0.235f, iconSize * 0.235f, paint);
            Drawable icon = icons[i];
            if (icon != null) {
                int layer = canvas.saveLayer(new RectF(left, iconTop, left + iconSize, iconTop + iconSize), null);
                icon.setBounds(Math.round(left), Math.round(iconTop),
                        Math.round(left + iconSize), Math.round(iconTop + iconSize));
                icon.setAlpha(Math.round(255 * a));
                icon.draw(canvas);
                canvas.restoreToCount(layer);
            }
            canvas.restore();
            float labelA = easeOut(seg(p, start + 0.06f, start + 0.22f));
            if (labelA > 0.001f) {
                paint.setTypeface(labelTypeface);
                paint.setTextSize(pw * 0.052f);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setColor(withAlpha(0xFFE9E9EC, labelA));
                canvas.drawText(labels[i], centerX, iconTop + iconSize + pw * 0.082f, paint);
                paint.setTextAlign(Paint.Align.LEFT);
            }
        }
    }

    /** Minimal, hand-drawn status bar: time on the left, signal / Wi-Fi / battery on the right. */
    private void drawStatusBar(Canvas canvas, RectF screen, float pw, float alpha) {
        float unit = pw * 0.052f;
        float mid = screen.top + pw * 0.095f;
        paint.setTypeface(labelTypeface);
        paint.setTextSize(unit);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setColor(withAlpha(0xFFE9E9EC, alpha));
        canvas.drawText("9:41", screen.left + pw * 0.105f, mid + unit * 0.36f, paint);

        int color = withAlpha(0xFFDCDCDC, alpha * 0.92f);
        float right = screen.right - pw * 0.105f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, pw * 0.007f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(color);

        // Battery: outline, cap, and a fill.
        float bw = unit * 1.55f;
        float bh = unit * 0.80f;
        RectF battery = new RectF(right - bw, mid - bh / 2f, right, mid + bh / 2f);
        canvas.drawRoundRect(battery, bh * 0.30f, bh * 0.30f, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(new RectF(battery.right + unit * 0.05f, mid - bh * 0.18f,
                battery.right + unit * 0.12f, mid + bh * 0.18f), unit * 0.04f, unit * 0.04f, paint);
        canvas.drawRoundRect(new RectF(battery.left + unit * 0.09f, battery.top + unit * 0.09f,
                battery.left + unit * 0.09f + (bw - unit * 0.18f) * 0.7f, battery.bottom - unit * 0.09f),
                unit * 0.04f, unit * 0.04f, paint);

        // Wi-Fi: three arcs + dot.
        float wx = right - bw - unit * 0.95f;
        float wy = mid + bh * 0.42f;
        paint.setStyle(Paint.Style.STROKE);
        for (float rr : new float[]{unit * 0.55f, unit * 0.37f, unit * 0.19f}) {
            canvas.drawArc(new RectF(wx - rr, wy - rr, wx + rr, wy + rr), 225f, 90f, false, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(wx, wy, unit * 0.075f, paint);

        // Signal: four ascending bars.
        float sx = wx - unit * 1.15f;
        float barW = unit * 0.15f;
        for (int i = 0; i < 4; i++) {
            float barH = unit * (0.20f + i * 0.15f);
            float left = sx - barW * 4f - unit * 0.10f * 3f + i * (barW + unit * 0.10f);
            canvas.drawRoundRect(new RectF(left, mid + bh * 0.42f - barH, left + barW, mid + bh * 0.42f),
                    barW * 0.35f, barW * 0.35f, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!motionAllowed()) {
            bootProgress = 1f;
            setAlpha(1f);
            setScaleX(1f);
            setScaleY(1f);
            setTranslationY(0f);
            return;
        }
        // Settle in, boot the phone, then let it float very slowly.
        setAlpha(0f);
        setScaleX(0.95f);
        setScaleY(0.95f);
        setTranslationY(dp(14));
        animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(480).setInterpolator(new DecelerateInterpolator())
                .withEndAction(this::startBoot).start();
    }

    private void startBoot() {
        if (!motionAllowed()) return;
        bootProgress = 0f;
        invalidate();
        bootAnimator = ValueAnimator.ofFloat(0f, 1f);
        bootAnimator.setDuration((long) BOOT_MS);
        bootAnimator.addUpdateListener(a -> {
            bootProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        bootAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                bootProgress = 1f;
                invalidate();
                startFloat();
            }
        });
        bootAnimator.start();
    }

    private void startFloat() {
        if (!motionAllowed() || floatAnimator != null) return;
        floatAnimator = ValueAnimator.ofFloat(0f, 1f);
        floatAnimator.setDuration(3600);
        floatAnimator.setRepeatCount(ValueAnimator.INFINITE);
        floatAnimator.setRepeatMode(ValueAnimator.REVERSE);
        floatAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        floatAnimator.addUpdateListener(a ->
                setTranslationY(-dp(2.4f) * (float) a.getAnimatedValue()));
        floatAnimator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        animate().cancel();
        if (bootAnimator != null) {
            bootAnimator.cancel();
            bootAnimator = null;
        }
        if (floatAnimator != null) {
            floatAnimator.cancel();
            floatAnimator = null;
        }
        super.onDetachedFromWindow();
    }

    private static float seg(float p, float a, float b) {
        if (p <= a) return 0f;
        if (p >= b) return 1f;
        return (p - a) / (b - a);
    }

    private static float easeOut(float t) {
        float u = 1f - t;
        return 1f - u * u * u;
    }

    private static int withAlpha(int color, float alpha) {
        float a = Math.max(0f, Math.min(1f, alpha));
        return (color & 0x00FFFFFF) | (Math.round(255 * a) << 24);
    }

    private boolean motionAllowed() {
        return Settings.Global.getFloat(getContext().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
