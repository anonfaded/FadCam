package com.fadcam.ui.widgets;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.fadcam.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * One face of the {@link ProBundleShowcaseView} phone, drawn entirely on Canvas.
 *
 * <p>Front: a small working launcher — live clock, Wi-Fi, industry-style battery, the three Pro
 * apps (with page dots), a lock screen (status bar + camera + real time + usage Easter egg +
 * animated swipe-up hint), a minimal notification shade with a discreet weather-style
 * recording notification, a volume HUD, and tappable mini app previews (Notes with real
 * typing, a working Calculator, a Weather card). Back: camera visor + etched FadSec Lab flag.
 */
public class BundlePhoneSurfaceView extends View {
    public static final int SIDE_FRONT = 0;
    public static final int SIDE_BACK = 1;

    private static final float ASPECT = 2.05f;
    private static final float SPLASH_MS = 3000f;

    private static final String[] CALC_KEYS = {"7", "8", "9", "÷", "4", "5", "6", "×",
            "1", "2", "3", "−", "C", "0", "=", "+"};

    private final int side;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF phone = new RectF();
    private final RectF screen = new RectF();
    private final RectF powerBtn = new RectF();
    private final RectF volUpBtn = new RectF();
    private final RectF volDownBtn = new RectF();
    private final RectF previewBackRect = new RectF();
    private final RectF calcGrid = new RectF();
    private final RectF visorRect = new RectF();
    private final RectF[] iconRects = {new RectF(), new RectF(), new RectF()};
    private final Path bolt = new Path();
    private final Drawable[] icons = new Drawable[3];
    private final String[] labels = new String[3];
    private final Drawable flag;
    private final Typeface labelTypeface;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("EEEE, MMM d", Locale.getDefault());

    private float screenR;
    private float statusBarMid;
    private float statusBarBottom;
    private float iconTopY;
    private float iconSizePx;

    private String clockText = "9:41";
    private String dateText = "";
    private String usageText = "";
    private int batteryPercent = -1;
    private boolean charging = false;
    private boolean splashEnabled = true;
    private boolean cropFade = true;
    private float splashProgress = 1f;
    private ValueAnimator splashAnimator;

    private float unlockProgress = 1f; // start on the home screen; the power button locks it
    private float shadeProgress = 0f;
    private float volumeLevel = 0.5f;
    private float volumeHudAlpha = 0f;
    private ValueAnimator unlockAnimator;
    private ValueAnimator shadeAnimator;
    private ValueAnimator volumeAnimator;
    private ValueAnimator lockBobAnimator;
    private float lockBob = 0f;

    private int previewApp = 0; // 0 none, 1 notes, 2 calculator, 3 weather
    private String noteText = "";
    private String calcDisplay = "0";
    private double calcAccum = 0;
    private String calcOp = null;
    private boolean calcFresh = true;

    private float appPulse = 1f;
    private int pulseIndex = -1;
    private ValueAnimator pulseAnimator;
    private float captionAlpha = 0f;
    private ValueAnimator captionAnimator;

    private final Runnable shadeTicker = new Runnable() {
        @Override
        public void run() {
            if (shadeProgress > 0.01f) {
                invalidate();
                postDelayed(this, 1000L);
            }
        }
    };
    private final long bootAt = SystemClock.elapsedRealtime();

