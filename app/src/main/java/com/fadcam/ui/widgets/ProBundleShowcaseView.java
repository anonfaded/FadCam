package com.fadcam.ui.widgets;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.graphics.RectF;
import android.os.BatteryManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.inputmethod.InputMethodManager;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.fadcam.R;
import com.fadcam.ui.ProBundleFullscreenActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Reusable premium showcase for the Pro bundle: a headline block beside a programmatically
 * drawn FadSec Lab phone. The phone's front shows the three Pro apps (with a live status bar);
 * its back is etched with the FadSec Lab flag. Press and drag sideways to turn the device —
 * the rotation is a real {@code rotationY} transform, and a tap is reported through the
 * configurable click callback.
 *
 * <p>The component is self-contained: configure it, drop it into any layout, and it handles
 * its own splash animation, clock/battery updates, 3D drag physics and reduced-motion
 * behaviour. Vertical page scrolling is never blocked — the horizontal drag is only claimed
 * once it clearly exceeds vertical movement.
 */
public class ProBundleShowcaseView extends FrameLayout {
    private static final float PHONE_ASPECT = 2.05f;
    private static final float MIN_ROTATION = -210f;
    private static final float MAX_ROTATION = 210f;
    private static final float OVERDRAG_DAMPING = 0.25f;
    private static final float SNAP_VELOCITY_FACTOR = 0.06f;
    /** The object's single perspective: moderate, product-shot like, no face stretching. */
    private static final float CAMERA_DISTANCE_DP = 2500f;
    /** Faces stay rigid (near-orthographic) so only the object's perspective shapes them. */
    private static final float CHILD_CAMERA_DISTANCE_DP = 40000f;

    private final TextView labelView;
    private final TextView titleView;
    private final TextView subtitleView;
    private final TextView metaView;
    private final FrameLayout phoneWrapper;
    private final FrameLayout phoneContainer;
    private final BundlePhoneSurfaceView frontSurface;
    private final BundlePhoneSurfaceView backSurface;
    private final SimpleDateFormat clockFormat = new SimpleDateFormat("h:mm", Locale.getDefault());
    private final int touchSlop;

