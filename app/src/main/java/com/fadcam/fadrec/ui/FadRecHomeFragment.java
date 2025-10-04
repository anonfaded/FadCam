package com.fadcam.fadrec.ui;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.ContextCompat;

import com.fadcam.Constants;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.fadrec.MediaProjectionHelper;
import com.fadcam.fadrec.ScreenRecordingState;
import com.fadcam.ui.HomeFragment;
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
    
    // Screen recording state
    private ScreenRecordingState screenRecordingState = ScreenRecordingState.NONE;
    private SharedPreferencesManager sharedPreferencesManager;
    
    // Broadcast receivers for screen recording state
    private BroadcastReceiver screenRecordingStateReceiver;

    /**
     * Create a new instance of FadRecHomeFragment.
     */
    public static FadRecHomeFragment newInstance() {
        return new FadRecHomeFragment();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "FadRecHomeFragment created");
        
        // Initialize SharedPreferences
        sharedPreferencesManager = SharedPreferencesManager.getInstance(requireContext());
        
        // Initialize MediaProjection helper
        mediaProjectionHelper = new MediaProjectionHelper(requireContext());
        
        // Register audio permission launcher
        audioPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            isGranted -> {
                if (isGranted) {
                    Log.d(TAG, "Audio permission granted, proceeding with screen capture");
                    requestScreenCapturePermission();
                } else {
                    Log.w(TAG, "Audio permission denied, starting without audio");
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
                Log.d(TAG, "Screen capture result: resultCode=" + resultCode + 
                      " (RESULT_OK=" + Activity.RESULT_OK + "), data=" + (data != null ? "present" : "null"));
                
                if (resultCode == Activity.RESULT_OK && data != null) {
                    Log.d(TAG, "Screen capture permission granted, starting recording");
                    // RESULT_OK is -1, so we pass Activity.RESULT_OK constant instead
                    mediaProjectionHelper.startScreenRecording(Activity.RESULT_OK, data);
                } else {
                    Log.w(TAG, "Screen capture permission denied: resultCode=" + resultCode);
                    Toast.makeText(requireContext(), 
                        com.fadcam.R.string.fadrec_permission_denied, 
                        Toast.LENGTH_SHORT).show();
                }
            }
        );
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Apply FadRec-specific UI customizations
        customizeUIForScreenRecording(view);
        
        // Setup button click handlers
        setupButtonHandlers(view);
        
        // Register broadcast receivers
        registerScreenRecordingReceivers();
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
            
            // Update info cards to show screen recording info
            updateInfoCardsForScreenMode();
            
            Log.d(TAG, "UI customization complete");
        } catch (Exception e) {
            Log.e(TAG, "Error customizing UI for screen recording", e);
        }
    }

    /**
     * Hide camera-specific controls that are not relevant for screen recording.
     */
    private void hideCameraControls(View rootView) {
        // Camera switch button
        View buttonCamSwitch = rootView.findViewById(com.fadcam.R.id.buttonCamSwitch);
        if (buttonCamSwitch != null) {
            buttonCamSwitch.setVisibility(View.GONE);
        }
        
        // Torch button
        View buttonTorchSwitch = rootView.findViewById(com.fadcam.R.id.buttonTorchSwitch);
        if (buttonTorchSwitch != null) {
            buttonTorchSwitch.setVisibility(View.GONE);
        }
        
        // Hide entire preview area including TextureView and placeholder
        View textureView = rootView.findViewById(com.fadcam.R.id.textureView);
        if (textureView != null) {
            textureView.setVisibility(View.GONE);
        }
        
        View tvPreviewPlaceholder = rootView.findViewById(com.fadcam.R.id.tvPreviewPlaceholder);
        if (tvPreviewPlaceholder != null) {
            tvPreviewPlaceholder.setVisibility(View.GONE);
        }
        
        // Hide preview card container if it exists
        View previewCard = rootView.findViewById(com.fadcam.R.id.cardPreview);
        if (previewCard != null) {
            previewCard.setVisibility(View.GONE);
        }
        
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
        
        Log.d(TAG, "Camera controls hidden");
    }

    /**
     * Update info cards to show screen recording information.
     * Overrides camera info with device screen resolution.
     */
    private void updateInfoCardsForScreenMode() {
        try {
            // Get device screen resolution
            WindowManager windowManager = 
                (WindowManager) requireContext().getSystemService(Context.WINDOW_SERVICE);
            
            if (windowManager != null) {
                DisplayMetrics metrics = new DisplayMetrics();
                windowManager.getDefaultDisplay().getRealMetrics(metrics);
                
                String screenResolution = metrics.widthPixels + "x" + metrics.heightPixels;
                
                // Update camera card title to show screen resolution
                View rootView = getView();
                if (rootView != null) {
                    android.widget.TextView tvCameraTitle = 
                        rootView.findViewById(com.fadcam.R.id.tvCameraTitle);
                    if (tvCameraTitle != null) {
                        tvCameraTitle.setText(screenResolution);
                    }
                    
                    // Update subtitle
                    android.widget.TextView tvCameraSubtitle = 
                        rootView.findViewById(com.fadcam.R.id.tvCameraSubtitle);
                    if (tvCameraSubtitle != null) {
                        tvCameraSubtitle.setText("Screen Recording • FHD 30fps");
                    }
                    
                    // Update icon to screen_share
                    android.widget.TextView ivCameraIcon = 
                        rootView.findViewById(com.fadcam.R.id.ivCameraIcon);
                    if (ivCameraIcon != null) {
                        ivCameraIcon.setText("screen_share");
                    }
                }
                
                Log.d(TAG, "Info cards updated with screen resolution: " + screenResolution);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating info cards for screen mode", e);
        }
    }

    /**
     * Setup button click handlers for start/stop/pause/resume.
     */
    private void setupButtonHandlers(View rootView) {
        // Find buttons
        MaterialButton buttonStartStop = rootView.findViewById(com.fadcam.R.id.buttonStartStop);
        MaterialButton buttonPauseResume = rootView.findViewById(com.fadcam.R.id.buttonPauseResume);
        
        // Start/Stop button
        if (buttonStartStop != null) {
            buttonStartStop.setOnClickListener(v -> {
                if (screenRecordingState == ScreenRecordingState.NONE) {
                    // Start recording
                    requestScreenRecordingPermissionAndStart();
                } else {
                    // Stop recording
                    stopScreenRecording();
                }
            });
        }
        
        // Pause/Resume button
        if (buttonPauseResume != null) {
            buttonPauseResume.setOnClickListener(v -> {
                if (screenRecordingState == ScreenRecordingState.IN_PROGRESS) {
                    // Pause recording
                    mediaProjectionHelper.pauseScreenRecording();
                } else if (screenRecordingState == ScreenRecordingState.PAUSED) {
                    // Resume recording
                    mediaProjectionHelper.resumeScreenRecording();
                }
            });
        }
        
        Log.d(TAG, "Button handlers setup complete");
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
        screenRecordingStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;
                
                switch (action) {
                    case Constants.BROADCAST_ON_SCREEN_RECORDING_STARTED:
                        screenRecordingState = ScreenRecordingState.IN_PROGRESS;
                        updateUIForRecordingState();
                        Toast.makeText(context, com.fadcam.R.string.fadrec_screen_recording_started, 
                            Toast.LENGTH_SHORT).show();
                        break;
                        
                    case Constants.BROADCAST_ON_SCREEN_RECORDING_STOPPED:
                        screenRecordingState = ScreenRecordingState.NONE;
                        updateUIForRecordingState();
                        Toast.makeText(context, com.fadcam.R.string.fadrec_screen_recording_stopped, 
                            Toast.LENGTH_SHORT).show();
                        break;
                        
                    case Constants.BROADCAST_ON_SCREEN_RECORDING_PAUSED:
                        screenRecordingState = ScreenRecordingState.PAUSED;
                        updateUIForRecordingState();
                        Toast.makeText(context, com.fadcam.R.string.fadrec_screen_recording_paused, 
                            Toast.LENGTH_SHORT).show();
                        break;
                        
                    case Constants.BROADCAST_ON_SCREEN_RECORDING_RESUMED:
                        screenRecordingState = ScreenRecordingState.IN_PROGRESS;
                        updateUIForRecordingState();
                        Toast.makeText(context, com.fadcam.R.string.fadrec_screen_recording_resumed, 
                            Toast.LENGTH_SHORT).show();
                        break;
                        
                    case Constants.BROADCAST_ON_SCREEN_RECORDING_STATE_CALLBACK:
                        String stateStr = intent.getStringExtra("recordingState");
                        if (stateStr != null) {
                            try {
                                screenRecordingState = ScreenRecordingState.valueOf(stateStr);
                                updateUIForRecordingState();
                            } catch (IllegalArgumentException e) {
                                Log.e(TAG, "Invalid state: " + stateStr, e);
                            }
                        }
                        break;
                }
            }
        };
        
        // Register for all screen recording broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(Constants.BROADCAST_ON_SCREEN_RECORDING_STARTED);
        filter.addAction(Constants.BROADCAST_ON_SCREEN_RECORDING_STOPPED);
        filter.addAction(Constants.BROADCAST_ON_SCREEN_RECORDING_PAUSED);
        filter.addAction(Constants.BROADCAST_ON_SCREEN_RECORDING_RESUMED);
        filter.addAction(Constants.BROADCAST_ON_SCREEN_RECORDING_STATE_CALLBACK);
        
        requireContext().registerReceiver(screenRecordingStateReceiver, filter);
        Log.d(TAG, "Screen recording broadcast receivers registered");
    }

    /**
     * Update UI based on current recording state.
     */
    private void updateUIForRecordingState() {
        View rootView = getView();
        if (rootView == null) return;
        
        MaterialButton buttonStartStop = rootView.findViewById(com.fadcam.R.id.buttonStartStop);
        MaterialButton buttonPauseResume = rootView.findViewById(com.fadcam.R.id.buttonPauseResume);
        
        // Update Start/Stop button
        if (buttonStartStop != null) {
            if (screenRecordingState == ScreenRecordingState.NONE) {
                buttonStartStop.setText(com.fadcam.R.string.fadrec_start_screen_recording);
                buttonStartStop.setIcon(
                    AppCompatResources.getDrawable(requireContext(), com.fadcam.R.drawable.ic_play)
                );
                // Green color for start button
                buttonStartStop.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#4CAF50")
                    )
                );
            } else {
                buttonStartStop.setText(com.fadcam.R.string.button_stop);
                buttonStartStop.setIcon(
                    AppCompatResources.getDrawable(requireContext(), com.fadcam.R.drawable.ic_stop)
                );
                // Red color for stop button
                buttonStartStop.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(
                        androidx.core.content.ContextCompat.getColor(requireContext(), com.fadcam.R.color.button_stop)
                    )
                );
            }
        }
        
        // Update Pause/Resume button
        if (buttonPauseResume != null) {
            if (screenRecordingState == ScreenRecordingState.IN_PROGRESS) {
                buttonPauseResume.setVisibility(View.VISIBLE);
                buttonPauseResume.setText(com.fadcam.R.string.button_pause);
                buttonPauseResume.setIcon(
                    AppCompatResources.getDrawable(requireContext(), com.fadcam.R.drawable.ic_pause)
                );
            } else if (screenRecordingState == ScreenRecordingState.PAUSED) {
                buttonPauseResume.setVisibility(View.VISIBLE);
                buttonPauseResume.setText(com.fadcam.R.string.button_resume);
                buttonPauseResume.setIcon(
                    AppCompatResources.getDrawable(requireContext(), com.fadcam.R.drawable.ic_play)
                );
            } else {
                buttonPauseResume.setVisibility(View.GONE);
            }
        }
        
        Log.d(TAG, "UI updated for state: " + screenRecordingState);
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "FadRecHomeFragment resumed");
        
        // Check current recording state from preferences
        if (sharedPreferencesManager.isScreenRecordingInProgress()) {
            screenRecordingState = ScreenRecordingState.IN_PROGRESS;
            updateUIForRecordingState();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.d(TAG, "FadRecHomeFragment paused");
    }

    @Override
    public void onDestroyView() {
        Log.d(TAG, "FadRecHomeFragment view destroyed");
        
        // Unregister broadcast receivers
        if (screenRecordingStateReceiver != null) {
            try {
                requireContext().unregisterReceiver(screenRecordingStateReceiver);
                screenRecordingStateReceiver = null;
                Log.d(TAG, "Screen recording receivers unregistered");
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering receivers", e);
            }
        }
        
        super.onDestroyView();
    }
}
