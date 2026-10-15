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
import android.graphics.Rect;
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

    /** Render everything (default), only the static chassis, or only the screen content. */
    public static final int MODE_ALL = 0;
    public static final int MODE_FRAME = 1;
    public static final int MODE_SCREEN = 2;
    public static final int MODE_PANEL = 3;

    private static final float ASPECT = 2.05f;
    /** The boot theater: logo + "Starting" + loader, then the status bar and the icon pop. */
    private static final float BOOT_MS = 3600f;
    /** The shutdown phase that precedes a restart boot. */
    private static final float SHUTDOWN_MS = 1600f;

    private static final String[] CALC_KEYS = {"7", "8", "9", "÷", "4", "5", "6", "×",
            "1", "2", "3", "−", "C", "0", "=", "+"};

    private final int side;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private Typeface labelBold;
    private final RectF phone = new RectF();
    private final RectF screen = new RectF();
    private final RectF powerBtn = new RectF();
    private final RectF volUpBtn = new RectF();
    private final RectF volDownBtn = new RectF();
    private final RectF previewBackRect = new RectF();
    private final RectF calcGrid = new RectF();
    private final RectF[] iconRects = {new RectF(), new RectF(), new RectF()};
    private final Path bolt = new Path();
    private final Drawable[] icons = new Drawable[3];
    private final String[] labels = new String[3];
    private final Drawable flag;
    private final Typeface labelTypeface;
    private final Typeface materialIcons;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("EEEE, MMM d", Locale.getDefault());

    private float screenR;
    private float statusBarMid;
    private float statusBarBottom;
    private float navBarH;
    private final RectF navDock = new RectF();
    private float rippleX, rippleY, rippleProgress;
    private ValueAnimator rippleAnim;
    private final RectF rippleClip = new RectF();
    private float rippleClipRadius;
    private float restartProgress = 1f;
    private ValueAnimator restartAnim;
    private final RectF notifPowerRect = new RectF();
    private final RectF notifTorchRect = new RectF();
    private boolean torchAvailable = false;
    private boolean torchOn = false;
    private boolean powerMenuOpen;
    private final RectF powerMenuRestartRect = new RectF();
    private final RectF powerMenuCancelRect = new RectF();
    private boolean accessDialogOpen;
    private final RectF dialogOkRect = new RectF();
    private final RectF notifMicRect = new RectF();
    private final RectF notifCamRect = new RectF();
    private float popProgress = 1f;
    private ValueAnimator popAnim;
    private final Path screenClipPath = new Path();
    private int lastPreviewApp = 0;
    private boolean recentsOpen = false;
    private float recentsProgress = 0f;
    private ValueAnimator recentsAnim;
    private float iconTopY;
    private float iconSizePx;

    private String clockText = "9:41";
    private String dateText = "";
    private String usageText = "";
    private int batteryPercent = -1;
    private boolean charging = false;
    private boolean bootEnabled = true;
    private boolean cropFade = true;
    private boolean glMode = false;
    private int renderMode = MODE_ALL;
    private DirtyListener dirtyListener;

    /** Notifies the GL host that this face's pixels changed and its texture must refresh. */
    public interface DirtyListener {
        void onContentDirty();
    }
    private float bootProgress = 1f;
    private ValueAnimator bootAnimator;

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
        labelBold = Typeface.create(labelTypeface, Typeface.BOLD);
        materialIcons = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.materialicons);
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

    public void setBootEnabled(boolean enabled) {
        bootEnabled = enabled;
    }

    public void setDirtyListener(DirtyListener listener) {
        dirtyListener = listener;
    }

    public void setRenderMode(int mode) {
        renderMode = mode;
        invalidate();
    }

    /** GL mode: the face fills the view exactly (no margins, no painted hardware). */
    public void setGLMode(boolean enabled) {
        glMode = enabled;
        cropFade = !enabled;
        invalidate();
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (dirtyListener != null) dirtyListener.onContentDirty();
    }

    @Override
    public void invalidate(int l, int t, int r, int b) {
        super.invalidate(l, t, r, b);
        if (dirtyListener != null) dirtyListener.onContentDirty();
    }

    @Override
    public void invalidate(Rect dirty) {
        super.invalidate(dirty);
        if (dirtyListener != null) dirtyListener.onContentDirty();
    }

    /** The bottom crop fade — disabled when the whole device is shown (fullscreen mode). */
    public void setCropFade(boolean enabled) {
        cropFade = enabled;
        invalidate();
    }

    /**
     * The boot theater: the emblem and "Starting…" rise together, a slow loader follows,
     * then the status bar and the staggered icon pop-in. Runs on every Pro-screen visit
     * and again after a restart.
     */
    public void startBoot() {
        if (side != SIDE_FRONT) return;
        if (!bootEnabled || !motionAllowed()) {
            settleBoot();
            return;
        }
        if (bootAnimator != null) bootAnimator.cancel();
        prepareBoot();
        bootAnimator = ValueAnimator.ofFloat(0f, 1f);
        bootAnimator.setDuration((long) BOOT_MS);
        bootAnimator.addUpdateListener(a -> {
            bootProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        bootAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                settleBoot();
            }
        });
        bootAnimator.start();
    }

    public void settleBoot() {
        if (bootAnimator != null) {
            bootAnimator.cancel();
            bootAnimator = null;
        }
        bootProgress = 1f;
        invalidate();
    }

    /**
     * Powers the screen off without animating: called when the showcase attaches so the
     * fly-in never reveals a stale home screen from the previous visit. A boot always
     * lands on the lock screen — the user unlocks with the swipe, then the icons pop in.
     */
    public void prepareBoot() {
        if (side != SIDE_FRONT) return;
        setUnlockProgress(0f);
        setShadeProgress(0f);
        exitToHome();
        showRecents(false);
        popProgress = 1f;
        bootProgress = 0f;
        invalidate();
    }

    public float getBootProgress() {
        return bootProgress;
    }

    // ------------------------------------------------------- interaction API

    public boolean isLocked() {
        return unlockProgress < 0.5f;
    }

    public float getUnlockProgress() {
        return unlockProgress;
    }

    /** The glass rect in view pixels — the GL uses it to size the live screen layer. */
    public RectF getScreenRect() {
        return new RectF(screen);
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
        boolean unlocking = clamp(target) >= 1f && unlockProgress < 0.999f;
        if (unlocking) {
            // The icons must not sit visible behind the lock: reset the pop now so they rise
            // out of nothing as the lock screen slides away (no replay after it is gone).
            popProgress = 0f;
            startIconPop();
        }
        unlockAnimator = ValueAnimator.ofFloat(unlockProgress, clamp(target));
        unlockAnimator.setDuration(280);
        unlockAnimator.setInterpolator(new DecelerateInterpolator(1.6f));
        unlockAnimator.addUpdateListener(a -> setUnlockProgress((float) a.getAnimatedValue()));
        unlockAnimator.start();
    }

    /** Replays the staggered home-icon pop-in (used after unlocking, like the first boot). */
    private void startIconPop() {
        if (side != SIDE_FRONT || !motionAllowed()) return;
        if (popAnim != null) popAnim.cancel();
        popProgress = 0f;
        popAnim = ValueAnimator.ofFloat(0f, 1f);
        popAnim.setDuration(900);
        popAnim.addUpdateListener(a -> {
            popProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        popAnim.start();
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

    /** 1 = back, 2 = home, 3 = recents — the nav strip at the bottom of the glass. */
    public int navButtonAt(float x, float y) {
        if (unlockProgress < 0.9f || bootProgress < 0.8f) return 0;
        if (!navDock.contains(x, y)) return 0;
        float third = navDock.width() / 3f;
        if (x < navDock.left + third) return 1;
        if (x < navDock.left + third * 2f) return 2;
        return 3;
    }

    /** Material-style ripple bounded to the tapped element's own shape. */
    public void touchRipple(float x, float y) {
        RectF bounds = rippleBoundsAt(x, y);
        if (bounds == null) return;
        rippleX = x;
        rippleY = y;
        rippleClip.set(bounds);
        rippleClipRadius = Math.min(bounds.width(), bounds.height()) * (bounds == iconRects[0]
                || bounds == iconRects[1] || bounds == iconRects[2] ? 0.5f : 0.22f);
        rippleProgress = 0f;
        if (rippleAnim != null) rippleAnim.cancel();
        if (!motionAllowed()) {
            rippleProgress = 1f;
            return;
        }
        rippleAnim = ValueAnimator.ofFloat(0f, 1f);
        rippleAnim.setDuration(520);
        rippleAnim.setInterpolator(new DecelerateInterpolator(1.6f));
        rippleAnim.addUpdateListener(a -> {
            rippleProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        rippleAnim.start();
    }

    /** Which element's shape the ripple should live in (null = not a tappable element). */
    private RectF rippleBoundsAt(float x, float y) {
        if (navDock.contains(x, y)) {
            float third = navDock.width() / 3f;
            int idx = x < navDock.left + third ? 0 : (x < navDock.left + third * 2f ? 1 : 2);
            RectF zone = new RectF(navDock);
            zone.left = navDock.left + idx * third;
            zone.right = zone.left + third;
            return zone;
        }
        if (previewApp == 2 && calcGrid.contains(x, y)) {
            float cw = calcGrid.width() / 4f;
            float chh = calcGrid.height() / 4f;
            int col = Math.min(3, Math.max(0, (int) ((x - calcGrid.left) / cw)));
            int row = Math.min(3, Math.max(0, (int) ((y - calcGrid.top) / chh)));
            return new RectF(calcGrid.left + col * cw + phone.width() * 0.012f,
                    calcGrid.top + row * chh + phone.width() * 0.012f,
                    calcGrid.left + (col + 1) * cw - phone.width() * 0.012f,
                    calcGrid.top + (row + 1) * chh - phone.width() * 0.012f);
        }
        for (RectF icon : iconRects) {
            if (icon.contains(x, y)) return icon;
        }
        if (previewApp != 0 && previewBackRect.contains(x, y)) return previewBackRect;
        if (notifMicRect.contains(x, y) || notifCamRect.contains(x, y)) {
            RectF both = new RectF(notifCamRect);
            both.union(notifMicRect);
            return both;
        }
        if (notifPowerRect.contains(x, y)) return notifPowerRect;
        if (torchAvailable && notifTorchRect.contains(x, y)) return notifTorchRect;
        if (accessDialogOpen && dialogOkRect.contains(x, y)) return dialogOkRect;
        if (powerMenuOpen) {
            if (powerMenuRestartRect.contains(x, y)) return powerMenuRestartRect;
            if (powerMenuCancelRect.contains(x, y)) return powerMenuCancelRect;
        }
        return null;
    }

    private final RectF notifCardRect = new RectF();
    private final RectF loaderRect = new RectF();
    private final RectF dialogCardRect = new RectF();
    private final Path rippleClipPath = new Path();
    private long lastBobInvalidateAt;

    public boolean notifCardTappedAt(float x, float y) {
        return shadeProgress > 0.6f && notifCardRect.contains(x, y);
    }

    public boolean notifPowerAt(float x, float y) {
        return shadeProgress > 0.6f && notifPowerRect.contains(x, y);
    }

    /** The real torch state, straight from the CameraManager TorchCallback. */
    public void setTorchState(boolean available, boolean on) {
        if (torchAvailable == available && torchOn == on) return;
        torchAvailable = available;
        torchOn = on;
        invalidate();
    }

    public boolean torchButtonAt(float x, float y) {
        return shadeProgress > 0.6f && torchAvailable && notifTorchRect.contains(x, y);
    }

    public void startRestart() {
        exitToHome();
        showRecents(false);
        animateShade(0f);
        setUnlockProgress(1f);
        popProgress = 1f;
        showPowerMenu(false);
        if (restartAnim != null) restartAnim.cancel();
        restartProgress = 0f;
        invalidate();
        if (!motionAllowed()) {
            restartProgress = 1f;
            startBoot();
            return;
        }
        // The shutdown beat; the boot theater follows in startBoot().
        restartAnim = ValueAnimator.ofFloat(0f, 1f);
        restartAnim.setDuration((long) SHUTDOWN_MS);
        restartAnim.addUpdateListener(a -> {
            restartProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        restartAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                restartProgress = 1f;
                startBoot();
            }
        });
        restartAnim.start();
    }

    public void showPowerMenu(boolean open) {
        powerMenuOpen = open;
        invalidate();
    }

    public boolean isPowerMenuOpen() {
        return powerMenuOpen;
    }

    /** 1 = restart, 2 = cancel, 0 = elsewhere. */
    public int powerMenuActionAt(float x, float y) {
        if (!powerMenuOpen) return 0;
        if (powerMenuRestartRect.contains(x, y)) return 1;
        if (powerMenuCancelRect.contains(x, y)) return 2;
        return 0;
    }

    public void showAccessDialog(boolean open) {
        accessDialogOpen = open;
        invalidate();
    }

    public boolean isAccessDialogOpen() {
        return accessDialogOpen;
    }

    public boolean accessDialogOkAt(float x, float y) {
        return accessDialogOpen && dialogOkRect.contains(x, y);
    }

    /** 1 = microphone, 2 = camera in the notification; 0 elsewhere. */
    public int notificationIconAt(float x, float y) {
        if (shadeProgress < 0.9f) return 0;
        if (notifMicRect.contains(x, y)) return 1;
        if (notifCamRect.contains(x, y)) return 2;
        return 0;
    }

    public void showRecents(boolean open) {
        recentsOpen = open;
        if (!motionAllowed()) {
            recentsProgress = open ? 1f : 0f;
            invalidate();
            return;
        }
        if (recentsAnim != null) recentsAnim.cancel();
        recentsAnim = ValueAnimator.ofFloat(recentsProgress, open ? 1f : 0f);
        recentsAnim.setDuration(open ? 220 : 150);
        recentsAnim.setInterpolator(new DecelerateInterpolator(1.6f));
        recentsAnim.addUpdateListener(a -> {
            recentsProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        recentsAnim.start();
    }

    public boolean isRecentsOpen() {
        return recentsOpen;
    }

    public int getLastPreviewApp() {
        return lastPreviewApp;
    }

    public boolean isInStatusBar(float x, float y) {
        return y <= statusBarBottom && y >= phone.top;
    }

    public boolean isInUnlockZone(float x, float y) {
        return screen.contains(x, y) && y >= screen.top + screen.height() * 0.62f;
    }

    public boolean isInteractiveOverlayOpen() {
        return recentsOpen || previewApp != 0 || accessDialogOpen || powerMenuOpen
                || shadeProgress > 0.01f || bootProgress < 1f;
    }

    /** True while the touch sits on the (possibly partially pulled) shade panel itself. */
    public boolean shadePanelAt(float x, float y) {
        if (shadeProgress <= 0.4f) return false;
        float shadeH = screen.height() * 0.52f;
        return y <= screen.top + shadeH - (1f - shadeProgress) * shadeH;
    }

    // ---------------------------------------------------------- app previews

    /** 1..3 when the touch lands on an app icon, 0 otherwise. */
    public int appIconAt(float x, float y) {
        if (previewApp != 0 || unlockProgress < 0.999f || shadeProgress > 0.01f
                || bootProgress < 0.92f) return 0;
        for (int i = 0; i < 3; i++) {
            if (iconRects[i].contains(x, y)) return i + 1;
        }
        return 0;
    }

    public boolean previewVisible() {
        return previewApp != 0;
    }

    public void openPreview(int app) {
        lastPreviewApp = app;
        recentsOpen = false;
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

    public void exitToHome() {
        previewApp = 0;
        recentsOpen = false;
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
        float pw = glMode ? w : w * 0.95f;
        float ph = glMode ? h : pw * ASPECT;
        phone.set(glMode ? 0f : w * 0.025f, glMode ? 0f : h * 0.012f,
                glMode ? w : w * 0.975f, (glMode ? 0f : h * 0.012f) + ph);
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

        // Generous zones (0.270/0.365/0.400 of the height) that MUST NOT overlap each other:
        // half-height 0.045 < half the 0.095 spacing.
        float volZone = ph * 0.045f;
        float powerZone = ph * 0.055f;
        float volUpCenter = phone.top + ph * 0.270f;
        float volDownCenter = phone.top + ph * 0.365f;
        float powerCenter = phone.top + ph * 0.400f;
        // Mostly on/outside the button, only a slim sliver inside the glass.
        volUpBtn.set(phone.left - pw * 0.13f, volUpCenter - volZone, phone.left + pw * 0.055f, volUpCenter + volZone);
        volDownBtn.set(phone.left - pw * 0.13f, volDownCenter - volZone, phone.left + pw * 0.055f, volDownCenter + volZone);
        powerBtn.set(phone.right - pw * 0.055f, powerCenter - powerZone,
                phone.right + pw * 0.13f, powerCenter + powerZone);

        navBarH = pw * 0.085f;
        float dockW = pw * 0.58f;
        float dockH = pw * 0.115f;
        navDock.set(screen.centerX() - dockW / 2f, screen.bottom - dockH - pw * 0.028f,
                screen.centerX() + dockW / 2f, screen.bottom - pw * 0.028f);
        screenClipPath.reset();
        screenClipPath.addRoundRect(screen, screenR, screenR, Path.Direction.CW);

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
        previewBackRect.set(screen.left + pw * 0.055f, screen.top + pw * 0.165f,
                screen.left + pw * 0.055f + pw * 0.12f, screen.top + pw * 0.165f + pw * 0.12f);
        calcGrid.set(screen.left + pw * 0.06f, screen.top + screen.height() * 0.40f,
                screen.right - pw * 0.06f, screen.top + screen.height() * 0.82f);

    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) return;
        if (phone.isEmpty()) computeGeometry(w, h);

        int layer = cropFade ? canvas.saveLayer(0f, 0f, w, h, null) : -1;
        if (glMode) {
            // The chassis is real GL geometry; textures carry only content.
            if (side == SIDE_FRONT && renderMode == MODE_SCREEN) {
                paint.setStyle(Paint.Style.FILL);
                paint.setShader(null);
                paint.setColor(0xFF000000);
                canvas.drawRoundRect(screen, screenR, screenR, paint);
                drawScreenContent(canvas);
            } else if (side == SIDE_BACK && renderMode == MODE_PANEL) {
                drawBackPanel(canvas);
            }
        } else {
            drawChassis(canvas);
            if (side == SIDE_FRONT) {
                drawFrontBase(canvas);
                drawScreenContent(canvas);
            } else {
                drawBack(canvas);
            }
        }
        if (cropFade) {
            float fadeTop = h - dp(34);
            fadePaint.setShader(new LinearGradient(0f, fadeTop, 0f, h,
                    0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
            canvas.drawRect(-w, fadeTop, 2f * w, h + phone.height(), fadePaint);
        }
        if (layer >= 0) canvas.restoreToCount(layer);
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

        if (!glMode) {
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
        }

        paint.setShader(new LinearGradient(0f, phone.top, 0f, phone.bottom,
                back ? 0xFF3B3F47 : 0xFF828B9A,
                back ? 0xFF1D2025 : 0xFF484E59, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(phone, r, r, paint);
        float rail = Math.max(1.5f, pw * 0.018f);
        RectF face = new RectF(phone);
        face.inset(rail, rail);
        float faceR = r - rail;
        paint.setShader(new LinearGradient(0f, face.top, 0f, face.bottom,
                back ? 0xFF1C1F24 : 0xFF5B626D,
                back ? 0xFF101215 : 0xFF31363E, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(face, faceR, faceR, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2.5f, pw * 0.014f));
        paint.setShader(new LinearGradient(0f, phone.top, 0f, phone.bottom,
                0xBFFFFFFF, 0x33FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(phone, r, r, paint);
        paint.setShader(null);
        paint.setColor(0x30000000);
        canvas.drawRoundRect(face, faceR, faceR, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    // ---------------------------------------------------------------- front

    /** The static screen glass: fill + inset ring. Rendered once with the chassis. */
    private void drawFrontBase(Canvas canvas) {
        // Subtle bezel highlight hugging the glass: makes the frame readable from the front.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.5f, phone.width() * 0.008f));
        paint.setColor(0x38FFFFFF);
        canvas.drawRoundRect(screen, screenR, screenR, paint);
        // Reflective sheen sweeping the frame (frame mode only: static, never re-uploaded).
        if (renderMode != MODE_SCREEN) {
            paint.setShader(new LinearGradient(phone.left, phone.top, phone.right, phone.bottom,
                    new int[]{0x00FFFFFF, 0x0AFFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.45f, 0.8f}, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(phone, phone.width() * 0.135f, phone.width() * 0.135f, paint);
            paint.setShader(null);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(0xFF000000);
        canvas.drawRoundRect(screen, screenR, screenR, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, phone.width() * 0.005f));
        paint.setColor(0x30000000);
        canvas.drawRoundRect(screen, screenR, screenR, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /** The live screen content, redrawn whenever it changes. */
    private void drawScreenContent(Canvas canvas) {
        if (restartProgress < 1f) {
            drawRestartSequence(canvas);
            return;
        }
        drawBootSequence(canvas);
        drawPreview(canvas);
        drawRecents(canvas);
        drawNavBar(canvas);
        // The lock screen sits above the home but below every overlay: the shade, dialogs
        // and ripples must stay interactive on the lock screen (pull-down, volume, etc.).
        drawLockScreen(canvas);
        drawShade(canvas);
        drawAccessDialog(canvas);
        drawPowerMenu(canvas);
        drawTouchRipple(canvas);
        drawVolumeHud(canvas);
        drawCaption(canvas);
    }

    /**
     * The boot theater, drawn every time the phone powers on: the emblem rises with the
     * "Starting…" line, the loader joins below, then the status bar and the icon pop-in.
     */
    private void drawBootSequence(Canvas canvas) {
        float pw = phone.width();
        float p = bootProgress;

        // Base: black glass with a soft glow behind the emblem.
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(0xFF000000);
        canvas.drawRoundRect(screen, screenR, screenR, paint);

        // The theater rises, holds, then fades away completely before the home appears:
        // logo + "Starting" + loader on black, a beat, then the status bar and the icon pop.
        float theaterOut = easeOut(seg(p, 0.50f, 0.64f));
        float flagA = easeOut(seg(p, 0.00f, 0.18f)) * (1f - theaterOut);
        float bootTextA = easeOut(seg(p, 0.09f, 0.26f)) * (1f - theaterOut);
        float loaderA = easeOut(seg(p, 0.36f, 0.52f)) * (1f - easeOut(seg(p, 0.54f, 0.66f)));
        float statusA = easeOut(seg(p, 0.70f, 0.84f));

        float cx = screen.centerX();
        float cy = screen.top + screen.height() * 0.34f;

        if (flagA > 0.001f && flag != null) {
            float flagSize = screen.width() * 0.46f;
            float glow = Math.min(1f, flagA);
            paint.setShader(new RadialGradient(cx, cy, flagSize * 0.90f,
                    new int[]{withAlpha(0xFF1B1D24, glow), withAlpha(0xFF0A0B0F, glow * 0.55f), 0x00000000},
                    new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, flagSize * 0.90f, paint);
            paint.setShader(null);
            float half = flagSize * (0.94f + 0.06f * flagA) / 2f;
            flag.setBounds(Math.round(cx - half), Math.round(cy - half),
                    Math.round(cx + half), Math.round(cy + half));
            flag.setAlpha(Math.round(255 * flagA));
            flag.draw(canvas);
        }

        paint.setTypeface(labelTypeface);
        paint.setTextAlign(Paint.Align.CENTER);
        if (bootTextA > 0.001f) {
            int dots = (int) (p * 26f) % 4;
            StringBuilder label = new StringBuilder(getContext().getString(R.string.pro_showcase_starting));
            for (int i = 0; i < dots; i++) label.append('.');
            paint.setTextSize(pw * 0.048f);
            paint.setColor(withAlpha(0xFFB8B8BE, bootTextA));
            canvas.drawText(label.toString(), cx, cy + pw * 0.62f, paint);
        }
        if (loaderA > 0.001f) {
            float r = screen.width() * 0.062f;
            float ly = cy + pw * 1.06f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, pw * 0.010f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(withAlpha(0x26FFFFFF, loaderA));
            canvas.drawCircle(cx, ly, r, paint);
            paint.setColor(withAlpha(0xFFFFFFFF, loaderA));
            // One slow revolution every ~1.2s, eased like a real spinner.
            loaderRect.set(cx - r, ly - r, cx + r, ly + r);
            canvas.drawArc(loaderRect, p * 360f * 3.0f, 96f, false, paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
            paint.setStyle(Paint.Style.FILL);
        }
        paint.setTextAlign(Paint.Align.LEFT);

        // While locked the boot ends on the lock screen, not the home: no status bar or
        // icons may appear until the user actually unlocks.
        if (unlockProgress < 0.999f) return;

        if (statusA > 0.001f) drawStatusBar(canvas, statusA, true);

        float iconSize = iconSizePx;
        for (int i = 0; i < 3; i++) {
            // Every segment MUST end at or before p=1: the boot settles at exactly 1, so a
            // segment running past it would freeze the last icon/label at partial opacity
            // forever (the "dull Weather label" bug).
            float start = 0.74f + i * 0.06f;
            float a = easeOut(seg(p, start, start + 0.13f)) * easeOut(popProgress);
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
            float labelA = easeOut(seg(p, start + 0.02f, start + 0.13f)) * easeOut(popProgress);
            if (labelA > 0.001f) {
                paint.setTypeface(labelTypeface);
                paint.setTextSize(pw * 0.058f);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setColor(withAlpha(0xFFE9E9EC, labelA));
                canvas.drawText(labels[i], centerX, iconTopY + iconSize + pw * 0.082f, paint);
                paint.setTextAlign(Paint.Align.LEFT);
            }
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
            paint.setTypeface(labelBold);
            paint.setTextSize(unit * 0.72f);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setFakeBoldText(true);
            // Two-pass contrast (like real phones): light digits everywhere, then dark
            // digits clipped to the filled part — readable over BOTH green and black.
            String pctText = String.valueOf(batteryPercent);
            float pctY = mid + unit * 0.23f;
            paint.setColor(withAlpha(0xFFE9E9EC, alpha));
            canvas.drawText(pctText, battery.centerX(), pctY, paint);
            canvas.save();
            canvas.clipRect(battery.left + inset, battery.top + inset,
                    battery.left + inset + fillW, battery.bottom - inset);
            paint.setColor(withAlpha(0xFF0A0B0E, alpha));
            canvas.drawText(pctText, battery.centerX(), pctY, paint);
            canvas.restore();
            paint.setFakeBoldText(false);
            paint.setTypeface(labelTypeface);
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

        // Wi-Fi as the Material Icons ligature, like every other icon in the app.
        float wx = battery.left - unit * 1.15f;
        drawIcon(canvas, "wifi", wx, mid, unit * 1.12f,
                withAlpha(0xFFDCDCDC, alpha * 0.92f));

        float holeR = Math.max(2.5f, pw * 0.028f);
        float holeY = mid;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(withAlpha(0xFF020305, alpha));
        canvas.drawCircle(screen.centerX(), holeY, holeR, paint);
        paint.setColor(withAlpha(0xFF070A12, alpha));
        canvas.drawCircle(screen.centerX(), holeY, holeR * 0.66f, paint);
        paint.setColor(withAlpha(0xFF04060B, alpha));
        canvas.drawCircle(screen.centerX(), holeY, holeR * 0.38f, paint);
        paint.setColor(withAlpha(0x0AFFFFFF, alpha));
        canvas.drawCircle(screen.centerX() - holeR * 0.28f, holeY - holeR * 0.28f, holeR * 0.07f, paint);
    }

    // ------------------------------------------------------------- previews

    private void drawPreview(Canvas canvas) {
        if (previewApp == 0) return;
        float pw = phone.width();
        paint.setColor(0xF7000000);
        canvas.drawRoundRect(screen, screenR, screenR, paint);

        // Back chevron (Material Icons).
        drawIcon(canvas, "chevron_left", previewBackRect.centerX(), previewBackRect.centerY(),
                pw * 0.085f, 0xFFE9E9EC);

        paint.setTypeface(labelTypeface);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(pw * 0.070f);
        paint.setColor(0xFFF2F2F4);
        canvas.drawText(labels[previewApp - 1], screen.centerX(), screen.top + pw * 0.252f, paint);
        paint.setTextAlign(Paint.Align.LEFT);

        if (previewApp == 1) drawNotesPreview(canvas);
        else if (previewApp == 2) drawCalculatorPreview(canvas);
        else drawWeatherPreview(canvas);
    }

    private void drawNotesPreview(Canvas canvas) {
        float pw = phone.width();
        RectF card = new RectF(screen.left + pw * 0.06f, screen.top + screen.height() * 0.30f,
                screen.right - pw * 0.06f, screen.bottom - pw * 0.19f);
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

    /** The calculator input line: shows the pending operation live, like a real app. */
    private String calcDisplayText() {
        if (calcOp == null) return calcDisplay;
        return format(calcAccum) + " " + calcOp + (calcFresh ? "" : " " + calcDisplay);
    }

    private void drawCalculatorPreview(Canvas canvas) {
        float pw = phone.width();
        paint.setTypeface(labelTypeface);
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setTextSize(pw * 0.13f);
        paint.setColor(0xFFF2F2F4);
        canvas.drawText(calcDisplayText(), screen.right - pw * 0.09f, screen.top + screen.height() * 0.28f, paint);
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

    /** Three-button navigation, drawn like the real thing. */
    private void drawNavBar(Canvas canvas) {
        float navA = unlockProgress;
        if (bootProgress < 0.8f || navA <= 0.02f) return;
        float pw = phone.width();
        float dockR = navDock.height() / 2f;
        canvas.save();
        canvas.clipPath(screenClipPath);
        // Floating dock: dark glass, light glyphs (fades in with the unlock).
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(0xCC131418, navA));
        canvas.drawRoundRect(navDock, dockR, dockR, paint);
        float cy = navDock.centerY();
        float u = pw * 0.024f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.5f, pw * 0.007f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(withAlpha(0xFFC9C9CF, navA));

        float third = navDock.width() / 3f;
        int navInk = withAlpha(0xFFC9C9CF, navA);
        drawIcon(canvas, "arrow_back_ios", navDock.left + third / 2f, cy, pw * 0.048f, navInk);
        drawIcon(canvas, "radio_button_unchecked", navDock.centerX(), cy, pw * 0.051f, navInk);
        drawIcon(canvas, "crop_square", navDock.right - third / 2f, cy, pw * 0.051f, navInk);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        canvas.restore();
    }

    /** Minimal recents: a card for the last opened app. */
    private void drawRecents(Canvas canvas) {
        if (recentsProgress <= 0.001f) return;
        float pw = phone.width();
        float rp = easeOut(recentsProgress);
        canvas.save();
        canvas.clipPath(screenClipPath);
        canvas.drawColor(withAlpha(0x99000000, recentsProgress));
        RectF card = new RectF(screen.left + pw * 0.10f, screen.top + screen.height() * 0.18f,
                screen.right - pw * 0.10f, screen.top + screen.height() * 0.66f);
        float cardScale = 0.94f + 0.06f * rp;
        canvas.save();
        canvas.scale(cardScale, cardScale, card.centerX(), card.centerY());
        paint.setColor(withAlpha(0xFF1C1E24, recentsProgress));
        canvas.drawRoundRect(card, pw * 0.05f, pw * 0.05f, paint);
        Drawable icon = lastPreviewApp > 0 ? icons[lastPreviewApp - 1] : icons[0];
        if (icon != null) {
            float is = pw * 0.20f;
            icon.setAlpha(255);
            icon.setBounds(Math.round(card.left + pw * 0.06f), Math.round(card.top + pw * 0.06f),
                    Math.round(card.left + pw * 0.06f + is), Math.round(card.top + pw * 0.06f + is));
            icon.draw(canvas);
        }
        paint.setTypeface(labelTypeface);
        paint.setTextSize(pw * 0.055f);
        paint.setColor(0xFFE9E9EC);
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(lastPreviewApp > 0 ? labels[lastPreviewApp - 1] : labels[0],
                card.left + pw * 0.06f, card.top + pw * 0.20f + pw * 0.075f, paint);
        paint.setTextSize(pw * 0.040f);
        paint.setColor(withAlpha(0xFF9A9AA0, recentsProgress));
        canvas.drawText(getContext().getString(R.string.pro_showcase_recents_hint),
                card.left + pw * 0.06f, card.bottom - pw * 0.06f, paint);
        canvas.restore();
        canvas.restore();
    }

    /** The recents card's tap zone (reopen the last app). */
    public boolean recentsCardAt(float x, float y) {
        if (!recentsOpen) return false;
        float pw = phone.width();
        RectF card = new RectF(screen.left + pw * 0.10f, screen.top + screen.height() * 0.18f,
                screen.right - pw * 0.10f, screen.top + screen.height() * 0.66f);
        return card.contains(x, y);
    }

    private void drawShade(Canvas canvas) {
        if (shadeProgress <= 0.001f) return;
        float pw = phone.width();
        float shadeH = screen.height() * 0.52f;
        // Clear the front camera punch-hole: everything in the shade sits below it.
        float topInset = pw * 0.15f;
        canvas.save();
        canvas.clipPath(screenClipPath);
        canvas.translate(0f, -(1f - shadeProgress) * shadeH);

        RectF panel = new RectF(screen.left, screen.top - 2f, screen.right, screen.top + shadeH);
        paint.setColor(0xF20C0D11);
        canvas.drawRoundRect(panel, screenR, screenR, paint);

        paint.setTypeface(labelTypeface);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(pw * 0.075f);
        paint.setColor(0xFFF2F2F4);
        canvas.drawText(clockText, panel.centerX(), panel.top + topInset + pw * 0.16f, paint);
        paint.setTextSize(pw * 0.042f);
        paint.setColor(0xFF9A9AA0);
        canvas.drawText(dateText, panel.centerX(), panel.top + topInset + pw * 0.235f, paint);

        RectF card = new RectF(panel.left + pw * 0.055f, panel.top + topInset + pw * 0.30f,
                panel.right - pw * 0.055f, panel.top + topInset + pw * 0.30f + pw * 0.30f);
        notifCardRect.set(card);
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
        float nowW = paint.measureText(getContext().getString(R.string.pro_showcase_notif_now));
        // Mic + camera privacy indicators (tap for the demo disclaimer). Bigger than the
        // old glyphs and placed on the title row, left of "now", so they stay readable.
        float u = pw * 0.052f;
        float iconY = card.centerY() - pw * 0.042f;
        float iconsRight = card.right - pw * 0.045f - nowW - pw * 0.012f;
        float camW = u * 1.9f;
        float micW = u * 1.0f;
        float gap = u * 0.26f;
        float iconsLeft = iconsRight - (camW + gap + micW);
        notifCamRect.set(iconsLeft, iconY - u * 0.78f, iconsLeft + camW, iconY + u * 0.78f);
        notifMicRect.set(notifCamRect.right + gap, iconY - u * 0.78f,
                notifCamRect.right + gap + micW, iconY + u * 0.78f);
        // Privacy indicators as Material Icons — the same glyphs Android itself uses.
        drawIcon(canvas, "photo_camera", notifCamRect.centerX(), iconY, u * 1.45f, 0xFF9A9AA0);
        drawIcon(canvas, "mic", notifMicRect.centerX(), iconY, u * 1.45f, 0xFF9A9AA0);

        // Quick action: Torch (toggles the REAL phone torch; state from the real callback).
        notifTorchRect.set(panel.left + pw * 0.055f, card.bottom + pw * 0.045f,
                panel.left + pw * 0.055f + pw * 0.24f, card.bottom + pw * 0.045f + pw * 0.14f);
        float torchAlpha = torchAvailable ? 1f : 0.4f;
        paint.setStyle(Paint.Style.FILL);
        // Active = solid gold with dark ink; resting = the exact Restart glass, so the
        // button is dark at rest and only turns yellow when the torch is really on.
        paint.setColor(withAlpha(torchOn ? 0xFFE8C77B : 0x1AFFFFFF, torchAlpha));
        canvas.drawRoundRect(notifTorchRect, notifTorchRect.height() / 2f,
                notifTorchRect.height() / 2f, paint);
        float tu = pw * 0.036f;
        float tcx = notifTorchRect.left + pw * 0.040f;
        float tcy = notifTorchRect.centerY();
        int torchInk = torchOn ? 0xFF101112 : 0xFFE9E9EC;
        // Material Icons ligature — the same icon font the app uses everywhere.
        paint.setTypeface(materialIcons);
        paint.setTextSize(pw * 0.058f);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(withAlpha(torchInk, torchAlpha));
        float torchBaseline = tcy - (paint.descent() + paint.ascent()) / 2f;
        canvas.drawText("flashlight_on", tcx, torchBaseline, paint);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTypeface(labelTypeface);
        paint.setTextSize(pw * 0.042f);
        paint.setColor(withAlpha(torchInk, torchAlpha));
        canvas.drawText(getContext().getString(R.string.pro_showcase_torch),
                tcx + tu * 0.95f, tcy + pw * 0.015f, paint);

        // Quick action: Restart (the mockup's own restart, demo theater included).
        notifPowerRect.set(panel.right - pw * 0.330f, card.bottom + pw * 0.045f,
                panel.right - pw * 0.055f, card.bottom + pw * 0.045f + pw * 0.14f);
        paint.setColor(0x1AFFFFFF);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(notifPowerRect, notifPowerRect.height() / 2f, notifPowerRect.height() / 2f, paint);
        float pu = pw * 0.036f;
        float pcx = notifPowerRect.left + pw * 0.055f;
        float pcy = notifPowerRect.centerY();
        // Power glyph as the Material Icons ligature, like the rest of the app.
        drawIcon(canvas, "power_settings_new", pcx, pcy, pw * 0.062f, 0xFFE9E9EC);
        paint.setTextSize(pw * 0.042f);
        paint.setColor(0xFFE9E9EC);
        canvas.drawText(getContext().getString(R.string.pro_showcase_restart),
                pcx + pu * 1.15f, pcy + pw * 0.015f, paint);

        paint.setColor(0x33FFFFFF);
        RectF handle = new RectF(panel.centerX() - pw * 0.10f, panel.bottom - pw * 0.09f,
                panel.centerX() + pw * 0.10f, panel.bottom - pw * 0.075f);
        canvas.drawRoundRect(handle, handle.height() / 2f, handle.height() / 2f, paint);
        canvas.restore();
    }

    /**
     * The shutdown beat of a restart: the screen darkens and shows "Shutting down…".
     * The boot theater that follows is the shared {@link #drawBootSequence(Canvas)}.
     */
    private void drawRestartSequence(Canvas canvas) {
        float pw = phone.width();
        float p = restartProgress;
        canvas.save();
        canvas.clipPath(screenClipPath);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(0xFF000000);
        canvas.drawRoundRect(screen, screenR, screenR, paint);

        float shutdownA = easeOut(seg(p, 0.04f, 0.22f)) * (1f - easeOut(seg(p, 0.62f, 0.78f)));
        if (shutdownA > 0.001f) {
            paint.setTypeface(labelTypeface);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(pw * 0.048f);
            paint.setColor(withAlpha(0xFF9A9AA0, shutdownA));
            canvas.drawText(getContext().getString(R.string.pro_showcase_shutting_down),
                    screen.centerX(), screen.centerY(), paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }
        canvas.restore();
    }

    /** Long-press power menu: asks to restart, like a real device. */
    private void drawPowerMenu(Canvas canvas) {
        if (!powerMenuOpen) return;
        float pw = phone.width();
        canvas.save();
        canvas.clipPath(screenClipPath);
        canvas.drawColor(0x99000000);
        RectF card = new RectF(screen.left + pw * 0.10f, screen.centerY() - pw * 0.52f,
                screen.right - pw * 0.10f, screen.centerY() + pw * 0.28f);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(0xFF1C1E24);
        canvas.drawRoundRect(card, pw * 0.05f, pw * 0.05f, paint);
        paint.setTypeface(labelTypeface);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(pw * 0.055f);
        paint.setColor(0xFFF2F2F4);
        canvas.drawText(getContext().getString(R.string.pro_showcase_power_menu_title),
                card.centerX(), card.top + pw * 0.12f, paint);
        paint.setTextSize(pw * 0.040f);
        paint.setColor(0xFF9A9AA0);
        canvas.drawText(getContext().getString(R.string.pro_showcase_power_menu_body),
                card.centerX(), card.top + pw * 0.21f, paint);
        float bw = card.width() - pw * 0.08f;
        powerMenuRestartRect.set(card.left + pw * 0.04f, card.top + pw * 0.30f,
                card.left + pw * 0.04f + bw, card.top + pw * 0.30f + pw * 0.13f);
        paint.setColor(0x33FFD15C);
        canvas.drawRoundRect(powerMenuRestartRect, pw * 0.035f, pw * 0.035f, paint);
        paint.setTextSize(pw * 0.046f);
        paint.setColor(0xFFFFD15C);
        canvas.drawText(getContext().getString(R.string.pro_showcase_restart),
                powerMenuRestartRect.centerX(), powerMenuRestartRect.centerY() + pw * 0.016f, paint);
        powerMenuCancelRect.set(powerMenuRestartRect);
        powerMenuCancelRect.offset(0f, pw * 0.17f);
        paint.setColor(0x14FFFFFF);
        canvas.drawRoundRect(powerMenuCancelRect, pw * 0.035f, pw * 0.035f, paint);
        paint.setColor(0xFFCFCFD4);
        canvas.drawText(getContext().getString(R.string.pro_showcase_power_menu_cancel),
                powerMenuCancelRect.centerX(), powerMenuCancelRect.centerY() + pw * 0.016f, paint);
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.restore();
    }

    /** Demo disclaimer shown when the mic/camera indicators are tapped. */
    private void drawAccessDialog(Canvas canvas) {
        if (!accessDialogOpen) return;
        float pw = phone.width();
        canvas.save();
        canvas.clipPath(screenClipPath);
        canvas.drawColor(0x99000000);
        RectF card = dialogCardRect;
        card.set(screen.left + pw * 0.09f, screen.centerY() - pw * 0.60f,
                screen.right - pw * 0.09f, screen.centerY() + pw * 0.38f);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(0xFF1C1E24);
        canvas.drawRoundRect(card, pw * 0.05f, pw * 0.05f, paint);
        paint.setTypeface(labelTypeface);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(pw * 0.062f);
        paint.setColor(0xFFF2F2F4);
        canvas.drawText(getContext().getString(R.string.pro_showcase_access_title),
                card.centerX(), card.top + pw * 0.135f, paint);
        // Body: larger than a caption so it reads like a real dialog, not fine print.
        TextPaint bodyPaint = new TextPaint(paint);
        bodyPaint.setTextAlign(Paint.Align.LEFT); // StaticLayout must not inherit center align
        bodyPaint.setTextSize(pw * 0.054f);
        bodyPaint.setColor(0xFFB9B9C0);
        StaticLayout body = StaticLayout.Builder
                .obtain(getContext().getString(R.string.pro_showcase_access_body), 0,
                        getContext().getString(R.string.pro_showcase_access_body).length(),
                        bodyPaint, (int) (card.width() - pw * 0.10f))
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1.22f)
                .build();
        canvas.save();
        canvas.translate(card.left + pw * 0.05f, card.top + pw * 0.21f);
        body.draw(canvas);
        canvas.restore();
        dialogOkRect.set(card.centerX() - pw * 0.17f, card.bottom - pw * 0.155f,
                card.centerX() + pw * 0.17f, card.bottom - pw * 0.055f);
        paint.setColor(0x33FFD15C);
        canvas.drawRoundRect(dialogOkRect, dialogOkRect.height() / 2f, dialogOkRect.height() / 2f, paint);
        paint.setTextSize(pw * 0.052f);
        paint.setColor(0xFFFFD15C);
        canvas.drawText(getContext().getString(R.string.pro_showcase_access_ok),
                dialogOkRect.centerX(), dialogOkRect.centerY() + pw * 0.018f, paint);
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.restore();
    }

    /** Material-style ripple: grows inside the tapped element's rounded shape. */
    private void drawTouchRipple(Canvas canvas) {
        if (rippleProgress <= 0.001f || rippleProgress >= 1f) return;
        canvas.save();
        Path clip = rippleClipPath;
        clip.reset();
        clip.addRoundRect(rippleClip, rippleClipRadius, rippleClipRadius, Path.Direction.CW);
        canvas.clipPath(clip);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        // Keep full strength while expanding, fade out late (Material 3 feel).
        float fade = rippleProgress < 0.65f ? 1f : 1f - (rippleProgress - 0.65f) / 0.35f;
        paint.setColor(withAlpha(0xFFFFFFFF, 0.12f * fade));
        // Expand until the ripple covers the farthest corner of the button.
        float far = Math.max(
                (float) Math.hypot(rippleX - rippleClip.left, rippleY - rippleClip.top),
                Math.max((float) Math.hypot(rippleX - rippleClip.right, rippleY - rippleClip.top),
                        Math.max((float) Math.hypot(rippleX - rippleClip.left, rippleY - rippleClip.bottom),
                                (float) Math.hypot(rippleX - rippleClip.right, rippleY - rippleClip.bottom))));
        canvas.drawCircle(rippleX, rippleY, far * rippleProgress, paint);
        canvas.restore();
    }

    private void drawLockScreen(Canvas canvas) {
        if (unlockProgress >= 0.999f) return;
        // During a boot the lock screen appears only after the theater: a short black beat
        // between "Starting…" fading out and the lock UI, like a real device.
        if (bootProgress < 0.78f) return;
        float pw = phone.width();
        canvas.save();
        canvas.clipPath(screenClipPath);
        canvas.translate(0f, -unlockProgress * screen.height());

        paint.setColor(0xFF050608);
        canvas.drawRoundRect(screen, screenR, screenR, paint);
        paint.setShader(new RadialGradient(screen.centerX(), screen.top + screen.height() * 0.28f,
                screen.width() * 1.1f, new int[]{0xFF14161C, 0xFF08090C, 0xFF050608},
                new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(screen, screenR, screenR, paint);
        paint.setShader(null);

        // The FadSec flag as wallpaper art: above the dark gradient, behind the clock.
        if (flag != null) {
            float flagSize = pw * 0.66f;
            float cx = screen.centerX();
            float cy = screen.top + screen.height() * 0.53f;
            float half = flagSize / 2f;
            flag.setBounds(Math.round(cx - half), Math.round(cy - half),
                    Math.round(cx + half), Math.round(cy + half));
            flag.setAlpha(110);
            flag.draw(canvas);
        }

        // Status bar (icons + camera) stays visible on the lock screen.
        drawStatusBar(canvas, 1f, false);

        paint.setTypeface(labelTypeface);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(pw * 0.26f);
        paint.setColor(0xFFF5F5F7);
        canvas.drawText(clockText, screen.centerX(), screen.top + screen.height() * 0.225f, paint);
        paint.setTextSize(pw * 0.050f);
        paint.setColor(0xFF9A9AA0);
        canvas.drawText(dateText, screen.centerX(), screen.top + screen.height() * 0.275f, paint);
        if (!usageText.isEmpty()) {
            paint.setTextSize(pw * 0.042f);
            paint.setColor(0x8C9A9AA0);
            canvas.drawText(usageText, screen.centerX(), screen.top + screen.height() * 0.318f, paint);
        }

        float hintY = Math.min(screen.top + screen.height() * 0.72f, getHeight() - dp(26));
        paint.setTextSize(pw * 0.050f);
        paint.setColor(0xCCE9E9EC);
        canvas.drawText(getContext().getString(R.string.pro_showcase_swipe_unlock), screen.centerX(), hintY, paint);

        // Animated up arrow above the hint (Material Icons).
        float bob = (float) Math.sin(lockBob * Math.PI * 2.0) * dp(3.5f);
        drawIcon(canvas, "keyboard_arrow_up", screen.centerX(), hintY - pw * 0.145f + bob,
                pw * 0.080f, 0x99E9E9EC);
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.restore();
    }

    private void drawVolumeHud(Canvas canvas) {
        if (volumeHudAlpha <= 0.001f) return;
        float pw = phone.width();
        float hudW = pw * 0.58f;
        float hudH = pw * 0.15f;
        RectF hud = new RectF(screen.centerX() - hudW / 2f, screen.top + screen.height() * 0.20f,
                screen.centerX() + hudW / 2f, screen.top + screen.height() * 0.20f + hudH);
        paint.setColor(withAlpha(0xE61A1C22, volumeHudAlpha));
        canvas.drawRoundRect(hud, hudH / 2f, hudH / 2f, paint);

        float unit = pw * 0.045f;
        float wx = hud.left + hudH * 0.60f;
        float wy = hud.centerY();
        // Speaker as the Material Icons volume glyph.
        drawIcon(canvas, "volume_up", wx, wy, unit * 1.55f,
                withAlpha(0xFFE9E9EC, volumeHudAlpha));

        // Percentage first: the remaining width is the track, so it can never be mismeasured.
        String pct = Math.round(volumeLevel * 100f) + "%";
        paint.setTypeface(labelTypeface);
        paint.setTextSize(pw * 0.048f);
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setColor(withAlpha(0xFFE9E9EC, volumeHudAlpha));
        canvas.drawText(pct, hud.right - hudH * 0.38f, hud.centerY() + pw * 0.017f, paint);
        float pctW = paint.measureText(pct);
        paint.setTextAlign(Paint.Align.LEFT);

        // Android-style volume bar: a clearly darker empty track with a bright filled part.
        float trackL = hud.left + hudH * 1.05f;
        float trackR = hud.right - hudH * 0.38f - pctW - hudH * 0.22f;
        float trackH = Math.max(4f, hudH * 0.16f);
        RectF track = new RectF(trackL, hud.centerY() - trackH / 2f, trackR, hud.centerY() + trackH / 2f);
        paint.setColor(withAlpha(0xFF3A3D45, volumeHudAlpha));
        canvas.drawRoundRect(track, trackH / 2f, trackH / 2f, paint);
        float fillW = track.width() * volumeLevel;
        if (fillW > 0.5f) {
            paint.setColor(withAlpha(0xFFF2F2F4, volumeHudAlpha));
            canvas.drawRoundRect(new RectF(track.left, track.top, track.left + fillW, track.bottom),
                    trackH / 2f, trackH / 2f, paint);
        }
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

    /** The back panel: just the surface plus branding — the chassis is GL geometry. */
    private void drawBackPanel(Canvas canvas) {
        float pw = phone.width();
        paint.setStyle(Paint.Style.FILL);
        // The gradient must be fully opaque: `paint` still carries the wordmark's 0x3A alpha from the previous frame, and setShader() does not reset it (this made the whole back panel see-through).
        paint.setAlpha(255);
        paint.setShader(new LinearGradient(0f, screen.top, 0f, screen.bottom,
                0xFF1C1F25, 0xFF101216, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(screen, screenR, screenR, paint);
        paint.setShader(null);
        RectF face = new RectF(screen);
        if (flag != null) {
            float flagSize = face.width() * 0.52f;
            float cx = face.centerX();
            float cy = face.top + face.height() * 0.30f;
            float half = flagSize / 2f;
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
        canvas.drawText("FadSec Lab", face.centerX(), face.top + face.height() * 0.47f, paint);
        paint.setLetterSpacing(0f);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    // -------------------------------------------------------------- helpers

    /** Draws a Material Icons ligature centered at (cx, cy) — the app's icon font. */
    private void drawIcon(Canvas canvas, String ligature, float cx, float cy, float size, int color) {
        paint.setTypeface(materialIcons);
        paint.setTextSize(size);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(color);
        float baseline = cy - (paint.descent() + paint.ascent()) / 2f;
        canvas.drawText(ligature, cx, baseline, paint);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTypeface(labelTypeface);
    }

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
            lockBobAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
            lockBobAnimator.addUpdateListener(a -> {
                lockBob = (float) a.getAnimatedValue();
                long now = SystemClock.elapsedRealtime();
                if (now - lastBobInvalidateAt >= 33L) {
                    lastBobInvalidateAt = now;
                    if (glMode) {
                        // The surface is offscreen in GL mode: the host must re-render the
                        // face texture, otherwise the bob never reaches the screen.
                        if (dirtyListener != null) dirtyListener.onContentDirty();
                    } else if (isShown()) {
                        invalidate();
                    }
                }
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

    /** Scales a color's own alpha by {@code factor} (0..1) — never replaces it, so a
     *  translucent base like 0x1AFFFFFF stays translucent at full factor. */
    private static int withAlpha(int color, float factor) {
        float f = Math.max(0f, Math.min(1f, factor));
        int base = (color >>> 24);
        return (color & 0x00FFFFFF) | (Math.round(base * f) << 24);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(shadeTicker);
        if (bootAnimator != null) bootAnimator.cancel();
        if (restartAnim != null) restartAnim.cancel();
        if (popAnim != null) popAnim.cancel();
        if (rippleAnim != null) rippleAnim.cancel();
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
