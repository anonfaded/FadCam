package com.fadcam.fadrec.ui;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.Manifest;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.fadrec.MediaProjectionHelper;
import com.fadcam.fadrec.ScreenRecordingState;
import com.fadcam.fadrec.services.ScreenRecordingService;
import com.fadcam.ui.HomeFragment;
import com.fadcam.ui.picker.OptionItem;
import com.fadcam.ui.picker.PickerBottomSheetFragment;
import com.fadcam.ui.utils.AnimatedTextView;
import com.fadcam.utils.ServiceUtils;
import com.fadcam.utils.StorageInfoCache;
import java.util.ArrayList;
import java.util.Locale;
import com.google.android.material.button.MaterialButton;
import android.os.Parcelable;

/**
 * FadRec Home Fragment - Extends HomeFragment for screen recording functionality.
 * Uses inheritance to reuse camera recording UI while adapting for screen recording.
 * 
 * Key differences from parent HomeFragment:
 * - Reuses the same base HomeFragment layout/cards
 * - Hides camera-only controls (camera switch, flash, zoom)
 * - Shows screen resolution instead of camera info
 * - Uses ScreenRecordingService instead of RecordingService
 */
public class FadRecHomeFragment extends HomeFragment {
    
    private static final String TAG = "FadRecHomeFragment";

    // MediaProjection permission handling
    private MediaProjectionHelper mediaProjectionHelper;
    private ActivityResultLauncher<Intent> screenCapturePermissionLauncher;
    private ActivityResultLauncher<String> audioPermissionLauncher;
    private ActivityResultLauncher<Intent> overlayPermissionLauncher;
    
    // Screen recording state
    private ScreenRecordingState screenRecordingState = ScreenRecordingState.NONE;
    private boolean isScreenPreviewEnabled = false;
    private boolean isScreenPreviewOnlyActive = false;
    private boolean pendingPreviewOnlyPermission = false;
    private Surface previewSurface;
    private int previewSurfaceWidth = -1;
    private int previewSurfaceHeight = -1;
    private final android.os.Handler previewUiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean previewOpenSequenceRunning = false;
    private boolean previewCloseSequenceRunning = false;
    private boolean deferredStopPreviewOnly = false;
    private boolean deferredDetachPreviewSurface = false;
    private boolean pendingPreviewOpenUntilSurfaceReady = false;
    private boolean pendingPreviewCloseSleepAnimation = false;
    private SharedPreferencesManager sharedPreferencesManager;
    
    // Broadcast receivers for screen recording state
    private BroadcastReceiver screenRecordingStateReceiver;
    private BroadcastReceiver annotationServiceReceiver;
    
    // Registration flags to prevent double-registration
    private boolean isScreenRecordingReceiverRegistered = false;
    private boolean isAnnotationServiceReceiverRegistered = false;
    
    // Debouncing for button clicks to prevent rapid start/stop
    private long lastClickTime = 0;
    private static final long DEBOUNCE_DELAY_MS = 500; // 500ms debounce
    
    // Timer handler for live updates of elapsed/remaining time
    private android.os.Handler timerHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable timerUpdateRunnable;
    private MaterialButton buttonFadRecMute;
    private boolean screenCardInfoInitialized = false;
    private final Runnable pendingStopStateReconcileRunnable = this::queryScreenRecordingState;
    
    // Material loading dialog for annotation service startup
    private androidx.appcompat.app.AlertDialog loadingDialog;
    private boolean forceMutedNoAudioThisStart = false;
    private boolean isRecordingForcedMuted = false;

    /**
     * Create a new instance of FadRecHomeFragment.
     */
    public static FadRecHomeFragment newInstance() {
        return new FadRecHomeFragment();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // FLog.e(TAG, "============================================");
        // FLog.e(TAG, "FadRecHomeFragment.onCreate() - FRAGMENT CLASS: " + this.getClass().getName());
        // FLog.e(TAG, "============================================");
        
        // Initialize SharedPreferences
        sharedPreferencesManager = SharedPreferencesManager.getInstance(requireContext());
        
        // Initialize MediaProjection helper
        mediaProjectionHelper = new MediaProjectionHelper(requireContext());
        
        // Register broadcast receivers in onCreate for screen recording state
        // This ensures they persist even if the fragment view is recreated,
        // and are ready to receive broadcasts when the app comes to foreground
        registerScreenRecordingReceivers();
        registerAnnotationServiceReceiver();
        
        // Register audio permission launcher
        audioPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            isGranted -> {
                if (isGranted) {
                    // FLog.d(TAG, "Audio permission granted, proceeding with screen capture");
                    requestScreenCapturePermission();
                } else {
                    // FLog.w(TAG, "Audio permission denied, starting without audio");
                    requestScreenCapturePermission();
                }
            }
        );
        
        // Register screen capture permission launcher
        screenCapturePermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                int resultCode = result.getResultCode();
                Intent data = result.getData();
                // FLog.d(TAG, "Screen capture result: resultCode=" + resultCode + 
                //       " (RESULT_OK=" + Activity.RESULT_OK + "), data=" + (data != null ? "present" : "null"));
                
