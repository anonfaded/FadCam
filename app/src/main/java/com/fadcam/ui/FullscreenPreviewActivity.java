package com.fadcam.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Range;
import android.util.Rational;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.fadcam.CameraType;
import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.RecordingState;
import com.fadcam.RecordingControlIntents;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.dualcam.service.DualCameraRecordingService;
import com.fadcam.services.RecordingService;
import com.fadcam.ui.picker.OptionItem;
import com.fadcam.ui.picker.PickerBottomSheetFragment;
import com.fadcam.utils.ServiceStartPolicy;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Full-screen immersive camera preview.
 * <p>
 * Camera controls (AF / Exposure / Zoom), torch, and camera switch use the
 * <b>exact same</b> {@link PickerBottomSheetFragment} pickers and
 * {@link RecordingControlIntents} as {@link HomeFragment} — no duplicated logic.
 * <p>
 * Controls show/hide: tap the top or bottom 15% of the screen, or press
 * the device Back button. Tapping the central area triggers tap-to-focus.
 */
public class FullscreenPreviewActivity extends AppCompatActivity {

    private static final String TAG = "FullscreenPreview";
    private static final long CONTROLS_AUTO_HIDE_MS = 4000;
    private static final int ANIM_DURATION_MS = 200;
    /** Fraction of screen height reserved as top/bottom edge touch zones. */
    private static final float EDGE_ZONE_FRACTION = 0.15f;
    
    // Avatar animation constants (mirrored from HomeFragment)
    private static final int RES_IDLE = 0, RES_BLINK = 1, RES_WAKE = 2, RES_SLEEP = 3;
    private static final int[] FULLSCREEN_DEFAULT_DRAWABLES = {
        R.drawable.toggle_on_idle, R.drawable.toggle_on_blink,
        R.drawable.toggle_on_anim, R.drawable.toggle_off_anim
    };
    /** Maps eye-color ARGB ints → [idle, blink, wake, sleep] drawable resource IDs. */
    private static final java.util.Map<Integer, int[]> FULLSCREEN_EYE_COLOR_DRAWABLES;
    static {
        java.util.Map<Integer, int[]> m = new java.util.HashMap<>();
        m.put(0xFFFF1744, new int[]{ R.drawable.toggle_on_idle_ruby,    R.drawable.toggle_on_blink_ruby,    R.drawable.toggle_on_anim_ruby,    R.drawable.toggle_off_anim_ruby });
        m.put(0xFF00E5FF, new int[]{ R.drawable.toggle_on_idle_cyan,    R.drawable.toggle_on_blink_cyan,    R.drawable.toggle_on_anim_cyan,    R.drawable.toggle_off_anim_cyan });
        m.put(0xFFD500F9, new int[]{ R.drawable.toggle_on_idle_violet,  R.drawable.toggle_on_blink_violet,  R.drawable.toggle_on_anim_violet,  R.drawable.toggle_off_anim_violet });
        m.put(0xFF2979FF, new int[]{ R.drawable.toggle_on_idle_cobalt,  R.drawable.toggle_on_blink_cobalt,  R.drawable.toggle_on_anim_cobalt,  R.drawable.toggle_off_anim_cobalt });
        m.put(0xFFFFD740, new int[]{ R.drawable.toggle_on_idle_amber,   R.drawable.toggle_on_blink_amber,   R.drawable.toggle_on_anim_amber,   R.drawable.toggle_off_anim_amber });
        m.put(0xFF00E676, new int[]{ R.drawable.toggle_on_idle_lime,    R.drawable.toggle_on_blink_lime,    R.drawable.toggle_on_anim_lime,    R.drawable.toggle_off_anim_lime });
        m.put(0xFFF50057, new int[]{ R.drawable.toggle_on_idle_magenta, R.drawable.toggle_on_blink_magenta, R.drawable.toggle_on_anim_magenta, R.drawable.toggle_off_anim_magenta });
        FULLSCREEN_EYE_COLOR_DRAWABLES = java.util.Collections.unmodifiableMap(m);
    }

    // ── Views ────────────────────────────────────────────────────────────────
    private FrameLayout rootLayout;
    private TextureView textureView;
    private View topBar;
    private View bottomBar;
    private MaterialButton btnFullscreenTorch;
    private MaterialButton btnFullscreenCamSwitch;
    private MaterialButton btnFullscreenMirror;
    private MaterialButton btnFullscreenPauseResume;
    private MaterialButton btnFullscreenCaptureShot;
    private MaterialButton btnTapFocusToggle;
    private View containerFullscreenMirror;
    private View containerFullscreenShot;
    private TextView labelFullscreenMirror;
    private TextView labelFullscreenShot;
    private View containerZoomHud;
    private TextView textZoomHud;
    private MaterialButton btnZoomReset;
    private View containerZoomMap;
    private View viewZoomMapViewport;
    private TextView textFullscreenPreviewHint;
    private View viewFullscreenIdleMask;

    // Avatar-related views for fullscreen preview
    private FrameLayout flFullscreenPreviewAvatar;
    private View ivFullscreenSleepAmbiance;
    private View ivFullscreenWakeSun;
    private ImageView ivFullscreenPreviewAvatar;
    private View ivFullscreenPreviewEyeOverlay;
    private View zzzFullscreenBadgeGroup;
    private TextView tvFullscreenZzz1;
    private TextView tvFullscreenZzz2;
    private TextView tvFullscreenZzz3;

    // Recording-tile views (from included layout)
    private TextView tileAfToggle;
    private TextView tileExp;
    private TextView tileZoom;
    private TextView labelPauseResume;
    private TextView labelExp;
    private TextView labelZoom;

    // ── Surface ──────────────────────────────────────────────────────────────
    private Surface previewSurface;

    // ── State ────────────────────────────────────────────────────────────────
    private SharedPreferencesManager prefs;
    private CameraManager cameraManager;
    private boolean controlsVisible = true;
    private boolean isTorchOn = false;
    private boolean isRecordingPaused = false;
    private boolean isRecordingActive = false;
    private boolean isPreviewOnlyActive = false;
    private boolean isPreviewAttachedInRecording = true;
    private boolean tapToFocusEnabled = true;
    private float pinchZoomRatio = 1.0f;
    private long lastZoomDispatchMs = 0L;
    private float lastDispatchedZoomRatio = -1f;
    private ScaleGestureDetector scaleGestureDetector;
    private boolean longPressTriggered = false;
    private final Handler longPressHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingLongPressRunnable;
    private boolean autoPreviewStartRequested = false;
    private float previewUiScale = 1.0f;
    private float previewUiPanX = 0f;
    private float previewUiPanY = 0f;
    private float lastTouchX = 0f;
    private float lastTouchY = 0f;
    private boolean isPanningPreview = false;

    // Camera control state — mirrors HomeFragment's fields
    private int currentEvIndex;
    private boolean aeLocked;
    private int afMode;

