package com.fadcam.ui.widgets;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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
import android.view.View.MeasureSpec;
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
    private boolean bootEnabled = true;
    private boolean liveClockEnabled = true;
    private boolean realBatteryEnabled = true;
    private boolean compactMode = false;
    private float rotation = 0f;
    private boolean backVisible = false;
    private float downX, downY, startRotation;
    private float lastDy = 0f;
    private boolean dragging;
    private int touchMode = MODE_NONE;
    private float shadeStart = 0f;
    private int volumeDirection = 0;
    private boolean moved = false;
    private boolean directionDecided = false;
    private boolean movedFar = false;
    private boolean blockShadeGesture = false;
    private final Runnable powerHoldRunnable = this::onPowerHeld;
    private boolean powerMenuShown = false;
    private boolean touchOnPhone = false;
    private float wrapperScreenX, wrapperScreenY;
    private int pressedButton = 0;
    private VelocityTracker velocityTracker;
    private ValueAnimator snapAnimator;
    private OnClickListener showcaseClickListener;
    private boolean timeReceiverRegistered;
    private boolean batteryReceiverRegistered;
    private EditText noteInput;
    private View expandButton;
    private Runnable onFirstAppTap;
    private boolean nudgeShown = false;
    private PhoneGLView phoneGL;
    private Bitmap backBitmap;
    private Bitmap screenBitmap;
    private boolean frameDirty = true;
    private boolean frontDirty = true;
    private boolean backDirty = true;
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

        // The phone is a real OpenGL mesh now: the surfaces become offscreen texture
        // producers, rendered on demand whenever their content changes.
        frontSurface = new BundlePhoneSurfaceView(context, BundlePhoneSurfaceView.SIDE_FRONT);
        frontSurface.setGLMode(true);
        frontSurface.setDirtyListener(this::markFrontDirty);
        frontSurface.setRenderMode(BundlePhoneSurfaceView.MODE_SCREEN);
        backSurface = new BundlePhoneSurfaceView(context, BundlePhoneSurfaceView.SIDE_BACK);
        backSurface.setGLMode(true);
        backSurface.setDirtyListener(this::markBackDirty);
        backSurface.setRenderMode(BundlePhoneSurfaceView.MODE_PANEL);
        phoneGL = new PhoneGLView(context, null);
        phoneGL.setOverscan(1.5f);
        phoneGL.setCrop(1f, 0f);
        // Bigger than its slot (centred): the rotation headroom renders thanks to
        // clipChildren=false, so the phone can fill the card and still turn freely.
        LayoutParams glParams = new LayoutParams(
                Math.round(getResources().getDimension(R.dimen.pro_showcase_phone_w) * 1.5f),
                Math.round(getResources().getDimension(R.dimen.pro_showcase_phone_h) * 1.5f),
                Gravity.CENTER);
        phoneContainer.addView(phoneGL, glParams);
        // (wired in onLayout — the surfaces must have measured first)

        // The tilted device overflows its own bounds; never clip it (this was cutting the top).
        phoneWrapper.setClipChildren(false);
        phoneWrapper.setClipToPadding(false);
        setClipChildren(false);
        setClipToPadding(false);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        phoneWrapper.setContentDescription(context.getString(R.string.pro_showcase_phone_desc));
        setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                // Raw (screen) coordinates are nesting-proof: no reliance on parent offsets
                // or padding, which is exactly where the old hit-testing drifted.
                int[] loc = new int[2];
                phoneWrapper.getLocationOnScreen(loc);
                wrapperScreenX = loc[0];
                wrapperScreenY = loc[1];
                float lx = event.getRawX() - wrapperScreenX;
                float ly = event.getRawY() - wrapperScreenY;
                float margin = 24f * getResources().getDisplayMetrics().density;
                touchOnPhone = lx >= -margin && lx <= phoneWrapper.getWidth() + margin
                        && ly >= -margin && ly <= phoneWrapper.getHeight() + margin;
            }
            if (!touchOnPhone) return false;
            return handleTouch(event);
        });
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
            // (static button — no idle animation)
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

    /** Enable/disable the on-screen boot theater. Call before the view is attached. */
    public void setBootEnabled(boolean enabled) {
        bootEnabled = enabled;
        frontSurface.setBootEnabled(enabled);
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
                downX = event.getRawX() - wrapperScreenX;
                downY = event.getRawY() - wrapperScreenY;
                startRotation = rotation;
                dragging = false;
                moved = false;
                volumeDirection = 0;
                touchMode = MODE_NONE;
                directionDecided = false;
                movedFar = false;
                // Swipes inside the phone belong to the phone: never let the page steal them.
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                powerMenuShown = false;
                lastDy = 0f;
                boolean shadeOpen = frontSurface.getShadeProgress() > 0.4f;
                boolean dialogOpen = frontSurface.isAccessDialogOpen() || frontSurface.isPowerMenuOpen();
                blockShadeGesture = dialogOpen;
                if (dragEnabled && shadeOpen) {
                    // The open shade owns the vertical gesture: pulling further down holds it
                    // fully open, and an upward swipe closes it — never a reset-and-reopen.
                    touchMode = MODE_SHADE;
                    shadeStart = frontSurface.getShadeProgress();
                } else if (dragEnabled && !dialogOpen) {
                    if (frontSurface.isOnPowerButton(downX, downY)) {
                        touchMode = MODE_POWER;
                        pressedButton = 3;
                        hapticKey();
                        postDelayed(powerHoldRunnable, 550);
                    } else if ((volumeDirection = frontSurface.volumeButtonAt(downX, downY)) != 0) {
                        touchMode = MODE_VOLUME;
                        pressedButton = volumeDirection > 0 ? 1 : 2;
                        hapticKey();
                    } else if (frontSurface.isInStatusBar(downX, downY)) {
                        touchMode = MODE_SHADE;
                        shadeStart = frontSurface.getShadeProgress();
                    } else if (frontSurface.isLocked()) {
                        touchMode = MODE_UNLOCK;
                    }
                    if (pressedButton > 0 && phoneGL != null) {
                        phoneGL.setPressedButton(pressedButton);
                    }
                }
                if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
                else velocityTracker.clear();
                velocityTracker.addMovement(event);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (velocityTracker != null) velocityTracker.addMovement(event);
                float dx = (event.getRawX() - wrapperScreenX) - downX;
                float dy = (event.getRawY() - wrapperScreenY) - downY;
                lastDy = dy;
                float adx = Math.abs(dx);
                float ady = Math.abs(dy);
                if (adx > touchSlop || ady > touchSlop) moved = true;
                if (adx > touchSlop * 2f || ady > touchSlop * 2f) movedFar = true;
                if (touchMode == MODE_POWER || touchMode == MODE_VOLUME) return true;
                float phoneH = Math.max(1f, phoneWrapper.getHeight());
                // The first clear direction wins; afterwards the other axis is ignored.
                if (!directionDecided) {
                    if (adx <= touchSlop && ady <= touchSlop) return true;
                    directionDecided = true;
                    if (adx > ady * 1.15f) {
                        touchMode = MODE_ROTATE;
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    }
                }
                if (touchMode == MODE_ROTATE) {
                    if (dragEnabled) {
                        float sensitivity = 180f / Math.max(1f, phoneWrapper.getWidth());
                        applyRotation(resist(startRotation + dx * sensitivity));
                    }
                    return true;
                }
                if (touchMode == MODE_SHADE) {
                    // Downward drag opens the shade; upward closes it.
                    frontSurface.setShadeProgress(shadeStart + dy / (phoneH * 0.55f));
                    return true;
                }
                if (touchMode == MODE_UNLOCK) {
                    frontSurface.setUnlockProgress(-dy / (phoneH * 0.55f));
                    return true;
                }
                // Like a real phone: a downward drag anywhere on the home opens the shade.
                if (touchMode == MODE_NONE && !blockShadeGesture && dy > 0 && directionDecided && ady > adx) {
                    touchMode = MODE_SHADE;
                    shadeStart = 0f;
                    frontSurface.setShadeProgress(dy / (phoneH * 0.55f));
                    return true;
                }
                // Vertical on the home: inside the phone, so the page stays put.
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                switch (touchMode) {
                    case MODE_POWER:
                        removeCallbacks(powerHoldRunnable);
                        // Short press locks; a long press opens the power menu instead.
                        if (!powerMenuShown && !frontSurface.isLocked()) frontSurface.animateUnlock(0f);
                        break;
                    case MODE_VOLUME:
                        adjustDeviceVolume(volumeDirection);
                        break;
                    case MODE_SHADE:
                        if (!movedFar && event.getActionMasked() == MotionEvent.ACTION_UP) {
                            // A tap while the shade is open: buttons, notification, or dismiss.
                            handleTap(downX, downY);
                        } else {
                            float shadeH = Math.max(1f, phoneWrapper.getHeight());
                            hapticTap();
                            if (lastDy < -shadeH * 0.06f) {
                                // Any clear upward swipe closes, like the real shade.
                                frontSurface.animateShade(0f);
                            } else {
                                frontSurface.animateShade(frontSurface.getShadeProgress() > 0.55f ? 1f : 0f);
                            }
                        }
                        break;
                    case MODE_UNLOCK:
                        if (frontSurface.getUnlockProgress() > 0.35f) hapticTap();
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
                        // Forgiving taps: small wobbles still count, real swipes do not.
                        if (event.getActionMasked() == MotionEvent.ACTION_UP && !movedFar) {
                            handleTap(downX, downY);
                        }
                }
                if (pressedButton > 0 && phoneGL != null) {
                    phoneGL.setPressedButton(0);
                    pressedButton = 0;
                }
                touchMode = MODE_NONE;
                dragging = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            }
        }
        return true;
    }

    /** Long-press on the power button: ask to restart, like a real device. */
    private void onPowerHeld() {
        if (touchMode != MODE_POWER || movedFar) return;
        powerMenuShown = true;
        hapticLong();
        frontSurface.touchRipple(0f, 0f);
        frontSurface.showPowerMenu(true);
    }

    /** Taps inside the phone: nav bar, recents, previews, calculator keys, app icons. */
    private void handleTap(float x, float y) {
        // In-screen interactions only apply while the phone faces the viewer and has booted.
        if (Math.abs(rotation) > 35f) return;
        if (frontSurface.getBootProgress() < 0.95f) return;
        if (frontSurface.isLocked()) return;
        if (frontSurface.isPowerMenuOpen()) {
            int action = frontSurface.powerMenuActionAt(x, y);
            if (action == 1) {
                hapticKey();
                frontSurface.touchRipple(x, y);
                frontSurface.startRestart();
            } else if (action == 2) {
                hapticTap();
                frontSurface.touchRipple(x, y);
                frontSurface.showPowerMenu(false);
            } else {
                frontSurface.showPowerMenu(false);
            }
            return;
        }
        if (frontSurface.isAccessDialogOpen()) {
            if (frontSurface.accessDialogOkAt(x, y)) {
                hapticTap();
                frontSurface.touchRipple(x, y);
                frontSurface.showAccessDialog(false);
            }
            return;
        }
        if (frontSurface.getShadeProgress() > 0.4f) {
            if (frontSurface.notifPowerAt(x, y)) {
                // Never restart on a single touch: ask first, exactly like the long-press menu.
                hapticKey();
                frontSurface.touchRipple(x, y);
                frontSurface.animateShade(0f);
                frontSurface.showPowerMenu(true);
                return;
            }
            int notif = frontSurface.notificationIconAt(x, y);
            if (notif > 0 || frontSurface.notifCardTappedAt(x, y)) {
                hapticKey();
                frontSurface.touchRipple(x, y);
                frontSurface.showAccessDialog(true);
                return;
            }
            // Tapping the panel keeps it open; only a tap on the dimmed home collapses it.
            if (!frontSurface.shadePanelAt(x, y)) {
                frontSurface.animateShade(0f);
            }
            return;
        }
        if (frontSurface.getShadeProgress() > 0.01f) {
            // A half-open shade left by a small pull snaps shut instead of sticking.
            frontSurface.animateShade(0f);
            return;
        }
        if (frontSurface.getShadeProgress() < 0.05f) {
            int nav = frontSurface.navButtonAt(x, y);
            if (nav > 0) {
                hapticKey();
                frontSurface.touchRipple(x, y);
                if (nav == 1) {
                    if (frontSurface.isRecentsOpen()) frontSurface.showRecents(false);
                    else if (frontSurface.previewVisible()) closeAppPreview();
                    else frontSurface.animateShade(0f);
                } else if (nav == 2) {
                    closeAppPreview();
                    frontSurface.showRecents(false);
                    frontSurface.animateShade(0f);
                } else {
                    if (frontSurface.isRecentsOpen()) {
                        frontSurface.showRecents(false);
                    } else {
                        closeAppPreview();
                        frontSurface.showRecents(true);
                    }
                }
                return;
            }
        }
        if (frontSurface.isRecentsOpen()) {
            hapticKey();
            frontSurface.touchRipple(x, y);
            if (frontSurface.recentsCardAt(x, y)) {
                int app = frontSurface.getLastPreviewApp();
                frontSurface.showRecents(false);
                if (app > 0) openAppPreview(app);
            } else {
                frontSurface.showRecents(false);
            }
            return;
        }
        if (frontSurface.previewVisible()) {
            frontSurface.touchRipple(x, y);
            if (frontSurface.closePreviewAt(x, y)) {
                hapticTap();
                closeAppPreview();
            } else {
                int key = frontSurface.calculatorKeyAt(x, y);
                if (key >= 0) {
                    hapticKey();
                    frontSurface.calculatorPress(key);
                }
            }
            return;
        }
        int app = frontSurface.appIconAt(x, y);
        if (app > 0) {
            hapticKey();
            frontSurface.touchRipple(x, y);
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

    // ------------------------------------------------------------- haptics

    private void hapticKey() {
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private void hapticTap() {
        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
    }

    private void hapticLong() {
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
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
        int stream = android.media.AudioManager.STREAM_MUSIC;
        int max = Math.max(1, am.getStreamMaxVolume(stream));
        int before = am.getStreamVolume(stream);
        int raise = direction > 0 ? android.media.AudioManager.ADJUST_RAISE
                : android.media.AudioManager.ADJUST_LOWER;
        // Two steps per press: one step is barely visible in a tiny HUD.
        am.adjustStreamVolume(stream, raise, 0);
        am.adjustStreamVolume(stream, raise, 0);
        int now = am.getStreamVolume(stream);
        int intended = Math.max(0, Math.min(max, before + direction * 2));
        // Some OEM volume services lag behind the call: show the intended value immediately,
        // then correct to the real read-back once it settles.
        frontSurface.showVolumeHud((now != before ? now : intended) / (float) max);
        postDelayed(() -> frontSurface.showVolumeHud(
                am.getStreamVolume(stream) / (float) max), 160);
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
        backVisible = angle > 90f || angle < -90f;
        if (phoneGL != null) phoneGL.setAngle(angle);
    }

    // ------------------------------------------------------------ live status

    private final Runnable textureTask = this::updateTextures;

    private void markFrontDirty() {
        if (frontDirty) return;
        frontDirty = true;
        post(textureTask);
    }

    private void markBackDirty() {
        if (backDirty) return;
        backDirty = true;
        post(textureTask);
    }

    /**
     * Faces are rendered at {@code TEXTURE_SCALE}× the layout slot. The GL phone displays
     * the texture at roughly 1:1 slot pixels, so the supersample is what keeps small
     * details (status-bar strokes, icon labels) crisp on low-density phones
     * instead of blurring through bilinear magnification.
     */
    private static final float TEXTURE_SCALE = 1.5f;

    /** Renders the two faces offscreen and hands them to the GL thread (one pass per frame). */
    private void updateTextures() {
        if (phoneGL == null || phoneWrapper == null) return;
        int w = phoneWrapper.getWidth();
        int h = phoneWrapper.getHeight();
        if (w <= 0 || h <= 0) return;
        int tw = Math.max(1, Math.round(w * TEXTURE_SCALE));
        int th = Math.max(1, Math.round(h * TEXTURE_SCALE));
        Object lock = phoneGL.bitmapLock();
        synchronized (lock) {
            if (frontDirty) {
                frontDirty = false;
                screenBitmap = renderSurface(frontSurface, screenBitmap, tw, th);
                phoneGL.setScreenBitmap(screenBitmap);
            }
            if (frameDirty) {
                frameDirty = false;
                RectF sr = frontSurface.getScreenRect();
                if (sr.width() > 0) {
                    phoneGL.setScreenRect(sr.left / tw, sr.top / th, sr.right / tw, sr.bottom / th);
                }
            }
            if (backDirty) {
                backDirty = false;
                backBitmap = renderSurface(backSurface, backBitmap, tw, th);
                phoneGL.setBackBitmap(backBitmap);
            }
        }
    }

    private Bitmap renderSurface(View view, Bitmap reuse, int w, int h) {
        if (reuse == null || reuse.getWidth() != w || reuse.getHeight() != h) {
            reuse = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        } else {
            reuse.eraseColor(0);
        }
        view.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        view.layout(0, 0, w, h);
        view.draw(new Canvas(reuse));
        return reuse;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        wirePhoneParts();
    }

    /** Keeps the GL textures in sync whenever the phone's measured size changes. */
    private void wirePhoneParts() {
        if (phoneWrapper == null || phoneWrapper.getWidth() <= 0) return;
        frontDirty = true;
        backDirty = true;
        post(textureTask);
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
        // Every visit boots inside the mock-up: fly in first, then power on, so the user
        // actually sees the emblem, the "Starting" line and the loader before the home icons.
        if (bootEnabled) {
            // Power the screen off synchronously: no stale home may flash during the fly-in.
            frontSurface.prepareBoot();
            post(this::startIntroThenBoot);
        } else {
            frontSurface.settleBoot();
        }
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
        View root = getChildAt(0);
        if (root instanceof LinearLayout) {
            LinearLayout ll = (LinearLayout) root;
            if (ll.getChildCount() > 0 && ll.getChildAt(0) instanceof LinearLayout) {
                LinearLayout row = (LinearLayout) ll.getChildAt(0);
                // Hide only the text column — never the row that also holds the phone.
                if (row.getChildCount() > 0) {
                    row.getChildAt(0).setVisibility(phoneOnly ? GONE : VISIBLE);
                }
                row.setGravity(phoneOnly ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL);
            }
        }
        if (phoneOnly) {
            frontSurface.setCropFade(false);
            setPadding(0, 0, 0, 0);
            if (root instanceof LinearLayout) {
                ((LinearLayout) root).setBackground(null);
            }
            if (phoneGL != null) {
                phoneGL.setCrop(1f, 0f);
            }
        }
        frontDirty = true;
        backDirty = true;
        post(textureTask);
    }

    /** Explicit phone size (safe: fixed dimensions, no wrap-content measurement traps). */
    public void setPhoneSize(float widthDp, boolean fullHeight) {
        ViewGroup.LayoutParams lp = phoneWrapper.getLayoutParams();
        int w = Math.round(widthDp * getResources().getDisplayMetrics().density);
        int h = Math.round(w * (fullHeight ? 2.05f : 0.55f * 2.05f));
        lp.width = w;
        lp.height = h;
        phoneWrapper.setLayoutParams(lp);
        if (phoneGL != null) {
            LayoutParams glp = (LayoutParams) phoneGL.getLayoutParams();
            glp.width = Math.round(w * 1.5f);
            glp.height = Math.round(h * 1.5f);
            glp.gravity = Gravity.CENTER;
            phoneGL.setLayoutParams(glp);
        }
        frontDirty = true;
        backDirty = true;
        frameDirty = true;
        post(textureTask);
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

    /** Entrance, then the boot theater — with a beat in between so the settle is noticed. */
    private void startIntroThenBoot() {
        if (!bootEnabled) {
            frontSurface.settleBoot();
            return;
        }
        Runnable bootTask = () -> postDelayed(() -> {
            if (isAttachedToWindow()) frontSurface.startBoot();
        }, 320);
        if (!motionAllowed() || introPlayed || getWidth() == 0) {
            introPlayed = true;
            bootTask.run();
            return;
        }
        startIntro(bootTask);
    }

    /** The showcase entrance: the phone arrives big and spun, then settles into the card. */
    private void startIntro(Runnable onEnd) {
        if (!motionAllowed() || introPlayed || getWidth() == 0) {
            introPlayed = true;
            if (onEnd != null) onEnd.run();
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
                if (onEnd != null) onEnd.run();
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
        expandAnimator.addUpdateListener(a -> applyRotation((float) a.getAnimatedValue()));
        expandAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                expandAnimator = null;
                applyRotation(0f);
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
            long millis = Math.max(0L, System.currentTimeMillis() - info.firstInstallTime);
            frontSurface.setUsageText(getContext().getString(R.string.pro_showcase_usage, formatMillis(millis)));
        } catch (Exception ignored) {
            // No install time available: leave the line out.
        }
    }

    private static String formatMillis(long millis) {
        long minutes = millis / 60000L;
        long hours = minutes / 60L;
        long days = hours / 24L;
        long years = days / 365L;
        long months = (days % 365L) / 30L;
        long weeks = ((days % 365L) % 30L) / 7L;
        long remDays = ((days % 365L) % 30L) % 7L;
        long remHours = hours % 24L;
        long remMinutes = minutes % 60L;
        StringBuilder sb = new StringBuilder();
        appendUnit(sb, years, "year");
        appendUnit(sb, months, "month");
        appendUnit(sb, weeks, "week");
        appendUnit(sb, remDays, "day");
        appendUnit(sb, remHours, "hour");
        appendUnit(sb, remMinutes, "minute");
        if (sb.length() == 0) sb.append("under a minute");
        return sb.toString();
    }

    private static void appendUnit(StringBuilder sb, long value, String unit) {
        if (value <= 0) return;
        if (sb.length() > 0) sb.append(", ");
        int existingUnits = sb.toString().split(",").length;
        if (existingUnits >= 2) return;
        sb.append(value).append(' ').append(unit).append(value == 1 ? "" : "s");
    }

    // -------------------------------------------------------------- helpers

    private boolean motionAllowed() {
        return android.provider.Settings.Global.getFloat(getContext().getContentResolver(),
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
    }

}
