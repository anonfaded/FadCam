package com.fadcam.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Range;
import android.util.Rational;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
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
 * Camera controls (AF / Exposure / Zoom) use the <b>exact same</b>
 * {@link PickerBottomSheetFragment} pickers and {@link RecordingControlIntents}
 * as {@link HomeFragment} — no duplicated logic.
 * <p>
 * Controls show/hide: tap the top or bottom 15 % of the screen, or press
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
    private View focusIndicator;
    private TextView btnFullscreenTorch;

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

    // ── Handlers ─────────────────────────────────────────────────────────────
    private final Handler autoHideHandler = new Handler(Looper.getMainLooper());
    private final Handler focusHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoHideRunnable = this::hideControls;

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fullscreen_preview);

        prefs = SharedPreferencesManager.getInstance(this);
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);

        bindViews();
        enterImmersiveMode();
        setupTextureView();
        setupRecordingTiles();     // exact same pattern as HomeFragment
        registerResultListeners(); // exact same result-key listeners
        setupTouchHandling();
        setupCloseButton();
        setupTorchButton();
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
        focusHandler.removeCallbacksAndMessages(null);
        // Release preview → service will stop sending frames to this surface
        sendSurfaceToService(null, -1, -1);
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
    // Initialization
    // ─────────────────────────────────────────────────────────────────────────

    private void bindViews() {
        rootLayout = findViewById(R.id.fullscreenRoot);
        textureView = findViewById(R.id.fullscreenTextureView);
        topBar = findViewById(R.id.topBar);
        bottomBar = findViewById(R.id.bottomBar);
        focusIndicator = findViewById(R.id.focusIndicator);
        btnFullscreenTorch = findViewById(R.id.btnFullscreenTorch);
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
                sendSurfaceToService(null, -1, -1);
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

        // Restore current torch state
        isTorchOn = prefs.sharedPreferences.getBoolean(Constants.PREF_TORCH_STATE, false);
        updateTorchIcon();

        btnFullscreenTorch.setOnClickListener(v -> {
            // Always send toggle to recording service (we're only shown during recording)
            if (isDualRecordingRunning()) {
                // Dual camera → use RecordingService (back cam torch)
                // For dual mode the torch intent is handled by RecordingService
            }
            Intent intent = new Intent(this, RecordingService.class);
            intent.setAction(Constants.INTENT_ACTION_TOGGLE_RECORDING_TORCH);
            try {
                ContextCompat.startForegroundService(this, intent);
                // Optimistic UI update — the broadcast from service will confirm
                isTorchOn = !isTorchOn;
                updateTorchIcon();
            } catch (Exception e) {
                android.util.Log.e(TAG, "Torch toggle failed", e);
            }
        });
    }

    private void updateTorchIcon() {
        if (btnFullscreenTorch == null) return;
        btnFullscreenTorch.setText(isTorchOn ? "flash_on" : "flash_off");
        btnFullscreenTorch.setTextColor(isTorchOn ? 0xFFFFEB3B : 0xFFFFFFFF);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Recording tiles — SAME logic as HomeFragment.setupRecordingTiles()
    // Uses the same PickerBottomSheetFragment, same Constants, same intents.
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

        // ── AF click — same as HomeFragment ──
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

        // ── Exposure click — same as HomeFragment ──
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

        // ── Zoom click — same as HomeFragment ──
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
                    startService(intent);
                    updateExpTileTint();
                });

        // ── AE lock ──
        getSupportFragmentManager().setFragmentResultListener(
                Constants.RK_AE_LOCK, this, (key, result) -> {
                    aeLocked = result.getBoolean(PickerBottomSheetFragment.BUNDLE_SWITCH_STATE, false);
                    Intent intent = RecordingControlIntents.toggleAeLock(this, aeLocked);
                    startService(intent);
                    updateExpTileTint();
                });

        // ── AF mode ──
        getSupportFragmentManager().setFragmentResultListener(
                Constants.RK_AF_MODE, this, (key, result) -> {
                    String selectedId = result.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
                    if (selectedId != null) {
                        try { afMode = Integer.parseInt(selectedId); } catch (NumberFormatException ignored) { return; }
                    }
                    if (tileAfToggle != null) {
                        tileAfToggle.setText(
                                afMode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                                        ? "center_focus_strong" : "center_focus_weak");
                    }
                    Intent intent = RecordingControlIntents.setAfMode(this, afMode);
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
                    startService(intent);
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Camera utilities — same as HomeFragment's helpers
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
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) return id;
                }
            } else {
                String selected = prefs.getSelectedBackCameraId();
                if (selected != null) return selected;
                for (String id : cameraManager.getCameraIdList()) {
                    CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                    Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
                }
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "getCameraIdForType: " + e.getMessage());
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
    // Focus indicator
    // ─────────────────────────────────────────────────────────────────────────

    private void showFocusIndicator(float x, float y) {
        if (focusIndicator == null || rootLayout == null) return;

        int size = (int) (64 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(size, size);
        p.leftMargin = (int) (x - size / 2f);
        p.topMargin = (int) (y - size / 2f);
        focusIndicator.setLayoutParams(p);
        focusIndicator.setBackground(new FocusRingDrawable());
        focusIndicator.setAlpha(1f);
        focusIndicator.setScaleX(1.3f);
        focusIndicator.setScaleY(1.3f);
        focusIndicator.setVisibility(View.VISIBLE);

        focusIndicator.animate().scaleX(1f).scaleY(1f)
                .setDuration(200).setInterpolator(new AccelerateDecelerateInterpolator()).start();

        focusHandler.removeCallbacksAndMessages(null);
        focusHandler.postDelayed(() -> {
            if (focusIndicator == null) return;
            focusIndicator.animate().alpha(0f).setDuration(300)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator a) {
                            focusIndicator.setVisibility(View.GONE);
                        }
                    }).start();
        }, 800);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Surface → service communication
    // ─────────────────────────────────────────────────────────────────────────

    private void sendSurfaceToService(@Nullable Surface surface, int w, int h) {
        Class<?> svc = isDualRecordingRunning()
                ? DualCameraRecordingService.class
                : RecordingService.class;

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

    // ─────────────────────────────────────────────────────────────────────────
    // Focus ring drawable
    // ─────────────────────────────────────────────────────────────────────────

    private static class FocusRingDrawable extends android.graphics.drawable.Drawable {
        private final Paint paint;

        FocusRingDrawable() {
            paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(0xFFFF1744);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(4f);
        }

        @Override public void draw(@NonNull Canvas c) {
            float cx = getBounds().centerX(), cy = getBounds().centerY();
            c.drawCircle(cx, cy, Math.min(cx, cy) - paint.getStrokeWidth(), paint);
        }

        @Override public void setAlpha(int a) { paint.setAlpha(a); }
        @Override public void setColorFilter(@Nullable android.graphics.ColorFilter f) { paint.setColorFilter(f); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