    private boolean dragEnabled = true;
    private boolean splashEnabled = true;
    private boolean liveClockEnabled = true;
    private boolean realBatteryEnabled = true;
    private boolean compactMode = false;
    private float rotation = 0f;
    private boolean backVisible = false;
    private float downX, downY, startRotation;
    private boolean dragging;
    private int touchMode = MODE_NONE;
    private float shadeStart = 0f;
    private int volumeDirection = 0;
    private boolean moved = false;
    private VelocityTracker velocityTracker;
    private ValueAnimator snapAnimator;
    private OnClickListener showcaseClickListener;
    private boolean timeReceiverRegistered;
    private boolean batteryReceiverRegistered;
    private EditText noteInput;
    private View expandButton;
    private PhoneEdgeView leftEdge;
    private PhoneEdgeView rightEdge;
    private Runnable onFirstAppTap;
    private boolean nudgeShown = false;
    private boolean introRunning = false;
    private boolean introPlayed = false;
    private ValueAnimator introAnimator;
    private ValueAnimator expandAnimator;
    private ValueAnimator expandPulse;

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateClock();
        }
    };
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateBattery(intent);
        }
    };

    public ProBundleShowcaseView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        inflate(context, R.layout.view_pro_bundle_showcase, this);
        labelView = findViewById(R.id.showcaseLabel);
        titleView = findViewById(R.id.showcaseTitle);
        subtitleView = findViewById(R.id.showcaseSubtitle);
        metaView = findViewById(R.id.showcaseMeta);
        phoneWrapper = findViewById(R.id.showcasePhoneWrapper);
        phoneContainer = findViewById(R.id.showcasePhoneContainer);

        final float depth = context.getResources().getDimension(R.dimen.pro_showcase_phone_depth);
        final float visorDepth = context.getResources().getDimension(R.dimen.pro_showcase_visor_depth);
        frontSurface = new BundlePhoneSurfaceView(context, BundlePhoneSurfaceView.SIDE_FRONT);
        backSurface = new BundlePhoneSurfaceView(context, BundlePhoneSurfaceView.SIDE_BACK);
        phoneContainer.addView(frontSurface, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        phoneContainer.addView(backSurface, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        backSurface.setRotationY(180f);
        backSurface.setVisibility(INVISIBLE);
        // Real thickness: front and back sit half a body apart; the edges connect them.
        frontSurface.setTranslationZ(depth / 2f);
        backSurface.setTranslationZ(-depth / 2f);
        leftEdge = new PhoneEdgeView(context);
        leftEdge.setLayoutParams(new LayoutParams(Math.round(depth), LayoutParams.MATCH_PARENT, Gravity.START | Gravity.TOP));
        leftEdge.setRotationY(-90f);
        rightEdge = new PhoneEdgeView(context);
        rightEdge.setLayoutParams(new LayoutParams(Math.round(depth), LayoutParams.MATCH_PARENT, Gravity.END | Gravity.TOP));
        rightEdge.setRotationY(90f);
        phoneContainer.addView(leftEdge);
        phoneContainer.addView(rightEdge);
        float camera = context.getResources().getDisplayMetrics().density * CAMERA_DISTANCE_DP;
        float rigidCamera = context.getResources().getDisplayMetrics().density * CHILD_CAMERA_DISTANCE_DP;
        phoneContainer.setCameraDistance(camera);
        frontSurface.setCameraDistance(rigidCamera);
        backSurface.setCameraDistance(rigidCamera);
        leftEdge.setCameraDistance(rigidCamera);
        rightEdge.setCameraDistance(rigidCamera);
        // (wired in onLayout — the surfaces must have measured first)

        // The tilted device overflows its own bounds; never clip it (this was cutting the top).
        phoneWrapper.setClipChildren(false);
        phoneWrapper.setClipToPadding(false);
        setClipChildren(false);
        setClipToPadding(false);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        phoneWrapper.setContentDescription(context.getString(R.string.pro_showcase_phone_desc));
        phoneWrapper.setOnTouchListener((v, event) -> handleTouch(event));
        applyRotation(0f);

        // Hidden input that lets the Notes preview be typed on with the real keyboard.
        noteInput = new EditText(context);
        noteInput.setBackground(null);
        noteInput.setAlpha(0f);
        noteInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        noteInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        noteInput.setLayoutParams(new LayoutParams(1, 1));
        noteInput.setVisibility(GONE);
        noteInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable e) {
                frontSurface.setNote(e.toString());
            }
        });
        addView(noteInput);
        expandButton = findViewById(R.id.showcaseExpand);
        if (expandButton != null) {
            expandButton.setOnClickListener(v -> playExpandSequence());
            expandButton.setContentDescription(context.getString(R.string.pro_showcase_expand_desc));
            if (motionAllowed()) {
                expandPulse = ValueAnimator.ofFloat(1f, 1.07f);
                expandPulse.setDuration(1500);
                expandPulse.setRepeatCount(ValueAnimator.INFINITE);
                expandPulse.setRepeatMode(ValueAnimator.REVERSE);
                expandPulse.addUpdateListener(a -> {
                    float v = (float) a.getAnimatedValue();
                    expandButton.setScaleX(v);
                    expandButton.setScaleY(v);
                });
                expandPulse.start();
            }
        }
    }

    // ------------------------------------------------------------ configuration

    /** Convenience: set the four text slots in one call. */
    public void configure(CharSequence label, CharSequence title, CharSequence subtitle, CharSequence meta) {
        setLabel(label);
        setTitle(title);
        setSubtitle(subtitle);
        setMeta(meta);
    }

    public void setLabel(CharSequence text) {
        labelView.setText(text);
    }

    public void setTitle(CharSequence text) {
        titleView.setText(text);
    }

    public void setSubtitle(CharSequence text) {
        subtitleView.setText(text);
    }

    public void setMeta(CharSequence text) {
        metaView.setText(text);
    }

    public void setLabelVisible(boolean visible) {
        labelView.setVisibility(visible ? VISIBLE : GONE);
    }

    /** Replace the three showcase apps (icon resources and display names). */
    public void setApps(int[] iconRes, String[] names) {
        frontSurface.setApps(iconRes, names);
    }

    /** Enable/disable the short wake animation. Call before the view is attached. */
    public void setSplashEnabled(boolean enabled) {
        splashEnabled = enabled;
        frontSurface.setSplashEnabled(enabled);
    }

    public void setDragEnabled(boolean enabled) {
        dragEnabled = enabled;
    }

    /** Live clock in the phone status bar. Call before the view is attached. */
    public void setLiveClockEnabled(boolean enabled) {
        liveClockEnabled = enabled;
    }

    /** Real battery percentage in the phone status bar (sticky system broadcast, no permission). */
    public void setRealBatteryEnabled(boolean enabled) {
        realBatteryEnabled = enabled;
    }

    public void setInitialRotation(float degrees) {
        applyRotation(Math.max(MIN_ROTATION, Math.min(MAX_ROTATION, degrees)));
    }

    /** Tighter variant: hides the metadata line and shrinks the phone slightly. */
    public void setCompactMode(boolean compact) {
        compactMode = compact;

    }

    public void setOnShowcaseClickListener(OnClickListener listener) {
        showcaseClickListener = listener;
    }

    // ------------------------------------------------------------------ sizing


    // -------------------------------------------------------------- 3D drag

    private static final int MODE_NONE = 0;
    private static final int MODE_ROTATE = 1;
    private static final int MODE_SHADE = 2;
    private static final int MODE_UNLOCK = 3;
    private static final int MODE_POWER = 4;
    private static final int MODE_VOLUME = 5;

    private boolean handleTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (snapAnimator != null) snapAnimator.cancel();
                if (introRunning) return true;
                downX = event.getX();
                downY = event.getY();
                startRotation = rotation;
                dragging = false;
                moved = false;
                volumeDirection = 0;
                touchMode = MODE_NONE;
                // Swipes inside the phone belong to the phone: never let the page steal them.
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                if (dragEnabled) {
                    if (frontSurface.isOnPowerButton(downX, downY)) {
                        touchMode = MODE_POWER;
                    } else if ((volumeDirection = frontSurface.volumeButtonAt(downX, downY)) != 0) {
                        touchMode = MODE_VOLUME;
                    } else if (frontSurface.getShadeProgress() > 0.4f) {
                        touchMode = MODE_SHADE;
                        shadeStart = frontSurface.getShadeProgress();
                    } else if (frontSurface.isInStatusBar(downX, downY)) {
                        touchMode = MODE_SHADE;
                        shadeStart = frontSurface.getShadeProgress();
                    } else if (frontSurface.isLocked()) {
                        touchMode = MODE_UNLOCK;
                    }
                }
                if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
                else velocityTracker.clear();
                velocityTracker.addMovement(event);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (velocityTracker != null) velocityTracker.addMovement(event);
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) moved = true;
                if (touchMode == MODE_POWER || touchMode == MODE_VOLUME) return true;
                float phoneH = Math.max(1f, phoneWrapper.getHeight());
                if (touchMode == MODE_SHADE) {
                    frontSurface.setShadeProgress(shadeStart - dy / (phoneH * 0.55f));
                    return true;
                }
                if (touchMode == MODE_UNLOCK) {
                    if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                        touchMode = MODE_ROTATE;
                    } else {
                        frontSurface.setUnlockProgress(-dy / (phoneH * 0.55f));
                        return true;
                    }
                }
                if (touchMode == MODE_NONE) {
                    float adx = Math.abs(dx);
                    float ady = Math.abs(dy);
                    if (adx > touchSlop && adx > ady * 1.4f) {
                        touchMode = MODE_ROTATE;
                    } else {
                        // Any other movement stays inside the phone (page scroll stays locked).
                        return true;
                    }
                }
                if (touchMode == MODE_ROTATE && dragEnabled) {
                    float sensitivity = 180f / Math.max(1f, phoneWrapper.getWidth());
                    applyRotation(resist(startRotation + dx * sensitivity));
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                switch (touchMode) {
                    case MODE_POWER:
                        frontSurface.animateUnlock(frontSurface.isLocked() ? 1f : 0f);
                        break;
                    case MODE_VOLUME:
                        adjustDeviceVolume(volumeDirection);
                        break;
                    case MODE_SHADE:
                        frontSurface.animateShade(frontSurface.getShadeProgress() > 0.4f ? 1f : 0f);
                        break;
                    case MODE_UNLOCK:
                        frontSurface.animateUnlock(frontSurface.getUnlockProgress() > 0.35f ? 1f : 0f);
                        break;
                    case MODE_ROTATE:
                        if (velocityTracker != null) velocityTracker.addMovement(event);
                        if (dragEnabled && event.getActionMasked() == MotionEvent.ACTION_UP) {
                            finishDrag();
                        } else {
                            animateTo(rotation > 90f ? 180f : (rotation < -90f ? -180f : 0f));
                        }
                        break;
                    default:
                        if (event.getActionMasked() == MotionEvent.ACTION_UP && !moved) {
                            handleTap(downX, downY);
                        }
                }
                touchMode = MODE_NONE;
                dragging = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            }
        }
        return true;
    }

    /** Taps inside the phone: previews, calculator keys, the close chevron, app icons. */
    private void handleTap(float x, float y) {
        if (frontSurface.previewVisible()) {
            if (frontSurface.closePreviewAt(x, y)) {
                closeAppPreview();
            } else {
                int key = frontSurface.calculatorKeyAt(x, y);
                if (key >= 0) frontSurface.calculatorPress(key);
            }
            return;
        }
        int app = frontSurface.appIconAt(x, y);
        if (app > 0) {
            if (!nudgeShown) {
                nudgeShown = true;
                frontSurface.pulseApp(app - 1, true);
                if (onFirstAppTap != null) onFirstAppTap.run();
            }
            openAppPreview(app);
            return;
        }
        if (showcaseClickListener != null) showcaseClickListener.onClick(this);
    }

    private void openAppPreview(int app) {
        frontSurface.openPreview(app);
        if (app == 1) {
            noteInput.setVisibility(VISIBLE);
            noteInput.requestFocus();
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(noteInput, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void closeAppPreview() {
        frontSurface.closePreview();
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(noteInput.getWindowToken(), 0);
        noteInput.clearFocus();
        noteInput.setVisibility(GONE);
    }

    /** Taps on the mock volume buttons change the real device volume (no permission needed). */
    private void adjustDeviceVolume(int direction) {
        if (direction == 0) return;
        android.media.AudioManager am =
                (android.media.AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                direction > 0 ? android.media.AudioManager.ADJUST_RAISE : android.media.AudioManager.ADJUST_LOWER, 0);
        int max = Math.max(1, am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC));
        int now = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        frontSurface.showVolumeHud(now / (float) max);
    }

    /** Rubber-band resistance past the sensible viewing range. */
    private float resist(float angle) {
        if (angle < MIN_ROTATION) return MIN_ROTATION - (MIN_ROTATION - angle) * OVERDRAG_DAMPING;
        if (angle > MAX_ROTATION) return MAX_ROTATION + (angle - MAX_ROTATION) * OVERDRAG_DAMPING;
        return angle;
    }

    /** Snap to the nearest meaningful orientation, using velocity only as a gentle nudge. */
    private void finishDrag() {
        float vx = 0f;
        if (velocityTracker != null) {
            velocityTracker.computeCurrentVelocity(1000);
            vx = velocityTracker.getXVelocity();
        }
        float projected = rotation + vx * SNAP_VELOCITY_FACTOR;
        float target;
        if (projected > 90f) target = 180f;
        else if (projected < -90f) target = -180f;
        else target = 0f;
        animateTo(target);
    }

    private void animateTo(float target) {
        if (snapAnimator != null) snapAnimator.cancel();
        float from = rotation;
        if (!motionAllowed()) {
            applyRotation(target);
            return;
        }
        snapAnimator = ValueAnimator.ofFloat(from, target);
        snapAnimator.setDuration((long) Math.min(500f, 220f + Math.abs(target - from) * 1.4f));
        snapAnimator.setInterpolator(new DecelerateInterpolator(1.7f));
        snapAnimator.addUpdateListener(a -> applyRotation((float) a.getAnimatedValue()));
        snapAnimator.start();
    }

    private void applyRotation(float angle) {
        rotation = angle;
        phoneContainer.setRotationY(angle);
        boolean showBack = angle > 90f || angle < -90f;
        if (showBack != backVisible) {
            backVisible = showBack;
            // Both surfaces are edge-on at 90 degrees, so the swap is invisible.
            frontSurface.setVisibility(showBack ? INVISIBLE : VISIBLE);
            backSurface.setVisibility(showBack ? VISIBLE : INVISIBLE);
        }
    }

    // ------------------------------------------------------------ live status

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        wirePhoneParts();
    }

    /** Positions the 3D edges, visor and button models once real measurements exist. */
    private void wirePhoneParts() {
        if (phoneWrapper == null || frontSurface == null) return;
        float depth = getResources().getDimension(R.dimen.pro_showcase_phone_depth);
        float w = phoneWrapper.getWidth();
        if (w > 0f) {
            if (leftEdge != null) leftEdge.setTranslationX(-depth / 2f + w * 0.025f);
            if (rightEdge != null) rightEdge.setTranslationX(depth / 2f - w * 0.025f);
        }
        if (leftEdge != null) {
            leftEdge.setButtonRects(frontSurface.getVolUpRect(), frontSurface.getVolDownRect(), null);
        }
        if (rightEdge != null) {
            rightEdge.setButtonRects(null, null, frontSurface.getPowerBtnRect());
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Context context = getContext();
        if (liveClockEnabled && !timeReceiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_TIME_TICK);
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            context.registerReceiver(timeReceiver, filter);
            timeReceiverRegistered = true;
            updateClock();
        }
        if (realBatteryEnabled && !batteryReceiverRegistered) {
            Intent sticky = context.registerReceiver(batteryReceiver,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            batteryReceiverRegistered = true;
            updateBattery(sticky);
        }
        updateUsageEasterEgg();
        // Posted so the first layout has run: the fly-in needs real coordinates.
        post(() -> {
            if (splashEnabled) startIntro();
            else frontSurface.settleSplash();
        });
    }

    @Override
    protected void onDetachedFromWindow() {
        Context context = getContext();
        if (timeReceiverRegistered) {
            try {
                context.unregisterReceiver(timeReceiver);
            } catch (IllegalArgumentException ignored) {
                // Already unregistered.
            }
            timeReceiverRegistered = false;
        }
        if (batteryReceiverRegistered) {
            try {
                context.unregisterReceiver(batteryReceiver);
            } catch (IllegalArgumentException ignored) {
                // Already unregistered.
            }
            batteryReceiverRegistered = false;
        }
        if (snapAnimator != null) {
            snapAnimator.cancel();
            snapAnimator = null;
        }
        if (introAnimator != null) {
            introAnimator.cancel();
            introAnimator = null;
        }
        if (expandAnimator != null) {
            expandAnimator.cancel();
            expandAnimator = null;
        }
        if (expandPulse != null) {
            expandPulse.cancel();
            expandPulse = null;
        }
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
        super.onDetachedFromWindow();
    }

    private void updateClock() {
        frontSurface.setClockText(clockFormat.format(new Date()));
    }

    private void updateBattery(Intent intent) {
        if (intent == null) return;
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        if (level >= 0 && scale > 0) {
            frontSurface.setBattery(Math.round(level * 100f / scale), charging);
        }
    }

    /** Called on the first-ever app icon tap (used to pulse the CTA). */
    public void setOnFirstAppTapListener(Runnable listener) {
        onFirstAppTap = listener;
    }

    /** Phone-only mode for the fullscreen view: no text column, phone centered, full height. */
    public void setPhoneOnlyMode(boolean phoneOnly) {
        View root = getChildAt(0) instanceof LinearLayout ? getChildAt(0) : null;
        if (root instanceof LinearLayout) {
            LinearLayout ll = (LinearLayout) root;
            ll.setGravity(phoneOnly ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL);
            if (ll.getChildCount() > 0) {
                ll.getChildAt(0).setVisibility(phoneOnly ? GONE : VISIBLE);
            }
        }
        if (phoneOnly) {
            frontSurface.setCropFade(false);
            setPadding(0, 0, 0, 0);
        }
    }

    /** Explicit phone size (safe: fixed dimensions, no wrap-content measurement traps). */
    public void setPhoneSize(float widthDp, boolean fullHeight) {
        ViewGroup.LayoutParams lp = phoneWrapper.getLayoutParams();
        int w = Math.round(widthDp * getResources().getDisplayMetrics().density);
        lp.width = w;
        lp.height = Math.round(w * (fullHeight ? 2.05f : 0.55f * 2.05f));
        phoneWrapper.setLayoutParams(lp);
    }

    /** One 360-degree turn, like a product shot. */
    public void playIntroSpin() {
        if (!motionAllowed()) return;
        ValueAnimator spin = ValueAnimator.ofFloat(0f, 360f);
        spin.setDuration(760);
        spin.setInterpolator(new DecelerateInterpolator(1.1f));
        spin.addUpdateListener(a -> phoneWrapper.setRotationY((float) a.getAnimatedValue()));
        spin.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                phoneWrapper.setRotationY(0f);
            }
        });
        spin.start();
    }

    /** The showcase entrance: the phone arrives big and spun, then settles into the card. */
    private void startIntro() {
        if (!motionAllowed() || introPlayed || getWidth() == 0) {
            introPlayed = true;
            frontSurface.startSplash();
            return;
        }
        introPlayed = true;
        introRunning = true;
        int[] loc = new int[2];
        phoneWrapper.getLocationOnScreen(loc);
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        final float dx = dm.widthPixels / 2f - (loc[0] + phoneWrapper.getWidth() / 2f);
        final float dy = dm.heightPixels * 0.42f - (loc[1] + phoneWrapper.getHeight() / 2f);
        phoneWrapper.setScaleX(2.1f);
        phoneWrapper.setScaleY(2.1f);
        phoneWrapper.setTranslationX(dx);
        phoneWrapper.setTranslationY(dy);
        phoneWrapper.setAlpha(0.35f);
        // The phone arrives showing its back, then flips to the front as it settles.
        applyRotation(180f);
        introAnimator = ValueAnimator.ofFloat(0f, 1f);
        introAnimator.setDuration(1150);
        introAnimator.setInterpolator(new DecelerateInterpolator(1.35f));
        introAnimator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            phoneWrapper.setScaleX(2.1f - 1.1f * t);
            phoneWrapper.setScaleY(2.1f - 1.1f * t);
            phoneWrapper.setTranslationX(dx * (1f - t));
            phoneWrapper.setTranslationY(dy * (1f - t));
            phoneWrapper.setAlpha(0.35f + 0.65f * t);
            applyRotation(180f * (1f - t));
        });
        introAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                phoneWrapper.setScaleX(1f);
                phoneWrapper.setScaleY(1f);
                phoneWrapper.setTranslationX(0f);
                phoneWrapper.setTranslationY(0f);
                phoneWrapper.setAlpha(1f);
                applyRotation(0f);
                introRunning = false;
                frontSurface.startSplash();
            }
        });
        introAnimator.start();
    }

    /** Spin the whole phone 360 degrees, then open the fullscreen inspector. */
    private void playExpandSequence() {
        if (introRunning || expandAnimator != null) return;
        if (!motionAllowed()) {
            launchFullscreen();
            return;
        }
        expandAnimator = ValueAnimator.ofFloat(0f, 360f);
        expandAnimator.setDuration(640);
        expandAnimator.setInterpolator(new DecelerateInterpolator(1.1f));
        expandAnimator.addUpdateListener(a -> phoneWrapper.setRotationY((float) a.getAnimatedValue()));
        expandAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                expandAnimator = null;
                phoneWrapper.setRotationY(0f);
                launchFullscreen();
            }
        });
        expandAnimator.start();
    }

    private void launchFullscreen() {
        Intent intent = new Intent(getContext(), ProBundleFullscreenActivity.class);
        getContext().startActivity(intent);
        if (getContext() instanceof android.app.Activity) {
            ((android.app.Activity) getContext()).overridePendingTransition(
                    android.R.anim.fade_in, android.R.anim.fade_out);
        }
    }

    /** "With FadCam for X years, Y months" — from the real first-install time. */
    private void updateUsageEasterEgg() {
        try {
            PackageInfo info = getContext().getPackageManager()
                    .getPackageInfo(getContext().getPackageName(), 0);
            long days = Math.max(0L, (System.currentTimeMillis() - info.firstInstallTime) / 86400000L);
            frontSurface.setUsageText(getContext().getString(R.string.pro_showcase_usage, formatDays(days)));
        } catch (Exception ignored) {
            // No install time available: leave the line out.
        }
    }

    private static String formatDays(long days) {
        long years = days / 365;
        long rem = days % 365;
        long months = rem / 30;
        rem %= 30;
        long weeks = rem / 7;
        long d = rem % 7;
        StringBuilder sb = new StringBuilder();
        appendUnit(sb, years, "year");
        appendUnit(sb, months, "month");
        if (sb.length() == 0) {
            appendUnit(sb, weeks, "week");
            appendUnit(sb, d, "day");
        }
        if (sb.length() == 0) sb.append("a moment");
        return sb.toString();
    }

    private static void appendUnit(StringBuilder sb, long value, String unit) {
        if (value <= 0 || sb.length() > 0) return;
        sb.append(value).append(' ').append(unit).append(value == 1 ? "" : "s");
    }

    // -------------------------------------------------------------- helpers

    private boolean motionAllowed() {
        return android.provider.Settings.Global.getFloat(getContext().getContentResolver(),
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
    }

}