                if (resultCode == Activity.RESULT_OK && data != null) {
                    if (pendingPreviewOnlyPermission) {
                        pendingPreviewOnlyPermission = false;
                        Toast.makeText(
                            requireContext(),
                            com.fadcam.R.string.fullscreen_preview_not_recording,
                            Toast.LENGTH_LONG
                        ).show();
                    } else {
                        // FLog.d(TAG, "Screen capture permission granted, starting recording");
                        // RESULT_OK is -1, so we pass Activity.RESULT_OK constant instead
                        boolean forceNoAudio = forceMutedNoAudioThisStart;
                        isRecordingForcedMuted = forceNoAudio;
                        forceMutedNoAudioThisStart = false;
                        mediaProjectionHelper.startScreenRecording(Activity.RESULT_OK, data, forceNoAudio);
                        syncMuteUiState();
                    }
                } else {
                    pendingPreviewOnlyPermission = false;
                    updateUIForRecordingState();
                    pendingPreviewOnlyPermission = false;
                    // FLog.w(TAG, "Screen capture permission denied: resultCode=" + resultCode);
                    Toast.makeText(requireContext(), 
                        com.fadcam.R.string.fadrec_permission_denied, 
                        Toast.LENGTH_SHORT).show();
                }
            }
        );
        
        // Register overlay permission launcher for floating controls
        overlayPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (android.provider.Settings.canDrawOverlays(requireContext())) {
                    // FLog.d(TAG, "Overlay permission granted");
                    // Enable unified annotation overlay
                    sharedPreferencesManager.setFloatingControlsEnabled(true);
                    // Update switch if view exists
                    if (getView() != null) {
                        View cardFloatingControls = getView().findViewById(com.fadcam.R.id.cardFloatingControls);
                        if (cardFloatingControls != null) {
                            com.fadcam.ui.AvatarToggleView switchFloatingControls = 
                                cardFloatingControls.findViewById(com.fadcam.R.id.switchFloatingControls);
                            if (switchFloatingControls != null) {
                                switchFloatingControls.setChecked(true);
                            }
                        }
                    }
                    startAnnotationService();
                } else {
                    // FLog.w(TAG, "Overlay permission denied");
                    com.fadcam.Utils.showQuickToast(
                        requireContext(),
                        com.fadcam.R.string.floating_controls_permission_needed
                    );
                }
            }
        );
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // FLog.d(TAG, "========== onViewCreated START ==========");
        // FLog.d(TAG, "Calling super.onViewCreated...");
        super.onViewCreated(view, savedInstanceState);
        // FLog.d(TAG, "super.onViewCreated completed");
        
        // Apply FadRec-specific UI customizations
        // FLog.d(TAG, "Customizing UI for screen recording...");
        customizeUIForScreenRecording(view);
        view.post(this::updateScreenRecordingCardInfo);
        
        // Setup button click handlers
        // FLog.d(TAG, "Setting up button handlers...");
        setupButtonHandlers(view);
        // FLog.d(TAG, "========== onViewCreated COMPLETE ==========");
        
        // NOTE: Receiver registration moved to onStart() to avoid double-registration
        // on fragment recreation and to maintain proper lifecycle coordination
        screenCardInfoInitialized = false;
    }
    
    @Override
    public void onStart() {
        super.onStart();
        // FLog.e(TAG, "============================================");
        // FLog.e(TAG, "FadRecHomeFragment.onStart()");
        // FLog.e(TAG, "buttonStartStop: " + buttonStartStop);
        // FLog.e(TAG, "buttonStartStop enabled: " + (buttonStartStop != null ? buttonStartStop.isEnabled() : "NULL"));
        // FLog.e(TAG, "buttonStartStop hasOnClickListeners: " + (buttonStartStop != null ? buttonStartStop.hasOnClickListeners() : "NULL"));
        // FLog.e(TAG, "============================================");
        // Note: Broadcast receivers are now registered in onCreate()
        // to ensure they persist and are ready to receive state updates
        reapplyScreenRecordingCardState(false);
    }
    
    /**
     * Register broadcast receiver to listen for annotation service stop.
     * Includes guard to prevent double-registration on fragment recreation.
     */
    private void registerAnnotationServiceReceiver() {
        // Guard: Don't register twice
        if (isAnnotationServiceReceiverRegistered) {
            FLog.d(TAG, "Annotation service receiver already registered, skipping.");
            return;
        }
        
        // Create receiver if not already created
        if (annotationServiceReceiver == null) {
            annotationServiceReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if ("com.fadcam.fadrec.ANNOTATION_SERVICE_STOPPED".equals(action) || 
                        "com.fadcam.fadrec.ACTION_SERVICE_TERMINATED".equals(action)) {
                        // Service stopped or terminated, turn off the menu switch
                        if (getView() != null) {
                            View cardFloatingControls = getView().findViewById(com.fadcam.R.id.cardFloatingControls);
                            if (cardFloatingControls != null) {
                                com.fadcam.ui.AvatarToggleView switchFloatingControls = 
                                    cardFloatingControls.findViewById(com.fadcam.R.id.switchFloatingControls);
                                if (switchFloatingControls != null) {
                                    switchFloatingControls.setChecked(false);
                                }
                            }
                        }
                        // Update SharedPreferences
                        sharedPreferencesManager.setFloatingControlsEnabled(false);
                        FLog.d(TAG, "Annotation service stopped/terminated - menu switch turned off");
                    } else if ("com.fadcam.fadrec.ANNOTATION_SERVICE_READY".equals(action)) {
                        // Service initialization complete, dismiss loading dialog
                        if (loadingDialog != null && loadingDialog.isShowing()) {
                            loadingDialog.dismiss();
                            FLog.d(TAG, "Annotation service ready - loading dialog dismissed");
                        }
                    } else if ("com.fadcam.fadrec.ANNOTATION_SERVICE_PERMISSION_ERROR".equals(action)) {
                        // Overlay permission error, turn off the menu switch
                        if (getView() != null) {
                            View cardFloatingControls = getView().findViewById(com.fadcam.R.id.cardFloatingControls);
                            if (cardFloatingControls != null) {
                                com.fadcam.ui.AvatarToggleView switchFloatingControls = 
                                    cardFloatingControls.findViewById(com.fadcam.R.id.switchFloatingControls);
                                if (switchFloatingControls != null) {
                                    switchFloatingControls.setChecked(false);
                                }
                            }
                        }
                        // Update SharedPreferences
                        sharedPreferencesManager.setFloatingControlsEnabled(false);
                        FLog.d(TAG, "Overlay permission not granted - menu switch turned off");
                        
                        // Show toast to user
                        if (isAdded()) {
                            android.widget.Toast.makeText(getContext(), 
                                "Overlay permission required for floating controls", 
                                android.widget.Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            };
        }
        
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction("com.fadcam.fadrec.ANNOTATION_SERVICE_STOPPED");
            filter.addAction("com.fadcam.fadrec.ACTION_SERVICE_TERMINATED");
            filter.addAction("com.fadcam.fadrec.ANNOTATION_SERVICE_READY");
            filter.addAction("com.fadcam.fadrec.ANNOTATION_SERVICE_PERMISSION_ERROR");
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                requireContext().registerReceiver(annotationServiceReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED);
            } else {
                androidx.core.content.ContextCompat.registerReceiver(requireContext(), annotationServiceReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
            }
            
            isAnnotationServiceReceiverRegistered = true;
            FLog.d(TAG, "Annotation service receiver registered.");
        } catch (IllegalArgumentException e) {
            FLog.w(TAG, "Error registering annotation service receiver: " + e.getMessage());
            isAnnotationServiceReceiverRegistered = false;
        }
    }
        /**
     * Override parent's method to handle button reset for screen recording mode.
     * In FadRec mode, we don't have camera switch or torch, and pause is always enabled.
     * IMPORTANT: Only reset to idle if truly idle - preserve recording state.
     */
    @Override
    protected void resetUIButtonsToIdleState() {
        FLog.d(TAG, "FadRec: Reset UI to idle state");
        if (!isAdded() || getContext() == null || getView() == null) {
            FLog.w(TAG, "resetUIButtonsToIdleState: Fragment/context unavailable");
            return;
        }
        
        try {
            // Check current recording state before resetting
            ScreenRecordingState currentState = screenRecordingState;
            
            // Only reset to idle if actually idle, otherwise preserve recording state
            if (currentState == ScreenRecordingState.NONE) {
                // Reset Start/Stop button to green "Start Screen Recording" state
                if (buttonStartStop != null) {
                    buttonStartStop.setText(com.fadcam.R.string.fadrec_start_screen_recording);  // ALWAYS use full text
                    buttonStartStop.setIcon(
                        AppCompatResources.getDrawable(getContext(), com.fadcam.R.drawable.play_button_rounded)
                    );
                    buttonStartStop.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(
                            android.graphics.Color.parseColor("#4CAF50")
                        )
                    );
                    buttonStartStop.setEnabled(true);
                    buttonStartStop.setAlpha(1.0f);
                }
                FLog.d(TAG, "FadRec: Start button reset to idle (Start Screen Recording)");
            } else {
                // Recording in progress or paused - keep stop button state
                if (buttonStartStop != null) {
                    buttonStartStop.setText(com.fadcam.R.string.button_stop);
                    buttonStartStop.setIcon(
                        AppCompatResources.getDrawable(getContext(), com.fadcam.R.drawable.stop_rounded)
                    );
                    buttonStartStop.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(
                            androidx.core.content.ContextCompat.getColor(getContext(), com.fadcam.R.color.button_stop)
                        )
                    );
                    buttonStartStop.setEnabled(true);
                    buttonStartStop.setAlpha(1.0f);
                }
                FLog.d(TAG, "FadRec: Start button kept as Stop (recording active: " + currentState + ")");
            }
            
            // Keep pause button ENABLED (different from parent which disables it)
            if (buttonPauseResume != null) {
                buttonPauseResume.setVisibility(View.VISIBLE);
                
                // Enable/disable based on recording state
                if (currentState == ScreenRecordingState.NONE) {
                    // Not recording: gray out pause button
                    buttonPauseResume.setEnabled(false);
                    buttonPauseResume.setAlpha(0.5f);
                } else {
                    // Recording or paused: enable pause button
                    buttonPauseResume.setEnabled(true);
                    buttonPauseResume.setAlpha(1.0f);
                }
                
                // Icon-only, no text label (like FadCam)
                if (currentState == ScreenRecordingState.PAUSED) {
                    buttonPauseResume.setIcon(
                        AppCompatResources.getDrawable(getContext(), com.fadcam.R.drawable.play_button_rounded)
                    );
                } else {
                    buttonPauseResume.setIcon(
                        AppCompatResources.getDrawable(getContext(), com.fadcam.R.drawable.pause_rounded)
                    );
                }
            }
            
            // Keep camera controls HIDDEN (parent makes them visible)
            if (buttonCamSwitch != null) {
                buttonCamSwitch.setVisibility(View.GONE);
            }
            if (buttonTorchSwitch != null) {
                buttonTorchSwitch.setVisibility(View.GONE);
            }
            
            FLog.d(TAG, "FadRec: UI elements reset complete (screen recording mode, state: " + currentState + ")");
        } catch (Exception e) {
            FLog.e(TAG, "Error in resetUIButtonsToIdleState", e);
        }
    }

    /**
     * Override parent's method to prevent camera-specific button state updates.
     * In FadRec mode, start button is always available (no camera dependency).
     */
    @Override
    protected void updateStartButtonAvailability() {
        FLog.e(TAG, "============================================");
        FLog.e(TAG, "FadRecHomeFragment.updateStartButtonAvailability() CALLED");
        FLog.e(TAG, "screenRecordingState: " + screenRecordingState);
        FLog.e(TAG, "============================================");
        
        if (!isAdded() || buttonStartStop == null) {
            FLog.e(TAG, "Button or fragment not available");
            return;
        }
        
        // For screen recording, the Start/Stop button must ALWAYS be enabled:
        // - When idle, user must be able to start
        // - While recording/paused, user must be able to stop
        buttonStartStop.setEnabled(true);
        buttonStartStop.setClickable(true);
        buttonStartStop.setAlpha(1.0f);
        FLog.e(TAG, "!!! BUTTON FORCE ENABLED IN updateStartButtonAvailability !!!");
        FLog.e(TAG, "Button enabled: " + buttonStartStop.isEnabled());
        FLog.e(TAG, "Button alpha: " + buttonStartStop.getAlpha());
        
        // Also ensure camera controls stay hidden
        if (buttonCamSwitch != null) {
            buttonCamSwitch.setVisibility(View.GONE);
        }
    }

    /**
     * Customize the UI for screen recording mode.
     * Hides camera-specific controls and updates info cards.
     */
    private void customizeUIForScreenRecording(View rootView) {
        FLog.d(TAG, "Customizing UI for screen recording");
        
        try {
            // Hide camera-specific controls
            hideCameraControls(rootView);
            
            FLog.d(TAG, "UI customization complete");
        } catch (Exception e) {
            FLog.e(TAG, "Error customizing UI for screen recording", e);
        }
    }

    /**
     * Hide camera-specific controls that are not relevant for screen recording.
     * Clean implementation - our override of resetUIButtonsToIdleState() handles persistence.
     */
    private void hideCameraControls(View rootView) {
        // Hide camera switch button (single call - no timing workarounds needed)
        View camSwitchBtn = rootView.findViewById(com.fadcam.R.id.buttonCamSwitch);
        if (camSwitchBtn != null) {
            camSwitchBtn.setVisibility(View.GONE);
            FLog.d(TAG, "Camera switch button hidden");
        }
        
        // Hide torch button
        View torchBtn = rootView.findViewById(com.fadcam.R.id.buttonTorchSwitch);
        if (torchBtn != null) {
            torchBtn.setVisibility(View.GONE);
            FLog.d(TAG, "Torch button hidden");
        }
        
        // Update camera info card to show screen recording info instead
        updateCardForScreenRecording(rootView);
        configurePreviewCardForScreenRecording(rootView);
        
        // Hide recording tiles (AF, exposure, zoom - camera specific)
        View tileAfToggle = rootView.findViewById(com.fadcam.R.id.tile_af_toggle);
        if (tileAfToggle != null) {
            tileAfToggle.setVisibility(View.GONE);
        }
        View tileExp = rootView.findViewById(com.fadcam.R.id.tile_exp);
        if (tileExp != null) {
            tileExp.setVisibility(View.GONE);
        }
        
        View tileZoom = rootView.findViewById(com.fadcam.R.id.tile_zoom);
        if (tileZoom != null) {
            tileZoom.setVisibility(View.GONE);
        }
        
        // Hide recording controls title (AF · Exposure · Zoom)
        View tvRecordingControlsTitle = rootView.findViewById(com.fadcam.R.id.tv_recording_controls_title);
        if (tvRecordingControlsTitle != null) {
            tvRecordingControlsTitle.setVisibility(View.GONE);
            FLog.d(TAG, "Recording controls title hidden");
        }
        
        // Setup floating controls toggle card in the tiles area
        setupFloatingControlsCard(rootView);
        
        FLog.d(TAG, "Camera controls hidden");
    }

    private void configurePreviewCardForScreenRecording(View rootView) {
        TextView tvPreviewHint = rootView.findViewById(com.fadcam.R.id.tvPreviewHint);
        if (tvPreviewHint != null) {
            tvPreviewHint.setText(com.fadcam.R.string.fadrec_preview_enable_hint);
        }
    }
    
    /**
     * Setup floating controls toggle card in the camera tiles area.
     * Shows toggle switch to enable/disable floating assistive touch overlay.
     */
    private void setupFloatingControlsCard(View rootView) {
        // Inflate floating controls card
        View cardFloatingControls = getLayoutInflater().inflate(
            com.fadcam.R.layout.card_floating_controls,
            null
        );
        
        // Find the parent layout where tiles were (should be tile_af_toggle's parent)
        View tileAfToggle = rootView.findViewById(com.fadcam.R.id.tile_af_toggle);
        android.view.ViewGroup tilesParent = null;
        
        if (tileAfToggle != null && tileAfToggle.getParent() instanceof android.view.ViewGroup) {
            tilesParent = (android.view.ViewGroup) tileAfToggle.getParent();
        }
        
        if (tilesParent != null) {
            // Add the floating controls card at the beginning
            tilesParent.addView(cardFloatingControls, 0);
            
            // Setup switch
            com.fadcam.ui.AvatarToggleView switchFloatingControls = 
                cardFloatingControls.findViewById(com.fadcam.R.id.switchFloatingControls);
            
            if (switchFloatingControls != null) {
                // Set initial state from SharedPreferences
                boolean isEnabled = sharedPreferencesManager.isFloatingControlsEnabled();
                switchFloatingControls.setChecked(isEnabled);
                
                // Handle switch toggle
                switchFloatingControls.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    if (isChecked) {
                        // Request overlay permission if not granted
                        if (!android.provider.Settings.canDrawOverlays(requireContext())) {
                            // Show permission dialog
                            requestOverlayPermission();
                            // Uncheck the switch until permission is granted
                            switchFloatingControls.setChecked(false);
                        } else {
                            // Enable unified annotation overlay
                            sharedPreferencesManager.setFloatingControlsEnabled(true);
                            startAnnotationService();
                        }
                    } else {
                        // Disable annotation overlay
                        sharedPreferencesManager.setFloatingControlsEnabled(false);
                        stopAnnotationService();
                    }
                });
                
                // If already enabled and permission granted, start unified annotation service
                if (isEnabled && android.provider.Settings.canDrawOverlays(requireContext())) {
                    startAnnotationService();
                }
            }

            cardFloatingControls.setOnClickListener(v -> {
                if (switchFloatingControls != null) {
                    switchFloatingControls.performClick();
                }
            });
            
            FLog.d(TAG, "Floating controls card added");
        }
    }
    
    /**
     * Request overlay permission for floating controls.
     * Shows an informative dialog before opening system settings.
     */
    private void requestOverlayPermission() {
        // Show informative Material dialog with proper theming
        androidx.appcompat.app.AlertDialog permissionDialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(com.fadcam.R.string.floating_controls_permission_title)
            .setMessage(com.fadcam.R.string.floating_controls_permission_message)
            .setPositiveButton(com.fadcam.R.string.floating_controls_permission_grant, (dialog, which) -> {
                try {
                    // Check if overlayPermissionLauncher exists before using it
                    if (overlayPermissionLauncher == null) {
                        FLog.w(TAG, "overlayPermissionLauncher is null, opening settings directly");
                        android.content.Intent settingsIntent = new android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + requireContext().getPackageName())
                        );
                        startActivity(settingsIntent);
                        return;
                    }
                    
                    android.content.Intent intent = new android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + requireContext().getPackageName())
                    );
                    overlayPermissionLauncher.launch(intent);
                } catch (Exception e) {
                    FLog.e(TAG, "Error requesting overlay permission: " + e.getMessage(), e);
                    try {
                        // Fallback: Try to open settings directly
                        android.content.Intent settingsIntent = new android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + requireContext().getPackageName())
                        );
                        startActivity(settingsIntent);
                    } catch (Exception e2) {
                        FLog.e(TAG, "Fallback also failed: " + e2.getMessage(), e2);
                        com.fadcam.Utils.showQuickToast(
                            requireContext(),
                            com.fadcam.R.string.floating_controls_permission_needed
                        );
                    }
                }
            })
            .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                dialog.dismiss();
            })
            .setCancelable(true)
            .create();
        
        // Apply hardcoded colors for visibility
        permissionDialog.setOnShowListener(dialog -> {
            // Set background
            permissionDialog.getWindow().getDecorView().setBackgroundColor(0xFF2A2A2A);
            
            // Set title color
            int titleId = getResources().getIdentifier("alertTitle", "id", requireContext().getPackageName());
            if (titleId > 0) {
                android.widget.TextView titleView = permissionDialog.findViewById(titleId);
                if (titleView != null) {
                    titleView.setTextColor(0xFFFFFFFF);
                }
            }
            
            // Set message color
            int messageId = android.R.id.message;
            android.widget.TextView messageView = permissionDialog.findViewById(messageId);
            if (messageView != null) {
                messageView.setTextColor(0xFFE0E0E0);
            }
            
            // Set button colors
            android.widget.Button positiveButton = permissionDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
            android.widget.Button negativeButton = permissionDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE);
            if (positiveButton != null) {
                positiveButton.setTextColor(0xFF4CAF50);
            }
            if (negativeButton != null) {
                negativeButton.setTextColor(0xFF4CAF50);
            }
        });
        
        permissionDialog.show();
    }
    
    /**
     * Start the unified annotation overlay service.
     */
    private void startAnnotationService() {
        try {
            // Show Material Design loading dialog while service initializes
            if (loadingDialog == null || !loadingDialog.isShowing()) {
                // Create Material Design dialog with custom layout
                android.view.LayoutInflater inflater = android.view.LayoutInflater.from(requireContext());
                android.view.View dialogView = inflater.inflate(com.fadcam.R.layout.dialog_loading, null);
                
                loadingDialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setView(dialogView)
                    .setCancelable(false)
                    .create();
                loadingDialog.show();
            }
            
            android.content.Intent intent = new android.content.Intent(
                requireContext(),
                AnnotationService.class
            );
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                requireContext().startForegroundService(intent);
            } else {
                requireContext().startService(intent);
            }
            FLog.d(TAG, "Annotation service started");
        } catch (Exception e) {
            FLog.e(TAG, "Error starting annotation service", e);
            if (loadingDialog != null && loadingDialog.isShowing()) {
                loadingDialog.dismiss();
            }
        }
    }
    
    /**
     * Stop the unified annotation overlay service.
     */
    private void stopAnnotationService() {
        try {
            android.content.Intent intent = new android.content.Intent(
                requireContext(),
                AnnotationService.class
            );
            requireContext().stopService(intent);
            FLog.d(TAG, "Annotation service stopped");
        } catch (Exception e) {
            FLog.e(TAG, "Error stopping annotation service", e);
        }
    }
    
    /**
     * Start the floating controls overlay service.
     * @deprecated Use startAnnotationService() instead - old service is no longer used
     */
    @Deprecated
    private void startFloatingControlsService() {
        // Redirect to unified annotation service
        startAnnotationService();
    }
    
    /**
     * Stop the floating controls overlay service.
     * @deprecated Use stopAnnotationService() instead - old service is no longer used
     */
    @Deprecated
    private void stopFloatingControlsService() {
        // Redirect to unified annotation service
        stopAnnotationService();
    }
    
    /**
     * Update the camera info card to show screen recording information.
     * Modifies icon, title, and subtitle to reflect screen recording mode.
     * Uses OOP inheritance - overrides parent's camera card with screen recording info.
     */
    private void updateCardForScreenRecording(View rootView) {
        // Post to ensure parent's view initialization is complete
        rootView.post(() -> {
            // Find card elements using the original camera IDs (parent's elements)
            View oldIconView = rootView.findViewById(com.fadcam.R.id.ivCameraIcon);
            TextView tvCameraTitle = rootView.findViewById(com.fadcam.R.id.tvCameraTitle);
            TextView tvCameraSubtitle = rootView.findViewById(com.fadcam.R.id.tvCameraSubtitle);
            
            if (oldIconView != null && oldIconView.getParent() instanceof android.view.ViewGroup) {
                // Replace TextView icon with ImageView for PNG drawable
                android.view.ViewGroup parent = (android.view.ViewGroup) oldIconView.getParent();
                int index = parent.indexOfChild(oldIconView);
                
                // Create new ImageView for screen recording icon
                android.widget.ImageView ivScreenRecordIcon = new android.widget.ImageView(requireContext());
                ivScreenRecordIcon.setId(com.fadcam.R.id.ivCameraIcon); // Keep same ID
                
                // Set layout params (same as original TextView)
                // Use 28dp for icon to match other icons in the card and add small padding
                final float d = getResources().getDisplayMetrics().density;
                int size = (int) (28 * d);
                android.view.ViewGroup.MarginLayoutParams layoutParams = 
                    new android.view.ViewGroup.MarginLayoutParams(size, size);
                layoutParams.setMarginEnd((int) (4 * d));
                ivScreenRecordIcon.setLayoutParams(layoutParams);
                
                // Set the PNG drawable
                ivScreenRecordIcon.setImageResource(com.fadcam.R.drawable.screen_recorder);
                
                // Apply tint (green color matching camera icon)
                ivScreenRecordIcon.setColorFilter(
                    ContextCompat.getColor(requireContext(), com.fadcam.R.color.greenPastel),
                    android.graphics.PorterDuff.Mode.SRC_IN
                );
                
                ivScreenRecordIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                ivScreenRecordIcon.setPadding((int)(4*d), (int)(4*d), (int)(4*d), (int)(4*d));
                
                // Replace the old TextView with new ImageView
                parent.removeViewAt(index);
                parent.addView(ivScreenRecordIcon, index);
                ivScreenRecordIcon.setAlpha(0f);
                ivScreenRecordIcon.setScaleX(0.9f);
                ivScreenRecordIcon.setScaleY(0.9f);
                ivScreenRecordIcon.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start();
                
                FLog.d(TAG, "Card icon replaced with screen_recorder.png");
            }
            
            if (tvCameraTitle != null) {
                // Get device screen resolution
                android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
                if (getActivity() != null) {
                    getActivity().getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
                    int width = metrics.widthPixels;
                    int height = metrics.heightPixels;
                    
                    // Update title to show screen recording info
                    if (tvCameraTitle instanceof AnimatedTextView) {
                        ((AnimatedTextView) tvCameraTitle).animateSlotFull("Screen Recording", 400);
                    } else {
                        tvCameraTitle.setText("Screen Recording");
                    }
                    FLog.d(TAG, "Card title updated to Screen Recording");
                    
                    // Update subtitle with device screen resolution and fps
                    if (tvCameraSubtitle != null) {
                        String subtitle = width + "x" + height + " • 30fps";
                        if (tvCameraSubtitle instanceof AnimatedTextView) {
                            ((AnimatedTextView) tvCameraSubtitle).animateSlotFull(subtitle, 400);
                        } else {
                            tvCameraSubtitle.setText(subtitle);
                        }
                        FLog.d(TAG, "Card subtitle updated to: " + subtitle);
                    }
                }
            }
        });
    }

    /**
     * Update camera info card with screen recording text (called during timer updates).
     * This prevents parent's updateStorageInfo from resetting it back to camera info.
     */
    private void updateScreenRecordingCardInfo() {
        if (!isAdded() || getView() == null) {
            return;
        }
        
        try {
            View rootView = getView();
            TextView tvCameraTitle = rootView.findViewById(com.fadcam.R.id.tvCameraTitle);
            TextView tvCameraSubtitle = rootView.findViewById(com.fadcam.R.id.tvCameraSubtitle);
            boolean animate = !screenCardInfoInitialized;
            
            if (tvCameraTitle != null) {
                if (animate && tvCameraTitle instanceof AnimatedTextView) {
                    ((AnimatedTextView) tvCameraTitle).animateSlotFull("Screen Recording", 400);
                } else {
                    tvCameraTitle.setText("Screen Recording");
                }
            }
            
            if (tvCameraSubtitle != null) {
                // Get device screen resolution
                android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
                if (getActivity() != null) {
                    getActivity().getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
                    int width = metrics.widthPixels;
                    int height = metrics.heightPixels;
                    String subtitle = width + "x" + height + " • 30fps";
                    if (animate && tvCameraSubtitle instanceof AnimatedTextView) {
                        ((AnimatedTextView) tvCameraSubtitle).animateSlotFull(subtitle, 400);
                    } else {
                        tvCameraSubtitle.setText(subtitle);
                    }
                }
            }
            screenCardInfoInitialized = true;
        } catch (Exception e) {
            FLog.e(TAG, "Error updating screen recording card info", e);
        }
    }

    /**
     * Setup button click handlers for start/stop/pause/resume.
     * No need to force-enable buttons - our override handles that.
     */
    private void setupButtonHandlers(View rootView) {
        FLog.d(TAG, "========== setupButtonHandlers() CALLED ==========");
        
        // Use inherited protected fields from parent HomeFragment instead of local variables
        // This ensures we override the parent's camera click listener with screen recording logic
        buttonStartStop = rootView.findViewById(com.fadcam.R.id.buttonStartStop);
        buttonPauseResume = rootView.findViewById(com.fadcam.R.id.buttonPauseResume);
        buttonFadRecMute = rootView.findViewById(com.fadcam.R.id.buttonFadRecMute);
        
        FLog.d(TAG, "buttonStartStop found: " + (buttonStartStop != null));
        FLog.d(TAG, "buttonPauseResume found: " + (buttonPauseResume != null));
        
        // NOTE: Don't load persisted state here - it interferes with broadcast-based state
        // State will be loaded from broadcasts or set to NONE if no broadcasts arrive
        
        // Start/Stop button
        if (buttonStartStop != null) {
            buttonStartStop.setOnClickListener(v -> {
                // FLog.d(TAG, "=== FADREC START/STOP BUTTON CLICKED ===");
                // FLog.d(TAG, "Current screenRecordingState: " + screenRecordingState);
                
                // Debounce rapid clicks
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastClickTime < DEBOUNCE_DELAY_MS) {
                    FLog.d(TAG, "Button click ignored (debounced)");
                    return;
                }
                lastClickTime = currentTime;
                
                if (screenRecordingState == ScreenRecordingState.NONE) {
                    if (buttonPauseResume != null) {
                        buttonPauseResume.setEnabled(false);
                        buttonPauseResume.setAlpha(0.5f);
                    }
                    requestScreenRecordingPermissionAndStart();
                } else if (screenRecordingState != ScreenRecordingState.STOPPING) {
                    renderPrimaryButtonForRecordingState(false);
                    if (buttonPauseResume != null) {
                        buttonPauseResume.setEnabled(false);
                        buttonPauseResume.setAlpha(0.5f);
                    }
                    stopScreenRecording();
                }
            });
            
            FLog.e(TAG, "============================================");
            FLog.e(TAG, "Click listener SET on Start/Stop button");
            FLog.e(TAG, "Button now has onClickListener: " + buttonStartStop.hasOnClickListeners());
            FLog.e(TAG, "Button enabled: " + buttonStartStop.isEnabled());
            FLog.e(TAG, "Button clickable: " + buttonStartStop.isClickable());
            FLog.e(TAG, "============================================");
        } else {
            FLog.e(TAG, "ERROR: buttonStartStop is NULL!");
        }
        
        // Pause/Resume button
        if (buttonPauseResume != null) {
            buttonPauseResume.setOnClickListener(v -> {
                // Debounce rapid clicks
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastClickTime < DEBOUNCE_DELAY_MS) {
                    FLog.d(TAG, "Button click ignored (debounced)");
                    return;
                }
                lastClickTime = currentTime;
                
                if (screenRecordingState == ScreenRecordingState.IN_PROGRESS) {
                    // Pause recording
                    buttonPauseResume.setEnabled(false);
                    mediaProjectionHelper.pauseScreenRecording();
                } else if (screenRecordingState == ScreenRecordingState.PAUSED) {
                    // Resume recording
                    buttonPauseResume.setEnabled(false);
                    mediaProjectionHelper.resumeScreenRecording();
                }
            });
            FLog.d(TAG, "Click listener successfully attached to buttonStartStop");
        } else {
            FLog.e(TAG, "buttonStartStop is NULL - cannot setup click listener!");
        }

        if (buttonFadRecMute != null) {
            buttonFadRecMute.setVisibility(View.VISIBLE);
            buttonFadRecMute.setOnClickListener(v -> {
                boolean isRecordingActive = screenRecordingState == ScreenRecordingState.IN_PROGRESS
                    || screenRecordingState == ScreenRecordingState.PAUSED;
                
                if (isRecordingActive) {
                    // During recording: show mute toggle only
                    showAudioMuteToggle();
                } else {
                    // Show audio source picker when not recording
                    showAudioSourcePicker();
                }
            });
            updateMuteButtonUi();
        }
        
        FLog.d(TAG, "========== SETUP BUTTON HANDLERS COMPLETE ==========");

        // Safety: HomeFragment can disable this button based on camera resources.
        // FadRec must keep it enabled at all times.
        try {
            if (buttonStartStop != null) {
                buttonStartStop.setEnabled(true);
                buttonStartStop.setClickable(true);
                buttonStartStop.setAlpha(1.0f);
            }
        } catch (Exception e) {
            FLog.w(TAG, "Failed to force-enable Start/Stop button", e);
        }
    }
    
    /**
     * Load persisted recording state from SharedPreferences.
     */
    private void loadPersistedRecordingState() {
        String persistedState = sharedPreferencesManager.getScreenRecordingState();
        try {
            screenRecordingState = ScreenRecordingState.valueOf(persistedState);
            FLog.d(TAG, "Loaded persisted state: " + screenRecordingState);
            updateUIForRecordingState();
        } catch (Exception e) {
            FLog.e(TAG, "Failed to load persisted state, defaulting to NONE", e);
            screenRecordingState = ScreenRecordingState.NONE;
        }
    }
    
    /**
     * Persist recording state to SharedPreferences.
     */
    private void persistRecordingState(ScreenRecordingState state) {
        sharedPreferencesManager.setScreenRecordingState(state.name());
        FLog.d(TAG, "Persisted state: " + state);
    }

    /**
     * Request MediaProjection permission and start screen recording.
     */
    private void requestScreenRecordingPermissionAndStart() {
        FLog.d(TAG, "Requesting screen recording permission");
        
        if (!mediaProjectionHelper.isAvailable()) {
            Toast.makeText(requireContext(), 
                "Screen recording not available on this device", 
                Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Check audio permission first if audio source requires it
        String audioSource = sharedPreferencesManager.getScreenRecordingAudioSource();
        if (Constants.AUDIO_SOURCE_MIC.equals(audioSource)
                || Constants.AUDIO_SOURCE_INTERNAL.equals(audioSource)) {
            if (!isMicPermissionGranted()) {
                FLog.d(TAG, "Audio permission not granted - showing muted recording dialog (source: " + audioSource + ")");
                showMicPermissionDeniedDialog();
                return;
            }
        }
        
        // Internal audio requires API 29+
        if (Constants.AUDIO_SOURCE_INTERNAL.equals(audioSource)
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            FLog.w(TAG, "Internal audio requires Android 10+ - falling back to mic");
            sharedPreferencesManager.setScreenRecordingAudioSource(Constants.AUDIO_SOURCE_MIC);
        }
        
        // Audio permission granted or not needed, proceed with screen capture
        requestScreenCapturePermission();
    }
    
    /**
     * Request screen capture permission.
     */
    private void requestScreenCapturePermission() {
        FLog.d(TAG, "Requesting screen capture permission");
        
        try {
            Intent permissionIntent = mediaProjectionHelper.createScreenCaptureIntent();
            if (permissionIntent != null) {
                screenCapturePermissionLauncher.launch(permissionIntent);
            } else {
                Toast.makeText(requireContext(), 
                    "Failed to create screen capture intent", 
                    Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            FLog.e(TAG, "Error requesting screen recording permission", e);
            Toast.makeText(requireContext(), 
                "Error starting screen recording", 
                Toast.LENGTH_SHORT).show();
        }
    }

    private void showMicPermissionDeniedDialog() {
        if (!isAdded()) return;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(com.fadcam.R.string.fadrec_mic_permission_needed_title)
            .setMessage(com.fadcam.R.string.fadrec_mic_permission_needed_message)
            .setPositiveButton(com.fadcam.R.string.fadrec_record_muted, (dialog, which) -> {
                forceMutedNoAudioThisStart = true;
                if (sharedPreferencesManager != null) {
                    sharedPreferencesManager.setScreenRecordingMuted(true);
                }
                syncMuteUiState();
                requestScreenCapturePermission();
            })
            .setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.dismiss())
            .show();
    }

    private boolean isMicPermissionGranted() {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isAudioEffectivelyAvailable() {
        if (sharedPreferencesManager == null) return false;
        String audioSource = sharedPreferencesManager.getScreenRecordingAudioSource();
        boolean prefersAudio = Constants.AUDIO_SOURCE_MIC.equals(audioSource)
            || Constants.AUDIO_SOURCE_INTERNAL.equals(audioSource);
        return prefersAudio && isMicPermissionGranted() && !isRecordingForcedMuted;
    }

    /**
     * Handle recording start from overlay without bringing app to foreground.
     * Launches TransparentPermissionActivity to request permission without activating main app.
     */
    private void handleOverlayRecordingStart() {
        FLog.d(TAG, "handleOverlayRecordingStart: Launching transparent permission activity");
        
        // Launch TransparentPermissionActivity to handle permission request
        // This activity is transparent and won't bring the main app to foreground
        Intent intent = new Intent(requireContext(), com.fadcam.fadrec.ui.TransparentPermissionActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    /**
     * Stop screen recording.
     */
    private void stopScreenRecording() {
        FLog.d(TAG, "Stopping screen recording");
        timerHandler.removeCallbacks(pendingStopStateReconcileRunnable);
        mediaProjectionHelper.stopScreenRecording();
        timerHandler.postDelayed(pendingStopStateReconcileRunnable, 900L);
    }

    private void renderPrimaryButtonForRecordingState(boolean recordingActive) {
        if (!isAdded() || buttonStartStop == null) {
            return;
        }

        applyButtonTransition(
            buttonStartStop,
            recordingActive
                ? getString(com.fadcam.R.string.button_stop)
                : getString(com.fadcam.R.string.fadrec_start_screen_recording),
            AppCompatResources.getDrawable(
                requireContext(),
                recordingActive
                    ? com.fadcam.R.drawable.stop_rounded
                    : com.fadcam.R.drawable.play_button_rounded
            ),
            () -> {
                buttonStartStop.setBackgroundTintList(
                    recordingActive
                        ? ContextCompat.getColorStateList(requireContext(), com.fadcam.R.color.button_stop)
                        : ColorStateList.valueOf(Color.parseColor("#4CAF50"))
                );
                buttonStartStop.setAlpha(1.0f);
            }
        );
    }

    private void queryScreenRecordingState() {
        if (!isAdded()) {
            return;
        }
        try {
            Intent queryIntent = new Intent(requireContext(), ScreenRecordingService.class);
            queryIntent.setAction(Constants.INTENT_ACTION_QUERY_SCREEN_RECORDING_STATE);
            requireContext().startService(queryIntent);
        } catch (Exception e) {
            FLog.w(TAG, "Failed to query ScreenRecordingService state", e);
        }
    }

    /**
     * Register broadcast receivers for screen recording state changes.
     */
    private void registerScreenRecordingReceivers() {
        // Guard: Don't register twice
        if (isScreenRecordingReceiverRegistered) {
            // FLog.d(TAG, "Screen recording receiver already registered, skipping.");
            return;
        }
        
        if (screenRecordingStateReceiver == null) {
            screenRecordingStateReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    // FLog.d(TAG, "screenRecordingStateReceiver.onReceive: action=" + action + 
                    //            ", Android=" + android.os.Build.VERSION.SDK_INT);
                    if (action == null) return;
                    
                    switch (action) {
                        case Constants.BROADCAST_ON_SCREEN_RECORDING_STARTED:
                            // FLog.d(TAG, "Broadcast: SCREEN_RECORDING_STARTED");
                            timerHandler.removeCallbacks(pendingStopStateReconcileRunnable);
                            screenRecordingState = ScreenRecordingState.IN_PROGRESS;
                            isScreenPreviewOnlyActive = false;
                            persistRecordingState(screenRecordingState);
                            updateUIForRecordingState();
                            syncMuteUiState();
                            // Toast.makeText(context, com.fadcam.R.string.fadrec_screen_recording_started, 
                            //     Toast.LENGTH_SHORT).show();
                            break;
                            
                        case Constants.BROADCAST_ON_SCREEN_RECORDING_STOPPED:
                            // FLog.d(TAG, "Broadcast: SCREEN_RECORDING_STOPPED");
                            timerHandler.removeCallbacks(pendingStopStateReconcileRunnable);
                            screenRecordingState = ScreenRecordingState.NONE;
                            isScreenPreviewOnlyActive = false;
                            isScreenPreviewEnabled = false;
                            pendingPreviewOpenUntilSurfaceReady = false;
                            pendingPreviewCloseSleepAnimation = false;
                            persistRecordingState(screenRecordingState);
                            isRecordingForcedMuted = false;
                            updateUIForRecordingState();
                            syncMuteUiState();
                            // Toast.makeText(context, com.fadcam.R.string.fadrec_screen_recording_stopped, 
                            //     Toast.LENGTH_SHORT).show();
                            break;
                            
                        case Constants.BROADCAST_ON_SCREEN_RECORDING_PAUSED:
                            // FLog.d(TAG, "Broadcast: SCREEN_RECORDING_PAUSED");
                            timerHandler.removeCallbacks(pendingStopStateReconcileRunnable);
                            screenRecordingState = ScreenRecordingState.PAUSED;
                            isScreenPreviewOnlyActive = false;
                            persistRecordingState(screenRecordingState);
                            updateUIForRecordingState();
                            syncMuteUiState();
                            // Toast.makeText(context, com.fadcam.R.string.fadrec_screen_recording_paused, 
                            //     Toast.LENGTH_SHORT).show();
                            break;
                            
                        case Constants.BROADCAST_ON_SCREEN_RECORDING_RESUMED:
                            // FLog.d(TAG, "Broadcast: SCREEN_RECORDING_RESUMED");
                            timerHandler.removeCallbacks(pendingStopStateReconcileRunnable);
                            screenRecordingState = ScreenRecordingState.IN_PROGRESS;
                            isScreenPreviewOnlyActive = false;
                            persistRecordingState(screenRecordingState);
                            updateUIForRecordingState();
                            syncMuteUiState();
                            // Toast.makeText(context, com.fadcam.R.string.fadrec_screen_recording_resumed, 
                            //     Toast.LENGTH_SHORT).show();
                            break;

                        case Constants.BROADCAST_ON_SCREEN_RECORDING_STATE_CALLBACK:
                            String stateName = intent.getStringExtra("recordingState");
                            if (stateName != null) {
                                try {
                                    ScreenRecordingState callbackState = ScreenRecordingState.valueOf(stateName);
                                    if (callbackState != ScreenRecordingState.STOPPING) {
                                        screenRecordingState = callbackState;
                                    }
                                    persistRecordingState(screenRecordingState);
                                } catch (IllegalArgumentException ignored) {
                                }
                            }
                            boolean previewOnlyActive = intent.getBooleanExtra(
                                Constants.EXTRA_SCREEN_PREVIEW_ONLY_ACTIVE,
                                false
                            );
                            isScreenPreviewOnlyActive = previewOnlyActive;
                            isScreenPreviewEnabled = intent.getBooleanExtra(
                                Constants.EXTRA_SCREEN_PREVIEW_ENABLED,
                                isScreenPreviewEnabled
                            );
                            if (screenRecordingState == ScreenRecordingState.NONE) {
                                timerHandler.removeCallbacks(pendingStopStateReconcileRunnable);
                            }
                            updateUIForRecordingState();
                            syncMuteUiState();
                            break;
                            
                        // Handle overlay actions
                        case Constants.ACTION_START_SCREEN_RECORDING_FROM_OVERLAY:
                            // FLog.d(TAG, "Received ACTION_START_SCREEN_RECORDING_FROM_OVERLAY");
                            if (screenRecordingState == ScreenRecordingState.NONE) {
                                // When called from overlay while app is in background,
                                // we need to handle this differently to avoid bringing app to foreground
                                handleOverlayRecordingStart();
                            }
                            break;
                        
                        // Handle permission results from TransparentPermissionActivity
                        case Constants.ACTION_SCREEN_RECORDING_PERMISSION_GRANTED:
                            // FLog.d(TAG, "Received ACTION_SCREEN_RECORDING_PERMISSION_GRANTED");
                            // Permission granted, start recording with the provided Intent
                            Intent permissionData = intent.getParcelableExtra("data");
                            if (permissionData == null) {
                                permissionData = intent.getParcelableExtra("mediaProjectionData");
                            }
                            if (permissionData != null && mediaProjectionHelper != null) {
                                int resultCode = intent.getIntExtra("resultCode", -1);
                                // FLog.d(TAG, "Starting recording with resultCode: " + resultCode);
                                mediaProjectionHelper.startScreenRecording(resultCode, permissionData, false);
                            } else {
                                // FLog.e(TAG, "Permission granted but data or helper is null");
                            }
                            break;
                            
                        case Constants.ACTION_SCREEN_RECORDING_PERMISSION_DENIED:
                            // FLog.d(TAG, "Received ACTION_SCREEN_RECORDING_PERMISSION_DENIED");
                            pendingPreviewOnlyPermission = false;
                            pendingPreviewOpenUntilSurfaceReady = false;
                            updateUIForRecordingState();
                            Toast.makeText(context, "Screen recording permission denied", Toast.LENGTH_SHORT).show();
                            break;
                            
                        case Constants.ACTION_PAUSE_SCREEN_RECORDING:
                            // FLog.d(TAG, "Received ACTION_PAUSE_SCREEN_RECORDING");
                            if (screenRecordingState == ScreenRecordingState.IN_PROGRESS) {
                                if (mediaProjectionHelper != null) {
                                    mediaProjectionHelper.pauseScreenRecording();
                                }
                            }
                            break;
                            
                        case Constants.ACTION_RESUME_SCREEN_RECORDING:
                            // FLog.d(TAG, "Received ACTION_RESUME_SCREEN_RECORDING");
                            if (screenRecordingState == ScreenRecordingState.PAUSED) {
                                if (mediaProjectionHelper != null) {
                                    mediaProjectionHelper.resumeScreenRecording();
                                }
                            }
                            break;
                            
                        case Constants.ACTION_STOP_SCREEN_RECORDING:
                            // FLog.d(TAG, "Received ACTION_STOP_SCREEN_RECORDING");
                            if (screenRecordingState != ScreenRecordingState.NONE) {
                                if (mediaProjectionHelper != null) {
                                    mediaProjectionHelper.stopScreenRecording();
                                }
                            }
                            break;
                        case Constants.BROADCAST_ON_SCREEN_RECORDING_MUTE_CHANGED:
                            syncMuteUiState();
                            break;
                    }
                }
            };
        }
        
        try {
            // Register for all screen recording broadcasts
            IntentFilter filter = new IntentFilter();
            filter.addAction(Constants.BROADCAST_ON_SCREEN_RECORDING_STARTED);
            filter.addAction(Constants.BROADCAST_ON_SCREEN_RECORDING_STOPPED);
            filter.addAction(Constants.BROADCAST_ON_SCREEN_RECORDING_PAUSED);
            filter.addAction(Constants.BROADCAST_ON_SCREEN_RECORDING_RESUMED);
            filter.addAction(Constants.BROADCAST_ON_SCREEN_RECORDING_STATE_CALLBACK);
            filter.addAction(Constants.BROADCAST_ON_SCREEN_RECORDING_MUTE_CHANGED);
            // Add overlay actions
            filter.addAction(Constants.ACTION_START_SCREEN_RECORDING_FROM_OVERLAY);
            filter.addAction(Constants.ACTION_PAUSE_SCREEN_RECORDING);
            filter.addAction(Constants.ACTION_RESUME_SCREEN_RECORDING);
            filter.addAction(Constants.ACTION_STOP_SCREEN_RECORDING);
            // Add permission result actions from TransparentPermissionActivity
            filter.addAction(Constants.ACTION_SCREEN_RECORDING_PERMISSION_GRANTED);
            filter.addAction(Constants.ACTION_SCREEN_RECORDING_PERMISSION_DENIED);
            
            // Use LocalBroadcastManager for guaranteed delivery on Android 12+
            // This bypasses the background app broadcast restrictions
            LocalBroadcastManager.getInstance(requireContext()).registerReceiver(screenRecordingStateReceiver, filter);
            
            isScreenRecordingReceiverRegistered = true;
            // FLog.d(TAG, "Screen recording broadcast receivers registered via LocalBroadcastManager");
        } catch (IllegalArgumentException e) {
            // FLog.w(TAG, "Error registering screen recording receiver: " + e.getMessage());
            isScreenRecordingReceiverRegistered = false;
        }
    }

    /**
     * Update UI based on current recording state.
     */
    private void updateUIForRecordingState() {
        View rootView = getView();
        if (rootView == null) {
            // FLog.w(TAG, "updateUIForRecordingState: rootView is null, skipping state update");
            return;
        }
        
        // FLog.d(TAG, "updateUIForRecordingState: screenRecordingState=" + screenRecordingState + 
        //            ", Android=" + android.os.Build.VERSION.SDK_INT);
        
        MaterialButton buttonStartStop = rootView.findViewById(com.fadcam.R.id.buttonStartStop);
        MaterialButton buttonPauseResume = rootView.findViewById(com.fadcam.R.id.buttonPauseResume);
        
        if (buttonStartStop == null) {
            // FLog.e(TAG, "updateUIForRecordingState: buttonStartStop is null!");
        }
        
        // Update Start/Stop button with animation
        if (buttonStartStop != null) {
            // Never allow this button to be disabled in FadRec.
            buttonStartStop.setEnabled(true);
            buttonStartStop.setClickable(true);
            buttonStartStop.setAlpha(1.0f);
            applyButtonTransition(
                buttonStartStop,
                screenRecordingState == ScreenRecordingState.NONE
                    ? getString(com.fadcam.R.string.fadrec_start_screen_recording)
                    : getString(com.fadcam.R.string.button_stop),
                AppCompatResources.getDrawable(
                    requireContext(),
                    screenRecordingState == ScreenRecordingState.NONE
                        ? com.fadcam.R.drawable.play_button_rounded
                        : com.fadcam.R.drawable.stop_rounded
                ),
                () -> buttonStartStop.setBackgroundTintList(
                    screenRecordingState == ScreenRecordingState.NONE
                        ? ColorStateList.valueOf(Color.parseColor("#4CAF50"))
                        : ContextCompat.getColorStateList(requireContext(), com.fadcam.R.color.button_stop)
                )
            );
        }
        
        // Update Pause/Resume button (always visible, icon-only like FadCam)
        if (buttonPauseResume != null) {
            buttonPauseResume.setVisibility(View.VISIBLE); // Always visible
            
            // Enable/disable based on recording state
            if (screenRecordingState == ScreenRecordingState.NONE) {
                // Not recording: gray out pause button
                buttonPauseResume.setEnabled(false);
                buttonPauseResume.setAlpha(0.5f);
                buttonPauseResume.setIcon(
                    AppCompatResources.getDrawable(requireContext(), com.fadcam.R.drawable.pause_rounded)
                );
            } else if (screenRecordingState == ScreenRecordingState.IN_PROGRESS) {
                // Recording: Enable with pause icon (no text label)
                buttonPauseResume.setEnabled(true);
                buttonPauseResume.setAlpha(1.0f);
                buttonPauseResume.setIcon(
                    AppCompatResources.getDrawable(requireContext(), com.fadcam.R.drawable.pause_rounded)
                );
            } else if (screenRecordingState == ScreenRecordingState.PAUSED) {
                // Paused: Enable with resume/play icon (no text label)
                buttonPauseResume.setEnabled(true);
                buttonPauseResume.setAlpha(1.0f);
                buttonPauseResume.setIcon(
                    AppCompatResources.getDrawable(requireContext(), com.fadcam.R.drawable.play_button_rounded)
                );
            }
        }

        if (buttonFadRecMute != null) {
            buttonFadRecMute.setVisibility(View.VISIBLE);
            syncMuteUiState();
        }

        if (screenRecordingState == ScreenRecordingState.NONE && !isScreenPreviewOnlyActive) {
            isScreenPreviewEnabled = false;
        }
        refreshElapsedHeroAppearance();
        updateModeSpecificPreviewVisibility();
        
        FLog.d(TAG, "UI updated for state: " + screenRecordingState);
        
        // Start/stop timer updates based on state
        if (screenRecordingState == ScreenRecordingState.IN_PROGRESS) {
            // Recording in progress - start live timer updates
            startTimerUpdates();
        } else if (screenRecordingState == ScreenRecordingState.PAUSED) {
            // Recording paused - stop timer but keep last value visible
            stopTimerUpdates();
            // Update one last time to show paused value
            updateStorageInfo();
        } else {
            // Not recording - stop timer updates
            stopTimerUpdates();
        }
    }
    
    private void updateMuteButtonUi() {
        if (buttonFadRecMute == null || !isAdded()) return;
        String audioSource = sharedPreferencesManager != null
            ? sharedPreferencesManager.getScreenRecordingAudioSource()
            : Constants.AUDIO_SOURCE_MIC;
        boolean muted = sharedPreferencesManager != null && sharedPreferencesManager.isScreenRecordingMuted();
        boolean isRecordingActive = screenRecordingState == ScreenRecordingState.IN_PROGRESS
            || screenRecordingState == ScreenRecordingState.PAUSED;
        
        // Choose icon based on audio source and mute state
        int iconRes;
        if (Constants.AUDIO_SOURCE_NONE.equals(audioSource)) {
            iconRes = com.fadcam.R.drawable.ic_volume_off_24;
        } else if (muted && isRecordingActive) {
            iconRes = com.fadcam.R.drawable.ic_volume_off_24;
        } else if (Constants.AUDIO_SOURCE_INTERNAL.equals(audioSource)) {
            iconRes = com.fadcam.R.drawable.ic_volume_up_24;
        } else {
            // Microphone
            iconRes = com.fadcam.R.drawable.ic_mic;
        }
        
        buttonFadRecMute.setIcon(
            AppCompatResources.getDrawable(requireContext(), iconRes)
        );
        
        // Button always enabled - behavior changes based on recording state
        buttonFadRecMute.setEnabled(true);
        buttonFadRecMute.setAlpha(1.0f);
        if (isRecordingActive) {
            buttonFadRecMute.setContentDescription("Toggle mute (during recording)");
        } else {
            buttonFadRecMute.setContentDescription(
                getString(com.fadcam.R.string.fadrec_audio_source_choose)
            );
        }
        
        // Tint: red when off, default when active
        boolean isOff = Constants.AUDIO_SOURCE_NONE.equals(audioSource);
        buttonFadRecMute.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
            isOff
                ? androidx.core.content.ContextCompat.getColor(requireContext(), com.fadcam.R.color.button_stop)
                : 0xFF3A3A3A
        ));
        buttonFadRecMute.setIconTint(android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE));
    }

    /**
     * Show audio source picker bottom sheet.
     * Allows user to choose between No Audio, Microphone, and Device Audio (internal).
     * Device Audio option requires Android 10+ (API 29).
     * Only available when not recording.
     */
    private void showAudioSourcePicker() {
        if (!isAdded()) return;
        
        String currentSource = sharedPreferencesManager.getScreenRecordingAudioSource();
        ArrayList<OptionItem> items = new ArrayList<>();
        
        // Add audio source options with icons
        items.add(new OptionItem(
            Constants.AUDIO_SOURCE_NONE,
            getString(R.string.fadrec_audio_source_none),
            null,
            null,
            null,
            null,
            null,
            null,
            "volume_off"  // Material Symbol icon
        ));
        items.add(new OptionItem(
            Constants.AUDIO_SOURCE_MIC,
            getString(R.string.fadrec_audio_source_mic),
            null,
            null,
            null,
            null,
            null,
            null,
            "mic"  // Material Symbol icon
        ));
        items.add(new OptionItem(
            Constants.AUDIO_SOURCE_INTERNAL,
            getString(R.string.fadrec_audio_source_internal),
            null,
            null,
            null,
            null,
            null,
            null,
            "speaker"  // Material Symbol icon
        ));
        
        final String resultKey = "picker_result_audio_source";
        
        // Set up fragment result listener
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k, b) -> {
            String selectedSource = b.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (selectedSource != null && !selectedSource.equals(currentSource)) {
                sharedPreferencesManager.setScreenRecordingAudioSource(selectedSource);
                FLog.d(TAG, "Audio source changed to: " + selectedSource);
                updateMuteButtonUi();
            }
        });
        
        PickerBottomSheetFragment picker = PickerBottomSheetFragment.newInstance(
            getString(R.string.fadrec_audio_source_title),
            items,
            currentSource,
            resultKey,
            getString(R.string.fadrec_audio_source_choose)
        );
        picker.show(getParentFragmentManager(), "audio_source_picker");
    }
    
    /**
     * Show mute toggle picker during recording.
     * Allows user to toggle mute on/off without changing audio source.
     */
    private void showAudioMuteToggle() {
        if (!isAdded()) return;
        
        boolean isMuted = sharedPreferencesManager != null && sharedPreferencesManager.isScreenRecordingMuted();
        ArrayList<OptionItem> items = new ArrayList<>();
        
        // Add mute/unmute options with icons
        items.add(new OptionItem(
            "unmute",
            "Audio On",
            null,
            null,
            null,
            null,
            null,
            null,
            "volume_up"  // Material Symbol icon
        ));
        items.add(new OptionItem(
            "mute",
            "Mute",
            null,
            null,
            null,
            null,
            null,
            null,
            "volume_off"  // Material Symbol icon
        ));
        
        final String resultKey = "picker_result_mute_toggle";
        
        // Set up fragment result listener
        getParentFragmentManager().setFragmentResultListener(resultKey, this, (k, b) -> {
            String selectedAction = b.getString(PickerBottomSheetFragment.BUNDLE_SELECTED_ID);
            if (selectedAction != null) {
                boolean shouldMute = "mute".equals(selectedAction);
                if (sharedPreferencesManager != null) {
                    sharedPreferencesManager.setScreenRecordingMuted(shouldMute);
                    FLog.d(TAG, "Mute toggled to: " + shouldMute);
                    updateMuteButtonUi();
                }
            }
        });
        
        String currentId = isMuted ? "mute" : "unmute";
        PickerBottomSheetFragment picker = PickerBottomSheetFragment.newInstance(
            "Audio Control",
            items,
            currentId,
            resultKey,
            "Mute or unmute audio during recording"
        );
        picker.show(getParentFragmentManager(), "mute_toggle_picker");
    }

    private void syncMuteUiState() {
        if (buttonFadRecMute == null || !isAdded()) return;
        boolean isRecordingActive = screenRecordingState == ScreenRecordingState.IN_PROGRESS
            || screenRecordingState == ScreenRecordingState.PAUSED;
        boolean audioAvailable = isAudioEffectivelyAvailable();

        if (isRecordingActive && !audioAvailable) {
            if (sharedPreferencesManager != null && !sharedPreferencesManager.isScreenRecordingMuted()) {
                sharedPreferencesManager.setScreenRecordingMuted(true);
                if (mediaProjectionHelper != null) {
                    mediaProjectionHelper.setScreenRecordingMuted(true);
                }
            }
            buttonFadRecMute.setEnabled(false);
            buttonFadRecMute.setAlpha(0.5f);
        } else {
            buttonFadRecMute.setEnabled(true);
            buttonFadRecMute.setAlpha(1.0f);
        }
        updateMuteButtonUi();
    }
    
    /**
     * Animate button visibility (slide in/out)
     */
    private void animateButtonVisibility(MaterialButton button, boolean show) {
        if (show && button.getVisibility() == View.GONE) {
            // Slide in from right with fade
            button.setVisibility(View.VISIBLE);
            button.setAlpha(0f);
            button.setTranslationX(50f);
            button.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(300)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
        } else if (!show && button.getVisibility() == View.VISIBLE) {
            // Slide out to right with fade
            button.animate()
                .alpha(0f)
                .translationX(50f)
                .setDuration(300)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> button.setVisibility(View.GONE))
                .start();
        }
    }

    /**
     * Generate dynamic labels string for elapsed time (e.g., "d • h • m • s").
     * Only includes labels for non-zero units to keep display clean.
     * @param elapsedTimeMs elapsed time in milliseconds
     * @return labels string aligned with timer display, or empty if all units are zero
     */
    protected String generateElapsedTimeLabels(long elapsedTimeMs) {
        long totalSeconds = elapsedTimeMs / 1000;
        long days = totalSeconds / (24 * 3600);
        long hours = (totalSeconds % (24 * 3600)) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        
        if (totalSeconds == 0) return "";
        
        // Build labels for active units only with bullet separator (matching camera controls style)
        if (days > 0) {
            return "d • h • m • s";
        } else if (hours > 0) {
            return "h • m • s";
        } else if (minutes > 0) {
            return "m • s";
        } else {
            return "s";
        }
    }

    /**
     * Format elapsed time as timer display (MM:SS or HH:MM:SS or DD:HH:MM:SS once it hits 24 hours).
     * @param elapsedTimeMs elapsed time in milliseconds
     * @return formatted time string
     */
    private String formatElapsedTimeDisplay(long elapsedTimeMs) {
        long totalSeconds = elapsedTimeMs / 1000;
        long days = totalSeconds / (24 * 3600);
        long hours = (totalSeconds % (24 * 3600)) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        
        if (days > 0) {
            return String.format(java.util.Locale.getDefault(), "%d:%02d:%02d:%02d", days, hours, minutes, seconds);
        } else if (hours > 0) {
            return String.format(java.util.Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format(java.util.Locale.getDefault(), "%02d:%02d", minutes, seconds);
        }
    }

    /**
     * Immediately refresh the timer display (e.g., after toggling labels preference).
     * Called from HomeFragment when user changes labels visibility setting.
     */
    public void refreshTimerDisplay() {
        if (getActivity() != null) {
            getActivity().runOnUiThread(this::updateStorageInfo);
        }
    }

    /**
     * Override parent's storage/timer update to show screen recording elapsed/remaining time.
     * This updates the timer cards with screen recording-specific data instead of camera data.
     */
    @Override
    protected void updateStorageInfo() {
        boolean isStreamOnlyMode = false;
        if (sharedPreferencesManager != null) {
            try {
                // STREAM_ONLY UI ("Unlimited" label, no storage deduction) is only meaningful
                // when the remote server is actually active.  If the server is off we always
                // behave like STREAM_AND_SAVE so the UI stays accurate.
                boolean serverActive =
                    com.fadcam.streaming.RemoteStreamManager.getInstance().isStreamingEnabled();
                com.fadcam.streaming.RemoteStreamManager.StreamingMode mode =
                    sharedPreferencesManager.getStreamingMode();
                isStreamOnlyMode = serverActive &&
                    (mode == com.fadcam.streaming.RemoteStreamManager.StreamingMode.STREAM_ONLY);
            } catch (Exception e) {
                FLog.e(TAG, "Error checking streaming mode", e);
            }
        }
        updateStorageInfoForScreenRecording(isStreamOnlyMode);
    }

    /**
     * Unified storage/timer UI update for FadRec (screen recording).
     *
     * <p>Both STREAM_ONLY and STREAM_AND_SAVE share the same view bindings; only the
     * deduction logic and the remaining-time label differ:
     * <ul>
     *   <li>STREAM_ONLY  – no bytes written to disk → no deduction, show ∞ for time-left.</li>
     *   <li>STREAM_AND_SAVE – deduct estimated bytes (bitrate × elapsed) and show live countdown.</li>
     * </ul>
     *
     * <p>Reads elapsed time from {@code "screen_recording_start_time"} saved by
     * {@link com.fadcam.fadrec.services.ScreenRecordingService}, uses
     * {@code tvEstimateTitle} / {@code tvEstimateSubtitle} (the actual layout views) for
     * the time-left row, and respects the elapsed-labels visibility preference.
     */
    private void updateStorageInfoForScreenRecording(boolean isStreamOnly) {
        if (!isAdded() || getActivity() == null) {
            return;
        }

        try {
            // Get storage info — prefer cache, fall back to fresh calculation.
            StorageInfoCache.StorageInfo storageInfo = StorageInfoCache.getCachedStorageInfo();
            if (storageInfo == null) {
                storageInfo = StorageInfoCache.calculateAndCacheStorageInfo(
                    requireContext(), sharedPreferencesManager);
            }
            if (storageInfo == null) {
                FLog.w(TAG, "updateStorageInfoForScreenRecording: storage info null, skipping");
                return;
            }

            // ── Elapsed time ─────────────────────────────────────────────────────────
            // ScreenRecordingService writes "screen_recording_start_time" on start and
            // shifts it forward on resume so a simple subtraction gives true elapsed.
            long screenStartTime = sharedPreferencesManager.sharedPreferences.getLong(
                "screen_recording_start_time", 0);
            boolean isActive = screenRecordingState == ScreenRecordingState.IN_PROGRESS
                || screenRecordingState == ScreenRecordingState.PAUSED;
            long elapsedTime = 0;
            if (screenStartTime > 0 && isActive) {
                elapsedTime = Math.max(0, SystemClock.elapsedRealtime() - screenStartTime);
            }

            // ── Storage deduction & remaining time ───────────────────────────────────
            long adjustedAvailable;
            String remainingTimeText;

            if (isStreamOnly) {
                // Nothing is written to disk — show full available space and infinite time.
                adjustedAvailable = storageInfo.availableBytes;
                remainingTimeText = "Unlimited"; // More readable than ∞ symbol
            } else {
                // Deduct estimated bytes written so far.
                long bitrateBps = calculateScreenRecordingBitrateBps();
                long estimatedBytesUsed = 0;
                if (elapsedTime > 0 && bitrateBps > 0) {
                    estimatedBytesUsed = (elapsedTime * bitrateBps) / 8000L;
                    estimatedBytesUsed = Math.min(estimatedBytesUsed, storageInfo.availableBytes);
                }
                adjustedAvailable = Math.max(0, storageInfo.availableBytes - estimatedBytesUsed);

                // Live countdown while recording; static estimate otherwise.
                if (bitrateBps > 0) {
                    long totalSecs = (adjustedAvailable * 8L) / bitrateBps;
                    totalSecs = Math.max(0, totalSecs);
                    remainingTimeText = formatScreenRemainingTime(totalSecs);
                } else {
                    remainingTimeText = "\u221e";
                }
            }

            // ── Format UI strings ────────────────────────────────────────────────────
            double gbAvailable = Math.max(0, adjustedAvailable / (1024.0 * 1024.0 * 1024.0));
            double gbTotal = storageInfo.getTotalGB();
            Locale numLocale = "fr".equalsIgnoreCase(Locale.getDefault().getLanguage())
                ? Locale.US : Locale.getDefault();
            String availableSpace = String.format(numLocale, "%.2f GB", gbAvailable);
            float availableFraction = gbTotal > 0
                ? (float) Math.max(0, Math.min(1, gbAvailable / gbTotal)) : 0f;

            String elapsedTimeText   = formatElapsedTimeDisplay(elapsedTime);
            String elapsedTimeLabels = generateElapsedTimeLabels(elapsedTime);
            boolean showLabels = sharedPreferencesManager == null
                || sharedPreferencesManager.isScreenRecordingElapsedTimeLabelsVisible();

            // Keep latestElapsedDisplay in sync so the folded-rail start/stop button shows
            // the live elapsed time (base-class field read by updateStartStopButtonForFoldedState).
            latestElapsedDisplay = elapsedTimeText;

            final String finalRemaining   = remainingTimeText;
            final String finalNumLocaleStr = String.format(numLocale, "/ %.2f GB", gbTotal);

            getActivity().runOnUiThread(() -> {
                if (getActivity() == null || !isAdded()) return;

                // Screen info card (resolution, fps, etc.)
                updateScreenRecordingCardInfo();

                // Elapsed row
                if (tvElapsedTitle != null)    tvElapsedTitle.setText(elapsedTimeText);
                if (tvElapsedSubtitle != null) tvElapsedSubtitle.setText(getString(R.string.recording_elapsed_time));
                if (tvElapsedReadable != null) {
                    if (showLabels && !elapsedTimeLabels.isEmpty()) {
                        tvElapsedReadable.setText(elapsedTimeLabels);
                        tvElapsedReadable.setVisibility(View.VISIBLE);
                    } else {
                        tvElapsedReadable.setVisibility(View.GONE);
                    }
                }

                // Remaining / estimate row — tvEstimateTitle is the real layout view
                // (tvRemainingTitle is never bound to any view and is always null).
                if (tvEstimateTitle != null)    tvEstimateTitle.setText(finalRemaining);
                if (tvEstimateSubtitle != null) tvEstimateSubtitle.setText(getString(R.string.recording_time_left));

                // Storage row
                if (tvSpaceTitle != null)    tvSpaceTitle.setText(availableSpace);
                if (tvSpaceTotal != null)    tvSpaceTotal.setText(finalNumLocaleStr);
                if (tvSpaceSubtitle != null) tvSpaceSubtitle.setText(getString(R.string.storage_available_space));
                if (storageProgressRing != null) storageProgressRing.setProgress(availableFraction);

                refreshElapsedHeroAppearance();
                updateStartStopButtonForFoldedState();
            });

            FLog.d(TAG, "Screen recording UI updated [" + (isStreamOnly ? "STREAM_ONLY" : "STREAM_AND_SAVE")
                + "] available=" + availableSpace + " elapsed=" + elapsedTimeText
                + " remaining=" + remainingTimeText);
        } catch (Exception e) {
            FLog.e(TAG, "Error in updateStorageInfoForScreenRecording", e);
        }
    }

    /**
     * Estimate the screen recording bitrate in bits per second.
     * Mirrors {@code ScreenRecordingService.calculateBitrate()} so deduction is accurate.
     */
    private long calculateScreenRecordingBitrateBps() {
        try {
            android.view.WindowManager wm = (android.view.WindowManager)
                requireContext().getSystemService(android.content.Context.WINDOW_SERVICE);
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            long pixels   = (long) dm.widthPixels * dm.heightPixels;
            long fps       = Constants.DEFAULT_SCREEN_RECORDING_FPS;
            long bitrate   = (long) (pixels * fps * 0.07);
            return Math.max(2_000_000L, Math.min(16_000_000L, bitrate));
        } catch (Exception e) {
            FLog.e(TAG, "calculateScreenRecordingBitrateBps failed", e);
            return 8_000_000L; // 8 Mbps safe fallback
        }
    }

    /** Format total seconds as a human-readable remaining-time string (e.g. "1h 23m 45s"). */
    private String formatScreenRemainingTime(long totalSeconds) {
        long days    = totalSeconds / (24 * 3600);
        long hours   = (totalSeconds % (24 * 3600)) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long secs    = totalSeconds % 60;
        StringBuilder sb = new StringBuilder();
        if (days    > 0) sb.append(days).append("d ");
        if (hours   > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        if (secs    > 0 || sb.length() == 0) sb.append(secs).append("s");
        return sb.toString().trim();
    }

    @Override
    protected boolean suppressDefaultCameraRowUpdates() {
        return true;
    }

    @Override
    protected boolean suppressDefaultElapsedRowUpdates() {
        return true;
    }

    @Override
    protected boolean suppressDefaultTimeLeftRowUpdates() {
        return true;
    }

    @Override
    protected boolean usesModeSpecificPreviewBehavior() {
        return true;
    }

    @Override
    protected boolean isModePausedForElapsedAppearance() {
        return screenRecordingState == ScreenRecordingState.PAUSED;
    }

    @Override
    protected boolean isModeRecordingForElapsedAppearance() {
        return screenRecordingState == ScreenRecordingState.IN_PROGRESS;
    }

    @Override
    protected int getPreviewEnableHintResId() {
        return com.fadcam.R.string.fadrec_preview_enable_hint;
    }

    @Override
    protected boolean handleModeSpecificPreviewLongPress() {
        if (!isAdded() || mediaProjectionHelper == null) {
            return true;
        }

        if (screenRecordingState == ScreenRecordingState.IN_PROGRESS
                || screenRecordingState == ScreenRecordingState.PAUSED) {
            isScreenPreviewEnabled = !isScreenPreviewEnabled;
            if (isScreenPreviewEnabled) {
                if (ensurePreviewSurfaceReady()) {
                    pendingPreviewOpenUntilSurfaceReady = false;
                    pendingPreviewCloseSleepAnimation = false;
                    requestAnimateNextPreviewTransition();
                    pushPreviewSurfaceToService();
                    updateModeSpecificPreviewVisibility();
                } else {
                    pendingPreviewOpenUntilSurfaceReady = true;
                }
            } else {
                pendingPreviewOpenUntilSurfaceReady = false;
                pendingPreviewCloseSleepAnimation = true;
                requestAnimateNextPreviewTransition();
                deferredDetachPreviewSurface = true;
                updateModeSpecificPreviewVisibility();
            }
            return true;
        }

        if (isScreenPreviewOnlyActive) {
            isScreenPreviewOnlyActive = false;
            isScreenPreviewEnabled = false;
            pendingPreviewCloseSleepAnimation = true;
            requestAnimateNextPreviewTransition();
            deferredStopPreviewOnly = true;
            updateModeSpecificPreviewVisibility();
            return true;
        }

        Toast.makeText(
            requireContext(),
            com.fadcam.R.string.fullscreen_preview_not_recording,
            Toast.LENGTH_LONG
        ).show();
        return true;
    }

    @Override
    protected void updateModeSpecificPreviewVisibility() {
        if (!isAdded() || getView() == null) {
            return;
        }

        View rootView = getView();
        View textureView = rootView.findViewById(com.fadcam.R.id.textureView);
        View flPreviewAvatar = rootView.findViewById(com.fadcam.R.id.fl_preview_avatar);
        View tvPreviewPlaceholder = rootView.findViewById(com.fadcam.R.id.tvPreviewPlaceholder);
        TextView tvPreviewHint = rootView.findViewById(com.fadcam.R.id.tvPreviewHint);
        boolean showPreview = isScreenPreviewEnabled
            && (screenRecordingState == ScreenRecordingState.IN_PROGRESS
            || screenRecordingState == ScreenRecordingState.PAUSED
            || isScreenPreviewOnlyActive);
        boolean shouldAnimate = consumeAnimateNextPreviewTransition();
        FLog.d(
            TAG,
            "updateModeSpecificPreviewVisibility: showPreview=" + showPreview
                + " shouldAnimate=" + shouldAnimate
                + " closePending=" + pendingPreviewCloseSleepAnimation
                + " openPendingSurface=" + pendingPreviewOpenUntilSurfaceReady
                + " previewOnly=" + isScreenPreviewOnlyActive
                + " state=" + screenRecordingState
        );

        if (showPreview && previewOpenSequenceRunning) {
            return;
        }
        if (!showPreview && previewCloseSequenceRunning) {
            return;
        }

        // Send preview surface to service when preview becomes enabled during recording
        if (showPreview && previewSurface != null && previewSurface.isValid()) {
            pushPreviewSurfaceToService();
        }

        if (tvPreviewPlaceholder != null) {
            tvPreviewPlaceholder.setVisibility(View.GONE);
        }
        if (tvPreviewHint != null) {
            tvPreviewHint.setText(getPreviewEnableHintResId());
        }

        if (textureView == null || flPreviewAvatar == null) {
            if (textureView != null) {
                textureView.setVisibility(showPreview ? View.VISIBLE : View.INVISIBLE);
                textureView.setAlpha(1f);
            }
            if (tvPreviewHint != null) {
                tvPreviewHint.setVisibility(showPreview ? View.GONE : View.VISIBLE);
                tvPreviewHint.setAlpha(0.75f);
            }
            return;
        }

        boolean avatarCurrentlyVisible = flPreviewAvatar.getVisibility() == View.VISIBLE;
        if (showPreview) {
            if (shouldAnimate) {
                if (!avatarCurrentlyVisible) {
                    flPreviewAvatar.setVisibility(View.VISIBLE);
                    flPreviewAvatar.setAlpha(1f);
                    flPreviewAvatar.setScaleX(1f);
                    flPreviewAvatar.setScaleY(1f);
                    flPreviewAvatar.setEnabled(true);
                }

                previewCloseSequenceRunning = false;
                previewOpenSequenceRunning = true;
                previewUiHandler.removeCallbacksAndMessages(null);
                runHintVisibilityAnimated(false);
                textureView.setVisibility(View.VISIBLE);
                textureView.setAlpha(0f);
                setPreviewOpenAnimating(true);
                runHomeAvatarState(true, true);

                previewUiHandler.postDelayed(() -> {
                    if (!isAdded() || getView() == null) {
                        previewOpenSequenceRunning = false;
                        setPreviewOpenAnimating(false);
                        return;
                    }
                    flPreviewAvatar.animate().cancel();
                    flPreviewAvatar.animate()
                        .alpha(0f)
                        .scaleX(0.72f)
                        .scaleY(0.72f)
                        .setDuration(280)
                        .setInterpolator(new android.view.animation.AccelerateInterpolator())
                        .withEndAction(() -> {
                            flPreviewAvatar.setVisibility(View.INVISIBLE);
                            flPreviewAvatar.setEnabled(false);
                            flPreviewAvatar.setAlpha(1f);
                            flPreviewAvatar.setScaleX(1f);
                            flPreviewAvatar.setScaleY(1f);
                        })
                        .start();

                    setPendingPreviewReveal(true);
                    previewUiHandler.postDelayed(() -> {
                        if (isAdded()) {
                            setPendingPreviewReveal(false);
                            runIrisOpenReveal();
                        } else {
                            setPreviewOpenAnimating(false);
                        }
                        previewOpenSequenceRunning = false;
                    }, 2500L);
                }, 480L);
            } else {
                previewOpenSequenceRunning = false;
                textureView.setVisibility(View.VISIBLE);
                textureView.setAlpha(1f);
                flPreviewAvatar.setVisibility(View.INVISIBLE);
                flPreviewAvatar.setEnabled(false);
                runHintVisibilityAnimated(false);
            }
            return;
        }

        boolean previewCurrentlyVisible = textureView.getVisibility() == View.VISIBLE;
        boolean shouldAnimateClose = (shouldAnimate || pendingPreviewCloseSleepAnimation)
            && (previewCurrentlyVisible || pendingPreviewCloseSleepAnimation);
        if (shouldAnimateClose) {
            previewOpenSequenceRunning = false;
            previewCloseSequenceRunning = true;
            previewUiHandler.removeCallbacksAndMessages(null);
            setPendingPreviewReveal(false);
            setPreviewCloseAnimating(true);
            FLog.d(
                TAG,
                "Preview close animation start: textureVisible=" + previewCurrentlyVisible
                    + " avatarVisible=" + avatarCurrentlyVisible
            );

            runHomeAvatarState(true, false);
            flPreviewAvatar.setEnabled(true);
            flPreviewAvatar.setAlpha(1f);
            flPreviewAvatar.setScaleX(1f);
            flPreviewAvatar.setScaleY(1f);
            flPreviewAvatar.setVisibility(View.VISIBLE);
            textureView.setVisibility(View.VISIBLE);
            textureView.setAlpha(1f);

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP
                && textureView.getWidth() > 0
                && textureView.getHeight() > 0) {
                int cx = textureView.getWidth() / 2;
                int cy = textureView.getHeight() / 2;
                float maxR = (float) Math.hypot(cx, cy);
                android.animation.Animator irisClose = android.view.ViewAnimationUtils.createCircularReveal(
                    textureView,
                    cx,
                    cy,
                    maxR,
                    0f
                );
                irisClose.setDuration(480L);
                irisClose.setInterpolator(new android.view.animation.AccelerateInterpolator(1.3f));
                irisClose.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(android.animation.Animator animation) {
                        setPreviewCloseAnimating(false);
                        textureView.setVisibility(View.INVISIBLE);
                        textureView.setAlpha(1f);
                        if (!isAdded()) {
                            previewCloseSequenceRunning = false;
                            pendingPreviewCloseSleepAnimation = false;
                            finalizeDeferredPreviewActions();
                            return;
                        }
                        runHintVisibilityAnimated(true);
                        View anchor = flPreviewAvatar;
                        anchor.postDelayed(() -> {
                            if (isAdded()) {
                                runHomeAvatarState(false, true);
                            }
                            previewCloseSequenceRunning = false;
                            pendingPreviewCloseSleepAnimation = false;
                            finalizeDeferredPreviewActions();
                        }, 650L);
                    }
                });
                irisClose.start();
            } else {
                textureView.animate().cancel();
                textureView.animate()
                    .alpha(0f)
                    .setDuration(340L)
                    .withEndAction(() -> {
                        setPreviewCloseAnimating(false);
                        textureView.setVisibility(View.INVISIBLE);
                        textureView.setAlpha(1f);
                        if (!isAdded()) {
                            previewCloseSequenceRunning = false;
                            pendingPreviewCloseSleepAnimation = false;
                            finalizeDeferredPreviewActions();
                            return;
                        }
                        runHintVisibilityAnimated(true);
                        previewUiHandler.postDelayed(() -> {
                            if (isAdded()) {
                                runHomeAvatarState(false, true);
                            }
                            previewCloseSequenceRunning = false;
                            pendingPreviewCloseSleepAnimation = false;
                            finalizeDeferredPreviewActions();
                        }, 650L);
                    })
                    .start();
            }
            return;
        }

        previewCloseSequenceRunning = false;
        pendingPreviewCloseSleepAnimation = false;
        textureView.setVisibility(View.INVISIBLE);
        textureView.setAlpha(1f);
        flPreviewAvatar.setVisibility(View.VISIBLE);
        flPreviewAvatar.setEnabled(true);
        flPreviewAvatar.setAlpha(1f);
        flPreviewAvatar.setScaleX(1f);
        flPreviewAvatar.setScaleY(1f);
        runHintVisibilityAnimated(true);
        runHomeAvatarState(false, false);
        finalizeDeferredPreviewActions();
    }

    @Override
    protected void onModeSpecificPreviewSurfaceChanged(@Nullable Surface surface, int width, int height) {
        previewSurface = surface;
        previewSurfaceWidth = width;
        previewSurfaceHeight = height;
        if (pendingPreviewOpenUntilSurfaceReady
            && surface != null
            && surface.isValid()
            && isScreenPreviewEnabled
            && (screenRecordingState == ScreenRecordingState.IN_PROGRESS
            || screenRecordingState == ScreenRecordingState.PAUSED)) {
            pendingPreviewOpenUntilSurfaceReady = false;
            requestAnimateNextPreviewTransition();
            pushPreviewSurfaceToService();
            updateModeSpecificPreviewVisibility();
            return;
        }
        pushPreviewSurfaceToService();
    }

    private boolean ensurePreviewSurfaceReady() {
        if (!isAdded() || getView() == null) {
            return false;
        }
        View maybeTextureView = getView().findViewById(com.fadcam.R.id.textureView);
        if (!(maybeTextureView instanceof android.view.TextureView)) {
            return previewSurface != null && previewSurface.isValid();
        }

        android.view.TextureView textureView = (android.view.TextureView) maybeTextureView;
        textureView.setVisibility(View.VISIBLE);
        textureView.setAlpha(0f);
        textureView.requestLayout();
        textureView.invalidate();

        if (!textureView.isAvailable() || textureView.getSurfaceTexture() == null) {
            return previewSurface != null && previewSurface.isValid();
        }

        Surface freshSurface = new Surface(textureView.getSurfaceTexture());
        previewSurface = freshSurface;
        previewSurfaceWidth = textureView.getWidth();
        previewSurfaceHeight = textureView.getHeight();
        return previewSurface.isValid();
    }

    private void pushPreviewSurfaceToService() {
        if (!isAdded() || mediaProjectionHelper == null) {
            return;
        }

        boolean shouldPush = isScreenPreviewEnabled
            && (screenRecordingState == ScreenRecordingState.IN_PROGRESS
            || screenRecordingState == ScreenRecordingState.PAUSED
            || isScreenPreviewOnlyActive);

        if (!shouldPush && screenRecordingState == ScreenRecordingState.NONE && !isScreenPreviewOnlyActive) {
            return;
        }

        mediaProjectionHelper.updateScreenPreviewSurface(
            shouldPush ? previewSurface : null,
            shouldPush ? previewSurfaceWidth : -1,
            shouldPush ? previewSurfaceHeight : -1
        );
    }

    private void finalizeDeferredPreviewActions() {
        if (deferredDetachPreviewSurface) {
            deferredDetachPreviewSurface = false;
            pushPreviewSurfaceToService();
        }
        if (deferredStopPreviewOnly) {
            deferredStopPreviewOnly = false;
            if (mediaProjectionHelper != null) {
                mediaProjectionHelper.stopScreenPreview();
            }
        }
    }

    @Override
    protected void updateStartStopButtonForFoldedState() {
        if (buttonStartStop == null || !isAdded()) {
            return;
        }

        boolean folded = sharedPreferencesManager != null
                && sharedPreferencesManager.sharedPreferences.getBoolean(
                Constants.PREF_HOME_CARD_RAIL_FOLDED,
                false);
        boolean showTimerOnButton = folded
                && (screenRecordingState == ScreenRecordingState.IN_PROGRESS
                || screenRecordingState == ScreenRecordingState.PAUSED);
        CharSequence currentText = buttonStartStop.getText();
        String currentValue = currentText != null ? currentText.toString() : "";
        boolean currentShowsTimer = currentValue.matches("\\d{2}:\\d{2}");

        if (showTimerOnButton) {
            buttonStartStop.setIcon(AppCompatResources.getDrawable(
                    requireContext(),
                    com.fadcam.R.drawable.stop_rounded));
            if (!currentValue.equals(latestElapsedDisplay)) {
                if (buttonStartStop instanceof com.fadcam.ui.utils.AnimatedMaterialButton
                        && currentShowsTimer) {
                    ((com.fadcam.ui.utils.AnimatedMaterialButton) buttonStartStop)
                            .animateSlot(latestElapsedDisplay, 400);
                } else {
                    buttonStartStop.setText(latestElapsedDisplay);
                }
            }
            return;
        }

        if (!currentShowsTimer) {
            return;
        }

        buttonStartStop.setIcon(AppCompatResources.getDrawable(
                requireContext(),
                screenRecordingState == ScreenRecordingState.NONE
                        ? com.fadcam.R.drawable.play_button_rounded
                        : com.fadcam.R.drawable.stop_rounded));
        buttonStartStop.setText(screenRecordingState == ScreenRecordingState.NONE
                ? getString(com.fadcam.R.string.fadrec_start_screen_recording)
                : getString(com.fadcam.R.string.button_stop));
    }

    /**
     * Start the timer that updates elapsed/remaining time every second during recording.
     */
    private void startTimerUpdates() {
        if (timerUpdateRunnable != null) {
            return;
        }
        
        timerUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (isAdded() && screenRecordingState == ScreenRecordingState.IN_PROGRESS) {
                    // Update the timer display
                    updateStorageInfo();
                    
                    // Schedule next update in 1 second
                    timerHandler.postDelayed(this, 1000);
                } else {
                    // Not recording anymore, stop the timer
                    FLog.d(TAG, "Timer stopped - not recording");
                }
            }
        };
        
        // Start the timer immediately
        timerHandler.post(timerUpdateRunnable);
        FLog.d(TAG, "Timer updates started");
    }
    
    /**
     * Stop the timer updates. Preserves elapsed time but hides live labels.
     */
    private void stopTimerUpdates() {
        if (timerUpdateRunnable != null) {
            timerHandler.removeCallbacks(timerUpdateRunnable);
            timerUpdateRunnable = null;
            FLog.d(TAG, "Timer updates stopped");
        }
        
        // Hide elapsed time labels when recording stops (but preserve the elapsed time value)
        if (tvElapsedReadable != null) {
            tvElapsedReadable.setVisibility(View.GONE);
            tvElapsedReadable.setText("");
        }
        // NOTE: tvElapsedTitle is NOT cleared - preserves the final elapsed time value
        // NOTE: tvRemainingTitle is NOT cleared - preserves state when paused
    }

    @Override
    public void onResume() {
        reconcileScreenRecordingStateWithReality();
        
        super.onResume(); // MUST call super - Android requirement
        // NOTE: Parent's onResume() calls fetchRecordingState() which starts RecordingService (camera recording)
        // This is WRONG for FadRec. We use ScreenRecordingService which broadcasts state via LocalBroadcastManager
        // Workaround: We create shadow methods below that do nothing to prevent parent from affecting us
        
        FLog.d(TAG, "FadRecHomeFragment resumed");
        
        // CRITICAL FIX: Re-setup button handlers after parent's onResume() to ensure
        // screen recording click listeners override any camera recording listeners
        // that the parent may have set up
        if (getView() != null) {
            setupButtonHandlers(getView());
            FLog.d(TAG, "Button handlers re-setup after parent onResume()");
        }
        
        // CRITICAL FIX: Force update button availability to override parent's camera-based logic
        // Parent disables button based on camera resource availability, but we need it always enabled
        updateStartButtonAvailability();
        FLog.e(TAG, "!!! updateStartButtonAvailability() called at end of onResume !!!");

        // Sync UI with persisted state after tab switches / fragment resume.
        updateUIForRecordingState();
        
        // CRITICAL FIX: If annotation service is already running (app reopened with service active),
        // dismiss any loading dialog since onCreate() won't be called again and READY broadcast won't fire
        if (sharedPreferencesManager.isFloatingControlsEnabled() && loadingDialog != null && loadingDialog.isShowing()) {
            loadingDialog.dismiss();
            FLog.d(TAG, "Service already running - dismissed loading dialog on resume");
        }
        
        // Parent's onResume() queries camera state and calls resetUIButtonsToIdleState()
        // Since we've overridden resetUIButtonsToIdleState(), our version runs instead
        // Our override keeps pause enabled and camera controls hidden - no timing hacks needed!
        
        // NOTE: Don't request state or load persisted state
        // The broadcast receiver is registered in onCreate() and will listen for state changes
        // from ScreenRecordingService. The UI will update automatically when broadcasts arrive.
        // This avoids the timing issue where state requests arrive before recording fully starts.
        
        // Timer updates are handled by updateUIForRecordingState().
        reapplyScreenRecordingCardState(false);
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            reconcileScreenRecordingStateWithReality();
            reapplyScreenRecordingCardState(false);
            if (getView() != null) {
                updateCardForScreenRecording(getView());
            }
        }
    }

    private void reapplyScreenRecordingCardState(boolean animate) {
        if (!isAdded() || getView() == null) {
            return;
        }

        View rootView = getView();
        View camSwitchBtn = rootView.findViewById(com.fadcam.R.id.buttonCamSwitch);
        View torchBtn = rootView.findViewById(com.fadcam.R.id.buttonTorchSwitch);
        View tileAfToggle = rootView.findViewById(com.fadcam.R.id.tile_af_toggle);
        View tileExp = rootView.findViewById(com.fadcam.R.id.tile_exp);
        View tileZoom = rootView.findViewById(com.fadcam.R.id.tile_zoom);
        View tvRecordingControlsTitle = rootView.findViewById(com.fadcam.R.id.tv_recording_controls_title);

        if (camSwitchBtn != null) camSwitchBtn.setVisibility(View.GONE);
        if (torchBtn != null) torchBtn.setVisibility(View.GONE);
        if (tileAfToggle != null) tileAfToggle.setVisibility(View.GONE);
        if (tileExp != null) tileExp.setVisibility(View.GONE);
        if (tileZoom != null) tileZoom.setVisibility(View.GONE);
        if (tvRecordingControlsTitle != null) tvRecordingControlsTitle.setVisibility(View.GONE);
        configurePreviewCardForScreenRecording(rootView);
        if (!animate) {
            screenCardInfoInitialized = true;
        }
        updateScreenRecordingCardInfo();
        updateModeSpecificPreviewVisibility();
    }

    /**
     * Reconciles persisted screen recording state with the actual running ScreenRecordingService.
     * This prevents stale UI (e.g. showing Stop when nothing is recording) after app updates/crashes.
     */
    private void reconcileScreenRecordingStateWithReality() {
        boolean isServiceRunning = false;
        try {
            isServiceRunning = ServiceUtils.isServiceRunning(requireContext(), ScreenRecordingService.class);
        } catch (Exception e) {
            FLog.w(TAG, "Failed to check ScreenRecordingService running state", e);
        }

        ScreenRecordingState restoredState;
        String savedState = sharedPreferencesManager.getScreenRecordingState();
        try {
            restoredState = ScreenRecordingState.valueOf(savedState);
        } catch (IllegalArgumentException e) {
            restoredState = ScreenRecordingState.NONE;
        }

        if (!isServiceRunning) {
            // Service isn't running -> cannot be recording. Clear stale flags.
            if (restoredState != ScreenRecordingState.NONE || sharedPreferencesManager.isScreenRecordingInProgress()) {
                FLog.w(TAG, "Clearing stale screen recording state (service not running). savedState=" + savedState);
            }
            screenRecordingState = ScreenRecordingState.NONE;
            sharedPreferencesManager.setScreenRecordingInProgress(false);
            sharedPreferencesManager.setScreenRecordingState(ScreenRecordingState.NONE.name());
            try {
                sharedPreferencesManager.sharedPreferences.edit().remove("screen_recording_start_time").apply();
            } catch (Exception e) {
                FLog.w(TAG, "Failed clearing screen_recording_start_time", e);
            }
            return;
        }

        // Service is running. Use persisted state as a hint, then immediately query service
        // to broadcast the authoritative current state when we suspect prefs might be stale.
        screenRecordingState = restoredState;

        FLog.d(TAG, "onResume: ScreenRecordingService running, restoredState=" + restoredState + " - querying");
        try {
            Intent queryIntent = new Intent(requireContext(), ScreenRecordingService.class);
            queryIntent.setAction(Constants.INTENT_ACTION_QUERY_SCREEN_RECORDING_STATE);
            requireContext().startService(queryIntent);
        } catch (Exception e) {
            FLog.w(TAG, "Failed to query ScreenRecordingService state", e);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        FLog.d(TAG, "FadRecHomeFragment paused");
        
        // Stop timer updates when fragment is paused
        stopTimerUpdates();
        timerHandler.removeCallbacks(pendingStopStateReconcileRunnable);
        previewUiHandler.removeCallbacksAndMessages(null);
        previewOpenSequenceRunning = false;
        previewCloseSequenceRunning = false;
        setPendingPreviewReveal(false);
        pendingPreviewOpenUntilSurfaceReady = false;
        pendingPreviewCloseSleepAnimation = false;
    }

    @Override
    public void onStop() {
        super.onStop();
        FLog.d(TAG, "FadRecHomeFragment onStop");
        // Note: Broadcast receivers remain registered to continue receiving state updates
        // even when fragment is stopped but still in the backstack
    }

    /**
     * Override fetchRecordingState to prevent parent from starting RecordingService (camera recording).
     * FadRec uses ScreenRecordingService (screen recording) which broadcasts state via LocalBroadcastManager.
     * State is received automatically by screenRecordingStateReceiver registered in onCreate().
     */
    @Override
    protected void fetchRecordingState() {
        // DO NOT call super.fetchRecordingState() - it starts RecordingService (camera recording)
        // FadRec uses ScreenRecordingService (screen recording) which broadcasts state automatically
        // The broadcast is received by screenRecordingStateReceiver when it arrives
        FLog.d(TAG, "fetchRecordingState: Overridden (skipped) - FadRec uses screen recording service");
    }

    /**
     * Override registerBroadcastReceivers to prevent parent from registering camera recording receivers.
     * FadRec uses screen recording receivers which are already registered in onCreate().
     */
    @Override
    protected void registerBroadcastReceivers() {
        // DO NOT call super.registerBroadcastReceivers() - it registers RecordingService (camera recording) receivers
        // FadRec screen recording receivers are already registered in onCreate() via registerScreenRecordingStateReceiver()
        FLog.d(TAG, "registerBroadcastReceivers: Overridden (skipped) - FadRec receivers already registered");
    }
}
