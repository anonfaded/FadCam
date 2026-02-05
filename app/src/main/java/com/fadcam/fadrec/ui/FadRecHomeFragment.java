package com.fadcam.fadrec.ui;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
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
import com.fadcam.utils.ServiceUtils;
import com.fadcam.utils.StorageInfoCache;
import com.google.android.material.button.MaterialButton;

/**
 * FadRec Home Fragment - Extends HomeFragment for screen recording functionality.
 * Uses inheritance to reuse camera recording UI while adapting for screen recording.
 * 
 * Key differences from parent HomeFragment:
 * - Hides camera-specific controls (camera switch, flash, zoom)
 * - Shows screen resolution instead of camera info
 * - Uses ScreenRecordingService instead of RecordingService
 * - No camera preview (TextureView hidden)
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
    
    // Material loading dialog for annotation service startup
    private androidx.appcompat.app.AlertDialog loadingDialog;

    /**
     * Create a new instance of FadRecHomeFragment.
     */
    public static FadRecHomeFragment newInstance() {
        return new FadRecHomeFragment();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Log.e(TAG, "============================================");
        // Log.e(TAG, "FadRecHomeFragment.onCreate() - FRAGMENT CLASS: " + this.getClass().getName());
        // Log.e(TAG, "============================================");
        
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
                    // Log.d(TAG, "Audio permission granted, proceeding with screen capture");
                    requestScreenCapturePermission();
                } else {
                    // Log.w(TAG, "Audio permission denied, starting without audio");
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
                // Log.d(TAG, "Screen capture result: resultCode=" + resultCode + 
                //       " (RESULT_OK=" + Activity.RESULT_OK + "), data=" + (data != null ? "present" : "null"));
                
                if (resultCode == Activity.RESULT_OK && data != null) {
                    // Log.d(TAG, "Screen capture permission granted, starting recording");
                    // RESULT_OK is -1, so we pass Activity.RESULT_OK constant instead
                    mediaProjectionHelper.startScreenRecording(Activity.RESULT_OK, data);
                } else {
                    // Log.w(TAG, "Screen capture permission denied: resultCode=" + resultCode);
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
                    // Log.d(TAG, "Overlay permission granted");
                    // Enable unified annotation overlay
                    sharedPreferencesManager.setFloatingControlsEnabled(true);
                    // Update switch if view exists
                    if (getView() != null) {
                        View cardFloatingControls = getView().findViewById(com.fadcam.R.id.cardFloatingControls);
                        if (cardFloatingControls != null) {
                            androidx.appcompat.widget.SwitchCompat switchFloatingControls = 
                                cardFloatingControls.findViewById(com.fadcam.R.id.switchFloatingControls);
                            if (switchFloatingControls != null) {
                                switchFloatingControls.setChecked(true);
                            }
                        }
                    }
                    startAnnotationService();
                } else {
                    // Log.w(TAG, "Overlay permission denied");
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
        // Log.d(TAG, "========== onViewCreated START ==========");
        // Log.d(TAG, "Calling super.onViewCreated...");
        super.onViewCreated(view, savedInstanceState);
        // Log.d(TAG, "super.onViewCreated completed");
        
        // Apply FadRec-specific UI customizations
        // Log.d(TAG, "Customizing UI for screen recording...");
        customizeUIForScreenRecording(view);
        
        // Setup button click handlers
        // Log.d(TAG, "Setting up button handlers...");
        setupButtonHandlers(view);
        // Log.d(TAG, "========== onViewCreated COMPLETE ==========");
        
        // NOTE: Receiver registration moved to onStart() to avoid double-registration
        // on fragment recreation and to maintain proper lifecycle coordination
    }
    
    @Override
    public void onStart() {
        super.onStart();
        // Log.e(TAG, "============================================");
        // Log.e(TAG, "FadRecHomeFragment.onStart()");
        // Log.e(TAG, "buttonStartStop: " + buttonStartStop);
        // Log.e(TAG, "buttonStartStop enabled: " + (buttonStartStop != null ? buttonStartStop.isEnabled() : "NULL"));
        // Log.e(TAG, "buttonStartStop hasOnClickListeners: " + (buttonStartStop != null ? buttonStartStop.hasOnClickListeners() : "NULL"));
        // Log.e(TAG, "============================================");
        // Note: Broadcast receivers are now registered in onCreate()
        // to ensure they persist and are ready to receive state updates
    }
    
    /**
     * Register broadcast receiver to listen for annotation service stop.
     * Includes guard to prevent double-registration on fragment recreation.
     */
    private void registerAnnotationServiceReceiver() {
        // Guard: Don't register twice
        if (isAnnotationServiceReceiverRegistered) {
            Log.d(TAG, "Annotation service receiver already registered, skipping.");
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
                                androidx.appcompat.widget.SwitchCompat switchFloatingControls = 
                                    cardFloatingControls.findViewById(com.fadcam.R.id.switchFloatingControls);
                                if (switchFloatingControls != null) {
                                    switchFloatingControls.setChecked(false);
                                }
                            }
                        }
                        // Update SharedPreferences
                        sharedPreferencesManager.setFloatingControlsEnabled(false);
                        Log.d(TAG, "Annotation service stopped/terminated - menu switch turned off");
                    } else if ("com.fadcam.fadrec.ANNOTATION_SERVICE_READY".equals(action)) {
                        // Service initialization complete, dismiss loading dialog
                        if (loadingDialog != null && loadingDialog.isShowing()) {
                            loadingDialog.dismiss();
                            Log.d(TAG, "Annotation service ready - loading dialog dismissed");
                        }
                    } else if ("com.fadcam.fadrec.ANNOTATION_SERVICE_PERMISSION_ERROR".equals(action)) {
                        // Overlay permission error, turn off the menu switch
                        if (getView() != null) {
                            View cardFloatingControls = getView().findViewById(com.fadcam.R.id.cardFloatingControls);
                            if (cardFloatingControls != null) {
                                androidx.appcompat.widget.SwitchCompat switchFloatingControls = 
                                    cardFloatingControls.findViewById(com.fadcam.R.id.switchFloatingControls);
                                if (switchFloatingControls != null) {
                                    switchFloatingControls.setChecked(false);
                                }
                            }
                        }
                        // Update SharedPreferences
                        sharedPreferencesManager.setFloatingControlsEnabled(false);
                        Log.d(TAG, "Overlay permission not granted - menu switch turned off");
                        
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
            Log.d(TAG, "Annotation service receiver registered.");
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Error registering annotation service receiver: " + e.getMessage());
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
        Log.d(TAG, "FadRec: Reset UI to idle state");
        if (!isAdded() || getContext() == null || getView() == null) {
            Log.w(TAG, "resetUIButtonsToIdleState: Fragment/context unavailable");
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
                        AppCompatResources.getDrawable(getContext(), com.fadcam.R.drawable.ic_play)
                    );
                    buttonStartStop.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(
                            android.graphics.Color.parseColor("#4CAF50")
                        )
                    );
                    buttonStartStop.setEnabled(true);
                    buttonStartStop.setAlpha(1.0f);
                }
                Log.d(TAG, "FadRec: Start button reset to idle (Start Screen Recording)");
            } else {
                // Recording in progress or paused - keep stop button state
                if (buttonStartStop != null) {
                    buttonStartStop.setText(com.fadcam.R.string.button_stop);
                    buttonStartStop.setIcon(
                        AppCompatResources.getDrawable(getContext(), com.fadcam.R.drawable.ic_stop)
                    );
                    buttonStartStop.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(
                            androidx.core.content.ContextCompat.getColor(getContext(), com.fadcam.R.color.button_stop)
                        )
                    );
                    buttonStartStop.setEnabled(true);
                    buttonStartStop.setAlpha(1.0f);
                }
                Log.d(TAG, "FadRec: Start button kept as Stop (recording active: " + currentState + ")");
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
                        AppCompatResources.getDrawable(getContext(), com.fadcam.R.drawable.ic_play)
                    );
                } else {
                    buttonPauseResume.setIcon(
                        AppCompatResources.getDrawable(getContext(), com.fadcam.R.drawable.ic_pause)
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
            
            Log.d(TAG, "FadRec: UI elements reset complete (screen recording mode, state: " + currentState + ")");
        } catch (Exception e) {
            Log.e(TAG, "Error in resetUIButtonsToIdleState", e);
        }
    }

    /**
     * Override parent's method to prevent camera-specific button state updates.
     * In FadRec mode, start button is always available (no camera dependency).
     */
    @Override
    protected void updateStartButtonAvailability() {
        Log.e(TAG, "============================================");
        Log.e(TAG, "FadRecHomeFragment.updateStartButtonAvailability() CALLED");
        Log.e(TAG, "screenRecordingState: " + screenRecordingState);
        Log.e(TAG, "============================================");
        
        if (!isAdded() || buttonStartStop == null) {
            Log.e(TAG, "Button or fragment not available");
            return;
        }
        
        // For screen recording, the Start/Stop button must ALWAYS be enabled:
        // - When idle, user must be able to start
        // - While recording/paused, user must be able to stop
        buttonStartStop.setEnabled(true);
        buttonStartStop.setClickable(true);
        buttonStartStop.setAlpha(1.0f);
        Log.e(TAG, "!!! BUTTON FORCE ENABLED IN updateStartButtonAvailability !!!");
        Log.e(TAG, "Button enabled: " + buttonStartStop.isEnabled());
        Log.e(TAG, "Button alpha: " + buttonStartStop.getAlpha());
        
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
        Log.d(TAG, "Customizing UI for screen recording");
        
        try {
            // Hide camera-specific controls
            hideCameraControls(rootView);
            
            Log.d(TAG, "UI customization complete");
        } catch (Exception e) {
            Log.e(TAG, "Error customizing UI for screen recording", e);
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
            Log.d(TAG, "Camera switch button hidden");
        }
        
        // Hide torch button
        View torchBtn = rootView.findViewById(com.fadcam.R.id.buttonTorchSwitch);
        if (torchBtn != null) {
            torchBtn.setVisibility(View.GONE);
            Log.d(TAG, "Torch button hidden");
        }
        
        // Hide entire preview area (TextureView and placeholder from parent layout)
        View textureView = rootView.findViewById(com.fadcam.R.id.textureView);
        if (textureView != null) {
            textureView.setVisibility(View.GONE);
        }
        
        View tvPreviewPlaceholder = rootView.findViewById(com.fadcam.R.id.tvPreviewPlaceholder);
        if (tvPreviewPlaceholder != null) {
            tvPreviewPlaceholder.setVisibility(View.GONE);
        }
        
        // Update camera info card to show screen recording info instead
        updateCardForScreenRecording(rootView);
        
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
            Log.d(TAG, "Recording controls title hidden");
        }
        
        // Setup floating controls toggle card in the tiles area
        setupFloatingControlsCard(rootView);
        
        // Replace preview card content with custom FadRec screen icon
        replacePreviewWithScreenIcon(rootView);
        
        Log.d(TAG, "Camera controls hidden");
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
            androidx.appcompat.widget.SwitchCompat switchFloatingControls = 
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
                            buttonView.setChecked(false);
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
            
            Log.d(TAG, "Floating controls card added");
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
                        Log.w(TAG, "overlayPermissionLauncher is null, opening settings directly");
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
                    Log.e(TAG, "Error requesting overlay permission: " + e.getMessage(), e);
                    try {
                        // Fallback: Try to open settings directly
                        android.content.Intent settingsIntent = new android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + requireContext().getPackageName())
                        );
                        startActivity(settingsIntent);
                    } catch (Exception e2) {
                        Log.e(TAG, "Fallback also failed: " + e2.getMessage(), e2);
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
            Log.d(TAG, "Annotation service started");
        } catch (Exception e) {
            Log.e(TAG, "Error starting annotation service", e);
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
            Log.d(TAG, "Annotation service stopped");
        } catch (Exception e) {
            Log.e(TAG, "Error stopping annotation service", e);
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
     * Replace the preview card content with a custom screen recording icon layout.
     * This completely overrides the parent's camera preview with FadRec-specific UI.
     */
    private void replacePreviewWithScreenIcon(View rootView) {
        // Find the preview card container
        android.view.ViewGroup previewCard = 
            rootView.findViewById(com.fadcam.R.id.cardPreview);
        
        if (previewCard != null) {
            // Make the parent card background transparent for FadRec
            if (previewCard instanceof androidx.cardview.widget.CardView) {
                ((androidx.cardview.widget.CardView) previewCard).setCardBackgroundColor(
                    android.graphics.Color.TRANSPARENT
                );
            }
            previewCard.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            
            // Remove all child views from the card
            previewCard.removeAllViews();
            
            // Inflate custom FadRec screen icon layout
            View screenIconView = getLayoutInflater().inflate(
                com.fadcam.R.layout.fadrec_screen_icon, 
                previewCard, 
                false
            );
            
            // Add the custom layout to the card
            previewCard.addView(screenIconView);
            previewCard.setVisibility(View.VISIBLE);
            
            // Start rotating bubble animation for modern visual effect
            android.widget.ImageView bubbleBackground = screenIconView.findViewById(com.fadcam.R.id.ivBubbleBackground);
            if (bubbleBackground != null) {
                android.view.animation.Animation rotateAnimation = 
                    android.view.animation.AnimationUtils.loadAnimation(requireContext(), com.fadcam.R.anim.rotate_slow_left);
                bubbleBackground.startAnimation(rotateAnimation);
                Log.d(TAG, "Started FadRec bubble background rotation animation");
            }
            
            // IMPORTANT: Disable long press listener from parent HomeFragment
            // FadRec doesn't need camera preview toggle functionality
            previewCard.setOnLongClickListener(null);
            previewCard.setLongClickable(false);
            
            Log.d(TAG, "Preview card replaced with screen recording icon, background transparent");
        }
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
                
                ivScreenRecordIcon.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
                ivScreenRecordIcon.setPadding((int)(4*d), (int)(4*d), (int)(4*d), (int)(4*d));
                
                // Replace the old TextView with new ImageView
                parent.removeViewAt(index);
                parent.addView(ivScreenRecordIcon, index);
                
                Log.d(TAG, "Card icon replaced with screen_recorder.png");
            }
            
            if (tvCameraTitle != null) {
                // Get device screen resolution
                android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
                if (getActivity() != null) {
                    getActivity().getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
                    int width = metrics.widthPixels;
                    int height = metrics.heightPixels;
                    
                    // Update title to show screen recording info
                    tvCameraTitle.setText("Screen Recording");
                    Log.d(TAG, "Card title updated to Screen Recording");
                    
                    // Update subtitle with device screen resolution and fps
                    if (tvCameraSubtitle != null) {
                        String subtitle = width + "x" + height + " • 30fps";
                        tvCameraSubtitle.setText(subtitle);
                        Log.d(TAG, "Card subtitle updated to: " + subtitle);
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
            
            if (tvCameraTitle != null) {
                tvCameraTitle.setText("Screen Recording");
            }
            
            if (tvCameraSubtitle != null) {
                // Get device screen resolution
                android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
                if (getActivity() != null) {
                    getActivity().getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
                    int width = metrics.widthPixels;
                    int height = metrics.heightPixels;
                    String subtitle = width + "x" + height + " • 30fps";
                    tvCameraSubtitle.setText(subtitle);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating screen recording card info", e);
        }
    }

    /**
     * Setup button click handlers for start/stop/pause/resume.
     * No need to force-enable buttons - our override handles that.
     */
    private void setupButtonHandlers(View rootView) {
        Log.d(TAG, "========== setupButtonHandlers() CALLED ==========");
        
        // Use inherited protected fields from parent HomeFragment instead of local variables
        // This ensures we override the parent's camera click listener with screen recording logic
        buttonStartStop = rootView.findViewById(com.fadcam.R.id.buttonStartStop);
        buttonPauseResume = rootView.findViewById(com.fadcam.R.id.buttonPauseResume);
        
        Log.d(TAG, "buttonStartStop found: " + (buttonStartStop != null));
        Log.d(TAG, "buttonPauseResume found: " + (buttonPauseResume != null));
        
        // NOTE: Don't load persisted state here - it interferes with broadcast-based state
        // State will be loaded from broadcasts or set to NONE if no broadcasts arrive
        
        // Start/Stop button
        if (buttonStartStop != null) {
            buttonStartStop.setOnClickListener(v -> {
                // Log.d(TAG, "=== FADREC START/STOP BUTTON CLICKED ===");
                // Log.d(TAG, "Current screenRecordingState: " + screenRecordingState);
                
                // Debounce rapid clicks
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastClickTime < DEBOUNCE_DELAY_MS) {
                    Log.d(TAG, "Button click ignored (debounced)");
                    return;
                }
                lastClickTime = currentTime;
                
                if (screenRecordingState == ScreenRecordingState.NONE) {
                    // Start recording
                    requestScreenRecordingPermissionAndStart();
                } else {
                    // Stop recording
                    stopScreenRecording();
                }
            });
            
            Log.e(TAG, "============================================");
            Log.e(TAG, "Click listener SET on Start/Stop button");
            Log.e(TAG, "Button now has onClickListener: " + buttonStartStop.hasOnClickListeners());
            Log.e(TAG, "Button enabled: " + buttonStartStop.isEnabled());
            Log.e(TAG, "Button clickable: " + buttonStartStop.isClickable());
            Log.e(TAG, "============================================");
        } else {
            Log.e(TAG, "ERROR: buttonStartStop is NULL!");
        }
        
        // Pause/Resume button
        if (buttonPauseResume != null) {
            buttonPauseResume.setOnClickListener(v -> {
                // Debounce rapid clicks
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastClickTime < DEBOUNCE_DELAY_MS) {
                    Log.d(TAG, "Button click ignored (debounced)");
                    return;
                }
                lastClickTime = currentTime;
                
                if (screenRecordingState == ScreenRecordingState.IN_PROGRESS) {
                    // Pause recording
                    mediaProjectionHelper.pauseScreenRecording();
                } else if (screenRecordingState == ScreenRecordingState.PAUSED) {
                    // Resume recording
                    mediaProjectionHelper.resumeScreenRecording();
                }
            });
            Log.d(TAG, "Click listener successfully attached to buttonStartStop");
        } else {
            Log.e(TAG, "buttonStartStop is NULL - cannot setup click listener!");
        }
        
        Log.d(TAG, "========== SETUP BUTTON HANDLERS COMPLETE ==========");

        // Safety: HomeFragment can disable this button based on camera resources.
        // FadRec must keep it enabled at all times.
        try {
            if (buttonStartStop != null) {
                buttonStartStop.setEnabled(true);
                buttonStartStop.setClickable(true);
                buttonStartStop.setAlpha(1.0f);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to force-enable Start/Stop button", e);
        }
    }
    
    /**
     * Load persisted recording state from SharedPreferences.
     */
    private void loadPersistedRecordingState() {
        String persistedState = sharedPreferencesManager.getScreenRecordingState();
        try {
            screenRecordingState = ScreenRecordingState.valueOf(persistedState);
            Log.d(TAG, "Loaded persisted state: " + screenRecordingState);
            updateUIForRecordingState();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load persisted state, defaulting to NONE", e);
            screenRecordingState = ScreenRecordingState.NONE;
        }
    }
    
    /**
     * Persist recording state to SharedPreferences.
     */
    private void persistRecordingState(ScreenRecordingState state) {
        sharedPreferencesManager.setScreenRecordingState(state.name());
        Log.d(TAG, "Persisted state: " + state);
    }

    /**
     * Request MediaProjection permission and start screen recording.
     */
    private void requestScreenRecordingPermissionAndStart() {
        Log.d(TAG, "Requesting screen recording permission");
        
        if (!mediaProjectionHelper.isAvailable()) {
            Toast.makeText(requireContext(), 
                "Screen recording not available on this device", 
                Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Check audio permission first if microphone is enabled
        String audioSource = sharedPreferencesManager.getScreenRecordingAudioSource();
        if (Constants.AUDIO_SOURCE_MIC.equals(audioSource)) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) 
                != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Requesting audio permission");
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
                return;
            }
        }
        
        // Audio permission granted or not needed, proceed with screen capture
        requestScreenCapturePermission();
    }
    
    /**
     * Request screen capture permission.
     */
    private void requestScreenCapturePermission() {
        Log.d(TAG, "Requesting screen capture permission");
        
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
            Log.e(TAG, "Error requesting screen recording permission", e);
            Toast.makeText(requireContext(), 
                "Error starting screen recording", 
                Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Handle recording start from overlay without bringing app to foreground.
     * Launches TransparentPermissionActivity to request permission without activating main app.
     */
    private void handleOverlayRecordingStart() {
        Log.d(TAG, "handleOverlayRecordingStart: Launching transparent permission activity");
        
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
        Log.d(TAG, "Stopping screen recording");
        mediaProjectionHelper.stopScreenRecording();
    }

    /**
     * Register broadcast receivers for screen recording state changes.
     */
    private void registerScreenRecordingReceivers() {
        // Guard: Don't register twice
        if (isScreenRecordingReceiverRegistered) {
            // Log.d(TAG, "Screen recording receiver already registered, skipping.");
            return;
        }
        
        if (screenRecordingStateReceiver == null) {
            screenRecordingStateReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    // Log.d(TAG, "screenRecordingStateReceiver.onReceive: action=" + action + 
                    //            ", Android=" + android.os.Build.VERSION.SDK_INT);
                    if (action == null) return;
                    
                    switch (action) {
                        case Constants.BROADCAST_ON_SCREEN_RECORDING_STARTED:
                            // Log.d(TAG, "Broadcast: SCREEN_RECORDING_STARTED");
                            screenRecordingState = ScreenRecordingState.IN_PROGRESS;
                            persistRecordingState(screenRecordingState);
                            updateUIForRecordingState();
                            // Toast.makeText(context, com.fadcam.R.string.fadrec_screen_recording_started, 
                            //     Toast.LENGTH_SHORT).show();
                            break;
                            
                        case Constants.BROADCAST_ON_SCREEN_RECORDING_STOPPED:
                            // Log.d(TAG, "Broadcast: SCREEN_RECORDING_STOPPED");
                            screenRecordingState = ScreenRecordingState.NONE;
                            persistRecordingState(screenRecordingState);
                            updateUIForRecordingState();
                            // Toast.makeText(context, com.fadcam.R.string.fadrec_screen_recording_stopped, 
                            //     Toast.LENGTH_SHORT).show();
                            break;
                            
                        case Constants.BROADCAST_ON_SCREEN_RECORDING_PAUSED:
                            // Log.d(TAG, "Broadcast: SCREEN_RECORDING_PAUSED");
                            screenRecordingState = ScreenRecordingState.PAUSED;
                            persistRecordingState(screenRecordingState);
                            updateUIForRecordingState();
                            // Toast.makeText(context, com.fadcam.R.string.fadrec_screen_recording_paused, 
                            //     Toast.LENGTH_SHORT).show();
                            break;
                            
                        case Constants.BROADCAST_ON_SCREEN_RECORDING_RESUMED:
                            // Log.d(TAG, "Broadcast: SCREEN_RECORDING_RESUMED");
                            screenRecordingState = ScreenRecordingState.IN_PROGRESS;
                            persistRecordingState(screenRecordingState);
                            updateUIForRecordingState();
                            // Toast.makeText(context, com.fadcam.R.string.fadrec_screen_recording_resumed, 
                            //     Toast.LENGTH_SHORT).show();
                            break;
                            
                        // Handle overlay actions
                        case Constants.ACTION_START_SCREEN_RECORDING_FROM_OVERLAY:
                            // Log.d(TAG, "Received ACTION_START_SCREEN_RECORDING_FROM_OVERLAY");
                            if (screenRecordingState == ScreenRecordingState.NONE) {
                                // When called from overlay while app is in background,
                                // we need to handle this differently to avoid bringing app to foreground
                                handleOverlayRecordingStart();
                            }
                            break;
                        
                        // Handle permission results from TransparentPermissionActivity
                        case Constants.ACTION_SCREEN_RECORDING_PERMISSION_GRANTED:
                            // Log.d(TAG, "Received ACTION_SCREEN_RECORDING_PERMISSION_GRANTED");
                            // Permission granted, start recording with the provided Intent
                            Intent permissionData = intent.getParcelableExtra("data");
                            if (permissionData != null && mediaProjectionHelper != null) {
                                int resultCode = intent.getIntExtra("resultCode", -1);
                                // Log.d(TAG, "Starting recording with resultCode: " + resultCode);
                                mediaProjectionHelper.startScreenRecording(resultCode, permissionData);
                            } else {
                                // Log.e(TAG, "Permission granted but data or helper is null");
                            }
                            break;
                            
                        case Constants.ACTION_SCREEN_RECORDING_PERMISSION_DENIED:
                            // Log.d(TAG, "Received ACTION_SCREEN_RECORDING_PERMISSION_DENIED");
                            Toast.makeText(context, "Screen recording permission denied", Toast.LENGTH_SHORT).show();
                            break;
                            
                        case Constants.ACTION_PAUSE_SCREEN_RECORDING:
                            // Log.d(TAG, "Received ACTION_PAUSE_SCREEN_RECORDING");
                            if (screenRecordingState == ScreenRecordingState.IN_PROGRESS) {
                                if (mediaProjectionHelper != null) {
                                    mediaProjectionHelper.pauseScreenRecording();
                                }
                            }
                            break;
                            
                        case Constants.ACTION_RESUME_SCREEN_RECORDING:
                            // Log.d(TAG, "Received ACTION_RESUME_SCREEN_RECORDING");
                            if (screenRecordingState == ScreenRecordingState.PAUSED) {
                                if (mediaProjectionHelper != null) {
                                    mediaProjectionHelper.resumeScreenRecording();
                                }
                            }
                            break;
                            
                        case Constants.ACTION_STOP_SCREEN_RECORDING:
                            // Log.d(TAG, "Received ACTION_STOP_SCREEN_RECORDING");
                            if (screenRecordingState != ScreenRecordingState.NONE) {
                                if (mediaProjectionHelper != null) {
                                    mediaProjectionHelper.stopScreenRecording();
                                }
                            }
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
            // Log.d(TAG, "Screen recording broadcast receivers registered via LocalBroadcastManager");
        } catch (IllegalArgumentException e) {
            // Log.w(TAG, "Error registering screen recording receiver: " + e.getMessage());
            isScreenRecordingReceiverRegistered = false;
        }
    }

    /**
     * Update UI based on current recording state.
     */
    private void updateUIForRecordingState() {
        View rootView = getView();
        if (rootView == null) {
            // Log.w(TAG, "updateUIForRecordingState: rootView is null, skipping state update");
            return;
        }
        
        // Log.d(TAG, "updateUIForRecordingState: screenRecordingState=" + screenRecordingState + 
        //            ", Android=" + android.os.Build.VERSION.SDK_INT);
        
        MaterialButton buttonStartStop = rootView.findViewById(com.fadcam.R.id.buttonStartStop);
        MaterialButton buttonPauseResume = rootView.findViewById(com.fadcam.R.id.buttonPauseResume);
        
        if (buttonStartStop == null) {
            // Log.e(TAG, "updateUIForRecordingState: buttonStartStop is null!");
        }
        
        // Update Start/Stop button with animation
        if (buttonStartStop != null) {
            // Never allow this button to be disabled in FadRec.
            buttonStartStop.setEnabled(true);
            buttonStartStop.setClickable(true);
            buttonStartStop.setAlpha(1.0f);
            if (screenRecordingState == ScreenRecordingState.NONE) {
                // IDLE STATE: Green start button
                buttonStartStop.setText(com.fadcam.R.string.fadrec_start_screen_recording);
                buttonStartStop.setIcon(
                    AppCompatResources.getDrawable(requireContext(), com.fadcam.R.drawable.ic_play)
                );
                // Green color for start button
                animateButtonColor(buttonStartStop, android.graphics.Color.parseColor("#4CAF50"));
            } else {
                // RECORDING STATE: Red stop button
                buttonStartStop.setText(com.fadcam.R.string.button_stop);
                buttonStartStop.setIcon(
                    AppCompatResources.getDrawable(requireContext(), com.fadcam.R.drawable.ic_stop)
                );
                // Red color for stop button
                animateButtonColor(buttonStartStop, 
                    androidx.core.content.ContextCompat.getColor(requireContext(), com.fadcam.R.color.button_stop));
            }
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
                    AppCompatResources.getDrawable(requireContext(), com.fadcam.R.drawable.ic_pause)
                );
            } else if (screenRecordingState == ScreenRecordingState.IN_PROGRESS) {
                // Recording: Enable with pause icon (no text label)
                buttonPauseResume.setEnabled(true);
                buttonPauseResume.setAlpha(1.0f);
                buttonPauseResume.setIcon(
                    AppCompatResources.getDrawable(requireContext(), com.fadcam.R.drawable.ic_pause)
                );
            } else if (screenRecordingState == ScreenRecordingState.PAUSED) {
                // Paused: Enable with resume/play icon (no text label)
                buttonPauseResume.setEnabled(true);
                buttonPauseResume.setAlpha(1.0f);
                buttonPauseResume.setIcon(
                    AppCompatResources.getDrawable(requireContext(), com.fadcam.R.drawable.ic_play)
                );
            }
        }
        
        Log.d(TAG, "UI updated for state: " + screenRecordingState);
        
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
    
    /**
     * Animate button background color change
     */
    private void animateButtonColor(MaterialButton button, int toColor) {
        try {
            android.animation.ValueAnimator colorAnimator = android.animation.ValueAnimator.ofArgb(
                ((android.graphics.drawable.ColorDrawable) button.getBackground()).getColor(),
                toColor
            );
            colorAnimator.setDuration(300);
            colorAnimator.addUpdateListener(animator -> 
                button.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf((Integer) animator.getAnimatedValue())
                )
            );
            colorAnimator.start();
        } catch (Exception e) {
            // Fallback to instant change
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(toColor));
        }
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
     * Override parent's storage/timer update to show screen recording elapsed/remaining time.
     * This updates the timer cards with screen recording-specific data instead of camera data.
     */
    @Override
    protected void updateStorageInfo() {
        // Call parent first to update storage info (estimate time card)
        super.updateStorageInfo();
        
        // Now override elapsed and remaining time for screen recording
        if (!isAdded() || getActivity() == null) {
            return;
        }
        
        try {
            long elapsedTime = 0;
            long remainingTime = 0;
            
            // Calculate elapsed time if recording
            if (screenRecordingState == ScreenRecordingState.IN_PROGRESS || 
                screenRecordingState == ScreenRecordingState.PAUSED) {
                
                // Get recording start time from SharedPreferences (set by service)
                long recordingStartTime = sharedPreferencesManager.sharedPreferences.getLong(
                    "screen_recording_start_time", 0
                );
                
                if (recordingStartTime > 0) {
                    elapsedTime = Math.max(0, SystemClock.elapsedRealtime() - recordingStartTime);
                }
                
                // Calculate remaining time based on storage and bitrate
                // For screen recording, we use video bitrate from settings
                try {
                    // Get storage info from cache
                    StorageInfoCache.StorageInfo storageInfo = 
                        StorageInfoCache.getCachedStorageInfo();
                    
                    if (storageInfo == null) {
                        // No cache, skip remaining time calculation
                        remainingTime = 0;
                    } else {
                        long availableBytes = storageInfo.availableBytes;
                        
                        // Estimate bytes used so far
                        long videoBitrate = sharedPreferencesManager.getCurrentBitrate(); // bits per second
                        long audioBitrate = sharedPreferencesManager.getAudioBitrate(); // bits per second
                        long totalBitrate = videoBitrate + audioBitrate;
                        
                        if (elapsedTime > 0 && totalBitrate > 0) {
                            long estimatedBytesUsed = (elapsedTime * totalBitrate) / 8000; // Convert to bytes
                            availableBytes = Math.max(0, availableBytes - estimatedBytesUsed);
                        }
                        
                        // Calculate remaining time
                        if (totalBitrate > 0 && availableBytes > 0) {
                            remainingTime = (availableBytes * 8) / totalBitrate; // Convert bytes to seconds
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error calculating remaining time", e);
                    remainingTime = 0;
                }
            }
            
            // Format elapsed time
            long elapsedMinutes = elapsedTime / 60000;
            long elapsedSeconds = (elapsedTime / 1000) % 60;
            final String elapsedTimeText = String.format(
                java.util.Locale.getDefault(),
                "%02d:%02d",
                elapsedMinutes,
                elapsedSeconds
            );
            
            // Format remaining time
            long days = remainingTime / (24 * 3600);
            long hours = (remainingTime % (24 * 3600)) / 3600;
            long minutes = (remainingTime % 3600) / 60;
            long seconds = remainingTime % 60;
            
            String remainingTimeText;
            if (days > 0) {
                remainingTimeText = String.format(
                    java.util.Locale.getDefault(),
                    "%dd %02dh %02dm",
                    days, hours, minutes
                );
            } else if (hours > 0) {
                remainingTimeText = String.format(
                    java.util.Locale.getDefault(),
                    "%02dh %02dm",
                    hours, minutes
                );
            } else {
                remainingTimeText = String.format(
                    java.util.Locale.getDefault(),
                    "%02d:%02d",
                    minutes, seconds
                );
            }
            
            // Update UI on main thread
            getActivity().runOnUiThread(() -> {
                // Update camera info card (override parent's camera text)
                updateScreenRecordingCardInfo();
                
                // Update elapsed time
                if (tvElapsedTitle != null) {
                    tvElapsedTitle.setText(elapsedTimeText);
                }
                if (tvElapsedSubtitle != null) {
                    tvElapsedSubtitle.setText(getString(R.string.recording_elapsed_time));
                }
                
                // Update remaining time
                if (tvRemainingTitle != null) {
                    tvRemainingTitle.setText(remainingTimeText);
                }
                if (tvRemainingSubtitle != null) {
                    tvRemainingSubtitle.setText(getString(R.string.recording_remaining_time));
                }
            });
            
            Log.d(TAG, "Timer updated - Elapsed: " + elapsedTimeText + ", Remaining: " + remainingTimeText);
        } catch (Exception e) {
            Log.e(TAG, "Error in updateStorageInfo override", e);
        }
    }

    /**
     * Start the timer that updates elapsed/remaining time every second during recording.
     */
    private void startTimerUpdates() {
        // Stop any existing timer first
        stopTimerUpdates();
        
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
                    Log.d(TAG, "Timer stopped - not recording");
                }
            }
        };
        
        // Start the timer immediately
        timerHandler.post(timerUpdateRunnable);
        Log.d(TAG, "Timer updates started");
    }
    
    /**
     * Stop the timer updates.
     */
    private void stopTimerUpdates() {
        if (timerUpdateRunnable != null) {
            timerHandler.removeCallbacks(timerUpdateRunnable);
            timerUpdateRunnable = null;
            Log.d(TAG, "Timer updates stopped");
        }
    }

    @Override
    public void onResume() {
        reconcileScreenRecordingStateWithReality();
        
        super.onResume(); // MUST call super - Android requirement
        // NOTE: Parent's onResume() calls fetchRecordingState() which starts RecordingService (camera recording)
        // This is WRONG for FadRec. We use ScreenRecordingService which broadcasts state via LocalBroadcastManager
        // Workaround: We create shadow methods below that do nothing to prevent parent from affecting us
        
        Log.d(TAG, "FadRecHomeFragment resumed");
        
        // CRITICAL FIX: Re-setup button handlers after parent's onResume() to ensure
        // screen recording click listeners override any camera recording listeners
        // that the parent may have set up
        if (getView() != null) {
            setupButtonHandlers(getView());
            Log.d(TAG, "Button handlers re-setup after parent onResume()");
        }
        
        // CRITICAL FIX: Force update button availability to override parent's camera-based logic
        // Parent disables button based on camera resource availability, but we need it always enabled
        updateStartButtonAvailability();
        Log.e(TAG, "!!! updateStartButtonAvailability() called at end of onResume !!!");

        // Sync UI with persisted state after tab switches / fragment resume.
        updateUIForRecordingState();
        
        // CRITICAL FIX: If annotation service is already running (app reopened with service active),
        // dismiss any loading dialog since onCreate() won't be called again and READY broadcast won't fire
        if (sharedPreferencesManager.isFloatingControlsEnabled() && loadingDialog != null && loadingDialog.isShowing()) {
            loadingDialog.dismiss();
            Log.d(TAG, "Service already running - dismissed loading dialog on resume");
        }
        
        // Parent's onResume() queries camera state and calls resetUIButtonsToIdleState()
        // Since we've overridden resetUIButtonsToIdleState(), our version runs instead
        // Our override keeps pause enabled and camera controls hidden - no timing hacks needed!
        
        // NOTE: Don't request state or load persisted state
        // The broadcast receiver is registered in onCreate() and will listen for state changes
        // from ScreenRecordingService. The UI will update automatically when broadcasts arrive.
        // This avoids the timing issue where state requests arrive before recording fully starts.
        
        // Timer updates are handled by updateUIForRecordingState().
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
            Log.w(TAG, "Failed to check ScreenRecordingService running state", e);
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
                Log.w(TAG, "Clearing stale screen recording state (service not running). savedState=" + savedState);
            }
            screenRecordingState = ScreenRecordingState.NONE;
            sharedPreferencesManager.setScreenRecordingInProgress(false);
            sharedPreferencesManager.setScreenRecordingState(ScreenRecordingState.NONE.name());
            try {
                sharedPreferencesManager.sharedPreferences.edit().remove("screen_recording_start_time").apply();
            } catch (Exception e) {
                Log.w(TAG, "Failed clearing screen_recording_start_time", e);
            }
            return;
        }

        // Service is running. Use persisted state as a hint, then immediately query service
        // to broadcast the authoritative current state when we suspect prefs might be stale.
        screenRecordingState = restoredState;

        // If prefs already say NONE, don't spam a query during the START flow; we'll get
        // real broadcasts from the service as it transitions to IN_PROGRESS.
        if (restoredState != ScreenRecordingState.NONE || sharedPreferencesManager.isScreenRecordingInProgress()) {
            Log.d(TAG, "onResume: ScreenRecordingService running, restoredState=" + restoredState + " - querying");
            try {
                Intent queryIntent = new Intent(requireContext(), ScreenRecordingService.class);
                queryIntent.setAction(Constants.INTENT_ACTION_QUERY_SCREEN_RECORDING_STATE);
                requireContext().startService(queryIntent);
            } catch (Exception e) {
                Log.w(TAG, "Failed to query ScreenRecordingService state", e);
            }
        } else {
            Log.d(TAG, "onResume: ScreenRecordingService running, restoredState=NONE - waiting for broadcasts");
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.d(TAG, "FadRecHomeFragment paused");
        
        // Stop timer updates when fragment is paused
        stopTimerUpdates();
    }

    @Override
    public void onStop() {
        super.onStop();
        Log.d(TAG, "FadRecHomeFragment onStop");
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
        Log.d(TAG, "fetchRecordingState: Overridden (skipped) - FadRec uses screen recording service");
    }

    /**
     * Override registerBroadcastReceivers to prevent parent from registering camera recording receivers.
     * FadRec uses screen recording receivers which are already registered in onCreate().
     */
    @Override
    protected void registerBroadcastReceivers() {
        // DO NOT call super.registerBroadcastReceivers() - it registers RecordingService (camera recording) receivers
        // FadRec screen recording receivers are already registered in onCreate() via registerScreenRecordingStateReceiver()
        Log.d(TAG, "registerBroadcastReceivers: Overridden (skipped) - FadRec receivers already registered");
    }
}
