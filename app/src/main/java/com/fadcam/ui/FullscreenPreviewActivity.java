package com.fadcam.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
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
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import com.fadcam.CameraType;
import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.RecordingControlIntents;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.dualcam.service.DualCameraRecordingService;
import com.fadcam.services.RecordingService;
import com.fadcam.ui.picker.OptionItem;
import com.fadcam.ui.picker.PickerBottomSheetFragment;

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

    // ── Views ────────────────────────────────────────────────────────────────
    private FrameLayout rootLayout;
    private TextureView textureView;
    private View topBar;
    private View bottomBar;
    private TextView btnFullscreenTorch;
    private TextView btnFullscreenCamSwitch;

    // Recording-tile views (from included layout)
    private TextView tileAfToggle;
    private TextView tileExp;
    private TextView tileZoom;

    // ── Surface ──────────────────────────────────────────────────────────────
    private Surface previewSurface;

    // ── State ────────────────────────────────────────────────────────────────
    private SharedPreferencesManager prefs;
    private CameraManager cameraManager;
    private boolean controlsVisible = true;
    private boolean isTorchOn = false;

    // Camera control state — mirrors HomeFragment's fields
    private int currentEvIndex;
    private boolean aeLocked;
    private int afMode;

    // ── Torch broadcast receiver ─────────────────────────────────────────────
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
        setupTouchHandling();
        setupCloseButton();
        setupTorchButton();
        setupCamSwitchButton();
        registerTorchReceiver();
        scheduleAutoHide();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
        if (previewSurface != null && textureView != null && textureView.isAvailable()) {
            sendSurfaceToService(previewSurface,
                    textureView.getWidth(), textureView.getHeight());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        autoHideHandler.removeCallbacks(autoHideRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        autoHideHandler.removeCallbacks(autoHideRunnable);
        unregisterTorchReceiver();
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
    }

    private void setupTextureView() {
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int w, int h) {
                previewSurface = new Surface(st);
                sendSurfaceToService(previewSurface, w, h);
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
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) { /* no-op */ }
        });
    }

    private void setupCloseButton() {
        TextView btnClose = findViewById(R.id.btnClose);
        if (btnClose != null) btnClose.setOnClickListener(v -> finish());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Touch handling — tap-to-focus center, controls toggle on edges / back
    // ─────────────────────────────────────────────────────────────────────────

    private void setupTouchHandling() {
        textureView.setOnTouchListener((v, event) -> {
            if (event.getAction() != MotionEvent.ACTION_UP) return true;

            float y = event.getY();
            float height = v.getHeight();
            float fraction = y / height;

            if (fraction < EDGE_ZONE_FRACTION || fraction > (1f - EDGE_ZONE_FRACTION)) {
                // Edge zone — toggle controls
                if (controlsVisible) {
                    hideControls();
                } else {
                    showControls();
                    scheduleAutoHide();
                }
            } else {
                // Center zone — tap-to-focus
                float normX = event.getX() / v.getWidth();
                float normY = event.getY() / v.getHeight();
                Intent intent = RecordingControlIntents.tapToFocus(this, normX, normY);
                intent.setClass(this, getTargetServiceClass());
                startService(intent);
                showFocusIndicator(event.getX(), event.getY());
            }
            return true;
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
            // Send toggle to correct recording service (single or dual)
            Intent intent = new Intent(this, getTargetServiceClass());
            intent.setAction(Constants.INTENT_ACTION_TOGGLE_RECORDING_TORCH);
            try {
                startService(intent);
            } catch (Exception e) {
                Log.e(TAG, "Torch toggle failed", e);
            }
        });
    }

    private void updateTorchIcon() {
        if (btnFullscreenTorch == null) return;
        btnFullscreenTorch.setText(isTorchOn ? "flash_on" : "flash_off");
        btnFullscreenTorch.setTextColor(isTorchOn ? 0xFFFFEB3B : 0xFFFFFFFF);
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

    // ─────────────────────────────────────────────────────────────────────────
    // Camera switch button — same logic as HomeFragment.switchCamera()
    // ─────────────────────────────────────────────────────────────────────────

    private void setupCamSwitchButton() {
        if (btnFullscreenCamSwitch == null) return;
        btnFullscreenCamSwitch.setOnClickListener(v -> switchCamera());
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

        Intent switchIntent = new Intent(this, RecordingService.class);
        switchIntent.setAction(Constants.INTENT_ACTION_SWITCH_CAMERA);
        switchIntent.putExtra(Constants.INTENT_EXTRA_CAMERA_TYPE_SWITCH, targetType.toString());
        startService(switchIntent);

        Toast.makeText(this,
                "Switching to " + (targetType == CameraType.FRONT ? "front" : "rear") + " camera...",
                Toast.LENGTH_SHORT).show();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Recording tiles — SAME logic as HomeFragment.setupRecordingTiles()
    // ─────────────────────────────────────────────────────────────────────────

    private void setupRecordingTiles() {
        View tilesRoot = findViewById(R.id.includeFullscreenTiles);
        if (tilesRoot == null) return;

        tileAfToggle = tilesRoot.findViewById(R.id.tile_af_toggle);
        tileExp = tilesRoot.findViewById(R.id.tile_exp);
        tileZoom = tilesRoot.findViewById(R.id.tile_zoom);

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
                    if (result.containsKey(PickerBottomSheetFragment.BUNDLE_SLIDER_VALUE)) {
                        currentEvIndex = result.getInt(PickerBottomSheetFragment.BUNDLE_SLIDER_VALUE);
                    } else if (result.containsKey(PickerBottomSheetFragment.BUNDLE_SELECTED_ID)) {
                        try {
                            currentEvIndex = Integer.parseInt(
                                    result.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID, "0"));
                        } catch (NumberFormatException ignored) { }
                    }
                    Intent intent = RecordingControlIntents.setExposureCompensation(this, currentEvIndex);
                    intent.setClass(this, getTargetServiceClass());
                    startService(intent);
                    updateExpTileTint();
                });

        // ── AE lock ──
        getSupportFragmentManager().setFragmentResultListener(
                Constants.RK_AE_LOCK, this, (key, result) -> {
                    aeLocked = result.getBoolean(PickerBottomSheetFragment.BUNDLE_SWITCH_STATE, false);
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

    // ─────────────────────────────────────────────────────────────────────────
    // Tile tinting helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void updateExpTileTint() {
        if (tileExp == null) return;
        boolean modified = currentEvIndex != 0 || aeLocked;
        tileExp.setTextColor(modified ? 0xFFFF9800 : 0xFFFFFFFF);
    }

    private void updateZoomTileTint() {
        if (tileZoom == null) return;
        CameraType cam = prefs.getCameraSelection();
        float zoom = prefs.getSpecificZoomRatio(cam);
        tileZoom.setTextColor(Math.abs(zoom - 1.0f) < 0.01f ? 0xFFFFFFFF : 0xFFFF9800);
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
    }

    private void hideControls() {
        controlsVisible = false;
        autoHideHandler.removeCallbacks(autoHideRunnable);
        animateBar(topBar, false);
        animateBar(bottomBar, false);
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

        if (!isServiceRunning(svc)) return;

        Intent intent = new Intent(this, svc);
        intent.setAction(Constants.INTENT_ACTION_CHANGE_SURFACE);
        if (surface != null) intent.putExtra("SURFACE", surface);
        intent.putExtra("SURFACE_WIDTH", w);
        intent.putExtra("SURFACE_HEIGHT", h);
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
}