    // Avatar animation state
    private boolean fullscreenAvatarLastEnabled = false;
    private boolean fullscreenAvatarWakingUp = false;        // Guard against updatePreviewHintVisibility resetting during wake
    private boolean isPreviewCloseAnimating = false;          // Guard iris-close transition from firing twice
    private boolean fullscreenPreviewSurfaceWasShowing = false; // Track prev preview state to detect transitions
    private boolean pendingIrisOpen = false;                    // Set when waiting for first camera frame before iris-open
    private Animator fullscreenIrisAnimator = null;  // Track iris reveal animation (eye overlay)
    private java.util.Random fullscreenBlinkRandom = new java.util.Random();
    private Handler fullscreenBlinkHandler = new Handler(Looper.getMainLooper());
    private Runnable fullscreenBlinkRunnable;
    private ValueAnimator fullscreenBreathingAnimator;
    private ObjectAnimator fullscreenAvatarFloatAnim;
    private ObjectAnimator fullscreenAmbianceTwinkleAnim;
    private ObjectAnimator fullscreenFloatingZAnim1;
    private ObjectAnimator fullscreenFloatingZAnim2;
    private ObjectAnimator fullscreenFloatingZAnim3;
    private boolean torchReceiverRegistered = false;
    private final BroadcastReceiver torchReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            if (Constants.BROADCAST_ON_TORCH_STATE_CHANGED.equals(intent.getAction())) {
                isTorchOn = intent.getBooleanExtra(Constants.INTENT_EXTRA_TORCH_STATE, false);
                updateTorchIcon();
            }
        }
    };

    private boolean recordingStateReceiverRegistered = false;
    private final BroadcastReceiver recordingStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String action = intent.getAction();
            if (Constants.BROADCAST_ON_RECORDING_PAUSED.equals(action)
                    || Constants.BROADCAST_ON_DUAL_RECORDING_PAUSED.equals(action)) {
                isRecordingPaused = true;
                isRecordingActive = true;
            } else if (Constants.BROADCAST_ON_RECORDING_RESUMED.equals(action)
                    || Constants.BROADCAST_ON_RECORDING_STARTED.equals(action)
                    || Constants.BROADCAST_ON_DUAL_RECORDING_RESUMED.equals(action)) {
                isRecordingPaused = false;
                isRecordingActive = true;
                isPreviewAttachedInRecording = true;
                if (previewSurface != null && previewSurface.isValid()) {
                    scheduleSurfaceResendBurst();
                }
            } else if (Constants.BROADCAST_ON_RECORDING_STOPPED.equals(action)
                    || Constants.BROADCAST_ON_DUAL_RECORDING_STOPPED.equals(action)) {
                isRecordingPaused = false;
                isRecordingActive = false;
                isPreviewOnlyActive = false;
            } else if (Constants.BROADCAST_ON_RECORDING_STATE_CALLBACK.equals(action)) {
                try {
                    RecordingState state = (RecordingState) intent.getSerializableExtra(
                            Constants.INTENT_EXTRA_RECORDING_STATE);
                    isPreviewOnlyActive = intent.getBooleanExtra(Constants.EXTRA_PREVIEW_ONLY_ACTIVE, false);
                    if (state == RecordingState.PAUSED) {
                        isRecordingPaused = true;
                        isRecordingActive = true;
                        isPreviewAttachedInRecording = true;
                    } else if (state == RecordingState.IN_PROGRESS || state == RecordingState.STARTING) {
                        isRecordingPaused = false;
                        isRecordingActive = true;
                        isPreviewAttachedInRecording = true;
                    } else {
                        isRecordingPaused = false;
                        isRecordingActive = false;
                    }
                } catch (Exception ignored) { }
            } else if (Constants.BROADCAST_ON_PREVIEW_ONLY_STARTED.equals(action)) {
                isPreviewOnlyActive = true;
                autoPreviewStartRequested = false;
                if (previewSurface != null && previewSurface.isValid() && textureView != null) {
                    sendSurfaceToService(previewSurface, textureView.getWidth(), textureView.getHeight());
                    scheduleSurfaceResendBurst();
                }
            } else if (Constants.BROADCAST_ON_PREVIEW_ONLY_STOPPED.equals(action)) {
                isPreviewOnlyActive = false;
                autoPreviewStartRequested = false;
            }
            updatePauseResumeButton();
            updateMirrorButtonVisibilityAndState();
            updatePreviewHintVisibility();
            maybeStartPreviewOnlyAutomatically();
        }
    };

    // ── Handlers ─────────────────────────────────────────────────────────────
    private final Handler autoHideHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoHideRunnable = this::hideControls;

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // Apply the user's selected theme BEFORE setContentView so bottom sheet
        // colours, slider accents, etc. match the rest of the app.
        applyRuntimeTheme();

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fullscreen_preview);

        prefs = SharedPreferencesManager.getInstance(this);
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);

        bindViews();
        enterImmersiveMode();
        setupTextureView();
        setupRecordingTiles();
        registerResultListeners();
        setupTapFocusToggle();
        setupTouchHandling();
        setupCloseButton();
        setupTorchButton();
        setupCamSwitchButton();
        setupMirrorButton();
        setupPauseResumeButton();
        setupCaptureShotButton();
        setupZoomHud();
        setupSystemInsets();
        updatePreviewHintVisibility();
        // Initialize avatar to sleeping state (shows zzz badge by default)
        applyFullscreenAvatarState(false, false);
        registerTorchReceiver();
        registerRecordingStateReceiver();
        requestRecordingStateSync();
        scheduleAutoHide();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
        updateMirrorButtonVisibilityAndState();
        isTorchOn = prefs.sharedPreferences.getBoolean(Constants.PREF_TORCH_STATE, false);
        updateTorchIcon();
        syncZoomUiStateFromPrefs(false);
        updatePreviewHintVisibility();
        requestRecordingStateSync();
        if (previewSurface != null && textureView != null && textureView.isAvailable()) {
            sendSurfaceToService(previewSurface,
                    textureView.getWidth(), textureView.getHeight());
            scheduleSurfaceResendBurst();
        }
        maybeStartPreviewOnlyAutomatically();
    }

    @Override
    protected void onPause() {
        super.onPause();
        autoHideHandler.removeCallbacks(autoHideRunnable);
        // Reset so onResume doesn't mistakenly trigger an iris-close transition
        fullscreenPreviewSurfaceWasShowing = false;
        isPreviewCloseAnimating = false;
        pendingIrisOpen = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        autoHideHandler.removeCallbacks(autoHideRunnable);
        unregisterTorchReceiver();
        unregisterRecordingStateReceiver();
        // Release local surface only — do NOT send null to service.
        // HomeFragment will immediately push its own surface when it resumes,
        // avoiding the race condition that causes "stuck preview" frames.
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (!controlsVisible) {
            showControls();
            scheduleAutoHide();
        } else {
            super.onBackPressed();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Theme — apply the same runtime theme as MainActivity
    // ─────────────────────────────────────────────────────────────────────────

    private void applyRuntimeTheme() {
        SharedPreferencesManager spm = SharedPreferencesManager.getInstance(this);
        String theme = spm.sharedPreferences.getString(
                Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        if (theme == null) theme = Constants.DEFAULT_APP_THEME;

        int styleRes;
        switch (theme) {
            case "Faded Night":   styleRes = R.style.Theme_FadCam_Amoled;       break;
            case "Midnight Dusk": styleRes = R.style.Theme_FadCam_MidnightDusk; break;
            case "Premium Gold":  styleRes = R.style.Theme_FadCam_Gold;         break;
            case "Silent Forest": styleRes = R.style.Theme_FadCam_SilentForest; break;
            case "Shadow Alloy":  styleRes = R.style.Theme_FadCam_ShadowAlloy;  break;
            case "Pookie Pink":   styleRes = R.style.Theme_FadCam_PookiePink;   break;
            case "Snow Veil":     styleRes = R.style.Theme_FadCam_SnowVeil;     break;
            case "Crimson Bloom":
            default:              styleRes = R.style.Theme_FadCam_Red;          break;
        }
        setTheme(styleRes);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Initialization
    // ─────────────────────────────────────────────────────────────────────────

    private void bindViews() {
        rootLayout = findViewById(R.id.fullscreenRoot);
        textureView = findViewById(R.id.fullscreenTextureView);
        topBar = findViewById(R.id.topBar);
        bottomBar = findViewById(R.id.bottomBar);
        btnFullscreenTorch = findViewById(R.id.btnFullscreenTorch);
        btnFullscreenCamSwitch = findViewById(R.id.btnFullscreenCamSwitch);
        btnFullscreenMirror = findViewById(R.id.btnFullscreenMirror);
        btnFullscreenPauseResume = findViewById(R.id.btnFullscreenPauseResume);
        btnFullscreenCaptureShot = findViewById(R.id.btnFullscreenCaptureShot);
        btnTapFocusToggle = findViewById(R.id.btnTapFocusToggle);
        containerFullscreenMirror = findViewById(R.id.containerFullscreenMirror);
        labelFullscreenMirror = findViewById(R.id.labelFullscreenMirror);
        containerFullscreenShot = findViewById(R.id.containerFullscreenShot);
        labelFullscreenShot = findViewById(R.id.labelFullscreenShot);
        containerZoomHud = findViewById(R.id.containerZoomHud);
        textZoomHud = findViewById(R.id.textZoomHud);
        btnZoomReset = findViewById(R.id.btnZoomReset);
        containerZoomMap = findViewById(R.id.containerZoomMap);
        viewZoomMapViewport = findViewById(R.id.viewZoomMapViewport);
        textFullscreenPreviewHint = findViewById(R.id.textFullscreenPreviewHint);
        viewFullscreenIdleMask = findViewById(R.id.viewFullscreenIdleMask);
        
        // Avatar UI elements for fullscreen preview
        flFullscreenPreviewAvatar = findViewById(R.id.fl_fullscreen_preview_avatar);
        ivFullscreenSleepAmbiance = findViewById(R.id.iv_fullscreen_sleep_ambiance);
        ivFullscreenWakeSun = findViewById(R.id.iv_fullscreen_wake_sun);
        ivFullscreenPreviewAvatar = findViewById(R.id.iv_fullscreen_preview_avatar);
        ivFullscreenPreviewEyeOverlay = findViewById(R.id.iv_fullscreen_preview_eye_overlay);
        zzzFullscreenBadgeGroup = findViewById(R.id.zzz_fullscreen_badge_group);
        tvFullscreenZzz1 = findViewById(R.id.tv_fullscreen_zzz_1);
        tvFullscreenZzz2 = findViewById(R.id.tv_fullscreen_zzz_2);
        tvFullscreenZzz3 = findViewById(R.id.tv_fullscreen_zzz_3);
    }

    private void setupCaptureShotButton() {
        if (btnFullscreenCaptureShot == null) return;
        btnFullscreenCaptureShot.setOnClickListener(v -> {
            Intent intent = new Intent(this, getTargetServiceClass());
            intent.setAction(Constants.INTENT_ACTION_CAPTURE_PHOTO);
            startService(intent);
            scheduleAutoHide();
        });
    }

    private void setupTextureView() {
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int w, int h) {
                android.util.Log.d("FullscreenPreview", "onSurfaceTextureAvailable: " + w + "x" + h);
                previewSurface = new Surface(st);
                sendSurfaceToService(previewSurface, w, h);
                scheduleSurfaceResendBurst();
                maybeStartPreviewOnlyAutomatically();
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int w, int h) {
                sendSurfaceToService(previewSurface, w, h);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) {
                // Do NOT send null to service here — HomeFragment will push its
                // own surface immediately when it resumes. Sending null causes
                // a race condition with HomeFragment's surface push.
                previewSurface = null;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {
                // Trigger iris-open once the first camera frame arrives — guarantees visible iris animation
                if (!pendingIrisOpen) return;
                pendingIrisOpen = false;
                // createCircularReveal must run on the UI thread
                fullscreenBlinkHandler.post(() -> {
                    if (textureView == null) return;
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        final int tcx = textureView.getWidth() / 2;
                        final int tcy = textureView.getHeight() / 2;
                        final float tmaxR = (float) Math.hypot(tcx, tcy);
                        if (tmaxR > 0) {
                            android.animation.Animator reveal =
                                    android.view.ViewAnimationUtils.createCircularReveal(
                                            textureView, tcx, tcy, 0f, tmaxR);
                            reveal.setDuration(500);
                            reveal.setInterpolator(
                                    new android.view.animation.DecelerateInterpolator(1.5f));
                            reveal.addListener(new android.animation.AnimatorListenerAdapter() {
                                @Override public void onAnimationStart(android.animation.Animator a) {
                                    if (textureView != null) textureView.setAlpha(1f);
                                }
                            });
                            reveal.start();
                        }
                    }
                });
            }
        });

        if (textureView.isAvailable()) {
            SurfaceTexture st = textureView.getSurfaceTexture();
            if (st != null) {
                previewSurface = new Surface(st);
                sendSurfaceToService(previewSurface, textureView.getWidth(), textureView.getHeight());
                scheduleSurfaceResendBurst();
                maybeStartPreviewOnlyAutomatically();
            }
        }
    }

    private void setupCloseButton() {
        android.widget.ImageView btnClose = findViewById(R.id.btnClose);
        if (btnClose != null) btnClose.setOnClickListener(v -> finish());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Touch handling — tap-to-focus center, controls toggle on edges / back
    // ─────────────────────────────────────────────────────────────────────────

    private void setupTouchHandling() {
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                applyPinchZoom(detector.getScaleFactor());
                return true;
            }
        });

        // Attach to rootLayout (always VISIBLE) so touch works when textureView is invisible (idle state)
        rootLayout.setOnTouchListener((v, event) -> {
            final int action = event.getActionMasked();
            final float touchSlop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
            final boolean zoomGestureLock = previewUiScale > 1.001f;
            if (scaleGestureDetector != null) {
                scaleGestureDetector.onTouchEvent(event);
                if (scaleGestureDetector.isInProgress()) {
                    if (pendingLongPressRunnable != null) {
                        longPressHandler.removeCallbacks(pendingLongPressRunnable);
                    }
                    isPanningPreview = false;
                    return true;
                }
            }
            if (action == MotionEvent.ACTION_POINTER_DOWN && pendingLongPressRunnable != null) {
                longPressHandler.removeCallbacks(pendingLongPressRunnable);
            }

            if (action == MotionEvent.ACTION_DOWN) {
                longPressTriggered = false;
                isPanningPreview = false;
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                pendingLongPressRunnable = () -> {
                    if (v.isPressed()) {
                        longPressTriggered = true;
                        handlePreviewLongPress();
                    }
                };
                if (!zoomGestureLock) {
                    longPressHandler.postDelayed(pendingLongPressRunnable, 420L);
                }
                v.setPressed(true);
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                float travelDx = event.getX() - lastTouchX;
                float travelDy = event.getY() - lastTouchY;
                if (Math.abs(travelDx) > touchSlop || Math.abs(travelDy) > touchSlop) {
                    if (pendingLongPressRunnable != null) {
                        longPressHandler.removeCallbacks(pendingLongPressRunnable);
                    }
                }
            }
            if (action == MotionEvent.ACTION_MOVE && previewUiScale > 1.001f) {
                float dx = event.getX() - lastTouchX;
                float dy = event.getY() - lastTouchY;
                if (Math.abs(dx) > 1f || Math.abs(dy) > 1f) {
                    isPanningPreview = true;
                    previewUiPanX += dx;
                    previewUiPanY += dy;
                    applyPreviewTransform();
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    return true;
                }
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                if (pendingLongPressRunnable != null) {
                    longPressHandler.removeCallbacks(pendingLongPressRunnable);
                }
                v.setPressed(false);
                return true;
            }
            if (action != MotionEvent.ACTION_UP) return true;

            if (pendingLongPressRunnable != null) {
                longPressHandler.removeCallbacks(pendingLongPressRunnable);
            }
            v.setPressed(false);
            if (longPressTriggered) {
                longPressTriggered = false;
                return true;
            }
            if (isPanningPreview) {
                isPanningPreview = false;
                return true;
            }

            float y = event.getY();
            float height = v.getHeight();
            float fraction = y / Math.max(1f, height);

            if (fraction < EDGE_ZONE_FRACTION || fraction > (1f - EDGE_ZONE_FRACTION)) {
                toggleControls();
                return true;
            }

            if (tapToFocusEnabled && (isRecordingActive || isPreviewOnlyActive)) {
                float normX = event.getX() / Math.max(1f, v.getWidth());
                float normY = event.getY() / Math.max(1f, v.getHeight());
                Intent intent = RecordingControlIntents.tapToFocus(this, normX, normY);
                intent.setClass(this, getTargetServiceClass());
                startService(intent);
                showFocusIndicator(event.getX(), event.getY());
            } else {
                toggleControls();
            }
            scheduleAutoHide();
            return true;
        });
    }

    private void handlePreviewLongPress() {
        if (previewUiScale > 1.001f) {
            Log.d(TAG, "Ignoring long-press toggle while zoom/pan gesture mode is active");
            return;
        }
        if (textureView != null) {
            textureView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        }
        if (isRecordingActive || isRecordingPaused) {
            isPreviewAttachedInRecording = !isPreviewAttachedInRecording;
            if (isPreviewAttachedInRecording && previewSurface != null && previewSurface.isValid()) {
                sendSurfaceToService(previewSurface,
                        textureView != null ? textureView.getWidth() : -1,
                        textureView != null ? textureView.getHeight() : -1);
            } else {
                sendSurfaceToService(null, -1, -1);
            }
        } else {
            Intent intent = new Intent(this, RecordingService.class);
            if (isPreviewOnlyActive) {
                intent.setAction(Constants.INTENT_ACTION_STOP_PREVIEW_ONLY);
            } else {
                if (prefs.getCameraSelection() != CameraType.BACK && prefs.getCameraSelection() != CameraType.FRONT) {
                    Toast.makeText(this, R.string.preview_dual_not_supported, Toast.LENGTH_SHORT).show();
                    return;
                }
                intent.setAction(Constants.INTENT_ACTION_START_PREVIEW_ONLY);
                if ((previewSurface == null || !previewSurface.isValid()) && textureView != null && textureView.isAvailable()) {
                    SurfaceTexture st = textureView.getSurfaceTexture();
                    if (st != null) {
                        previewSurface = new Surface(st);
                    }
                }
                if (previewSurface != null && previewSurface.isValid()) {
                    intent.putExtra("SURFACE", previewSurface);
                    intent.putExtra("SURFACE_WIDTH", textureView != null ? textureView.getWidth() : -1);
                    intent.putExtra("SURFACE_HEIGHT", textureView != null ? textureView.getHeight() : -1);
                }
                // Step 1: Wake avatar with animation (eyes open, sun spins in)
                if (flFullscreenPreviewAvatar != null) {
                    flFullscreenPreviewAvatar.setVisibility(View.VISIBLE);
                    flFullscreenPreviewAvatar.setAlpha(1f);
                    flFullscreenPreviewAvatar.setScaleX(1f);
                    flFullscreenPreviewAvatar.setScaleY(1f);
                    fullscreenAvatarWakingUp = true;
                    applyFullscreenAvatarState(true, true);
                    // Step 2: After wake anim (~480ms): shrink avatar out + iris-open camera
                    fullscreenBlinkHandler.postDelayed(() -> {
                        if (flFullscreenPreviewAvatar != null) {
                            flFullscreenPreviewAvatar.animate().cancel();
                            flFullscreenPreviewAvatar.animate()
                                .alpha(0f).scaleX(0.72f).scaleY(0.72f)
                                .setDuration(280)
                                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                                .withEndAction(() -> {
                                    if (flFullscreenPreviewAvatar != null) {
                                        flFullscreenPreviewAvatar.setVisibility(View.INVISIBLE);
                                        flFullscreenPreviewAvatar.setAlpha(1f);
                                        flFullscreenPreviewAvatar.setScaleX(1f);
                                        flFullscreenPreviewAvatar.setScaleY(1f);
                                    }
                                    fullscreenAvatarWakingUp = false;
                                }).start();
                        } else {
                            fullscreenAvatarWakingUp = false;
                        }
                        // Iris-open: signal that we want it on the next camera frame
                        // (avoids black-on-black invisible animation before first frame arrives)
                        if (textureView != null) {
                            pendingIrisOpen = true;
                            textureView.setAlpha(0f);   // ensure transparent until iris begins (SurfaceTexture always exists)
                            // Fallback: if no frame arrives within 1.5s, fade camera in directly
                            fullscreenBlinkHandler.postDelayed(() -> {
                                if (pendingIrisOpen) {
                                    pendingIrisOpen = false;
                                    if (textureView != null) textureView.setAlpha(1f);
                                }
                            }, 1500L);
                        }
                    }, 480L);
                }
            }
            ServiceStartPolicy.startRecordingAction(this, intent);
        }
        updatePreviewHintVisibility();
        scheduleAutoHide();
    }

    private void toggleControls() {
        if (controlsVisible) {
            hideControls();
        } else {
            showControls();
            scheduleAutoHide();
        }
    }

    private void setupTapFocusToggle() {
        tapToFocusEnabled = prefs.isFullscreenTapToFocusEnabled();
        updateTapFocusToggleUI();
        if (btnTapFocusToggle != null) {
            btnTapFocusToggle.setOnClickListener(v -> {
                tapToFocusEnabled = !tapToFocusEnabled;
                prefs.setFullscreenTapToFocusEnabled(tapToFocusEnabled);
                updateTapFocusToggleUI();
                scheduleAutoHide();
            });
        }
    }

    private void updateTapFocusToggleUI() {
        if (btnTapFocusToggle == null) return;
        btnTapFocusToggle.setIconResource(R.drawable.ic_focus_target);
        if (tapToFocusEnabled) {
            btnTapFocusToggle.setText(R.string.fullscreen_focus_on);
            btnTapFocusToggle.setIconTint(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
            btnTapFocusToggle.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x66101418));
            btnTapFocusToggle.setStrokeColor(android.content.res.ColorStateList.valueOf(0x33FFFFFF));
            btnTapFocusToggle.setTextColor(0xFFFFFFFF);
        } else {
            btnTapFocusToggle.setText(R.string.fullscreen_focus_off);
            btnTapFocusToggle.setIconTint(android.content.res.ColorStateList.valueOf(0xFFE0E0E0));
            btnTapFocusToggle.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x5A0D1015));
            btnTapFocusToggle.setStrokeColor(android.content.res.ColorStateList.valueOf(0x2AFFFFFF));
            btnTapFocusToggle.setTextColor(0xFFE0E0E0);
        }
    }

    private void setupSystemInsets() {
        if (rootLayout == null) return;
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            if (topBar != null && topBar.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) topBar.getLayoutParams();
                lp.topMargin = bars.top + dp(12);
                topBar.setLayoutParams(lp);
            }
            if (bottomBar != null) {
                bottomBar.setPadding(bottomBar.getPaddingLeft(), bottomBar.getPaddingTop(),
                        bottomBar.getPaddingRight(), bars.bottom + dp(12));
            }
            return insets;
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Torch button — same logic as HomeFragment
    // ─────────────────────────────────────────────────────────────────────────

    private void setupTorchButton() {
        if (btnFullscreenTorch == null) return;

        // Restore current torch state from prefs
        isTorchOn = prefs.sharedPreferences.getBoolean(Constants.PREF_TORCH_STATE, false);
        updateTorchIcon();

        btnFullscreenTorch.setOnClickListener(v -> {
            if (isRecordingActive || isRecordingPaused || isPreviewOnlyActive) {
                Intent intent = new Intent(this, getTargetServiceClass());
                intent.setAction(Constants.INTENT_ACTION_TOGGLE_RECORDING_TORCH);
                try {
                    startService(intent);
                } catch (Exception e) {
                    Log.e(TAG, "Torch toggle failed", e);
                }
            } else {
                toggleTorchIdle();
            }
        });
    }

    private void toggleTorchIdle() {
        try {
            CameraType selected = prefs.getCameraSelection();
            String targetId = getCameraIdForType(selected);
            if (targetId == null || cameraManager == null) {
                Toast.makeText(this, R.string.torch_unavailable, Toast.LENGTH_SHORT).show();
                return;
            }
            CameraCharacteristics cc = cameraManager.getCameraCharacteristics(targetId);
            Boolean hasFlash = cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (hasFlash == null || !hasFlash) {
                Toast.makeText(this, R.string.torch_unavailable, Toast.LENGTH_SHORT).show();
                return;
            }
            isTorchOn = !isTorchOn;
            cameraManager.setTorchMode(targetId, isTorchOn);
            prefs.sharedPreferences.edit()
                    .putBoolean(Constants.PREF_TORCH_STATE, isTorchOn)
                    .apply();
            Intent stateIntent = new Intent(Constants.BROADCAST_ON_TORCH_STATE_CHANGED);
            stateIntent.putExtra(Constants.INTENT_EXTRA_TORCH_STATE, isTorchOn);
            sendBroadcast(stateIntent);
            updateTorchIcon();
        } catch (Exception e) {
            Log.e(TAG, "Idle torch toggle failed", e);
            Toast.makeText(this, R.string.torch_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private void updateTorchIcon() {
        if (btnFullscreenTorch == null) return;
        btnFullscreenTorch.setIconResource(R.drawable.ic_flashlight_on);
        btnFullscreenTorch.setIconTint(android.content.res.ColorStateList.valueOf(
                isTorchOn ? 0xFFFFC107 : 0xFFFFFFFF));
        btnFullscreenTorch.setStrokeColor(android.content.res.ColorStateList.valueOf(
                isTorchOn ? 0x66FFC107 : 0x2AFFFFFF));
        btnFullscreenTorch.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                isTorchOn ? 0x40210F00 : 0x5A0D1015));
    }

    private void registerTorchReceiver() {
        if (torchReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(Constants.BROADCAST_ON_TORCH_STATE_CHANGED);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(torchReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(torchReceiver, filter);
            }
            torchReceiverRegistered = true;
        } catch (Exception e) {
            Log.e(TAG, "Error registering torch receiver", e);
        }
    }

    private void unregisterTorchReceiver() {
        if (!torchReceiverRegistered) return;
        try {
            unregisterReceiver(torchReceiver);
        } catch (Exception ignored) { }
        torchReceiverRegistered = false;
    }

    private void registerRecordingStateReceiver() {
        if (recordingStateReceiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Constants.BROADCAST_ON_RECORDING_STARTED);
        filter.addAction(Constants.BROADCAST_ON_RECORDING_RESUMED);
        filter.addAction(Constants.BROADCAST_ON_RECORDING_PAUSED);
        filter.addAction(Constants.BROADCAST_ON_RECORDING_STOPPED);
        filter.addAction(Constants.BROADCAST_ON_RECORDING_STATE_CALLBACK);
        filter.addAction(Constants.BROADCAST_ON_PREVIEW_ONLY_STARTED);
        filter.addAction(Constants.BROADCAST_ON_PREVIEW_ONLY_STOPPED);
        filter.addAction(Constants.BROADCAST_ON_DUAL_RECORDING_RESUMED);
        filter.addAction(Constants.BROADCAST_ON_DUAL_RECORDING_PAUSED);
        filter.addAction(Constants.BROADCAST_ON_DUAL_RECORDING_STOPPED);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(recordingStateReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(recordingStateReceiver, filter);
            }
            recordingStateReceiverRegistered = true;
        } catch (Exception e) {
            Log.e(TAG, "Error registering recording-state receiver", e);
        }
    }

    private void unregisterRecordingStateReceiver() {
        if (!recordingStateReceiverRegistered) return;
        try {
            unregisterReceiver(recordingStateReceiver);
        } catch (Exception ignored) { }
        recordingStateReceiverRegistered = false;
    }

    private void requestRecordingStateSync() {
        try {
            Intent i = new Intent(this, RecordingService.class);
            i.setAction(Constants.BROADCAST_ON_RECORDING_STATE_REQUEST);
            startService(i);
        } catch (Exception e) {
            Log.w(TAG, "Failed to request recording state", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Camera switch button — same logic as HomeFragment.switchCamera()
    // ─────────────────────────────────────────────────────────────────────────

    private void setupCamSwitchButton() {
        if (btnFullscreenCamSwitch == null) return;
        btnFullscreenCamSwitch.setOnClickListener(v -> switchCamera());
    }

    private void setupMirrorButton() {
        if (btnFullscreenMirror == null) return;
        btnFullscreenMirror.setOnClickListener(v -> {
            boolean enabled = !prefs.isFrontVideoMirrorEnabled();
            prefs.setFrontVideoMirrorEnabled(enabled);
            Intent intent = new Intent(this, RecordingService.class);
            intent.setAction(Constants.INTENT_ACTION_SET_FRONT_VIDEO_MIRROR);
            intent.putExtra(Constants.EXTRA_FRONT_VIDEO_MIRROR_ENABLED, enabled);
            startService(intent);
            updateMirrorButtonVisibilityAndState();
            scheduleAutoHide();
        });
        updateMirrorButtonVisibilityAndState();
    }

    private void updateMirrorButtonVisibilityAndState() {
        if (btnFullscreenMirror == null || containerFullscreenMirror == null) return;
        boolean front = prefs.getCameraSelection() == CameraType.FRONT;
        containerFullscreenMirror.setVisibility(front && controlsVisible ? View.VISIBLE : View.GONE);
        if (!front) return;

        boolean enabled = prefs.isFrontVideoMirrorEnabled();
        btnFullscreenMirror.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                enabled ? 0x405B1212 : 0x5A0D1015));
        btnFullscreenMirror.setStrokeColor(android.content.res.ColorStateList.valueOf(
                enabled ? 0x66FFC107 : 0x2AFFFFFF));
        btnFullscreenMirror.setContentDescription(getString(
                enabled ? R.string.front_video_mirror_disable : R.string.front_video_mirror_enable));
        if (labelFullscreenMirror != null) {
            labelFullscreenMirror.setTextColor(enabled ? 0xFFFFC107 : 0xB3FFFFFF);
        }
    }

    private void setupPauseResumeButton() {
        labelPauseResume = findViewById(R.id.labelPauseResume);
        updatePauseResumeButton();
        if (btnFullscreenPauseResume == null) return;
        btnFullscreenPauseResume.setOnClickListener(v -> togglePauseResumeRecording());
    }

    private void togglePauseResumeRecording() {
        Intent intent = new Intent(this, getTargetServiceClass());
        if (!isRecordingActive) {
            CameraType selected = prefs.getCameraSelection();
            boolean dual = selected != null && selected.isDual();
            intent.setClass(this, dual ? DualCameraRecordingService.class : RecordingService.class);
            intent.setAction(dual
                    ? Constants.INTENT_ACTION_START_DUAL_RECORDING
                    : Constants.INTENT_ACTION_START_RECORDING);
            ServiceStartPolicy.startRecordingAction(this, intent);
            isRecordingActive = true;
            isRecordingPaused = false;
            isPreviewAttachedInRecording = true;
            updatePauseResumeButton();
            scheduleSurfaceResendBurst();
        } else if (isDualRecordingRunning()) {
            intent.setAction(isRecordingPaused
                    ? Constants.INTENT_ACTION_RESUME_DUAL_RECORDING
                    : Constants.INTENT_ACTION_PAUSE_DUAL_RECORDING);
            startService(intent);
            if (!isRecordingPaused) {
                scheduleSurfaceResendBurst();
            }
        } else {
            intent.setAction(isRecordingPaused
                    ? Constants.INTENT_ACTION_RESUME_RECORDING
                    : Constants.INTENT_ACTION_PAUSE_RECORDING);
            // Root fix: on resume, pass current fullscreen preview surface so RecordingService
            // does not null out previewSurface in setupSurfaceTexture(intent).
            if (isRecordingPaused && textureView != null && textureView.isAvailable()) {
                SurfaceTexture st = textureView.getSurfaceTexture();
                if (st != null) {
                    intent.putExtra("SURFACE", new Surface(st));
                    intent.putExtra("SURFACE_WIDTH", textureView.getWidth());
                    intent.putExtra("SURFACE_HEIGHT", textureView.getHeight());
                }
            }
            startService(intent);
            if (isRecordingPaused) {
                scheduleSurfaceResendBurst();
            }
        }
        if (btnFullscreenPauseResume != null) {
            btnFullscreenPauseResume.setEnabled(false);
            btnFullscreenPauseResume.postDelayed(() -> {
                if (btnFullscreenPauseResume != null) btnFullscreenPauseResume.setEnabled(true);
            }, 350);
        }
    }

    private void updatePauseResumeButton() {
        if (btnFullscreenPauseResume == null) return;
        int iconRes;
        int labelRes;
        if (!isRecordingActive) {
            iconRes = R.drawable.play_button_rounded;
            labelRes = R.string.button_start;
        } else {
            iconRes = isRecordingPaused ? R.drawable.play_button_rounded : R.drawable.pause_rounded;
            labelRes = isRecordingPaused ? R.string.button_resume : R.string.button_pause;
        }
        btnFullscreenPauseResume.setIconResource(iconRes);
        btnFullscreenPauseResume.setIconTint(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
        btnFullscreenPauseResume.setContentDescription(getString(labelRes));
        btnFullscreenPauseResume.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                !isRecordingActive ? 0x404CAF50 : (isRecordingPaused ? 0x40210F00 : 0x5A0D1015)));
        btnFullscreenPauseResume.setStrokeColor(android.content.res.ColorStateList.valueOf(
                !isRecordingActive ? 0x664CAF50 : (isRecordingPaused ? 0x66FFC107 : 0x2AFFFFFF)));
        if (labelPauseResume != null) {
            labelPauseResume.setText(labelRes);
            labelPauseResume.setTextColor(!isRecordingActive ? 0xFF8DE28D : (isRecordingPaused ? 0xFFFFC107 : 0xB3FFFFFF));
        }
    }

    private void switchCamera() {
        CameraType currentType = prefs.getCameraSelection();

        // Dual camera mode → swap PiP cameras
        if (isDualRecordingRunning()) {
            Intent intent = new Intent(this, DualCameraRecordingService.class);
            intent.setAction(Constants.INTENT_ACTION_SWAP_DUAL_CAMERAS);
            startService(intent);
            Toast.makeText(this, getString(R.string.dual_cameras_swapped),
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // Single recording → live camera switch
        CameraType targetType = (currentType == CameraType.BACK)
                ? CameraType.FRONT : CameraType.BACK;

        if (!isRecordingActive && !isRecordingPaused && !isPreviewOnlyActive
                && !isServiceRunning(RecordingService.class)) {
            prefs.sharedPreferences
                    .edit()
                    .putString(Constants.PREF_CAMERA_SELECTION, targetType.toString())
                    .apply();
            pinchZoomRatio = prefs.getSpecificZoomRatio(targetType);
            updateZoomHudUi(pinchZoomRatio);
            updateMirrorButtonVisibilityAndState();
            autoPreviewStartRequested = false;
            maybeStartPreviewOnlyAutomatically();
            Toast.makeText(this,
                    "Switched to " + (targetType == CameraType.FRONT ? "front" : "rear") + " camera",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        Intent switchIntent = new Intent(this, RecordingService.class);
        switchIntent.setAction(Constants.INTENT_ACTION_SWITCH_CAMERA);
        switchIntent.putExtra(Constants.INTENT_EXTRA_CAMERA_TYPE_SWITCH, targetType.toString());
        startService(switchIntent);

        Toast.makeText(this,
                "Switching to " + (targetType == CameraType.FRONT ? "front" : "rear") + " camera...",
                Toast.LENGTH_SHORT).show();
        updateMirrorButtonVisibilityAndState();
        rootLayout.postDelayed(this::updateMirrorButtonVisibilityAndState, 700);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Recording tiles — SAME logic as HomeFragment.setupRecordingTiles()
    // ─────────────────────────────────────────────────────────────────────────

    private void setupRecordingTiles() {
        tileAfToggle = findViewById(R.id.tile_af_toggle);
        tileExp = findViewById(R.id.tile_exp);
        tileZoom = findViewById(R.id.tile_zoom);
        labelExp = findViewById(R.id.labelExp);
        labelZoom = findViewById(R.id.labelZoom);
        if (tileAfToggle == null || tileExp == null || tileZoom == null) return;

        // Apply Material Icons typeface
        Typeface materialIcons = ResourcesCompat.getFont(this, R.font.materialicons);
        if (materialIcons != null) {
            if (tileAfToggle != null) tileAfToggle.setTypeface(materialIcons);
            if (tileExp != null) tileExp.setTypeface(materialIcons);
            if (tileZoom != null) tileZoom.setTypeface(materialIcons);
        }

        // Make tiles white for dark fullscreen background
        int tileColor = 0xFFFFFFFF;
        if (tileAfToggle != null) tileAfToggle.setTextColor(tileColor);
        if (tileExp != null) tileExp.setTextColor(tileColor);
        if (tileZoom != null) tileZoom.setTextColor(tileColor);

        // Load saved state
        currentEvIndex = prefs.getSavedExposureCompensation();
        aeLocked = prefs.isAeLockedSaved();
        afMode = prefs.getSavedAfMode();

        // Set initial AF icon
        if (tileAfToggle != null) {
            tileAfToggle.setText(
                    afMode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                            ? "center_focus_strong" : "center_focus_weak");
        }

        // Tint if modified
        updateExpTileTint();
        updateZoomTileTint();

        // ── AF click ──
        if (tileAfToggle != null) {
            tileAfToggle.setOnClickListener(v -> {
                ArrayList<OptionItem> items = new ArrayList<>();
                items.add(new OptionItem(
                        String.valueOf(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO),
                        getString(R.string.af_continuous_title),
                        getString(R.string.af_continuous_description),
                        null, null, null, null, null,
                        "center_focus_strong"));
                items.add(new OptionItem(
                        String.valueOf(CaptureRequest.CONTROL_AF_MODE_OFF),
                        getString(R.string.af_manual_title),
                        getString(R.string.af_manual_description),
                        null, null, null, null, null,
                        "lock"));
                PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstance(
                        getString(R.string.af_mode_title), items,
                        String.valueOf(afMode), Constants.RK_AF_MODE,
                        getString(R.string.af_picker_helper));
                sheet.show(getSupportFragmentManager(), "af_mode_sheet");
            });
        }

        // ── Exposure click ──
        if (tileExp != null) {
            tileExp.setOnClickListener(v -> {
                // Reload exposure value from SharedPreferences before showing picker
                // This ensures UI reflects any changes made by web/RecordingService
                currentEvIndex = prefs.getSavedExposureCompensation();
                int min = -4, max = 4, step = 1;
                float stepFloat = 1f;
                try {
                    CameraType camType = prefs.getCameraSelection();
                    String camId = getCameraIdForType(camType);
                    if (cameraManager != null && camId != null) {
                        CameraCharacteristics chars = cameraManager.getCameraCharacteristics(camId);
                        Range<Integer> range = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
                        if (range != null) { min = range.getLower(); max = range.getUpper(); }
                        Rational stepRat = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
                        if (stepRat != null) stepFloat = stepRat.floatValue();
                        prefs.setExposureCompensationRange(min, max);
                        prefs.setExposureCompensationStep(stepFloat);
                    }
                } catch (Exception ignored) { }
                PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstanceSliderWithSwitch(
                        getString(R.string.exposure_title),
                        min, max, step, stepFloat, currentEvIndex,
                        Constants.RK_EXPOSURE_COMPENSATION,
                        getString(R.string.ae_lock_helper),
                        getString(R.string.ae_lock_switch_label), aeLocked);
                sheet.show(getSupportFragmentManager(), "ev_slider_sheet");
            });
        }

        // ── Zoom click ──
        if (tileZoom != null) {
            tileZoom.setOnClickListener(v -> {
                CameraType currentCam = prefs.getCameraSelection();
                List<Float> zoomRatios = buildZoomRatioOptions(currentCam);
                if (zoomRatios.isEmpty()) {
                    Toast.makeText(this, getString(R.string.zoom_not_available_toast),
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                float currentZoom = prefs.getSpecificZoomRatio(currentCam);
                PickerBottomSheetFragment sheet = PickerBottomSheetFragment.newInstanceSliderZoom(
                        getString(R.string.zoom_slider_title), zoomRatios, currentZoom,
                        Constants.RK_ZOOM_RATIO, getString(R.string.zoom_slider_helper));
                sheet.show(getSupportFragmentManager(), "zoom_slider_sheet");
            });
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Result listeners — SAME keys and SAME handling as HomeFragment
    // ─────────────────────────────────────────────────────────────────────────

    private void registerResultListeners() {
        // ── Exposure compensation ──
        getSupportFragmentManager().setFragmentResultListener(
                Constants.RK_EXPOSURE_COMPENSATION, this, (key, result) -> {
                    if (aeLocked) {
                        return;
                    }
                    if (result.containsKey(PickerBottomSheetFragment.BUNDLE_SLIDER_VALUE)) {
                        currentEvIndex = result.getInt(PickerBottomSheetFragment.BUNDLE_SLIDER_VALUE);
                    } else if (result.containsKey(PickerBottomSheetFragment.BUNDLE_SELECTED_ID)) {
                        try {
                            currentEvIndex = Integer.parseInt(
                                    result.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID, "0"));
                        } catch (NumberFormatException ignored) { }
                    }
                    prefs.setSavedExposureCompensation(currentEvIndex);
                    Intent intent = RecordingControlIntents.setExposureCompensation(this, currentEvIndex);
                    intent.setClass(this, getTargetServiceClass());
                    startService(intent);
                    updateExpTileTint();
                });

        // ── AE lock ──
        getSupportFragmentManager().setFragmentResultListener(
                Constants.RK_AE_LOCK, this, (key, result) -> {
                    aeLocked = result.getBoolean(PickerBottomSheetFragment.BUNDLE_SWITCH_STATE, false);
                    prefs.setSavedAeLock(aeLocked);
                    Intent intent = RecordingControlIntents.toggleAeLock(this, aeLocked);
                    intent.setClass(this, getTargetServiceClass());
                    startService(intent);
                    updateExpTileTint();
                });

        // ── AF mode ──
        getSupportFragmentManager().setFragmentResultListener(
                Constants.RK_AF_MODE, this, (key, result) -> {
                    String selectedId = result.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                    if (selectedId != null) {
                        try { afMode = Integer.parseInt(selectedId); }
                        catch (NumberFormatException ignored) { return; }
                    }
                    if (tileAfToggle != null) {
                        tileAfToggle.setText(
                                afMode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                                        ? "center_focus_strong" : "center_focus_weak");
                    }
                    Intent intent = RecordingControlIntents.setAfMode(this, afMode);
                    intent.setClass(this, getTargetServiceClass());
                    startService(intent);
                });

        // ── Zoom ratio ──
        getSupportFragmentManager().setFragmentResultListener(
                Constants.RK_ZOOM_RATIO, this, (key, result) -> {
                    if (!result.containsKey(PickerBottomSheetFragment.BUNDLE_SLIDER_VALUE)) return;
                    int index = result.getInt(PickerBottomSheetFragment.BUNDLE_SLIDER_VALUE);
                    CameraType cam = prefs.getCameraSelection();
                    List<Float> ratios = buildZoomRatioOptions(cam);
                    if (index < 0 || index >= ratios.size()) return;
                    float zoomRatio = ratios.get(index);
                    prefs.setSpecificZoomRatio(cam, zoomRatio);
                    updateZoomTileTint();
                    Intent intent = RecordingControlIntents.setZoomRatio(this, zoomRatio);
                    intent.setClass(this, getTargetServiceClass());
                    startService(intent);
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Camera utilities
    // ─────────────────────────────────────────────────────────────────────────

    @Nullable
    private String getCameraIdForType(@Nullable CameraType type) {
        if (type != null && type.isDual()) type = CameraType.BACK;
        try {
            if (cameraManager == null) return null;
            if (type == CameraType.FRONT) {
                for (String id : cameraManager.getCameraIdList()) {
                    CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                    Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT)
                        return id;
                }
            } else {
                String selected = prefs.getSelectedBackCameraId();
                if (selected != null) return selected;
                for (String id : cameraManager.getCameraIdList()) {
                    CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                    Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK)
                        return id;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "getCameraIdForType: " + e.getMessage());
        }
        return null;
    }

    private float getMaxZoomRatio(CameraType cam) {
        try {
            String id = getCameraIdForType(cam);
            if (id == null || cameraManager == null) return 5f;
            CameraCharacteristics ch = cameraManager.getCameraCharacteristics(id);
            if (Build.VERSION.SDK_INT >= 30) {
                Range<Float> range = ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                if (range != null) return range.getUpper();
            }
        } catch (Exception ignored) { }
        return 5f;
    }

    @NonNull
    private List<Float> buildZoomRatioOptions(CameraType cam) {
        List<Float> list = new ArrayList<>();
        float max = getMaxZoomRatio(cam);
        for (float z = 0.5f; z <= max + 0.001f; z += 0.5f) {
            list.add(((float) Math.round(z * 10)) / 10f);
        }
        if (!list.contains(1.0f)) list.add(1.0f);
        Collections.sort(list);
        return list;
    }

    private void applyPinchZoom(float scaleFactor) {
        CameraType cam = prefs.getCameraSelection();
        float minZoom = 0.5f;
        float maxZoom = getMaxZoomRatio(cam);
        float base = pinchZoomRatio > 0f ? pinchZoomRatio : prefs.getSpecificZoomRatio(cam);
        float next = Math.max(minZoom, Math.min(maxZoom, base * scaleFactor));
        pinchZoomRatio = next;
        previewUiScale = Math.max(1.0f, Math.min(4.0f, next));
        applyPreviewTransform();
        prefs.setSpecificZoomRatio(cam, next);
        updateZoomTileTint();
        updateZoomHudUi(next);

        long now = System.currentTimeMillis();
        if (now - lastZoomDispatchMs < 66) {
            return;
        }
        if (lastDispatchedZoomRatio > 0f && Math.abs(next - lastDispatchedZoomRatio) < 0.02f) {
            return;
        }
        lastZoomDispatchMs = now;
        lastDispatchedZoomRatio = next;
        Intent intent = RecordingControlIntents.setZoomRatio(this, next);
        intent.setClass(this, getTargetServiceClass());
        startService(intent);
    }

    private void applyPreviewTransform() {
        if (textureView == null) return;
        float w = textureView.getWidth();
        float h = textureView.getHeight();
        if (w <= 0 || h <= 0) return;

        float maxPanX = (w * (previewUiScale - 1f)) / 2f;
        float maxPanY = (h * (previewUiScale - 1f)) / 2f;
        previewUiPanX = Math.max(-maxPanX, Math.min(maxPanX, previewUiPanX));
        previewUiPanY = Math.max(-maxPanY, Math.min(maxPanY, previewUiPanY));

        android.graphics.Matrix m = new android.graphics.Matrix();
        m.postScale(previewUiScale, previewUiScale, w / 2f, h / 2f);
        m.postTranslate(previewUiPanX, previewUiPanY);
        textureView.setTransform(m);
        updateZoomMapUi();
    }

    private void setupZoomHud() {
        if (btnZoomReset != null) {
            btnZoomReset.setOnClickListener(v -> {
                CameraType cam = prefs.getCameraSelection();
                pinchZoomRatio = 1.0f;
                previewUiScale = 1.0f;
                previewUiPanX = 0f;
                previewUiPanY = 0f;
                prefs.setSpecificZoomRatio(cam, 1.0f);
                updateZoomTileTint();
                updateZoomHudUi(1.0f);
                applyPreviewTransform();
                Intent intent = RecordingControlIntents.setZoomRatio(this, 1.0f);
                intent.setClass(this, getTargetServiceClass());
                startService(intent);
                scheduleAutoHide();
            });
        }
        updateZoomHudUi(prefs.getSpecificZoomRatio(prefs.getCameraSelection()));
    }

    private void updateZoomHudUi(float zoomRatio) {
        if (containerZoomHud == null || textZoomHud == null || btnZoomReset == null) return;
        textZoomHud.setText(String.format(java.util.Locale.getDefault(), "%.1fx", zoomRatio));
        boolean show = zoomRatio > 1.01f;
        containerZoomHud.setVisibility(show ? View.VISIBLE : View.GONE);
        btnZoomReset.setVisibility(show ? View.VISIBLE : View.GONE);
        updateZoomMapUi();
    }

    private void syncZoomUiStateFromPrefs(boolean forceResetPan) {
        CameraType cam = prefs.getCameraSelection();
        if (cam == null) return;
        float savedZoom = prefs.getSpecificZoomRatio(cam);
        pinchZoomRatio = savedZoom;
        previewUiScale = Math.max(1.0f, Math.min(4.0f, savedZoom));
        if (forceResetPan || previewUiScale <= 1.001f) {
            previewUiPanX = 0f;
            previewUiPanY = 0f;
            isPanningPreview = false;
            longPressTriggered = false;
        }
        applyPreviewTransform();
        updateZoomHudUi(pinchZoomRatio);
    }

    private void updateZoomMapUi() {
        if (containerZoomMap == null || viewZoomMapViewport == null || textureView == null) return;
        if (containerZoomMap instanceof ViewGroup) {
            ((ViewGroup) containerZoomMap).setClipChildren(true);
            ((ViewGroup) containerZoomMap).setClipToPadding(true);
        }
        int mapW;
        int mapH;
        String orientation = prefs != null ? prefs.getVideoOrientation() : null;
        boolean portrait = orientation == null || !orientation.toLowerCase(java.util.Locale.US).contains("landscape");
        float density = getResources().getDisplayMetrics().density;
        if (portrait) {
            mapW = Math.round(42f * density);
            mapH = Math.round(56f * density);
        } else {
            mapW = Math.round(56f * density);
            mapH = Math.round(42f * density);
        }
        ViewGroup.LayoutParams mapLp = containerZoomMap.getLayoutParams();
        if (mapLp != null && (mapLp.width != mapW || mapLp.height != mapH)) {
            mapLp.width = mapW;
            mapLp.height = mapH;
            containerZoomMap.setLayoutParams(mapLp);
        }

        int actualMapW = containerZoomMap.getWidth() > 0 ? containerZoomMap.getWidth() : mapW;
        int actualMapH = containerZoomMap.getHeight() > 0 ? containerZoomMap.getHeight() : mapH;

        float scale = Math.max(1.0f, previewUiScale);
        int vpW = Math.max(8, Math.min(actualMapW, Math.round(actualMapW / scale)));
        int vpH = Math.max(8, Math.min(actualMapH, Math.round(actualMapH / scale)));
        ViewGroup.LayoutParams vpLp = viewZoomMapViewport.getLayoutParams();
        if (vpLp != null && (vpLp.width != vpW || vpLp.height != vpH)) {
            vpLp.width = vpW;
            vpLp.height = vpH;
            viewZoomMapViewport.setLayoutParams(vpLp);
        }

        float viewW = textureView.getWidth();
        float viewH = textureView.getHeight();
        float maxPanX = (viewW * (scale - 1f)) / 2f;
        float maxPanY = (viewH * (scale - 1f)) / 2f;
        float nx = 0.5f;
        float ny = 0.5f;
        if (maxPanX > 0f) {
            nx = (maxPanX - previewUiPanX) / (2f * maxPanX);
        }
        if (maxPanY > 0f) {
            ny = (maxPanY - previewUiPanY) / (2f * maxPanY);
        }
        nx = Math.max(0f, Math.min(1f, nx));
        ny = Math.max(0f, Math.min(1f, ny));
        float tx = (actualMapW - vpW) * nx;
        float ty = (actualMapH - vpH) * ny;
        tx = Math.max(0f, Math.min(Math.max(0f, actualMapW - vpW), tx));
        ty = Math.max(0f, Math.min(Math.max(0f, actualMapH - vpH), ty));
        viewZoomMapViewport.setTranslationX(tx);
        viewZoomMapViewport.setTranslationY(ty);
    }

    private void updatePreviewHintVisibility() {
        if (textFullscreenPreviewHint == null) return;
        boolean showPreviewSurface = isPreviewOnlyActive
                || ((isRecordingActive || isRecordingPaused) && isPreviewAttachedInRecording);
        boolean showIdlePlaceholder = !showPreviewSurface;

        // Detect transition: preview was showing and now it stopped
        boolean transition = fullscreenPreviewSurfaceWasShowing && !showPreviewSurface;
        fullscreenPreviewSurfaceWasShowing = showPreviewSurface;

        if (viewFullscreenIdleMask != null) {
            viewFullscreenIdleMask.setVisibility(View.GONE); // Mask not needed — avatar handles bg
        }
        setFullscreenHintVisibilityAnimated(showIdlePlaceholder);

        if (showIdlePlaceholder) {
            if (transition && !isPreviewCloseAnimating && !fullscreenAvatarWakingUp
                    && textureView != null && textureView.getAlpha() > 0.1f) {
                // ── Preview just stopped: mirror HomeFragment iris-close sequence ──────────
                isPreviewCloseAnimating = true;

                // 1. Reveal avatar container under textureView; set to instant-awake state
                if (flFullscreenPreviewAvatar != null) {
                    flFullscreenPreviewAvatar.setEnabled(true);
                    flFullscreenPreviewAvatar.setAlpha(1f);
                    flFullscreenPreviewAvatar.setScaleX(1f);
                    flFullscreenPreviewAvatar.setScaleY(1f);
                    flFullscreenPreviewAvatar.setVisibility(View.VISIBLE);
                }
                applyFullscreenAvatarState(true, false); // instant awake

                // Ensure textureView is fully opaque with last camera frame before we contract it
                textureView.setAlpha(1f);

                // 2. Iris-close: textureView contracts from full-screen to nothing
                if (textureView.getWidth() > 0
                        && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    final int cx = textureView.getWidth() / 2;
                    final int cy = textureView.getHeight() / 2;
                    final float maxR = (float) Math.hypot(cx, cy);
                    android.animation.Animator irisClose =
                            android.view.ViewAnimationUtils.createCircularReveal(
                                    textureView, cx, cy, maxR, 0f);
                    irisClose.setDuration(480);
                    irisClose.setInterpolator(
                            new android.view.animation.AccelerateInterpolator(1.3f));
                    irisClose.addListener(new android.animation.AnimatorListenerAdapter() {
                        @Override public void onAnimationEnd(android.animation.Animator a) {
                            isPreviewCloseAnimating = false;
                            if (textureView != null) textureView.setAlpha(0f);
                            // Brief hold → avatar falls asleep (mirrors HomeFragment's 650ms delay)
                            final android.view.View anchor = ivFullscreenPreviewAvatar;
                            if (anchor != null) {
                                anchor.postDelayed(() -> applyFullscreenAvatarState(false, true), 650);
                            } else {
                                applyFullscreenAvatarState(false, true);
                            }
                        }
                    });
                    irisClose.start();
                } else {
                    // Fallback: fade camera out
                    textureView.animate().alpha(0f).setDuration(340).withEndAction(() -> {
                        isPreviewCloseAnimating = false;
                        applyFullscreenAvatarState(false, true);
                    }).start();
                }

            } else if (!isPreviewCloseAnimating && !fullscreenAvatarWakingUp) {
                // ── No preview, no active transition: ensure correct steady-state ────────
                if (flFullscreenPreviewAvatar != null) {
                    flFullscreenPreviewAvatar.setVisibility(View.VISIBLE);
                    flFullscreenPreviewAvatar.setEnabled(true);
                }
                if (textureView != null) {
                    textureView.setAlpha(0f);
                }
                // Transition awake→sleep if needed (e.g. after onResume with no preview)
                if (fullscreenAvatarLastEnabled) {
                    applyFullscreenAvatarState(false, false);
                }
            }
        } else {
            // ── Preview is active: textureView (on top) covers avatar below ──────────────
            if (!fullscreenAvatarWakingUp && !isPreviewCloseAnimating) {
                if (flFullscreenPreviewAvatar != null) {
                    flFullscreenPreviewAvatar.setVisibility(View.INVISIBLE);
                }
                if (textureView != null) {
                    textureView.setAlpha(1f);
                }
            }
        }
    }

    /** Smoothly shows/hides the preview hint label with fade+scale — mirrors HomeFragment. */
    private void setFullscreenHintVisibilityAnimated(boolean show) {
        if (textFullscreenPreviewHint == null) return;
        if (show) {
            if (textFullscreenPreviewHint.getVisibility() == View.VISIBLE
                    && textFullscreenPreviewHint.getAlpha() >= 0.99f) return;
            textFullscreenPreviewHint.animate().cancel();
            textFullscreenPreviewHint.setAlpha(0f);
            textFullscreenPreviewHint.setScaleX(0.88f);
            textFullscreenPreviewHint.setScaleY(0.88f);
            textFullscreenPreviewHint.setVisibility(View.VISIBLE);
            textFullscreenPreviewHint.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(380)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                .start();
        } else {
            if (textFullscreenPreviewHint.getVisibility() != View.VISIBLE) return;
            textFullscreenPreviewHint.animate().cancel();
            textFullscreenPreviewHint.animate()
                .alpha(0f).scaleX(0.88f).scaleY(0.88f)
                .setDuration(260)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    if (textFullscreenPreviewHint != null) {
                        textFullscreenPreviewHint.setVisibility(View.GONE);
                        textFullscreenPreviewHint.setAlpha(1f);
                        textFullscreenPreviewHint.setScaleX(1f);
                        textFullscreenPreviewHint.setScaleY(1f);
                    }
                })
                .start();
        }
    }

    private void maybeStartPreviewOnlyAutomatically() {
        if (autoPreviewStartRequested || previewSurface == null || !previewSurface.isValid()) return;
        if (isRecordingActive || isRecordingPaused || isPreviewOnlyActive) return;
        CameraType cam = prefs.getCameraSelection();
        if (cam == null || cam.isDual()) return;
        if (!prefs.isPreviewEnabled()) return;

        autoPreviewStartRequested = true;
        Intent intent = new Intent(this, RecordingService.class);
        intent.setAction(Constants.INTENT_ACTION_START_PREVIEW_ONLY);
        intent.putExtra("SURFACE", previewSurface);
        intent.putExtra("SURFACE_WIDTH", textureView != null ? textureView.getWidth() : -1);
        intent.putExtra("SURFACE_HEIGHT", textureView != null ? textureView.getHeight() : -1);
        ServiceStartPolicy.startRecordingAction(this, intent);
        if (textureView != null) {
            textureView.postDelayed(() -> {
                if (!isPreviewOnlyActive && !isRecordingActive && !isRecordingPaused) {
                    autoPreviewStartRequested = false;
                }
            }, 1200L);
        }
    }

    private void scheduleSurfaceResendBurst() {
        if (previewSurface == null || !previewSurface.isValid() || textureView == null) {
            return;
        }
        final int w = textureView.getWidth();
        final int h = textureView.getHeight();
        textureView.post(() -> sendSurfaceToService(previewSurface, w, h));
        textureView.postDelayed(() -> sendSurfaceToService(previewSurface, w, h), 100L);
        textureView.postDelayed(() -> sendSurfaceToService(previewSurface, w, h), 300L);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tile tinting helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void updateExpTileTint() {
        if (tileExp == null) return;
        boolean modified = currentEvIndex != 0 || aeLocked;
        tileExp.setTextColor(modified ? 0xFFFF9800 : 0xFFFFFFFF);
        if (labelExp != null) {
            labelExp.setTextColor(modified ? 0xFFFFC107 : 0xB3FFFFFF);
        }
    }

    private void updateZoomTileTint() {
        if (tileZoom == null) return;
        CameraType cam = prefs.getCameraSelection();
        float zoom = prefs.getSpecificZoomRatio(cam);
        boolean modified = Math.abs(zoom - 1.0f) >= 0.01f;
        tileZoom.setTextColor(modified ? 0xFFFF9800 : 0xFFFFFFFF);
        if (labelZoom != null) {
            labelZoom.setTextColor(modified ? 0xFFFFC107 : 0xB3FFFFFF);
        }
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Immersive mode
    // ─────────────────────────────────────────────────────────────────────────

    private void enterImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController wic = getWindow().getInsetsController();
            if (wic != null) {
                wic.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                wic.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Controls visibility — auto-hide with fade animation
    // ─────────────────────────────────────────────────────────────────────────

    private void showControls() {
        controlsVisible = true;
        animateBar(topBar, true);
        animateBar(bottomBar, true);
        animateBar(containerFullscreenShot, true);
        animateBar(containerFullscreenMirror, prefs.getCameraSelection() == CameraType.FRONT);
    }

    private void hideControls() {
        controlsVisible = false;
        autoHideHandler.removeCallbacks(autoHideRunnable);
        animateBar(topBar, false);
        animateBar(bottomBar, false);
        animateBar(containerFullscreenShot, false);
        animateBar(containerFullscreenMirror, false);
    }

    private void scheduleAutoHide() {
        autoHideHandler.removeCallbacks(autoHideRunnable);
        autoHideHandler.postDelayed(autoHideRunnable, CONTROLS_AUTO_HIDE_MS);
    }

    private void animateBar(@Nullable View view, boolean show) {
        if (view == null) return;
        view.animate()
                .alpha(show ? 1f : 0f)
                .setDuration(ANIM_DURATION_MS)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationStart(Animator a) {
                        if (show) view.setVisibility(View.VISIBLE);
                    }
                    @Override
                    public void onAnimationEnd(Animator a) {
                        if (!show) view.setVisibility(View.GONE);
                    }
                })
                .start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Focus indicator — matches HomeFragment exactly (filled red ring, animated)
    // ─────────────────────────────────────────────────────────────────────────

    private void showFocusIndicator(float x, float y) {
        try {
            if (rootLayout == null) return;

            View focusView = new View(this);
            int size = (int) (80 * getResources().getDisplayMetrics().density);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size);
            params.leftMargin = (int) (x - size / 2f);
            params.topMargin = (int) (y - size / 2f);

            GradientDrawable drawable = new GradientDrawable();
            drawable.setShape(GradientDrawable.OVAL);
            drawable.setStroke(8, 0xFFFF0000);   // Bright red ring
            drawable.setColor(0x44FF0000);         // Semi-transparent red fill
            focusView.setBackground(drawable);
            focusView.setLayoutParams(params);
            focusView.setAlpha(0f);
            focusView.setElevation(20f);

            rootLayout.addView(focusView);

            // Same animation as HomeFragment: fade in + scale pulse in → out → fade out
            ObjectAnimator fadeIn = ObjectAnimator.ofFloat(focusView, "alpha", 0f, 1f);
            ObjectAnimator scaleXIn = ObjectAnimator.ofFloat(focusView, "scaleX", 1.5f, 1f);
            ObjectAnimator scaleYIn = ObjectAnimator.ofFloat(focusView, "scaleY", 1.5f, 1f);
            ObjectAnimator scaleXOut = ObjectAnimator.ofFloat(focusView, "scaleX", 1f, 0.8f);
            ObjectAnimator scaleYOut = ObjectAnimator.ofFloat(focusView, "scaleY", 1f, 0.8f);
            ObjectAnimator fadeOut = ObjectAnimator.ofFloat(focusView, "alpha", 1f, 0f);

            AnimatorSet animSet = new AnimatorSet();
            animSet.play(fadeIn).with(scaleXIn).with(scaleYIn);
            animSet.play(scaleXOut).with(scaleYOut).after(fadeIn);
            animSet.play(fadeOut).after(scaleXOut);

            fadeIn.setDuration(100);
            scaleXIn.setDuration(200); scaleYIn.setDuration(200);
            scaleXOut.setDuration(400); scaleYOut.setDuration(400);
            fadeOut.setDuration(200);

            animSet.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    try { rootLayout.removeView(focusView); }
                    catch (Exception e) { Log.w(TAG, "Error removing focus indicator", e); }
                }
            });
            animSet.start();
        } catch (Exception e) {
            Log.e(TAG, "Error showing focus indicator: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Service routing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the correct recording service class based on current mode.
     */
    private Class<?> getTargetServiceClass() {
        return isDualRecordingRunning()
                ? DualCameraRecordingService.class
                : RecordingService.class;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Surface → service communication
    // ─────────────────────────────────────────────────────────────────────────

    private void sendSurfaceToService(@Nullable Surface surface, int w, int h) {
        Class<?> svc = getTargetServiceClass();

        boolean shouldSync = isRecordingActive || isRecordingPaused || isPreviewOnlyActive
                || isServiceRunning(svc);
        if (!shouldSync && svc == RecordingService.class) {
            android.util.Log.d("FullscreenPreview", "sendSurfaceToService: skipped while idle");
            return;
        }
        
        android.util.Log.d("FullscreenPreview", "sendSurfaceToService: surface=" + 
                (surface != null && surface.isValid() ? "VALID " + w + "x" + h : "NULL") + 
                ", service=" + svc.getSimpleName());

        if (!isServiceRunning(svc) && svc == DualCameraRecordingService.class) {
            android.util.Log.w("FullscreenPreview", "Dual service not running: " + svc.getSimpleName());
            return;
        }

        Intent intent = new Intent(this, svc);
        intent.setAction(Constants.INTENT_ACTION_CHANGE_SURFACE);
        if (surface != null) intent.putExtra("SURFACE", surface);
        intent.putExtra("SURFACE_WIDTH", w);
        intent.putExtra("SURFACE_HEIGHT", h);
        // Mark as fullscreen transition for immediate surface application (bypass 200ms debounce)
        intent.putExtra("IS_FULLSCREEN_TRANSITION", true);
        android.util.Log.d("FullscreenPreview", "Sending surface with IS_FULLSCREEN_TRANSITION=true");
        startService(intent);
    }

    private boolean isDualRecordingRunning() {
        return isServiceRunning(DualCameraRecordingService.class);
    }

    @SuppressWarnings("deprecation")
    private boolean isServiceRunning(Class<?> cls) {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am == null) return false;
            for (ActivityManager.RunningServiceInfo info : am.getRunningServices(Integer.MAX_VALUE)) {
                if (cls.getName().equals(info.service.getClassName())) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    /**
     * Apply avatar state for fullscreen preview (sleeping or awake with all animations).
     * Mirrors HomeFragment's applyHomeAvatarState logic completely.
     */
    private void applyFullscreenAvatarState(boolean enabled, boolean animate) {
        if (ivFullscreenPreviewAvatar == null) return;

        if (enabled) {
            // Awake state: sun rises, moon fades out, show blinking
            stopFullscreenBreathing();
            stopFullscreenBlinkLoop();
            ivFullscreenPreviewAvatar.setAlpha(1.0f);
            
            // Cancel twinkle + animate moon out, spin sun in
            if (fullscreenAmbianceTwinkleAnim != null) { 
                fullscreenAmbianceTwinkleAnim.cancel(); 
                fullscreenAmbianceTwinkleAnim = null; 
            }
            
            if (ivFullscreenSleepAmbiance != null) {
                ivFullscreenSleepAmbiance.animate().cancel();
                ivFullscreenSleepAmbiance.animate().alpha(0f).scaleX(0.75f).scaleY(0.75f)
                    .setDuration(280)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .start();
            }
            if (ivFullscreenWakeSun != null) {
                // Cancel first, then post() the setup so ViewPropertyAnimator's cancel-reset
                // doesn't override our initial state (known issue with immediate re-animation).
                ivFullscreenWakeSun.animate().cancel();
                final View sun = ivFullscreenWakeSun;
                sun.post(() -> {
                    if (sun == null) return;
                    sun.setAlpha(0f);
                    sun.setScaleX(0.2f);
                    sun.setScaleY(0.2f);
                    sun.setRotation(-30f);
                    sun.animate()
                        .alpha(1f).scaleX(1f).scaleY(1f).rotation(0f)
                        .setDuration(520)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.5f))
                        .start();
                });
            }
            
            // Eye overlay: always alpha=0, consistent with HomeFragment (eye color shown via drawables)
            if (ivFullscreenPreviewEyeOverlay != null) {
                ivFullscreenPreviewEyeOverlay.animate().cancel();
                ivFullscreenPreviewEyeOverlay.setAlpha(0f);
            }
            
            // Hide zzz
            if (zzzFullscreenBadgeGroup != null) {
                if (animate && zzzFullscreenBadgeGroup.getVisibility() == View.VISIBLE) {
                    zzzFullscreenBadgeGroup.animate().alpha(0f).setDuration(180).withEndAction(() -> {
                        zzzFullscreenBadgeGroup.setVisibility(View.GONE);
                        zzzFullscreenBadgeGroup.setAlpha(1f);
                        resetFullscreenZLetters();
                    }).start();
                } else {
                    zzzFullscreenBadgeGroup.setVisibility(View.GONE);
                    resetFullscreenZLetters();
                }
            }
            
            if (animate) {
                // Wake-up AVD → idle + blink
                ivFullscreenPreviewAvatar.setImageResource(resolveFullscreenDrawable(RES_WAKE));
                // Post-delay ensures drawable is fully loaded before animation starts
                ivFullscreenPreviewAvatar.post(() -> {
                    android.graphics.drawable.Drawable d = ivFullscreenPreviewAvatar.getDrawable();
                    if (d instanceof android.graphics.drawable.Animatable2) {
                        ((android.graphics.drawable.Animatable2) d).clearAnimationCallbacks();
                        ((android.graphics.drawable.Animatable2) d).registerAnimationCallback(
                            new android.graphics.drawable.Animatable2.AnimationCallback() {
                                @Override public void onAnimationEnd(android.graphics.drawable.Drawable drawable) {
                                    if (ivFullscreenPreviewAvatar != null && ivFullscreenPreviewAvatar.isAttachedToWindow()) {
                                        ivFullscreenPreviewAvatar.setImageResource(resolveFullscreenDrawable(RES_IDLE));
                                        startFullscreenBlinkLoop();
                                    }
                                }
                            });
                        ((android.graphics.drawable.Animatable2) d).start();
                    } else if (d instanceof android.graphics.drawable.Animatable) {
                        ((android.graphics.drawable.Animatable) d).start();
                        ivFullscreenPreviewAvatar.postDelayed(() -> {
                            if (ivFullscreenPreviewAvatar != null && ivFullscreenPreviewAvatar.isAttachedToWindow()) {
                                ivFullscreenPreviewAvatar.setImageResource(resolveFullscreenDrawable(RES_IDLE));
                                startFullscreenBlinkLoop();
                            }
                        }, 480);
                    }
                });
            } else {
                ivFullscreenPreviewAvatar.setImageResource(resolveFullscreenDrawable(RES_IDLE));
                startFullscreenBlinkLoop();
            }

        } else {
            // Sleeping state: moon rises, sun fades, iris closes, show zzz badge
            stopFullscreenBlinkLoop();
            stopFullscreenFloatingZAnims();
            if (fullscreenIrisAnimator != null) {
                fullscreenIrisAnimator.cancel();
                fullscreenIrisAnimator = null;
            }
            
            // Eye overlay: keep at alpha=0 always (same as HomeFragment — never visible)
            if (ivFullscreenPreviewEyeOverlay != null) {
                if (fullscreenIrisAnimator != null) { fullscreenIrisAnimator.cancel(); fullscreenIrisAnimator = null; }
                ivFullscreenPreviewEyeOverlay.animate().cancel();
                ivFullscreenPreviewEyeOverlay.setAlpha(0f);
            }
            
            // Sun fades out (if visible)
            if (ivFullscreenWakeSun != null && ivFullscreenWakeSun.getAlpha() > 0.02f) {
                ivFullscreenWakeSun.animate().cancel();
                ivFullscreenWakeSun.animate().alpha(0f).scaleX(0.4f).scaleY(0.4f).rotation(20f)
                    .setDuration(220)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .start();
            }
            
            // Moon rises back in (or starts twinkle directly if already visible)
            if (ivFullscreenSleepAmbiance != null) {
                ivFullscreenSleepAmbiance.animate().cancel();
                if (ivFullscreenSleepAmbiance.getAlpha() < 0.1f) {
                    // Was hidden by wake animation — animate back in
                    ivFullscreenSleepAmbiance.setScaleX(0.8f);
                    ivFullscreenSleepAmbiance.setScaleY(0.8f);
                    ivFullscreenSleepAmbiance.animate().alpha(0.55f).scaleX(1f).scaleY(1f)
                        .setDuration(380)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.2f))
                        .withEndAction(() -> {
                            if (ivFullscreenSleepAmbiance != null && ivFullscreenSleepAmbiance.getAlpha() >= 0.1f) {
                                if (fullscreenAmbianceTwinkleAnim != null) fullscreenAmbianceTwinkleAnim.cancel();
                                fullscreenAmbianceTwinkleAnim = ObjectAnimator.ofFloat(ivFullscreenSleepAmbiance, "alpha", 0.55f, 1.0f);
                                fullscreenAmbianceTwinkleAnim.setDuration(3500);
                                fullscreenAmbianceTwinkleAnim.setRepeatCount(ObjectAnimator.INFINITE);
                                fullscreenAmbianceTwinkleAnim.setRepeatMode(ObjectAnimator.REVERSE);
                                fullscreenAmbianceTwinkleAnim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
                                fullscreenAmbianceTwinkleAnim.start();
                            }
                        })
                        .start();
                } else {
                    // Already visible — reset scale and just start twinkle
                    ivFullscreenSleepAmbiance.setScaleX(1f);
                    ivFullscreenSleepAmbiance.setScaleY(1f);
                    if (fullscreenAmbianceTwinkleAnim != null) fullscreenAmbianceTwinkleAnim.cancel();
                    fullscreenAmbianceTwinkleAnim = ObjectAnimator.ofFloat(ivFullscreenSleepAmbiance, "alpha", 0.55f, 1.0f);
                    fullscreenAmbianceTwinkleAnim.setDuration(3500);
                    fullscreenAmbianceTwinkleAnim.setRepeatCount(ObjectAnimator.INFINITE);
                    fullscreenAmbianceTwinkleAnim.setRepeatMode(ObjectAnimator.REVERSE);
                    fullscreenAmbianceTwinkleAnim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
                    fullscreenAmbianceTwinkleAnim.start();
                }
            }

            if (animate) {
                ivFullscreenPreviewAvatar.setAlpha(1f); // ensure full brightness before sleep AVD plays
                ivFullscreenPreviewAvatar.setImageResource(resolveFullscreenDrawable(RES_SLEEP));
                // Post-delay ensures drawable is fully loaded before animation starts
                ivFullscreenPreviewAvatar.post(() -> {
                    android.graphics.drawable.Drawable d = ivFullscreenPreviewAvatar.getDrawable();
                    Runnable afterOff = () -> {
                        if (ivFullscreenPreviewAvatar == null || !ivFullscreenPreviewAvatar.isAttachedToWindow()) return;
                        ivFullscreenPreviewAvatar.setImageResource(R.drawable.toggle_off);
                        startFullscreenBreathing(); // start breathing AFTER animation completes
                        showFullscreenZzzLetters(true);
                    };
                    if (d instanceof android.graphics.drawable.Animatable2) {
                        ((android.graphics.drawable.Animatable2) d).clearAnimationCallbacks();
                        ((android.graphics.drawable.Animatable2) d).registerAnimationCallback(
                            new android.graphics.drawable.Animatable2.AnimationCallback() {
                                @Override public void onAnimationEnd(android.graphics.drawable.Drawable drawable) { 
                                    fullscreenAvatarWakingUp = false;
                                    afterOff.run(); 
                                }
                            });
                        ((android.graphics.drawable.Animatable2) d).start();
                    } else if (d instanceof android.graphics.drawable.Animatable) {
                        ((android.graphics.drawable.Animatable) d).start();
                        ivFullscreenPreviewAvatar.postDelayed(() -> {
                            fullscreenAvatarWakingUp = false;
                            afterOff.run();
                        }, 480);
                    } else {
                        fullscreenAvatarWakingUp = false;
                        afterOff.run();
                    }
                });
            } else {
                ivFullscreenPreviewAvatar.setImageResource(R.drawable.toggle_off);
                startFullscreenBreathing();
                showFullscreenZzzLetters(false);
                fullscreenAvatarWakingUp = false;
            }
        }
        
        fullscreenAvatarLastEnabled = enabled;
    }

    /** Show zzz badge with optional animation and floating effects. */
    private void showFullscreenZzzLetters(boolean animate) {
        if (zzzFullscreenBadgeGroup == null) return;
        stopFullscreenFloatingZAnims();
        if (animate) {
            resetFullscreenZLetters();
            if (tvFullscreenZzz1 != null) { tvFullscreenZzz1.setAlpha(0f); tvFullscreenZzz1.setScaleX(0.1f); tvFullscreenZzz1.setScaleY(0.1f); tvFullscreenZzz1.setTranslationY(8f); }
            if (tvFullscreenZzz2 != null) { tvFullscreenZzz2.setAlpha(0f); tvFullscreenZzz2.setScaleX(0.1f); tvFullscreenZzz2.setScaleY(0.1f); tvFullscreenZzz2.setTranslationY(8f); }
            if (tvFullscreenZzz3 != null) { tvFullscreenZzz3.setAlpha(0f); tvFullscreenZzz3.setScaleX(0.1f); tvFullscreenZzz3.setScaleY(0.1f); tvFullscreenZzz3.setTranslationY(8f); }
            
            zzzFullscreenBadgeGroup.setAlpha(1f);
            zzzFullscreenBadgeGroup.setVisibility(View.VISIBLE);
            long delay = 130;
            for (View z : new View[]{tvFullscreenZzz1, tvFullscreenZzz2, tvFullscreenZzz3}) {
                if (z == null) continue;
                z.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setStartDelay(delay).setDuration(290)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(2.5f)).start();
                delay += 115;
            }
            zzzFullscreenBadgeGroup.postDelayed(() -> startFullscreenFloatingZAnims(), 700);
        } else {
            zzzFullscreenBadgeGroup.setAlpha(1f);
            zzzFullscreenBadgeGroup.setVisibility(View.VISIBLE);
            resetFullscreenZLetters();
            zzzFullscreenBadgeGroup.postDelayed(() -> startFullscreenFloatingZAnims(), 100);
        }
    }

    /** Reset zzz letters to default state. */
    private void resetFullscreenZLetters() {
        for (View z : new View[]{tvFullscreenZzz1, tvFullscreenZzz2, tvFullscreenZzz3}) {
            if (z == null) continue;
            z.setAlpha(1f); z.setScaleX(1f); z.setScaleY(1f); z.setTranslationY(0f);
        }
    }

    /** Start floating animations for zzz letters. */
    private void startFullscreenFloatingZAnims() {
        stopFullscreenFloatingZAnims();
        View[] zs = {tvFullscreenZzz1, tvFullscreenZzz2, tvFullscreenZzz3};
        long[] durations = {1600, 1900, 2200};
        float[] amps = {5f, 6f, 7f};
        
        for (int i = 0; i < Math.min(zs.length, durations.length); i++) {
            final View z = zs[i];
            if (z == null) continue;
            final int idx = i;
            ObjectAnimator anim = ObjectAnimator.ofFloat(z, "translationY", 0f, -amps[idx]);
            anim.setDuration(durations[idx]);
            anim.setRepeatCount(ObjectAnimator.INFINITE);
            anim.setRepeatMode(ObjectAnimator.REVERSE);
            anim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            anim.start();
            if (idx == 0) fullscreenFloatingZAnim1 = anim;
            else if (idx == 1) fullscreenFloatingZAnim2 = anim;
            else if (idx == 2) fullscreenFloatingZAnim3 = anim;
        }
    }

    /** Stop floating animations for zzz letters. */
    private void stopFullscreenFloatingZAnims() {
        if (fullscreenFloatingZAnim1 != null) { fullscreenFloatingZAnim1.cancel(); fullscreenFloatingZAnim1 = null; }
        if (fullscreenFloatingZAnim2 != null) { fullscreenFloatingZAnim2.cancel(); fullscreenFloatingZAnim2 = null; }
        if (fullscreenFloatingZAnim3 != null) { fullscreenFloatingZAnim3.cancel(); fullscreenFloatingZAnim3 = null; }
    }

    /** Start blinking loop for awake avatar. */
    private void startFullscreenBlinkLoop() {
        stopFullscreenBlinkLoop();
        scheduleNextFullscreenBlink(2500 + fullscreenBlinkRandom.nextInt(2000));
    }

    /** Schedule the next blink with animation callback. */
    private void scheduleNextFullscreenBlink(long delayMs) {
        fullscreenBlinkRunnable = () -> {
            if (ivFullscreenPreviewAvatar == null || !ivFullscreenPreviewAvatar.isAttachedToWindow() || !fullscreenAvatarLastEnabled) return;
            ivFullscreenPreviewAvatar.setImageResource(resolveFullscreenDrawable(RES_BLINK));
            android.graphics.drawable.Drawable d = ivFullscreenPreviewAvatar.getDrawable();
            if (d instanceof android.graphics.drawable.Animatable2) {
                android.graphics.drawable.Animatable2 avd2 = (android.graphics.drawable.Animatable2) d;
                avd2.clearAnimationCallbacks();
                avd2.registerAnimationCallback(
                    new android.graphics.drawable.Animatable2.AnimationCallback() {
                        @Override public void onAnimationEnd(android.graphics.drawable.Drawable drawable) {
                            if (ivFullscreenPreviewAvatar != null && ivFullscreenPreviewAvatar.isAttachedToWindow() && fullscreenAvatarLastEnabled) {
                                ivFullscreenPreviewAvatar.setImageResource(resolveFullscreenDrawable(RES_IDLE));
                                scheduleNextFullscreenBlink(3000 + fullscreenBlinkRandom.nextInt(2500));
                            }
                        }
                    });
                avd2.start();
            } else if (d instanceof android.graphics.drawable.Animatable) {
                ((android.graphics.drawable.Animatable) d).start();
                fullscreenBlinkHandler.postDelayed(() -> {
                    if (ivFullscreenPreviewAvatar != null && ivFullscreenPreviewAvatar.isAttachedToWindow() && fullscreenAvatarLastEnabled) {
                        ivFullscreenPreviewAvatar.setImageResource(resolveFullscreenDrawable(RES_IDLE));
                        scheduleNextFullscreenBlink(3000 + fullscreenBlinkRandom.nextInt(2500));
                    }
                }, 260);
            }
        };
        fullscreenBlinkHandler.postDelayed(fullscreenBlinkRunnable, delayMs);
    }

    /** Stop blink loop. */
    private void stopFullscreenBlinkLoop() {
        if (fullscreenBlinkRunnable != null) {
            fullscreenBlinkHandler.removeCallbacks(fullscreenBlinkRunnable);
            fullscreenBlinkRunnable = null;
        }
    }

    /** Start breathing animation for sleeping avatar. */
    private void startFullscreenBreathing() {
        if (fullscreenBreathingAnimator != null) fullscreenBreathingAnimator.cancel();
        if (ivFullscreenPreviewAvatar == null) return;

        // Alpha + scale pulse: gentle "inhale/exhale" feel
        fullscreenBreathingAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
        fullscreenBreathingAnimator.setDuration(2600);
        fullscreenBreathingAnimator.setRepeatCount(ValueAnimator.INFINITE);
        fullscreenBreathingAnimator.setRepeatMode(ValueAnimator.REVERSE);
        fullscreenBreathingAnimator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        fullscreenBreathingAnimator.addUpdateListener(a -> {
            if (ivFullscreenPreviewAvatar == null || !ivFullscreenPreviewAvatar.isAttachedToWindow()) return;
            float t = (float) a.getAnimatedValue();
            float alpha = 0.62f + 0.30f * t;
            float scale = 0.96f + 0.04f * t;
            ivFullscreenPreviewAvatar.setAlpha(alpha);
            ivFullscreenPreviewAvatar.setScaleX(scale);
            ivFullscreenPreviewAvatar.setScaleY(scale);
        });
        fullscreenBreathingAnimator.start();

        // Gentle floating bob up/down
        if (fullscreenAvatarFloatAnim != null) fullscreenAvatarFloatAnim.cancel();
        fullscreenAvatarFloatAnim = ObjectAnimator.ofFloat(ivFullscreenPreviewAvatar, "translationY", 0f, -9f);
        fullscreenAvatarFloatAnim.setDuration(3400);
        fullscreenAvatarFloatAnim.setRepeatCount(ObjectAnimator.INFINITE);
        fullscreenAvatarFloatAnim.setRepeatMode(ObjectAnimator.REVERSE);
        fullscreenAvatarFloatAnim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        fullscreenAvatarFloatAnim.start();
    }

    /** Stop breathing animation. */
    private void stopFullscreenBreathing() {
        if (fullscreenBreathingAnimator != null) { fullscreenBreathingAnimator.cancel(); fullscreenBreathingAnimator = null; }
        if (fullscreenAvatarFloatAnim != null) { fullscreenAvatarFloatAnim.cancel(); fullscreenAvatarFloatAnim = null; }
        if (ivFullscreenPreviewAvatar != null) {
            ivFullscreenPreviewAvatar.setAlpha(1.0f);
            ivFullscreenPreviewAvatar.setScaleX(1.0f);
            ivFullscreenPreviewAvatar.setScaleY(1.0f);
            ivFullscreenPreviewAvatar.setTranslationY(0f);
        }
    }

    /**
     * Returns the drawable resource for the given animation state,
     * choosing the color-specific variant when a custom eye color is set.
     */
    private int resolveFullscreenDrawable(int resIndex) {
        if (prefs == null) return FULLSCREEN_DEFAULT_DRAWABLES[resIndex];
        int eyeColor = prefs.sharedPreferences.getInt(
                Constants.PREF_AVATAR_EYE_COLOR, Constants.DEFAULT_AVATAR_EYE_COLOR);
        int[] res = FULLSCREEN_EYE_COLOR_DRAWABLES.get(eyeColor);
        return res != null ? res[resIndex] : FULLSCREEN_DEFAULT_DRAWABLES[resIndex];
    }
}
