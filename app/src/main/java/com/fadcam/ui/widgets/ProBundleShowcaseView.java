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

import com.fadcam.FLog;
import com.fadcam.R;

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
    /** The app's own settings store: the mockup's note rides along with backup/export. */
    private final com.fadcam.SharedPreferencesManager appPrefs;
    private final int touchSlop;
    /** Tuned text:phone ratio of the card's row, read from the layout. */
    private final float phoneWeight;

    private boolean dragEnabled = true;
    private boolean bootEnabled = true;
    private boolean liveClockEnabled = true;
    private boolean realBatteryEnabled = true;
    private boolean compactMode = false;
    private boolean phoneOnlyMode = false;
    private float rotation = 0f;
    private boolean backVisible = false;
    private float downX, downY, startRotation;
    private float lastDy = 0f;
    private boolean dragging;
    private int touchMode = MODE_NONE;
    private float shadeStart = 0f;
    private float noteDragFrom;
    private long lastNoteTapAt;
    private float lastNoteTapX, lastNoteTapY;
    private boolean noteSelectAllFired;
    private int volumeDirection = 0;
    private boolean moved = false;
    private boolean directionDecided = false;
    private boolean movedFar = false;
    private boolean blockShadeGesture = false;
    private final Runnable powerHoldRunnable = this::onPowerHeld;
    /** Long-press inside the mini Notes editor selects the whole note, like a real text field. */
    private final Runnable noteSelectAllRunnable = this::selectAllNote;
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
    private int uvTw, uvTh;
    private boolean frameDirty = true;
    private boolean frontDirty = true;
    private boolean backDirty = true;
    /** The turn reflection comes from a fixed side light. At rest the glass is AMOLED
     *  black; dragging fades the reflection in and releasing fades it out smoothly. */
    private static final float REST_SWEEP_X = -0.12f;
    private static final float DRAG_SWEEP_I = 0.30f;

    private FrameLayout cinematicHost;
    private PhoneGLView cinematicGL;
    private ValueAnimator cinematicAnimator;
    private boolean cinematicRunning = false;
    private boolean cinematicLaunched = false;
    private long cinematicShownAt;
    private final Runnable cinematicRunner = this::runCinematic;
    private Bitmap cinematicBackBitmap;
    private int entranceTries = 0;
    private int cinematicTries = 0;
    private boolean cineHaptic1;
    private boolean cineHaptic2;

    private boolean fullscreenActive;
    private FullscreenListener fullscreenListener;

    /** Notified when the in-place fullscreen inspector opens / closes. */
    public interface FullscreenListener {
        void onFullscreenActive(boolean active);
    }

    public void setFullscreenListener(FullscreenListener listener) {
        fullscreenListener = listener;
    }

    public boolean isFullscreenActive() {
        return fullscreenActive;
    }

    private android.hardware.camera2.CameraManager cameraManager;
    private String torchCameraId;
    private boolean torchOn;
    private boolean torchAvailable;
    private boolean torchCallbackRegistered;
    private final android.hardware.camera2.CameraManager.TorchCallback torchCallback =
            new android.hardware.camera2.CameraManager.TorchCallback() {
        @Override
        public void onTorchModeChanged(String cameraId, boolean enabled) {
            if (cameraId != null && cameraId.equals(torchCameraId)) {
                torchOn = enabled;
                torchAvailable = true;
                frontSurface.setTorchState(true, enabled);
                // The real LED is physically on: the mockup's camera flash lights too.
                if (phoneGL != null) phoneGL.setFlashGlow(enabled ? 1f : 0f);
                FLog.d(CINE_TAG, "torch state cam=" + cameraId + " on=" + enabled);
            }
        }

        @Override
        public void onTorchModeUnavailable(String cameraId) {
            if (cameraId != null && cameraId.equals(torchCameraId)) {
                torchAvailable = false;
                frontSurface.setTorchState(false, false);
                if (phoneGL != null) phoneGL.setFlashGlow(0f);
                FLog.d(CINE_TAG, "torch unavailable cam=" + cameraId);
            }
        }
    };
    private ValueAnimator inspectorSpin;

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
        appPrefs = com.fadcam.SharedPreferencesManager.getInstance(context);
        labelView = findViewById(R.id.showcaseLabel);
        titleView = findViewById(R.id.showcaseTitle);
        subtitleView = findViewById(R.id.showcaseSubtitle);
        metaView = findViewById(R.id.showcaseMeta);
        phoneWrapper = findViewById(R.id.showcasePhoneWrapper);
        phoneContainer = findViewById(R.id.showcasePhoneContainer);
        // The layout's weight is the tuned text:phone ratio; only the ratio is kept, because the
        // host rewrites the weight from the card's actual width (see applyPhoneSlotWidth).
        ViewGroup.LayoutParams slotParams = phoneWrapper.getLayoutParams();
        phoneWeight = slotParams instanceof LinearLayout.LayoutParams
                ? ((LinearLayout.LayoutParams) slotParams).weight : 0.72f;

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
        // TextureView rejects a themed background during its constructor. Dialog themes can
        // carry one, so give the GL surface a bare theme; it only needs resources/density.
        android.content.Context glContext = new android.view.ContextThemeWrapper(
                context, android.R.style.Theme_Material_NoActionBar);
        phoneGL = new PhoneGLView(glContext, null);
        phoneGL.setOverscan(glOverscan);
        phoneGL.setCrop(1f, 0f);
        phoneGL.setSweep(0f, REST_SWEEP_X);
        // Real size comes from the slot on every layout pass (see wirePhoneParts): the slot is
        // fluid, so a fixed dp layout param here would render the phone at the wrong scale.
        phoneContainer.addView(phoneGL, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));
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
                float scale = v.getScaleX() > 0f ? v.getScaleX() : 1f;
                float lx = (event.getRawX() - wrapperScreenX) / scale;
                float ly = (event.getRawY() - wrapperScreenY) / scale;
                float margin = 24f * getResources().getDisplayMetrics().density;
                touchOnPhone = lx >= -margin && lx <= phoneWrapper.getWidth() + margin
                        && ly >= -margin && ly <= phoneWrapper.getHeight() + margin;
            }
            if (!touchOnPhone) return false;
            return handleTouch(event);
        });
        applyRotation(0f);

        // Hidden input that lets the Notes preview be typed on with the real keyboard.
        noteInput = new EditText(context) {
            @Override
            protected void onSelectionChanged(int selStart, int selEnd) {
                super.onSelectionChanged(selStart, selEnd);
                if (frontSurface != null) frontSurface.setNoteSelection(selStart, selEnd);
            }
        };
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
                String text = e.toString();
                frontSurface.setNote(text);
                frontSurface.setNoteSelection(noteInput.getSelectionStart(), noteInput.getSelectionEnd());
                // The preview is a real notes app: the draft outlives the screen and the process.
                appPrefs.setProShowcaseNote(text);
            }
        });
        addView(noteInput);
        noteInput.setText(appPrefs.getProShowcaseNote());
        expandButton = findViewById(R.id.showcaseExpand);
        if (expandButton != null) {
            expandButton.setOnClickListener(v -> enterFullscreen());
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

    public void setSubtitleVisible(boolean visible) {
        subtitleView.setVisibility(visible ? VISIBLE : GONE);
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

    /** Tighter variant: hides the metadata line. The card remains the same object. */
    public void setCompactMode(boolean compact) {
        compactMode = compact;
        if (metaView != null) {
            metaView.setVisibility(compact ? GONE : VISIBLE);
        }
    }

    /**
     * Dialog variant: keep only the label, headline, supporting line and the phone.
     * The feature chips, divider and the fullscreen/footer row are removed so the card can
     * live inside a weekly prompt without turning the dialog into a second Pro screen.
     */
    public void setDialogMode(boolean dialog) {
        View features = findViewById(R.id.showcaseFeatures);
        View spacer = findViewById(R.id.showcaseSpacer);
        View divider = findViewById(R.id.showcaseDivider);
        View footer = findViewById(R.id.showcaseFooter);
        if (features != null) features.setVisibility(dialog ? GONE : VISIBLE);
        if (spacer != null) spacer.setVisibility(dialog ? GONE : VISIBLE);
        if (divider != null) divider.setVisibility(dialog ? GONE : VISIBLE);
        if (footer != null) footer.setVisibility(dialog ? GONE : VISIBLE);
        if (titleView != null) {
            // Big fonts must wrap, never shrink. Three lines is right for the Pro card; a
            // dialog has less width per line, so allow the headline to breathe.
            titleView.setMaxLines(dialog ? 6 : 3);
        }
        View textColumn = findViewById(R.id.showcaseTextColumn);
        if (textColumn instanceof LinearLayout) {
            ((LinearLayout) textColumn).setGravity(dialog ? Gravity.CENTER_VERTICAL : Gravity.TOP);
        }
        if (dialog) {
            if (metaView != null) metaView.setVisibility(GONE);
        }
    }

    /** Enable/disable the boot splash. Call before the view is attached. */
    public void setSplashEnabled(boolean enabled) {
        bootEnabled = enabled;
    }

    /**
     * Dossier/dialog variant: keep only the phone, centred at a fixed width, with the card
     * plate removed so a host can draw its own paper/frame around it. The boot splash and
     * drag interaction stay live.
     */
    public void setPhoneOnlyMode(boolean enabled, int widthDp) {
        phoneOnlyMode = enabled;
        View textColumn = findViewById(R.id.showcaseTextColumn);
        View root = findViewById(R.id.showcaseRoot);
        View row = findViewById(R.id.showcaseRow);
        if (textColumn != null) textColumn.setVisibility(enabled ? GONE : VISIBLE);
        if (root != null) {
            if (enabled) {
                root.setBackground(null);
                root.setPadding(0, 0, 0, 0);
            } else {
                root.setBackgroundResource(R.drawable.bg_pro_showcase);
                int pad = getResources().getDimensionPixelSize(R.dimen.pro_showcase_pad);
                root.setPadding(pad, pad, pad, pad);
            }
        }
        if (row instanceof LinearLayout) {
            ((LinearLayout) row).setGravity(enabled ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
        }
        if (phoneWrapper != null && phoneWrapper.getLayoutParams() instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams lp =
                    (LinearLayout.LayoutParams) phoneWrapper.getLayoutParams();
            if (enabled) {
                lp.width = Math.max(1, Math.round(widthDp
                        * getResources().getDisplayMetrics().density));
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                lp.weight = 0f;
                lp.gravity = Gravity.CENTER_HORIZONTAL;
                lp.setMarginStart(0);
            } else {
                lp.width = 0;
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                lp.weight = phoneWeight;
                lp.gravity = Gravity.NO_GRAVITY;
                lp.setMarginStart(getResources().getDimensionPixelSize(R.dimen.pro_showcase_gap));
            }
            phoneWrapper.setLayoutParams(lp);
        }
        lastPhoneSlot = -1;
        requestLayout();
    }

    public void setOnShowcaseClickListener(OnClickListener listener) {
        showcaseClickListener = listener;
    }

    /**
     * Fullscreen host for the entrance cinematic (a match-parent overlay at the top of the
     * activity window). Without one the showcase simply boots the phone in its slot.
     */
    public void setCinematicHost(FrameLayout host) {
        cinematicHost = host;
    }

    /** Notified when the cinematic takes over / releases the screen (host hides chrome). */
    public interface CinematicListener {
        void onCinematicActive(boolean active);
    }

    private CinematicListener cinematicListener;

    public void setCinematicListener(CinematicListener listener) {
        cinematicListener = listener;
    }

    // ------------------------------------------------------------------ sizing


    // -------------------------------------------------------------- 3D drag

    private boolean touchCaptured;

    private static final int MODE_NONE = 0;
    private static final int MODE_ROTATE = 1;
    private static final int MODE_SHADE = 2;
    private static final int MODE_UNLOCK = 3;
    private static final int MODE_POWER = 4;
    private static final int MODE_VOLUME = 5;
    private static final int MODE_NOTE = 6;

    private boolean handleTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (snapAnimator != null) snapAnimator.cancel();
                if (cinematicRunning) {
                    touchCaptured = true;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                if (inspectorSpin != null) {
                    // A touch takes over: stop the flight spin and settle on the card's face.
                    inspectorSpin.cancel();
                    inspectorSpin = null;
                    if (phoneGL != null) phoneGL.setAngle(rotation);
                }
                float scaleNow = getScaleX() > 0f ? getScaleX() : 1f;
                downX = (event.getRawX() - wrapperScreenX) / scaleNow;
                downY = (event.getRawY() - wrapperScreenY) / scaleNow;
                float foldedAngle = Math.abs(rotation) % 360f;
                if (foldedAngle > 180f) foldedAngle = 360f - foldedAngle;
                FLog.d("ProTouch", "DOWN raw=" + (int) event.getRawX() + "," + (int) event.getRawY()
                        + " wrapper=" + (int) wrapperScreenX + "," + (int) wrapperScreenY
                        + " scale=" + getScaleX() + " local=" + (int) downX + "," + (int) downY
                        + " tex=" + (int) (downX * textureScale) + "," + (int) (downY * textureScale)
                        + " locked=" + frontSurface.isLocked()
                        + " unlockZone=" + frontSurface.isInUnlockZone(downX * textureScale, downY * textureScale)
                        + " statusBar=" + frontSurface.isInStatusBar(downX * textureScale, downY * textureScale)
                        + " inspector=" + fullscreenActive);
                startRotation = rotation;
                dragging = false;
                moved = false;
                volumeDirection = 0;
                touchMode = MODE_NONE;
                directionDecided = false;
                movedFar = false;
                touchCaptured = false;
                powerMenuShown = false;
                lastDy = 0f;
                boolean shadeOpen = frontSurface.getShadeProgress() > 0.4f;
                boolean dialogOpen = frontSurface.isAccessDialogOpen() || frontSurface.isPowerMenuOpen();
                blockShadeGesture = dialogOpen;
                if (dragEnabled && shadeOpen && foldedAngle <= 90f) {
                    // The open shade owns the vertical gesture: pulling further down holds it
                    // fully open, and an upward swipe closes it — never a reset-and-reopen.
                    touchMode = MODE_SHADE;
                    shadeStart = frontSurface.getShadeProgress();
                } else if (dragEnabled && !dialogOpen) {
                    // The face surfaces live at textureScale× the wrapper, so their hit
                    // zones are in texture space: convert before every hit test.
                    float rawTx = downX * textureScale;
                    float rawTy = downY * textureScale;
                    float tx = rawTx;
                    float ty = rawTy;
                    float folded = Math.abs(rotation) % 360f;
                    if (folded > 180f) folded = 360f - folded;
                    if (folded > 90f) {
                        // Facing away: the model mirrors x, so the side buttons swap sides.
                        tx = phoneWrapper.getWidth() * textureScale - tx;
                    }
                    noteDragFrom = frontSurface.getNoteScroll();
                    if (frontSurface.isOnPowerButton(tx, ty)) {
                        touchMode = MODE_POWER;
                        pressedButton = 3;
                        hapticKey();
                        postDelayed(powerHoldRunnable, 550);
                    } else if ((volumeDirection = frontSurface.volumeButtonAt(tx, ty)) != 0) {
                        touchMode = MODE_VOLUME;
                        pressedButton = volumeDirection > 0 ? 1 : 2;
                        hapticKey();
                    } else if (foldedAngle <= 90f && frontSurface.isInStatusBar(rawTx, rawTy)) {
                        touchMode = MODE_SHADE;
                        shadeStart = frontSurface.getShadeProgress();
                    } else if (foldedAngle <= 90f && frontSurface.isLocked()
                            && frontSurface.isInUnlockZone(rawTx, rawTy)) {
                        touchMode = MODE_UNLOCK;
                    }
                    if (pressedButton > 0 && phoneGL != null) {
                        phoneGL.setPressedButton(pressedButton);
                    }
                }
                if (frontSurface.isInteractiveOverlayOpen() || touchMode != MODE_NONE
                        || touchOnPhone || (foldedAngle > 4f && foldedAngle < 176f)) {
                    touchCaptured = true;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (frontSurface.getPreviewApp() == 1) postDelayed(noteSelectAllRunnable, 500);
                if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
                else velocityTracker.clear();
                velocityTracker.addMovement(event);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float foldedNow = Math.abs(rotation) % 360f;
                if (foldedNow > 180f) foldedNow = 360f - foldedNow;
                if (velocityTracker != null) velocityTracker.addMovement(event);
                float moveScale = getScaleX() > 0f ? getScaleX() : 1f;
                float dx = (event.getRawX() - wrapperScreenX) / moveScale - downX;
                float dy = (event.getRawY() - wrapperScreenY) / moveScale - downY;
                lastDy = dy;
                float adx = Math.abs(dx);
                float ady = Math.abs(dy);
                float slopNow = touchSlop * (getScaleX() > 0f ? getScaleX() : 1f);
                if (adx > slopNow || ady > slopNow) {
                    moved = true;
                    removeCallbacks(noteSelectAllRunnable);
                }
                if (adx > slopNow * 2f || ady > slopNow * 2f) movedFar = true;
                if (touchMode == MODE_POWER || touchMode == MODE_VOLUME) return true;
                float phoneH = Math.max(1f, phoneWrapper.getHeight());
                // The first clear direction wins; afterwards the other axis is ignored.
                if (!directionDecided) {
                    if (adx <= slopNow && ady <= slopNow) return true;
                    directionDecided = true;
                    if (adx > ady * 1.15f) {
                        touchMode = MODE_ROTATE;
                        touchCaptured = true;
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    } else if (ady > adx * 1.15f && frontSurface.previewVisible()
                            && touchMode == MODE_NONE) {
                        // An open app owns vertical drags: the note scrolls, the page stays put.
                        touchMode = MODE_NOTE;
                        touchCaptured = true;
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    } else if (ady > adx * 1.15f && dy > 0f && touchMode == MODE_NONE
                            && !frontSurface.isLocked() && foldedNow <= 90f) {
                        // Downward drag anywhere on the phone pulls the shade, like a real device.
                        touchMode = MODE_SHADE;
                        touchCaptured = true;
                        shadeStart = frontSurface.getShadeProgress();
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    } else if (ady > adx * 1.15f && touchMode == MODE_NONE) {
                        // Upward drag on the idle phone: hand the gesture back so the page scrolls.
                        touchCaptured = false;
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    }
                }
                FLog.d("ProTouch", "MODE=" + touchMode + " dx=" + (int) dx + " dy=" + (int) dy
                        + " slop=" + (int) touchSlop + " captured=" + touchCaptured);
                if (touchMode == MODE_ROTATE) {
                    if (dragEnabled) {
                        float sensitivity = 180f / Math.max(1f, phoneWrapper.getWidth());
                        applyRotation(resist(startRotation + dx * sensitivity));
                        // Reflections appear ONLY while the device is actually turning:
                        // the strip light lives at the side, so it slides across the frame
                        // and glass as they pass it, then fades away at rest.
                        // (the reflection level is driven by the angle in applyRotation)
                    }
                    return true;
                }
                if (touchMode == MODE_NOTE) {
                    if (frontSurface.getPreviewApp() == 1) {
                        frontSurface.scrollNoteTo(noteDragFrom - dy);
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
                return touchCaptured;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                removeCallbacks(noteSelectAllRunnable);
                FLog.d("ProTouch", "UP mode=" + touchMode + " movedFar=" + movedFar
                        + " dy=" + (int) lastDy);
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
                        if (!moved && event.getActionMasked() == MotionEvent.ACTION_UP) {
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
                        FLog.d("ProTouch", "rotate-release rotation=" + rotation);
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
                touchCaptured = false;
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
        // The face surfaces are rendered at textureScale× the wrapper: all their hit zones
        // are in texture space, so convert once here (wrapper space -> texture space).
        x *= textureScale;
        y *= textureScale;
        // In-screen interactions only apply while the phone faces the viewer and has booted.
        if (Math.abs(rotation) > 35f) return;
        if (frontSurface.getBootProgress() < 0.95f) return;
        // The open shade owns the tap: its quick actions (torch, restart, notifications)
        // must work on the lock screen too, so this runs before the locked gate below.
        if (frontSurface.getShadeProgress() > 0.4f) {
            if (frontSurface.torchButtonAt(x, y)) {
                hapticKey();
                frontSurface.touchRipple(x, y);
                toggleRealTorch();
                return;
            }
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
            if (frontSurface.closePreviewAt(x, y)) {
                hapticTap();
                closeAppPreview();
            } else {
                int key = frontSurface.calculatorKeyAt(x, y);
                if (key >= 0) {
                    hapticKey();
                    frontSurface.calculatorPress(key);
                } else if (frontSurface.getPreviewApp() == 1 && noteInput != null) {
                    if (noteSelectAllFired) {
                        // This is the release of the long press that just selected the note.
                        noteSelectAllFired = false;
                        return;
                    }
                    long now = android.os.SystemClock.uptimeMillis();
                    float slop = phoneWrapper.getWidth() * 0.12f;
                    boolean doubleTap = now - lastNoteTapAt < 320L
                            && Math.abs(x - lastNoteTapX) < slop && Math.abs(y - lastNoteTapY) < slop;
                    lastNoteTapAt = now;
                    lastNoteTapX = x;
                    lastNoteTapY = y;
                    hapticTap();
                    noteInput.requestFocus();
                    int offset = frontSurface.noteOffsetAt(x, y);
                    if (doubleTap) {
                        selectWordAt(offset);
                    } else {
                        noteInput.setSelection(offset);
                    }
                    InputMethodManager imm = (InputMethodManager)
                            getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(noteInput, InputMethodManager.SHOW_IMPLICIT);
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
                frontSurface.pulseApp(app - 1);
                if (onFirstAppTap != null) onFirstAppTap.run();
            }
            openAppPreview(app);
            return;
        }
        if (showcaseClickListener != null) showcaseClickListener.onClick(this);
    }

    // ------------------------------------------------------------- haptics

    private void selectAllNote() {
        if (frontSurface == null || frontSurface.getPreviewApp() != 1 || noteInput == null) return;
        noteInput.selectAll();
        noteSelectAllFired = true;
        hapticTap();
    }

    /** Selects the word around a character, for the double-tap a text field normally handles. */
    private void selectWordAt(int offset) {
        CharSequence text = noteInput.getText();
        int start = Math.max(0, Math.min(offset, text.length()));
        int end = start;
        while (start > 0 && isWordChar(text.charAt(start - 1))) start--;
        while (end < text.length() && isWordChar(text.charAt(end))) end++;
        if (start == end) return;
        noteInput.setSelection(start, end);
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '\'';
    }

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
        if (phoneGL != null) {
            phoneGL.setAngle(angle);
            // The side-light reflection is a pure function of the angle: zero on either
            // resting face, strongest mid-turn. Holding still keeps it steady; settling
            // back to a face melts it away frame by frame — no sudden appearance.
            float a = Math.abs(angle);
            float level = DRAG_SWEEP_I * Math.min(1f, a / 70f)
                    * Math.min(1f, Math.max(0f, (180f - a) / 70f));
            phoneGL.setSweep(level, REST_SWEEP_X);
            if (backDirty && backNeeded()) post(textureTask);
        }
    }

        // ------------------------------------------------------------ live status

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        applyPhoneSlotWidth(widthMeasureSpec);
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    /**
     * The phone's slot takes a share of the card's content width and the text column takes the
     * rest. Sizing it in dp made the slot a constant 150dp of every screen (136dp of copy left
     * on a 360dp phone, where the row collapsed); sizing it purely by share would blow the
     * device up on a wide card (landscape, tablets), so the slot is capped at its tuned width.
     * The share is the row's weight from the layout; this resolves it into an actual width and
     * only rewrites the weight when the slot changes (writing layout params schedules a layout
     * pass of its own).
     */
    private void applyPhoneSlotWidth(int widthMeasureSpec) {
        if (phoneWrapper == null || phoneOnlyMode) return;
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.EXACTLY) return;
        ViewGroup.LayoutParams raw = phoneWrapper.getLayoutParams();
        if (!(raw instanceof LinearLayout.LayoutParams)) return;
        LinearLayout.LayoutParams phoneParams = (LinearLayout.LayoutParams) raw;
        int contentWidth = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
        float share = phoneWeight / (1f + phoneWeight);
        int slot = Math.min(Math.round(contentWidth * share),
                getResources().getDimensionPixelSize(R.dimen.pro_showcase_phone_max_w));
        int available = contentWidth - phoneParams.getMarginStart() - phoneParams.getMarginEnd();
        if (slot > 0 && slot != lastPhoneSlot) {
            lastPhoneSlot = slot;
            phoneParams.weight = (float) slot / Math.max(1, available - slot);
            phoneWrapper.setLayoutParams(phoneParams);
        }
    }

    private int lastPhoneSlot;

    private final Runnable textureTask = this::updateTextures;
    private long lastTextureTaskAt;

    private void markFrontDirty() {
        if (frontDirty) return;
        frontDirty = true;
        long now = android.os.SystemClock.uptimeMillis();
        long wait = 16L - (now - lastTextureTaskAt);
        if (wait <= 0) {
            lastTextureTaskAt = now;
            post(textureTask);
        } else {
            postDelayed(textureTask, wait);
        }
    }

    private void markBackDirty() {
        if (backDirty) return;
        backDirty = true;
        post(textureTask);
    }

    /**
     * Faces are rendered at {@code textureScale}× the layout slot. The GL phone displays
     * the texture at roughly 1:1 slot pixels, so the supersample is what keeps small
     * details (status-bar strokes, icon labels) crisp on low-density phones
     * instead of blurring through bilinear magnification.
     */
    private float textureScale = 1.5f;
    /**
     * Rotation headroom, as a multiple of the slot. The model's silhouette reaches ~1.05x the
     * slot at its widest instant of a turn (buttons included), so the viewport only needs a
     * little more than the slot itself.
     */
    private static final float glOverscan = 1.15f;

    /** Renders the two faces offscreen and hands them to the GL thread (one pass per frame). */
    private void updateTextures() {
        if (phoneGL == null || phoneWrapper == null) return;
        int w = phoneWrapper.getWidth();
        int h = phoneWrapper.getHeight();
        if (w <= 0 || h <= 0) return;
        int tw = Math.max(1, Math.round(w * textureScale));
        int th = Math.max(1, Math.round(h * textureScale));
        Object lock = phoneGL.bitmapLock();
        synchronized (lock) {
            if (frontDirty) {
                frontDirty = false;
                screenBitmap = renderSurface(frontSurface, screenBitmap, tw, th);
                phoneGL.setScreenBitmap(screenBitmap);
            }
            if (frameDirty || uvTw != tw || uvTh != th) {
                frameDirty = false;
                uvTw = tw;
                uvTh = th;
                RectF sr = frontSurface.getScreenRect();
                if (sr.width() > 0) {
                    phoneGL.setScreenRect(sr.left / tw, sr.top / th, sr.right / tw, sr.bottom / th);
                }
            }
            if (backDirty && backNeeded()) {
                backDirty = false;
                backBitmap = renderSurface(backSurface, backBitmap, tw, th);
                phoneGL.setBackBitmap(backBitmap);
            }
        }
    }

    private boolean backNeeded() {
        float a = Math.abs(rotation) % 360f;
        if (a > 180f) a = 360f - a;
        return a > 60f;
    }

    private Bitmap renderSurface(View view, Bitmap reuse, int w, int h) {
        if (reuse == null || reuse.getWidth() != w || reuse.getHeight() != h) {
            reuse = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        } else {
            reuse.eraseColor(0);
        }
        if (view.getWidth() != w || view.getHeight() != h) {
            view.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
            view.layout(0, 0, w, h);
        }
        view.draw(new Canvas(reuse));
        return reuse;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        wirePhoneParts();
    }

    /**
     * Keeps the GL viewport and the face textures in sync with the slot's measured size. The
     * viewport is the slot plus the rotation headroom, centred on it: the model's projected
     * silhouette grows by at most ~5% beyond the slot (measured over a full turn), so the
     * headroom stays tight instead of painting a 1.5x rectangle over the text column.
     */
    private void wirePhoneParts() {
        if (phoneWrapper == null || phoneWrapper.getWidth() <= 0 || phoneWrapper.getHeight() <= 0) return;
        int glW = Math.round(phoneWrapper.getWidth() * glOverscan);
        int glH = Math.round(phoneWrapper.getHeight() * glOverscan);
        ViewGroup.LayoutParams glp = phoneGL.getLayoutParams();
        if (glp.width != glW || glp.height != glH) {
            glp.width = glW;
            glp.height = glH;
            phoneGL.setLayoutParams(glp);
        }
        frontDirty = true;
        backDirty = true;
        frameDirty = true;
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
        setupTorchCallback();
        // Every visit powers the phone on: the fullscreen cinematic plays first (when a host
        // is available), then the boot theater inside the card ends on the lock screen.
        if (bootEnabled) {
            // Power the screen off synchronously: no stale home may flash during the intro.
            frontSurface.prepareBoot();
            post(this::startEntrance);
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
        cancelCinematic();
        teardownTorchCallback();
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
        super.onDetachedFromWindow();
    }

    /**
     * Real torch wiring: the back camera with flash is controlled through CameraManager,
     * and the button state comes from the hardware TorchCallback — never a guessed bool.
     */
    private void setupTorchCallback() {
        if (torchCallbackRegistered) return;
        try {
            cameraManager = (android.hardware.camera2.CameraManager)
                    getContext().getSystemService(Context.CAMERA_SERVICE);
            if (cameraManager == null) {
                frontSurface.setTorchState(false, false);
                return;
            }
            String backId = null;
            String anyId = null;
            for (String id : cameraManager.getCameraIdList()) {
                android.hardware.camera2.CameraCharacteristics chars =
                        cameraManager.getCameraCharacteristics(id);
                Boolean hasFlash = chars.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (hasFlash == null || !hasFlash) continue;
                if (anyId == null) anyId = id;
                Integer facing = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                if (facing != null
                        && facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) {
                    backId = id;
                    break;
                }
            }
            torchCameraId = backId != null ? backId : anyId;
            if (torchCameraId == null) {
                frontSurface.setTorchState(false, false);
                return;
            }
            // Capable is enough to make the button live: some devices only report the
            // torch state after the first toggle, so waiting for the callback would leave
            // the button dead. The callback refines/corrects from here on.
            frontSurface.setTorchState(true, false);
            cameraManager.registerTorchCallback(torchCallback, null);
            torchCallbackRegistered = true;
            FLog.d(CINE_TAG, "torch capable, camera=" + torchCameraId);
        } catch (Exception e) {
            FLog.w(CINE_TAG, "torch setup failed: " + e.getMessage());
            frontSurface.setTorchState(false, false);
        }
    }

    private void teardownTorchCallback() {
        if (!torchCallbackRegistered || cameraManager == null) return;
        try {
            cameraManager.unregisterTorchCallback(torchCallback);
        } catch (Exception ignored) {
            // Already gone with the process.
        }
        torchCallbackRegistered = false;
    }

    private void toggleRealTorch() {
        if (cameraManager == null || torchCameraId == null) return;
        boolean target = !torchOn;
        FLog.d(CINE_TAG, "torch toggle request -> " + target + " cam=" + torchCameraId);
        try {
            cameraManager.setTorchMode(torchCameraId, target);
            // Reflect immediately so the button can never stick on a missed callback
            // event; the TorchCallback still corrects us if the hardware disagrees.
            torchOn = target;
            frontSurface.setTorchState(true, target);
            if (phoneGL != null) phoneGL.setFlashGlow(target ? 1f : 0f);
        } catch (Exception e) {
            FLog.w(CINE_TAG, "torch toggle failed: " + e.getMessage());
        }
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

    private static final String CINE_TAG = "ProCinematic";
    private static final float CINE_OVERSCAN = 1.5f;
    private static final float CINE_AMBIENT_START = 0.18f;
    // The dark layer: the top is lit, a long smooth gradient sinks the lower phone into black.
    private static final float CINE_MASK_FROM = 0.40f;
    private static final float CINE_MASK_TO = 1.0f;
    private static final float CINE_MASK_MIN = 0.04f;

    /** Entrance: the fullscreen cinematic when possible, otherwise the phone boots in place. */
    private void startEntrance() {
        if (!bootEnabled) {
            if (cinematicHost != null) cinematicHost.setVisibility(View.GONE);
            if (cinematicListener != null) cinematicListener.onCinematicActive(false);
            frontSurface.settleBoot();
            return;
        }
        if (cinematicHost == null || !motionAllowed()) {
            FLog.d(CINE_TAG, "skip: host=" + (cinematicHost != null)
                    + " motion=" + motionAllowed());
            if (cinematicHost != null) cinematicHost.setVisibility(View.GONE);
            if (cinematicListener != null) cinematicListener.onCinematicActive(false);
            frontSurface.startBoot();
            return;
        }
        // The card phone must be laid out before the cinematic can target its slot.
        if (phoneWrapper.getWidth() == 0 || phoneWrapper.getHeight() == 0) {
            if (entranceTries++ < 24) {
                post(this::startEntrance);
                return;
            }
            entranceTries = 0;
            FLog.d(CINE_TAG, "skip: card phone never laid out");
            if (cinematicHost != null) cinematicHost.setVisibility(View.GONE);
            if (cinematicListener != null) cinematicListener.onCinematicActive(false);
            frontSurface.startBoot();
            return;
        }
        entranceTries = 0;
        startCinematic();
    }

    // ------------------------------------------------------------ cinematic

    /**
     * The fullscreen first-boot cinematic: the phone towers over the screen showing its
     * back, the camera flash fires twice like a photo, then it flips to the front and
     * settles exactly into the bundle card — where the boot theater takes over. The card
     * phone stays hidden until the handoff, and both are pixel-identical at that moment
     * (front, powered off), so the swap is invisible.
     */
    private void startCinematic() {
        if (cinematicRunning) return;
        cinematicRunning = true;
        Context context = getContext();
        // CRITICAL: the host starts GONE and a GONE view is never laid out, so any size
        // read would be 0. Prepare it, hide the card phone, then show it and WAIT for the
        // layout pass before measuring anything.
        phoneWrapper.setAlpha(0f);
        if (cinematicGL == null) {
            cinematicGL = new PhoneGLView(context, null);
            cinematicGL.setOverscan(CINE_OVERSCAN);
            cinematicGL.setCrop(1f, 0f);
            cinematicHost.addView(cinematicGL, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            cinematicHost.setBackgroundColor(0xFF000000);
            cinematicHost.setClickable(true);
            cinematicHost.setFocusable(true);
        }
        // Kill any stale frame from the previous visit BEFORE the host becomes visible:
        // alpha 0 + dark exposure + the back pose, so the very first visible frame is
        // already the dark-room silhouette (no flash of the docked, fully-lit phone).
        if (cinematicGL != null) {
            cinematicGL.setAlpha(0f);
            cinematicGL.setAmbient(CINE_AMBIENT_START);
            cinematicGL.setAngle(180f);
            cinematicGL.setSweep(0f, 0f);
            cinematicGL.setFlashGlow(0f);
            cinematicGL.setBeam(0f, 0f, 0f, 0.2f);
            cinematicGL.setDarkMask(CINE_MASK_FROM, CINE_MASK_TO, CINE_MASK_MIN);
        }
        cinematicLaunched = false;
        cinematicShownAt = android.os.SystemClock.uptimeMillis();
        cinematicHost.setAlpha(1f);
        cinematicHost.setVisibility(View.VISIBLE);
        if (cinematicListener != null) cinematicListener.onCinematicActive(true);
        FLog.d(CINE_TAG, "host shown, waiting for layout");
        post(this::startCinematicShow);
    }

    /** Second step: the host is visible now — wait until it actually has its laid-out size. */
    private void startCinematicShow() {
        if (!cinematicRunning) return;
        int ow = cinematicHost.getWidth();
        int oh = cinematicHost.getHeight();
        if (ow <= 0 || oh <= 0) {
            if (cinematicTries++ < 24) {
                post(this::startCinematicShow);
                return;
            }
            cinematicTries = 0;
            finishCinematic(false);
            return;
        }
        cinematicTries = 0;
        // The face textures must exist before the show starts.
        updateTextures();
        if (screenBitmap == null) {
            FLog.d(CINE_TAG, "skip: no textures");
            finishCinematic(false);
            return;
        }
        // A crisp back render for the huge phone. Re-rendered every visit so the surface's
        // current layout matches the texture (the card pipeline may have re-laid it out at
        // its own, smaller size meanwhile) and the UV rect stays exactly right.
        int texW = Math.min(1600, Math.max(720, Math.round(oh * 0.90f / 2.05f)));
        int texH = Math.round(texW * 2.05f);
        cinematicBackBitmap = renderSurface(backSurface, cinematicBackBitmap, texW, texH);
        cinematicGL.setScreenBitmap(screenBitmap);
        cinematicGL.setBackBitmap(cinematicBackBitmap);
        RectF sr = backSurface.getScreenRect();
        cinematicGL.setScreenRect(sr.left / texW, sr.top / texH, sr.right / texW, sr.bottom / texH);
        cinematicGL.setAngle(180f);
        cinematicGL.setFlashGlow(0f);
        cinematicGL.setAlpha(0f);
        // The animation clock must not start until the GL thread has uploaded both face
        // textures and drawn a real frame — otherwise the upload stall eats the opening
        // beat and the intro feels late and stuttery. The timeout is only a safety net.
        cinematicGL.awaitNextFrame(cinematicRunner);
        postDelayed(cinematicRunner, 450);
    }

    private void runCinematic() {
        if (!cinematicRunning || cinematicLaunched) return;
        if (cinematicGL == null || cinematicGL.getWidth() == 0 || cinematicGL.getHeight() == 0) {
            if (cinematicTries++ < 24) {
                postDelayed(cinematicRunner, 16);
                return;
            }
            cinematicTries = 0;
            finishCinematic(false);
            return;
        }
        cinematicLaunched = true;
        removeCallbacks(cinematicRunner);
        cinematicTries = 0;
        final float ow = cinematicGL.getWidth();
        final float oh = cinematicGL.getHeight();
        FLog.d(CINE_TAG, "playing: gl=" + (int) ow + "x" + (int) oh
                + " card=" + phoneWrapper.getWidth() + "x" + phoneWrapper.getHeight()
                + " ready=" + (android.os.SystemClock.uptimeMillis() - cinematicShownAt) + "ms");

        // Composition: the phone fits fully on screen with clear side padding — width
        // capped at 78% of the view (never clipped at the edges), height capped at 86%.
        // End: exactly the card's slot.
        final float phoneHStart = Math.min(0.78f * ow * 2.05f, 0.86f * oh);
        final float phoneWStart = phoneHStart / 2.05f;
        final float sStart = introScaleFor(phoneHStart, ow, oh);
        final float sEnd = introScaleFor(phoneWrapper.getHeight(), ow, oh);
        final float tyStart = (oh - phoneHStart) / 2f - oh / 6f;
        int[] loc = new int[2];
        phoneWrapper.getLocationOnScreen(loc);
        int[] hostLoc = new int[2];
        cinematicHost.getLocationOnScreen(hostLoc);
        final float tyEnd = (loc[1] - hostLoc[1]) - oh / 6f;
        final float txEnd = (loc[0] + phoneWrapper.getWidth() / 2f)
                - (hostLoc[0] + cinematicGL.getWidth() / 2f);

        cinematicGL.setExtraScale(sStart);
        cinematicGL.setTranslationY(tyStart);
        cinematicGL.setTranslationX(0f);
        cinematicGL.setSweep(0f, 0f);
        cinematicGL.setAmbient(CINE_AMBIENT_START);
        cineHaptic1 = false;
        cineHaptic2 = false;

        // The light beam: one diagonal front racing down-right across the whole scene. Its
        // path is measured on the phone's real silhouette, and its crossing times of the lens
        // pair and the LED are solved from their exact projected positions — so the lens
        // streak and the flash fire on the very frame the light touches them.
        final float beamDeg = -58f;
        final float bdx = (float) Math.cos(Math.toRadians(beamDeg));
        final float bdy = (float) Math.sin(Math.toRadians(beamDeg));
        final float reach = (Math.abs(bdx) * phoneWStart / 2f + Math.abs(bdy) * phoneHStart / 2f) / oh;
        final float beamFrom = -reach - 0.015f;
        final float beamTo = reach + 0.012f;
        final float[] island = new float[4];
        PhoneGLView.projectCameraIsland(ow, oh, CINE_OVERSCAN, sStart, 0f, tyStart, island);
        final float ledCoord = ((island[2] - ow / 2f) * bdx + (oh / 2f - island[3]) * bdy) / oh;

        final float beamStart = 120f;
        final float beamDur = 1300f;
        final float tLed = beamStart + beamDur * solveBeam(beamFrom, beamTo, ledCoord);
        final float tFlash1 = beamStart + beamDur + 350f;
        final float tFlash2 = tFlash1 + 650f;
        final float tFlip = tFlash2 + 400f;
        final float flipDur = 1700f;
        final float total = tFlip + flipDur + 60f;
        FLog.d(CINE_TAG, "timeline led=" + (int) tLed
                + " flash2=" + (int) tFlash2 + " flip=" + (int) tFlip + " total=" + (int) total);

        // Second pass on the front glass while the phone turns: a gentle reflection sheet.
        final float beam2Deg = -20f;
        final float b2dx = (float) Math.cos(Math.toRadians(beam2Deg));
        final float b2dy = (float) Math.sin(Math.toRadians(beam2Deg));
        final float reach2 = (Math.abs(b2dx) * 0.5f * ow + Math.abs(b2dy) * 0.5f * oh) / oh + 0.08f;

        if (cinematicAnimator != null) cinematicAnimator.cancel();
        cinematicAnimator = ValueAnimator.ofFloat(0f, 1f);
        cinematicAnimator.setDuration((long) total);
        cinematicAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
        cinematicAnimator.addUpdateListener(a -> {
            if (cinematicGL == null) return;
            float t = (float) a.getAnimatedValue() * total;

            cinematicGL.setAlpha(easeOutCubic(clamp01(t / 220f)));

            float flash1 = flashBurst(t, tFlash1, 30f, 420f, 160f);
            float flash2 = flashBurst(t, tFlash2, 28f, 0f, 360f);
            cinematicGL.setFlashGlow(Math.max(flash1, flash2));
            if (!cineHaptic1 && t >= tFlash1) {
                cineHaptic1 = true;
                hapticTap();
            }
            if (!cineHaptic2 && t >= tFlash2) {
                cineHaptic2 = true;
                hapticKey();
            }

            float flipT = clamp01((t - tFlip) / flipDur);
            float fe = easeInOutCubic(flipT);
            float open = easeInOutCubic(clamp01(flipT * 1.35f));

            // The sweep IS the reveal: exposure and the dark layer open together with the
            // beam front, so the phone returns to its normal matte color as the light passes.
            float sweepP = cinematicSweep(clamp01((t - beamStart) / (beamDur + 200f)));
            float amb = CINE_AMBIENT_START + (1f - CINE_AMBIENT_START) * sweepP;
            cinematicGL.setAmbient(amb + (1f - amb) * open);
            float maskMin = CINE_MASK_MIN + (1f - CINE_MASK_MIN) * sweepP;
            cinematicGL.setDarkMask(CINE_MASK_FROM, CINE_MASK_TO,
                    Math.min(1f, maskMin + (1f - maskMin) * open));

            // Beam 1 (down-right across the whole product), then beam 2 across the front.
            float u1 = clamp01((t - beamStart) / beamDur);
            float in1 = clamp01((t - beamStart) / 120f);
            float out1 = 1f - clamp01((t - (beamStart + beamDur - 150f)) / 300f);
            float u2 = clamp01((t - tFlip - 330f) / 1100f);
            if (u2 > 0f && u2 < 1f) {
                float pos2 = -reach2 + 2f * reach2 * easeInOutCubic(u2);
                cinematicGL.setBeam(0.85f * (float) Math.sin(Math.PI * u2), pos2, beam2Deg, 0.20f);
                cinematicGL.setBeamBlur(0f);
            } else if (u1 > 0f && in1 * out1 > 0.001f) {
                float pos1 = beamFrom + (beamTo - beamFrom) * cinematicSweep(u1);
                cinematicGL.setBeam(in1 * out1, pos1, beamDeg, 0.10f);
                // Motion blur follows the front's real speed (analytic derivative of the curve).
                float speed = (beamTo - beamFrom)
                        * (1f - 0.88f * (float) Math.cos(2.0 * Math.PI * u1)) / beamDur;
                cinematicGL.setBeamBlur(Math.min(1f, speed / 0.0011f) * 0.9f);
            } else {
                cinematicGL.setBeam(0f, 0f, beamDeg, 0.26f);
                cinematicGL.setBeamBlur(0f);
            }

            cinematicGL.setExtraScale(sStart + (sEnd - sStart) * fe);
            cinematicGL.setTranslationY(tyStart + (tyEnd - tyStart) * fe);
            cinematicGL.setTranslationX(txEnd * fe);
            cinematicGL.setAngle(180f * (1f - fe));
        });
        cinematicAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                finishCinematic(true);
            }
        });
        cinematicAnimator.start();
    }

    /** The eased beam-front fraction (0..1 of the travel time) at which it reaches {@code coord}. */
    private static float solveBeam(float from, float to, float coord) {
        for (int i = 0; i <= 240; i++) {
            float u = i / 240f;
            if (from + (to - from) * cinematicSweep(u) >= coord) return u;
        }
        return 0.5f;
    }

    /** Flash discharge: a smooth, very fast attack followed by an exponential decay (ms). */
    private static float flashBurst(float t, float start, float attackMs, float holdMs, float tauMs) {
        if (t <= start) return 0f;
        float dt = t - start;
        if (dt < attackMs) return smooth01(dt / attackMs);
        if (dt < attackMs + holdMs) return 1f;
        return clamp01((float) Math.exp(-(dt - attackMs - holdMs) / tauMs));
    }

    private static float smooth01(float x) {
        return x * x * (3f - 2f * x);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float easeOutCubic(float x) {
        float u = 1f - x;
        return 1f - u * u * u;
    }

    private static float easeInOutCubic(float x) {
        return x < 0.5f
                ? 4f * x * x * x
                : 1f - (float) Math.pow(-2f * x + 2f, 3f) / 2f;
    }

    /**
     * The entrance beam's velocity profile: one smooth hump — the front moves at ~12% of
     * the average speed at the start, accelerates to ~188% in the middle and eases back to
     * ~12% at the end, with a continuous (never zero) velocity and no phase joins, so the
     * motion reads as a single cinematic camera move with no stutter.
     */
    private static float cinematicSweep(float x) {
        return x - 0.88f * (float) Math.sin(2.0 * Math.PI * x) / (float) (2.0 * Math.PI);
    }

    /**
     * Replicates the GL view's scale math: the {@code extraScale} that renders the phone at
     * exactly {@code targetPhoneH} px, so the handoff lands on the card's phone slot.
     */
    private static float introScaleFor(float targetPhoneH, float viewW, float viewH) {
        float halfH = (float) Math.tan(Math.toRadians(15.0)) * 3.2f;
        return targetPhoneH * (2f * halfH) / (2.05f * introModelScale(viewW, viewH, 1f) * viewH);
    }

    /** The GL's effective model scale for a view size and extra scale (see PhoneGLView). */
    private static float introModelScale(float viewW, float viewH, float extraScale) {
        float halfH = (float) Math.tan(Math.toRadians(15.0)) * 3.2f;
        float halfW = halfH * (viewW / viewH);
        float scaleBase = Math.min(halfW / 0.75f, halfH / 1.5375f);
        return scaleBase * extraScale;
    }

    /** Handoff: reveal the (pixel-identical) card phone under the fading overlay. */
    private void finishCinematic(boolean landed) {
        FLog.d(CINE_TAG, "finish landed=" + landed);
        if (cinematicAnimator != null) {
            cinematicAnimator.cancel();
            cinematicAnimator = null;
        }
        cinematicRunning = false;
        removeCallbacks(cinematicRunner);
        if (cinematicListener != null) cinematicListener.onCinematicActive(false);
        if (cinematicGL != null) {
            cinematicGL.setFlashGlow(0f);
            cinematicGL.setSweep(0f, REST_SWEEP_X);
            cinematicGL.setAmbient(1f);
            cinematicGL.setBeam(0f, 0f, 0f, 0.2f);
            cinematicGL.setDarkMask(2f, 3f, 1f);
        }
        phoneWrapper.setAlpha(1f);
        if (cinematicHost == null) {
            if (bootEnabled) frontSurface.startBoot();
            return;
        }
        if (!landed || !isAttachedToWindow()) {
            cinematicHost.setVisibility(View.GONE);
            cinematicHost.setAlpha(1f);
            if (bootEnabled && isAttachedToWindow()) frontSurface.startBoot();
            return;
        }
        cinematicHost.animate().alpha(0f).setDuration(220).withEndAction(() -> {
            if (cinematicHost != null) {
                cinematicHost.setVisibility(View.GONE);
                cinematicHost.setAlpha(1f);
            }
            postDelayed(() -> {
                if (isAttachedToWindow() && bootEnabled) frontSurface.startBoot();
            }, 120);
        }).start();
    }

    private void cancelCinematic() {
        if (cinematicAnimator != null) {
            cinematicAnimator.cancel();
            cinematicAnimator = null;
        }
        cinematicRunning = false;
        removeCallbacks(cinematicRunner);
        if (cinematicListener != null) cinematicListener.onCinematicActive(false);
        phoneWrapper.setAlpha(1f);
        if (cinematicGL != null) {
            cinematicGL.setFlashGlow(0f);
            cinematicGL.setSweep(0f, REST_SWEEP_X);
            cinematicGL.setAmbient(1f);
            cinematicGL.setBeam(0f, 0f, 0f, 0.2f);
            cinematicGL.setDarkMask(2f, 3f, 1f);
        }
        if (cinematicHost != null) {
            cinematicHost.animate().cancel();
            cinematicHost.setVisibility(View.GONE);
            cinematicHost.setAlpha(1f);
        }
    }

    /** Open the in-place inspector: the same phone flies to the screen centre with a full spin. */
    public void enterFullscreen() {
        if (fullscreenActive || cinematicRunning || phoneWrapper == null) return;
        if (!motionAllowed()) return;
        // Deterministic: start from the card's rest pose even if an exit is mid-flight.
        animate().cancel();
        setScaleX(1f);
        setScaleY(1f);
        setTranslationX(0f);
        setTranslationY(0f);
        fullscreenActive = true;
        if (fullscreenListener != null) fullscreenListener.onFullscreenActive(true);
        View textColumn = findViewById(R.id.showcaseTextColumn);
        if (textColumn != null) textColumn.setVisibility(View.INVISIBLE);
        View root = findViewById(R.id.showcaseRoot);
        if (root != null) root.setBackground(null);
        // The supersample bump is the most expensive step of the whole flight: re-rendering
        // both faces at 2.6x stalls the opening frames. Schedule it for the tail of the
        // flight instead — and only if the inspector is still open by then.
        postDelayed(() -> {
            if (!fullscreenActive) return;
            textureScale = Math.max(textureScale, 2.6f);
            frontDirty = true;
            backDirty = true;
            frameDirty = true;
            post(textureTask);
        }, 700);
        float w = phoneWrapper.getWidth();
        float h = phoneWrapper.getHeight();
        if (w <= 0f || h <= 0f) return;
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        int[] wLoc = new int[2];
        phoneWrapper.getLocationOnScreen(wLoc);
        float pivotX = wLoc[0] - loc[0] + w / 2f;
        float pivotY = wLoc[1] - loc[1] + h / 2f;
        float screenW = getResources().getDisplayMetrics().widthPixels;
        float screenH = getResources().getDisplayMetrics().heightPixels;
        float targetH = Math.min(0.86f * screenH, 0.74f * screenW * 2.05f);
        float scale = targetH / h;
        float tx = screenW / 2f - (loc[0] + pivotX);
        float ty = screenH / 2f - (loc[1] + pivotY);
        setPivotX(pivotX);
        setPivotY(pivotY);
        // Slow-fast-slow velocity, like the entrance beam: one smooth 360 degree turn.
        android.animation.TimeInterpolator ease = new android.view.animation.AccelerateDecelerateInterpolator();
        animate().scaleX(scale).scaleY(scale).translationX(tx).translationY(ty)
                .setDuration(900).setInterpolator(ease).start();
        inspectorSpin = ValueAnimator.ofFloat(rotation, rotation + 360f);
        inspectorSpin.setDuration(900);
        inspectorSpin.setInterpolator(ease);
        inspectorSpin.addUpdateListener(a -> {
            if (phoneGL != null) phoneGL.setAngle((float) a.getAnimatedValue());
        });
        inspectorSpin.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                inspectorSpin = null;
                // The field and the GL angle must agree again (360 == 0 visually).
                if (phoneGL != null) phoneGL.setAngle(rotation);
            }
        });
        inspectorSpin.start();
    }

    /** Close the inspector: the same phone flies back into the card slot with the reverse spin. */
    public void exitFullscreen() {
        if (!fullscreenActive || phoneWrapper == null) return;
        animate().cancel();
        fullscreenActive = false;
        // Back to card quality right away: the close shrinks the phone, so the big texture
        // is dead weight — restoring the scale now keeps the flight smooth.
        textureScale = 1.5f;
        frontDirty = true;
        backDirty = true;
        frameDirty = true;
        post(textureTask);
        android.animation.TimeInterpolator ease = new android.view.animation.AccelerateDecelerateInterpolator();
        animate().scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
                .setDuration(820).setInterpolator(ease)
                .withEndAction(() -> {
                    View textColumn = findViewById(R.id.showcaseTextColumn);
                    if (textColumn != null) textColumn.setVisibility(View.VISIBLE);
                    View root = findViewById(R.id.showcaseRoot);
                    if (root != null) root.setBackgroundResource(R.drawable.bg_pro_showcase);
                    if (fullscreenListener != null) fullscreenListener.onFullscreenActive(false);
                }).start();
        inspectorSpin = ValueAnimator.ofFloat(rotation + 360f, rotation);
        inspectorSpin.setDuration(820);
        inspectorSpin.setInterpolator(ease);
        inspectorSpin.addUpdateListener(a -> {
            if (phoneGL != null) phoneGL.setAngle((float) a.getAnimatedValue());
        });
        inspectorSpin.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                inspectorSpin = null;
                // The field and the GL angle must agree again (360 == 0 visually).
                if (phoneGL != null) phoneGL.setAngle(rotation);
            }
        });
        inspectorSpin.start();
    }

    /** "Using FadCam for X years, Y months" — from the real first-install time. */
    private void updateUsageEasterEgg() {
        frontSurface.setUsageText(com.fadcam.utils.AppUsageAge.text(getContext()));
    }


        // -------------------------------------------------------------- helpers

    private boolean motionAllowed() {
        return android.provider.Settings.Global.getFloat(getContext().getContentResolver(),
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
    }

}