    public BundlePhoneSurfaceView(Context context, int side) {
        super(context);
        this.side = side;
        fadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        Typeface ubuntu = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.ubuntu_regular);
        labelTypeface = ubuntu != null ? ubuntu : Typeface.create("sans-serif", Typeface.NORMAL);
        flag = ContextCompat.getDrawable(context, R.drawable.fadseclab_flag);
        int[] iconRes = {R.mipmap.ic_launcher_notes, R.mipmap.ic_launcher_calculator, R.mipmap.ic_launcher_weather};
        int[] labelRes = {R.string.pro_screen_notes, R.string.pro_screen_calculator, R.string.pro_screen_weather};
        for (int i = 0; i < 3; i++) {
            icons[i] = ContextCompat.getDrawable(context, iconRes[i]);
            labels[i] = context.getString(labelRes[i]);
        }
        setClickable(false);
        setFocusable(false);
        refreshDate();
    }

    // ------------------------------------------------------------- public API

    public void setClockText(String text) {
        if (side != SIDE_FRONT || text == null || text.equals(clockText)) return;
        clockText = text;
        refreshDate();
        invalidate();
    }

    public void setBattery(int percent, boolean isCharging) {
        if (side != SIDE_FRONT || (percent == batteryPercent && isCharging == charging)) return;
        batteryPercent = percent;
        charging = isCharging;
        invalidate();
    }

    public void setApps(int[] iconRes, String[] names) {
        if (side != SIDE_FRONT || iconRes == null || names == null
                || iconRes.length != 3 || names.length != 3) return;
        for (int i = 0; i < 3; i++) {
            icons[i] = ContextCompat.getDrawable(getContext(), iconRes[i]);
            labels[i] = names[i];
        }
        invalidate();
    }

    /** The "With FadCam for …" line shown on the lock screen. */
    public void setUsageText(String text) {
        usageText = text == null ? "" : text;
        invalidate();
    }

    public void setSplashEnabled(boolean enabled) {
        splashEnabled = enabled;
    }

    /** The bottom crop fade — disabled when the whole device is shown (fullscreen mode). */
    public void setCropFade(boolean enabled) {
        cropFade = enabled;
        invalidate();
    }

    public void startSplash() {
        if (side != SIDE_FRONT) return;
        if (!splashEnabled || !motionAllowed()) {
            settleSplash();
            return;
        }
        splashProgress = 0f;
        invalidate();
        splashAnimator = ValueAnimator.ofFloat(0f, 1f);
        splashAnimator.setDuration((long) SPLASH_MS);
        splashAnimator.addUpdateListener(a -> {
            splashProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        splashAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                settleSplash();
            }
        });
        splashAnimator.start();
    }

    public void settleSplash() {
        if (splashAnimator != null) {
            splashAnimator.cancel();
            splashAnimator = null;
        }
        splashProgress = 1f;
        invalidate();
    }

    // ------------------------------------------------------- interaction API

    public boolean isLocked() {
        return unlockProgress < 0.5f;
    }

    public float getUnlockProgress() {
        return unlockProgress;
    }

    public void setUnlockProgress(float p) {
        unlockProgress = clamp(p);
        updateLockBob();
        invalidate();
    }

    public void animateUnlock(float target) {
        if (unlockAnimator != null) unlockAnimator.cancel();
        if (!motionAllowed()) {
            setUnlockProgress(target);
            return;
        }
        unlockAnimator = ValueAnimator.ofFloat(unlockProgress, clamp(target));
        unlockAnimator.setDuration(280);
        unlockAnimator.setInterpolator(new DecelerateInterpolator(1.6f));
        unlockAnimator.addUpdateListener(a -> setUnlockProgress((float) a.getAnimatedValue()));
        unlockAnimator.start();
    }

    public float getShadeProgress() {
        return shadeProgress;
    }

    public void setShadeProgress(float p) {
        shadeProgress = clamp(p);
        if (shadeProgress > 0.01f) {
            removeCallbacks(shadeTicker);
            postDelayed(shadeTicker, 1000L);
        } else {
            removeCallbacks(shadeTicker);
        }
        invalidate();
    }

    public void animateShade(float target) {
        if (shadeAnimator != null) shadeAnimator.cancel();
        if (!motionAllowed()) {
            setShadeProgress(target);
            return;
        }
        shadeAnimator = ValueAnimator.ofFloat(shadeProgress, clamp(target));
        shadeAnimator.setDuration(260);
        shadeAnimator.setInterpolator(new DecelerateInterpolator(1.6f));
        shadeAnimator.addUpdateListener(a -> setShadeProgress((float) a.getAnimatedValue()));
        shadeAnimator.start();
    }

    public void showVolumeHud(float level) {
        volumeLevel = clamp(level);
        volumeHudAlpha = 1f;
        invalidate();
        if (volumeAnimator != null) volumeAnimator.cancel();
        if (!motionAllowed()) {
            volumeHudAlpha = 0f;
            return;
        }
        volumeAnimator = ValueAnimator.ofFloat(1f, 0f);
        volumeAnimator.setStartDelay(950);
        volumeAnimator.setDuration(380);
        volumeAnimator.addUpdateListener(a -> {
            volumeHudAlpha = (float) a.getAnimatedValue();
            invalidate();
        });
        volumeAnimator.start();
    }

    public boolean isOnPowerButton(float x, float y) {
        return powerBtn.contains(x, y);
    }

    public int volumeButtonAt(float x, float y) {
        if (volUpBtn.contains(x, y)) return 1;
        if (volDownBtn.contains(x, y)) return -1;
        return 0;
    }

    public boolean isInStatusBar(float x, float y) {
        return y <= statusBarBottom && y >= phone.top;
    }

    /** The camera visor's rect (parent coordinates) — the showcase models it as a 3D element. */
    public RectF getVisorRect() {
        return new RectF(visorRect);
    }

    public RectF getPowerBtnRect() {
        return new RectF(powerBtn);
    }

    public RectF getVolUpRect() {
        return new RectF(volUpBtn);
    }

    public RectF getVolDownRect() {
        return new RectF(volDownBtn);
    }

    // ---------------------------------------------------------- app previews

    /** 1..3 when the touch lands on an app icon, 0 otherwise. */
    public int appIconAt(float x, float y) {
        if (previewApp != 0 || unlockProgress < 0.999f || shadeProgress > 0.01f) return 0;
        for (int i = 0; i < 3; i++) {
            if (iconRects[i].contains(x, y)) return i + 1;
        }
        return 0;
    }

    public boolean previewVisible() {
        return previewApp != 0;
    }

    public void openPreview(int app) {
        previewApp = app;
        if (app != 2) {
            calcDisplay = "0";
            calcAccum = 0;
            calcOp = null;
            calcFresh = true;
        }
        invalidate();
    }

    public void closePreview() {
        previewApp = 0;
        invalidate();
    }

    /** The back chevron inside a preview. */
    public boolean closePreviewAt(float x, float y) {
        return previewApp != 0 && previewBackRect.contains(x, y);
    }

    /** The calculator key index (0..15) or -1. */
    public int calculatorKeyAt(float x, float y) {
        if (previewApp != 2 || !calcGrid.contains(x, y)) return -1;
        int col = (int) ((x - calcGrid.left) / (calcGrid.width() / 4f));
        int row = (int) ((y - calcGrid.top) / (calcGrid.height() / 4f));
        col = Math.max(0, Math.min(3, col));
        row = Math.max(0, Math.min(3, row));
        return row * 4 + col;
    }

    public void calculatorPress(int index) {
        if (index < 0 || index >= CALC_KEYS.length) return;
        String key = CALC_KEYS[index];
        if (key.matches("[0-9]")) {
            calcDisplay = calcFresh || calcDisplay.equals("0") ? key : calcDisplay + key;
            calcFresh = false;
        } else if (key.equals("C")) {
            calcDisplay = "0";
            calcAccum = 0;
            calcOp = null;
            calcFresh = true;
        } else if (key.equals("=")) {
            if (calcOp != null) {
                calcDisplay = format(apply(calcAccum, parse(calcDisplay), calcOp));
                calcOp = null;
                calcFresh = true;
            }
        } else {
            if (calcOp != null && !calcFresh) {
                calcDisplay = format(apply(calcAccum, parse(calcDisplay), calcOp));
            }
            calcAccum = parse(calcDisplay);
            calcOp = key;
            calcFresh = true;
        }
        invalidate();
    }

    public void setNote(String text) {
        noteText = text == null ? "" : text;
        invalidate();
    }

    /** A quick scale pulse on one app icon (the first-tap nudge). */
    public void pulseApp(int appIndex, boolean showCaption) {
        if (pulseAnimator != null) pulseAnimator.cancel();
        pulseIndex = appIndex;
        if (showCaption) {
            captionAlpha = 1f;
            if (captionAnimator != null) captionAnimator.cancel();
            if (motionAllowed()) {
                captionAnimator = ValueAnimator.ofFloat(1f, 0f);
                captionAnimator.setStartDelay(1700);
                captionAnimator.setDuration(420);
                captionAnimator.addUpdateListener(a -> {
                    captionAlpha = (float) a.getAnimatedValue();
                    invalidate();
                });
                captionAnimator.start();
            }
        }
        if (!motionAllowed()) {
            appPulse = 1f;
            invalidate();
            return;
        }
        pulseAnimator = ValueAnimator.ofFloat(1f, 1.16f, 1f);
        pulseAnimator.setDuration(420);
        pulseAnimator.setInterpolator(new DecelerateInterpolator(1.4f));
        pulseAnimator.addUpdateListener(a -> {
            appPulse = (float) a.getAnimatedValue();
            invalidate();
        });
        pulseAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                pulseIndex = -1;
                appPulse = 1f;
            }
        });
        pulseAnimator.start();
    }

    // ------------------------------------------------------------------ draw

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        computeGeometry(w, h);
    }

    private void computeGeometry(float w, float h) {
        if (w <= 0f || h <= 0f) return;
        float pw = w * 0.95f;
        float ph = pw * ASPECT;
        phone.set(w * 0.025f, h * 0.012f, w * 0.975f, h * 0.012f + ph);
        float rail = Math.max(1.5f, pw * 0.014f);
        RectF face = new RectF(phone);
        face.inset(rail, rail);
        float faceR = pw * 0.135f - rail;
        float bezel = Math.max(1.5f, pw * 0.016f);
        screen.set(face);
        screen.inset(bezel, bezel);
        screenR = faceR - bezel;
        statusBarMid = screen.top + pw * 0.078f;
        statusBarBottom = statusBarMid + pw * 0.070f;

        float powerTop = phone.top + ph * 0.235f;
        float powerH = ph * 0.085f;
        powerBtn.set(phone.right - pw * 0.075f, powerTop, phone.right + pw * 0.04f, powerTop + powerH);
        float volTop = phone.top + ph * 0.185f;
        float volH = ph * 0.060f;
        float volGap = ph * 0.013f;
        volUpBtn.set(phone.left - pw * 0.04f, volTop, phone.left + pw * 0.075f, volTop + volH);
        volDownBtn.set(phone.left - pw * 0.04f, volTop + volH + volGap,
                phone.left + pw * 0.075f, volTop + volH + volGap + volH);

        // App icon zones (same math as the drawing).
        iconSizePx = pw * 0.225f;
        float gap = pw * 0.065f;
        float totalW = iconSizePx * 3 + gap * 2;
        float startX = screen.centerX() - totalW / 2f;
        iconTopY = screen.top + screen.height() * 0.14f;
        for (int i = 0; i < 3; i++) {
            float left = startX + i * (iconSizePx + gap);
            iconRects[i].set(left, iconTopY, left + iconSizePx, iconTopY + iconSizePx);
        }
        previewBackRect.set(screen.left + pw * 0.045f, screen.top + pw * 0.05f,
                screen.left + pw * 0.045f + pw * 0.13f, screen.top + pw * 0.05f + pw * 0.13f);
        calcGrid.set(screen.left + pw * 0.06f, screen.top + screen.height() * 0.36f,
                screen.right - pw * 0.06f, screen.top + screen.height() * 0.93f);

        float barH = pw * 0.21f;
        visorRect.set(phone.left + pw * 0.075f, phone.top + pw * 0.075f,
                phone.right - pw * 0.075f, phone.top + pw * 0.075f + barH);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) return;
        if (phone.isEmpty()) computeGeometry(w, h);

        int layer = canvas.saveLayer(0f, 0f, w, h, null);
        drawChassis(canvas);
        if (side == SIDE_FRONT) drawFront(canvas);
        else drawBack(canvas);
        if (cropFade) {
            float fadeTop = h - dp(34);
            fadePaint.setShader(new LinearGradient(0f, fadeTop, 0f, h,
                    0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
            canvas.drawRect(-w, fadeTop, 2f * w, h + phone.height(), fadePaint);
        }
        canvas.restoreToCount(layer);
    }

    private void drawChassis(Canvas canvas) {
        float pw = phone.width();
        float ph = phone.height();
        float r = pw * 0.135f;
        boolean back = side == SIDE_BACK;

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setXfermode(null);
        paint.setColorFilter(null);

        float bw = Math.max(2.5f, pw * 0.020f);
        float br = bw * 0.55f;
        paint.setColor(0xFF4A4D56);
        RectF power = back
                ? new RectF(phone.left - bw * 0.65f, powerBtn.top, phone.left + bw * 0.35f, powerBtn.bottom)
                : new RectF(phone.right - bw * 0.35f, powerBtn.top, phone.right + bw * 0.65f, powerBtn.bottom);
        canvas.drawRoundRect(power, br, br, paint);
        float volOuter = back ? phone.right : phone.left;
        float volInA = back ? volOuter - bw * 0.35f : volOuter - bw * 0.65f;
        float volInB = back ? volOuter + bw * 0.65f : volOuter + bw * 0.35f;
        canvas.drawRoundRect(new RectF(volInA, volUpBtn.top, volInB, volUpBtn.bottom), br, br, paint);
        canvas.drawRoundRect(new RectF(volInA, volDownBtn.top, volInB, volDownBtn.bottom), br, br, paint);

        paint.setShader(new LinearGradient(0f, phone.top, 0f, phone.bottom,
                0xFF3A3D45, 0xFF1F2126, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(phone, r, r, paint);
        float rail = Math.max(1.5f, pw * 0.014f);
        RectF face = new RectF(phone);
        face.inset(rail, rail);
        float faceR = r - rail;
        paint.setShader(new LinearGradient(0f, face.top, 0f, face.bottom,
                back ? 0xFF1B1D22 : 0xFF1E2026,
                back ? 0xFF0F1114 : 0xFF101216, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(face, faceR, faceR, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, pw * 0.006f));
        paint.setShader(new LinearGradient(0f, phone.top, 0f, phone.bottom,
                0x3AFFFFFF, 0x08FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(phone, r, r, paint);
        paint.setShader(null);
        paint.setColor(0x30000000);
        canvas.drawRoundRect(face, faceR, faceR, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    // ---------------------------------------------------------------- front

    private void drawFront(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(0xFF000000);
        canvas.drawRoundRect(screen, screenR, screenR, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, phone.width() * 0.005f));
        paint.setColor(0x30000000);
        canvas.drawRoundRect(screen, screenR, screenR, paint);
        paint.setStyle(Paint.Style.FILL);

        drawWakeSequence(canvas);
        drawPreview(canvas);
        drawShade(canvas);
        drawLockScreen(canvas);
        drawVolumeHud(canvas);
        drawCaption(canvas);
    }

    private void drawWakeSequence(Canvas canvas) {
        float pw = phone.width();
        float p = splashProgress;
        float flagIn = easeOut(seg(p, 0.00f, 0.24f));
        float flagOut = easeOut(seg(p, 0.46f, 0.62f));
        float flagA = flagIn * (1f - flagOut);
        float flagS = 0.92f + 0.08f * flagIn + 0.03f * flagOut;
        float powerFlash = 0.07f * (1f - easeOut(seg(p, 0.00f, 0.08f)));
        float statusA = easeOut(seg(p, 0.56f, 0.74f));

        paint.setShader(new LinearGradient(0f, screen.top, 0f, screen.top + screen.height() * 0.35f,
                0x16FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(screen, screenR, screenR, paint);
        paint.setShader(null);

        if (powerFlash > 0.001f) {
            paint.setColor(withAlpha(0xFFFFFFFF, powerFlash));
            canvas.drawRoundRect(screen, screenR, screenR, paint);
        }

        if (flagA > 0.001f && flag != null) {
            float flagSize = screen.width() * 0.50f;
            float cx = screen.centerX();
            float cy = screen.top + screen.height() * 0.24f;
            paint.setShader(new RadialGradient(cx, cy, flagSize * 0.95f,
                    new int[]{withAlpha(0xFF23252C, flagA), withAlpha(0xFF0B0C10, flagA * 0.6f), 0x00000000},
                    new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, flagSize * 0.95f, paint);
            paint.setShader(null);
            float half = flagSize * flagS / 2f;
            flag.setBounds(Math.round(cx - half), Math.round(cy - half),
                    Math.round(cx + half), Math.round(cy + half));
            flag.setAlpha(Math.round(255 * flagA));
            flag.draw(canvas);
        }

        if (statusA > 0.001f) drawStatusBar(canvas, statusA, true);

        float iconSize = iconSizePx;
        float gap = phone.width() * 0.065f;
        for (int i = 0; i < 3; i++) {
            float start = 0.62f + i * 0.08f;
            float a = easeOut(seg(p, start, start + 0.20f));
            if (a <= 0.001f) continue;
            float s = (0.60f + 0.40f * a) * (pulseIndex == i ? appPulse : 1f);
            float left = iconRects[i].left;
            float centerX = iconRects[i].centerX();
            float centerY = iconRects[i].centerY();
            Drawable icon = icons[i];
            if (icon == null) continue;
            canvas.save();
            canvas.scale(s, s, centerX, centerY);
            icon.setBounds(Math.round(left), Math.round(iconTopY),
                    Math.round(left + iconSize), Math.round(iconTopY + iconSize));
            icon.setAlpha(Math.round(255 * a));
            icon.draw(canvas);
            canvas.restore();
            float labelA = easeOut(seg(p, start + 0.05f, start + 0.22f));
            if (labelA > 0.001f) {
                paint.setTypeface(labelTypeface);
                paint.setTextSize(phone.width() * 0.058f);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setColor(withAlpha(0xFFE9E9EC, labelA));
                canvas.drawText(labels[i], centerX, iconTopY + iconSize + phone.width() * 0.082f, paint);
                paint.setTextAlign(Paint.Align.LEFT);
            }
        }

        // Page indicator: two launcher dots.
        if (p >= 0.9f) {
            float dotY = iconTopY + iconSize + phone.width() * 0.19f;
            float dotR = Math.max(1.5f, phone.width() * 0.011f);
            float dotGap = phone.width() * 0.045f;
            paint.setColor(0xE6E9E9EC);
            canvas.drawCircle(screen.centerX() - dotGap / 2f, dotY, dotR, paint);
            paint.setColor(0x4DFFFFFF);
            canvas.drawCircle(screen.centerX() + dotGap / 2f, dotY, dotR, paint);
        }
    }

    /** Clock, three-arc Wi-Fi, industry battery, punch-hole — one horizontal row. */
    private void drawStatusBar(Canvas canvas, float alpha, boolean showClock) {
        float pw = phone.width();
        float unit = pw * 0.050f;
        float mid = statusBarMid;
        paint.setTypeface(labelTypeface);
        if (showClock) {
            paint.setTextSize(unit);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setColor(withAlpha(0xFFE9E9EC, alpha));
            canvas.drawText(clockText, screen.left + pw * 0.10f, mid + unit * 0.36f, paint);
        }

        int ink = charging ? 0xFF4CD964 : (batteryPercent >= 0 && batteryPercent <= 15 ? 0xFFFF453A : 0xFFEDEDF0);
        float right = screen.right - pw * 0.10f;
        float boltW = charging ? unit * 0.95f : 0f;
        float bw = unit * 1.85f;
        float bh = unit * 0.95f;
        RectF battery = new RectF(right - boltW - bw, mid - bh / 2f, right - boltW, mid + bh / 2f);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, pw * 0.006f));
        paint.setColor(withAlpha(0x99FFFFFF, alpha));
        canvas.drawRoundRect(battery, bh * 0.32f, bh * 0.32f, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(0x99FFFFFF, alpha));
        canvas.drawRoundRect(new RectF(battery.right + unit * 0.05f, mid - bh * 0.14f,
                battery.right + unit * 0.11f, mid + bh * 0.14f), unit * 0.035f, unit * 0.035f, paint);

        if (batteryPercent >= 0) {
            float inset = unit * 0.13f;
            float trackW = bw - inset * 2f;
            float fillW = Math.max(trackW * 0.04f, trackW * batteryPercent / 100f);
            paint.setColor(withAlpha(ink, alpha));
            canvas.drawRoundRect(new RectF(battery.left + inset, battery.top + inset,
                    battery.left + inset + fillW, battery.bottom - inset), unit * 0.08f, unit * 0.08f, paint);
            paint.setTypeface(labelTypeface);
            paint.setTextSize(unit * 0.62f);
            paint.setTextAlign(Paint.Align.CENTER);
            boolean overFill = batteryPercent >= 52;
            paint.setColor(withAlpha(overFill ? 0xFF0A0B0E : 0xFFE9E9EC, alpha));
            canvas.drawText(String.valueOf(batteryPercent), battery.centerX(), mid + unit * 0.20f, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        if (charging) {
            float bx = right - boltW * 0.85f;
            float u = unit * 0.40f;
            bolt.reset();
            bolt.moveTo(bx + u * 0.62f, mid - u * 1.05f);
            bolt.lineTo(bx + u * 0.02f, mid + u * 0.12f);
            bolt.lineTo(bx + u * 0.48f, mid + u * 0.12f);
            bolt.lineTo(bx + u * 0.20f, mid + u * 1.05f);
            bolt.lineTo(bx + u * 0.95f, mid - u * 0.25f);
            bolt.lineTo(bx + u * 0.50f, mid - u * 0.25f);
            bolt.close();
            paint.setColor(withAlpha(0xFF4CD964, alpha));
            canvas.drawPath(bolt, paint);
        }

        float wx = battery.left - unit * 1.15f;
        float wy = mid + unit * 0.24f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, pw * 0.006f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(withAlpha(0xFFDCDCDC, alpha * 0.92f));
        for (float rr : new float[]{unit * 0.48f, unit * 0.31f, unit * 0.15f}) {
            canvas.drawArc(new RectF(wx - rr, wy - rr, wx + rr, wy + rr), 225f, 90f, false, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);

        float holeR = Math.max(2.5f, pw * 0.028f);
        float holeY = mid;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(0xFF05060A, alpha));
        canvas.drawCircle(screen.centerX(), holeY, holeR, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, pw * 0.005f));
        paint.setColor(withAlpha(0x33FFFFFF, alpha));
        canvas.drawCircle(screen.centerX(), holeY, holeR, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(0xFF0B1220, alpha));
        canvas.drawCircle(screen.centerX(), holeY, holeR * 0.62f, paint);
        paint.setColor(withAlpha(0x59FFFFFF, alpha));
        canvas.drawCircle(screen.centerX() - holeR * 0.22f, holeY - holeR * 0.22f, holeR * 0.13f, paint);
    }

    // ------------------------------------------------------------- previews

    private void drawPreview(Canvas canvas) {
        if (previewApp == 0) return;
        float pw = phone.width();
        paint.setColor(0xF7000000);
        canvas.drawRoundRect(screen, screenR, screenR, paint);

        // Back chevron.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.5f, pw * 0.009f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0xFFE9E9EC);
        float cx = previewBackRect.centerX();
        float cy = previewBackRect.centerY();
        float u = previewBackRect.width() * 0.22f;
        canvas.drawLine(cx + u * 0.5f, cy - u, cx - u * 0.5f, cy, paint);
        canvas.drawLine(cx - u * 0.5f, cy, cx + u * 0.5f, cy + u, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);

        paint.setTypeface(labelTypeface);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(pw * 0.075f);
        paint.setColor(0xFFF2F2F4);
        canvas.drawText(labels[previewApp - 1], screen.centerX(), screen.top + pw * 0.145f, paint);
        paint.setTextAlign(Paint.Align.LEFT);

        if (previewApp == 1) drawNotesPreview(canvas);
        else if (previewApp == 2) drawCalculatorPreview(canvas);
        else drawWeatherPreview(canvas);
    }

    private void drawNotesPreview(Canvas canvas) {
        float pw = phone.width();
        RectF card = new RectF(screen.left + pw * 0.06f, screen.top + screen.height() * 0.24f,
                screen.right - pw * 0.06f, screen.bottom - pw * 0.10f);
        paint.setColor(0x14FFFFFF);
        canvas.drawRoundRect(card, pw * 0.045f, pw * 0.045f, paint);
        String text = noteText.trim().isEmpty()
                ? getContext().getString(R.string.pro_showcase_notes_hint) : noteText;
        textPaint.setTypeface(labelTypeface);
        textPaint.setTextSize(pw * 0.048f);
        textPaint.setColor(noteText.trim().isEmpty() ? 0x66E9E9EC : 0xFFE9E9EC);
        StaticLayout layout = StaticLayout.Builder
                .obtain(text, 0, text.length(), textPaint, (int) (card.width() - pw * 0.08f))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.25f)
                .build();
        canvas.save();
        canvas.translate(card.left + pw * 0.04f, card.top + pw * 0.04f);
        layout.draw(canvas);
        canvas.restore();
    }

    private void drawCalculatorPreview(Canvas canvas) {
        float pw = phone.width();
        paint.setTypeface(labelTypeface);
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setTextSize(pw * 0.13f);
        paint.setColor(0xFFF2F2F4);
        canvas.drawText(calcDisplay, screen.right - pw * 0.09f, screen.top + screen.height() * 0.30f, paint);
        paint.setTextAlign(Paint.Align.LEFT);

        float cellW = calcGrid.width() / 4f;
        float cellH = calcGrid.height() / 4f;
        for (int i = 0; i < CALC_KEYS.length; i++) {
            int col = i % 4;
            int row = i / 4;
            RectF cell = new RectF(calcGrid.left + col * cellW + pw * 0.012f,
                    calcGrid.top + row * cellH + pw * 0.012f,
                    calcGrid.left + (col + 1) * cellW - pw * 0.012f,
                    calcGrid.top + (row + 1) * cellH - pw * 0.012f);
            boolean accent = CALC_KEYS[i].equals("=");
            paint.setColor(accent ? 0x33FFD15C : 0x14FFFFFF);
            canvas.drawRoundRect(cell, pw * 0.035f, pw * 0.035f, paint);
            paint.setTypeface(labelTypeface);
            paint.setTextSize(pw * 0.062f);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setColor(accent ? 0xFFFFD15C : 0xFFE9E9EC);
            canvas.drawText(CALC_KEYS[i], cell.centerX(), cell.centerY() + pw * 0.022f, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void drawWeatherPreview(Canvas canvas) {
        float pw = phone.width();
        float iconSize = pw * 0.30f;
        Drawable weather = icons[2];
        if (weather != null) {
            weather.setAlpha(255);
            weather.setBounds(Math.round(screen.centerX() - iconSize / 2f),
                    Math.round(screen.top + screen.height() * 0.24f),
                    Math.round(screen.centerX() + iconSize / 2f),
                    Math.round(screen.top + screen.height() * 0.24f + iconSize));
            weather.draw(canvas);
        }
        paint.setTypeface(labelTypeface);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(pw * 0.16f);
        paint.setColor(0xFFF2F2F4);
        canvas.drawText(getContext().getString(R.string.pro_showcase_weather_temp),
                screen.centerX(), screen.top + screen.height() * 0.52f, paint);
        paint.setTextSize(pw * 0.052f);
        paint.setColor(0xFF9A9AA0);
        canvas.drawText(getContext().getString(R.string.pro_showcase_weather_line),
                screen.centerX(), screen.top + screen.height() * 0.57f, paint);

        RectF card = new RectF(screen.left + pw * 0.07f, screen.top + screen.height() * 0.63f,
                screen.right - pw * 0.07f, screen.top + screen.height() * 0.80f);
        paint.setColor(0x12FFFFFF);
        canvas.drawRoundRect(card, pw * 0.04f, pw * 0.04f, paint);
        paint.setTextSize(pw * 0.045f);
        paint.setColor(0xFF9A9AA0);
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(getContext().getString(R.string.pro_showcase_weather_row1),
                card.left + pw * 0.04f, card.centerY() - pw * 0.015f, paint);
        canvas.drawText(getContext().getString(R.string.pro_showcase_weather_row2),
                card.left + pw * 0.04f, card.centerY() + pw * 0.055f, paint);
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setColor(0xFFE9E9EC);
        canvas.drawText(getContext().getString(R.string.pro_showcase_weather_row1_value),
                card.right - pw * 0.04f, card.centerY() - pw * 0.015f, paint);
        canvas.drawText(getContext().getString(R.string.pro_showcase_weather_row2_value),
                card.right - pw * 0.04f, card.centerY() + pw * 0.055f, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    // ------------------------------------------------------------------ misc

    private void drawShade(Canvas canvas) {
        if (shadeProgress <= 0.001f) return;
        float pw = phone.width();
        float shadeH = screen.height() * 0.52f;
        canvas.save();
        canvas.translate(0f, -(1f - shadeProgress) * shadeH);

        RectF panel = new RectF(screen.left, screen.top - 2f, screen.right, screen.top + shadeH);
        paint.setColor(0xF20C0D11);
        canvas.drawRoundRect(panel, screenR, screenR, paint);

        paint.setTypeface(labelTypeface);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(pw * 0.075f);
        paint.setColor(0xFFF2F2F4);
        canvas.drawText(clockText, panel.centerX(), panel.top + pw * 0.16f, paint);
        paint.setTextSize(pw * 0.042f);
        paint.setColor(0xFF9A9AA0);
        canvas.drawText(dateText, panel.centerX(), panel.top + pw * 0.235f, paint);

        RectF card = new RectF(panel.left + pw * 0.055f, panel.top + pw * 0.30f,
                panel.right - pw * 0.055f, panel.top + pw * 0.30f + pw * 0.30f);
        paint.setColor(0x1AFFFFFF);
        canvas.drawRoundRect(card, pw * 0.045f, pw * 0.045f, paint);
        float iconSize = card.height() * 0.52f;
        Drawable weather = icons[2];
        if (weather != null) {
            weather.setAlpha(255);
            weather.setBounds(Math.round(card.left + pw * 0.045f), Math.round(card.centerY() - iconSize / 2f),
                    Math.round(card.left + pw * 0.045f + iconSize), Math.round(card.centerY() + iconSize / 2f));
            weather.draw(canvas);
        }
        float textX = card.left + pw * 0.045f + iconSize + pw * 0.04f;
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTextSize(pw * 0.050f);
        paint.setColor(0xFFE9E9EC);
        canvas.drawText(labels[2], textX, card.centerY() - pw * 0.015f, paint);
        paint.setTextSize(pw * 0.042f);
        paint.setColor(0xFF9A9AA0);
        canvas.drawText(getContext().getString(R.string.pro_showcase_weather_line) + " · " + elapsedText(),
                textX, card.centerY() + pw * 0.045f, paint);
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setTextSize(pw * 0.038f);
        paint.setColor(0xFF6E6E74);
        canvas.drawText(getContext().getString(R.string.pro_showcase_notif_now), card.right - pw * 0.045f,
                card.centerY() - pw * 0.02f, paint);
        paint.setTextAlign(Paint.Align.LEFT);

        paint.setColor(0x33FFFFFF);
        RectF handle = new RectF(panel.centerX() - pw * 0.10f, panel.bottom - pw * 0.09f,
                panel.centerX() + pw * 0.10f, panel.bottom - pw * 0.075f);
        canvas.drawRoundRect(handle, handle.height() / 2f, handle.height() / 2f, paint);
        canvas.restore();
    }

    /** Lock screen: status bar + camera, real time near the top, usage line, animated hint. */
    private void drawLockScreen(Canvas canvas) {
        if (unlockProgress >= 0.999f) return;
        float pw = phone.width();
        canvas.save();
        canvas.translate(0f, -unlockProgress * screen.height());

        paint.setColor(0xFF050608);
        canvas.drawRoundRect(screen, screenR, screenR, paint);
        paint.setShader(new RadialGradient(screen.centerX(), screen.top + screen.height() * 0.28f,
                screen.width() * 1.1f, new int[]{0xFF14161C, 0xFF08090C, 0xFF050608},
                new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(screen, screenR, screenR, paint);
        paint.setShader(null);

        // Status bar (icons + camera) stays visible on the lock screen.
        drawStatusBar(canvas, 1f, false);

        paint.setTypeface(labelTypeface);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(pw * 0.26f);
        paint.setColor(0xFFF5F5F7);
        canvas.drawText(clockText, screen.centerX(), screen.top + screen.height() * 0.185f, paint);
        paint.setTextSize(pw * 0.050f);
        paint.setColor(0xFF9A9AA0);
        canvas.drawText(dateText, screen.centerX(), screen.top + screen.height() * 0.235f, paint);
        if (!usageText.isEmpty()) {
            paint.setTextSize(pw * 0.042f);
            paint.setColor(0x8C9A9AA0);
            canvas.drawText(usageText, screen.centerX(), screen.top + screen.height() * 0.278f, paint);
        }

        float hintY = Math.min(screen.top + screen.height() * 0.72f, getHeight() - dp(26));
        paint.setTextSize(pw * 0.050f);
        paint.setColor(0xCCE9E9EC);
        canvas.drawText(getContext().getString(R.string.pro_showcase_swipe_unlock), screen.centerX(), hintY, paint);

        // Animated up/down arrow above the hint.
        float bob = (float) Math.sin(lockBob * Math.PI * 2.0) * dp(3.5f);
        float cx = screen.centerX();
        float u = pw * 0.045f;
        float ay = hintY - u * 2.2f + bob;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.5f, pw * 0.008f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0x99E9E9EC);
        canvas.drawLine(cx - u * 0.6f, ay + u * 0.6f, cx, ay, paint);
        canvas.drawLine(cx, ay, cx + u * 0.6f, ay + u * 0.6f, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.restore();
    }

    private void drawVolumeHud(Canvas canvas) {
        if (volumeHudAlpha <= 0.001f) return;
        float pw = phone.width();
        float hudW = pw * 0.46f;
        float hudH = pw * 0.15f;
        RectF hud = new RectF(screen.centerX() - hudW / 2f, screen.top + screen.height() * 0.20f,
                screen.centerX() + hudW / 2f, screen.top + screen.height() * 0.20f + hudH);
        paint.setColor(withAlpha(0xE61A1C22, volumeHudAlpha));
        canvas.drawRoundRect(hud, hudH / 2f, hudH / 2f, paint);

        float unit = pw * 0.045f;
        float wx = hud.left + hudH * 0.62f;
        float wy = hud.centerY() + unit * 0.20f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, pw * 0.006f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(withAlpha(0xFFE9E9EC, volumeHudAlpha));
        for (float rr : new float[]{unit * 0.52f, unit * 0.34f, unit * 0.16f}) {
            canvas.drawArc(new RectF(wx - rr, wy - rr, wx + rr, wy + rr), 225f, 90f, false, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);

        float trackL = hud.left + hudH * 1.05f;
        float trackR = hud.right - hudH * 0.35f;
        float trackH = Math.max(3f, hudH * 0.13f);
        RectF track = new RectF(trackL, hud.centerY() - trackH / 2f, trackR, hud.centerY() + trackH / 2f);
        paint.setColor(withAlpha(0x33FFFFFF, volumeHudAlpha));
        canvas.drawRoundRect(track, trackH / 2f, trackH / 2f, paint);
        paint.setColor(withAlpha(0xFFE9E9EC, volumeHudAlpha));
        canvas.drawRoundRect(new RectF(track.left, track.top, track.left + track.width() * volumeLevel, track.bottom),
                trackH / 2f, trackH / 2f, paint);
    }

    /** "Included in the bundle" toast after the first app tap. */
    private void drawCaption(Canvas canvas) {
        if (captionAlpha <= 0.001f) return;
        float pw = phone.width();
        paint.setTypeface(labelTypeface);
        paint.setTextSize(pw * 0.048f);
        String text = getContext().getString(R.string.pro_showcase_included_caption);
        float textW = paint.measureText(text);
        float padH = pw * 0.05f;
        float padV = pw * 0.032f;
        float y = Math.min(screen.bottom - pw * 0.16f, getHeight() - dp(52));
        RectF pill = new RectF(screen.centerX() - textW / 2f - padH, y - padV - pw * 0.035f,
                screen.centerX() + textW / 2f + padH, y + padV);
        paint.setColor(withAlpha(0xE61A1C22, captionAlpha));
        canvas.drawRoundRect(pill, pill.height() / 2f, pill.height() / 2f, paint);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(withAlpha(0xFFE9E9EC, captionAlpha));
        canvas.drawText(text, screen.centerX(), y, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    // ----------------------------------------------------------------- back

    private void drawBack(Canvas canvas) {
        float pw = phone.width();
        float rail = Math.max(1.5f, pw * 0.014f);
        RectF face = new RectF(phone);
        face.inset(rail, rail);

        // Camera visor with strong 3D shading: rim light on top, shadow at the base.
        float barH = pw * 0.21f;
        RectF bar = new RectF(face.left + pw * 0.045f, face.top + pw * 0.075f,
                face.right - pw * 0.045f, face.top + pw * 0.075f + barH);
        float barR = barH * 0.42f;
        paint.setShader(new LinearGradient(0f, bar.top, 0f, bar.bottom,
                0xFF454A55, 0xFF16181D, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(bar, barR, barR, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, pw * 0.005f));
        paint.setShader(new LinearGradient(0f, bar.top, 0f, bar.bottom,
                0x66FFFFFF, 0x0AFFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(bar, barR, barR, paint);
        paint.setShader(null);
        paint.setColor(0x66000000);
        canvas.drawLine(bar.left + barR * 0.8f, bar.bottom - pw * 0.010f,
                bar.right - barR * 0.8f, bar.bottom - pw * 0.010f, paint);
        paint.setStyle(Paint.Style.FILL);

        float lensR = barH * 0.30f;
        float lensY = bar.centerY();
        for (float lensX : new float[]{bar.left + barH * 0.55f, bar.left + barH * 1.52f}) {
            paint.setColor(0xFF0A0B0F);
            canvas.drawCircle(lensX, lensY, lensR, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, pw * 0.005f));
            paint.setColor(0x40FFFFFF);
            canvas.drawCircle(lensX, lensY, lensR, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF10161F);
            canvas.drawCircle(lensX, lensY, lensR * 0.62f, paint);
            paint.setColor(0x55FFFFFF);
            canvas.drawCircle(lensX - lensR * 0.22f, lensY - lensR * 0.22f, lensR * 0.16f, paint);
        }
        float flashX = bar.right - barH * 0.72f;
        paint.setColor(0xFF2E2C22);
        canvas.drawCircle(flashX, lensY, barH * 0.13f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0x30FFFFFF);
        canvas.drawCircle(flashX, lensY, barH * 0.13f, paint);
        paint.setStyle(Paint.Style.FILL);

        if (flag != null) {
            float flagSize = face.width() * 0.52f;
            float cx = face.centerX();
            float cy = face.top + phone.height() * 0.34f;
            float half = flagSize / 2f;
            float off = pw * 0.005f;
            flag.setBounds(Math.round(cx - half + off), Math.round(cy - half + off),
                    Math.round(cx + half + off), Math.round(cy + half + off));
            flag.setAlpha(52);
            flag.setColorFilter(new PorterDuffColorFilter(0x40FFFFFF, PorterDuff.Mode.SRC_ATOP));
            flag.draw(canvas);
            flag.setColorFilter(null);
            flag.setBounds(Math.round(cx - half), Math.round(cy - half),
                    Math.round(cx + half), Math.round(cy + half));
            flag.setAlpha(150);
            flag.draw(canvas);
        }

        paint.setTypeface(labelTypeface);
        paint.setTextSize(pw * 0.058f);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setLetterSpacing(0.18f);
        paint.setColor(0x3AFFFFFF);
        canvas.drawText("FadSec Lab", face.centerX(), face.top + phone.height() * 0.49f, paint);
        paint.setLetterSpacing(0f);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    // -------------------------------------------------------------- helpers

    private void refreshDate() {
        dateText = dateFormat.format(new Date());
    }

    private String elapsedText() {
        int secs = (int) ((SystemClock.elapsedRealtime() - bootAt) / 1000L);
        return String.format(Locale.US, "%d:%02d", secs / 60, secs % 60);
    }

    private void updateLockBob() {
        boolean locked = unlockProgress < 0.999f;
        if (locked && lockBobAnimator == null && motionAllowed()) {
            lockBobAnimator = ValueAnimator.ofFloat(0f, 1f);
            lockBobAnimator.setDuration(1400);
            lockBobAnimator.setRepeatCount(ValueAnimator.INFINITE);
            lockBobAnimator.setRepeatMode(ValueAnimator.REVERSE);
            lockBobAnimator.addUpdateListener(a -> {
                lockBob = (float) a.getAnimatedValue();
                invalidate();
            });
            lockBobAnimator.start();
        } else if (!locked && lockBobAnimator != null) {
            lockBobAnimator.cancel();
            lockBobAnimator = null;
            lockBob = 0f;
        }
    }

    private static double parse(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String format(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e12) return String.valueOf((long) v);
        return String.valueOf(Math.round(v * 1e8) / 1e8);
    }

    private static double apply(double a, double b, String op) {
        switch (op) {
            case "+": return a + b;
            case "−": return a - b;
            case "×": return a * b;
            case "÷": return b == 0 ? 0 : a / b;
            default: return b;
        }
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
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

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(shadeTicker);
        if (splashAnimator != null) splashAnimator.cancel();
        if (unlockAnimator != null) unlockAnimator.cancel();
        if (shadeAnimator != null) shadeAnimator.cancel();
        if (volumeAnimator != null) volumeAnimator.cancel();
        if (lockBobAnimator != null) lockBobAnimator.cancel();
        if (pulseAnimator != null) pulseAnimator.cancel();
        if (captionAnimator != null) captionAnimator.cancel();
        super.onDetachedFromWindow();
    }

    private boolean motionAllowed() {
        return Settings.Global.getFloat(getContext().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
