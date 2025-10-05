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
import android.widget.TextView;
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
    
    // Debouncing for button clicks to prevent rapid start/stop
    private long lastClickTime = 0;
    private static final long DEBOUNCE_DELAY_MS = 500; // 500ms debounce

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
            
            Log.d(TAG, "UI customization complete");
        } catch (Exception e) {
            Log.e(TAG, "Error customizing UI for screen recording", e);
        }
    }

    /**
     * Hide camera-specific controls that are not relevant for screen recording.
     */
    private void hideCameraControls(View rootView) {
        // Hide camera switch button
        View buttonCamSwitch = rootView.findViewById(com.fadcam.R.id.buttonCamSwitch);
        if (buttonCamSwitch != null) {
            buttonCamSwitch.setVisibility(View.GONE);
            Log.d(TAG, "Camera switch button hidden");
        }
        
        // Hide torch button
        View buttonTorchSwitch = rootView.findViewById(com.fadcam.R.id.buttonTorchSwitch);
        if (buttonTorchSwitch != null) {
            buttonTorchSwitch.setVisibility(View.GONE);
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
        
        // Replace preview card content with custom FadRec screen icon
        replacePreviewWithScreenIcon(rootView);
        
        Log.d(TAG, "Camera controls hidden");
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
            
            // IMPORTANT: Disable long press listener from parent HomeFragment
            // FadRec doesn't need camera preview toggle functionality
            previewCard.setOnLongClickListener(null);
            previewCard.setLongClickable(false);
            
            Log.d(TAG, "Preview card replaced with screen recording icon, long press disabled");
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
     * Setup button click handlers for start/stop/pause/resume.
     */
    private void setupButtonHandlers(View rootView) {
        // Find buttons
        MaterialButton buttonStartStop = rootView.findViewById(com.fadcam.R.id.buttonStartStop);
        MaterialButton buttonPauseResume = rootView.findViewById(com.fadcam.R.id.buttonPauseResume);
        
        // Load persisted state on initialization
        loadPersistedRecordingState();
        
        // Initially hide pause button (will appear during recording)
        if (buttonPauseResume != null && screenRecordingState == ScreenRecordingState.NONE) {
            buttonPauseResume.setVisibility(View.GONE);
        }
        
        // Start/Stop button
        if (buttonStartStop != null) {
            buttonStartStop.setOnClickListener(v -> {
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
        }
        
        Log.d(TAG, "Button handlers setup complete");
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
                        persistRecordingState(screenRecordingState);
                        updateUIForRecordingState();
                        Toast.makeText(context, com.fadcam.R.string.fadrec_screen_recording_started, 
                            Toast.LENGTH_SHORT).show();
                        break;
                        
                    case Constants.BROADCAST_ON_SCREEN_RECORDING_STOPPED:
                        screenRecordingState = ScreenRecordingState.NONE;
                        persistRecordingState(screenRecordingState);
                        updateUIForRecordingState();
                        Toast.makeText(context, com.fadcam.R.string.fadrec_screen_recording_stopped, 
                            Toast.LENGTH_SHORT).show();
                        break;
                        
                    case Constants.BROADCAST_ON_SCREEN_RECORDING_PAUSED:
                        screenRecordingState = ScreenRecordingState.PAUSED;
                        persistRecordingState(screenRecordingState);
                        updateUIForRecordingState();
                        Toast.makeText(context, com.fadcam.R.string.fadrec_screen_recording_paused, 
                            Toast.LENGTH_SHORT).show();
                        break;
                        
                    case Constants.BROADCAST_ON_SCREEN_RECORDING_RESUMED:
                        screenRecordingState = ScreenRecordingState.IN_PROGRESS;
                        persistRecordingState(screenRecordingState);
                        updateUIForRecordingState();
                        Toast.makeText(context, com.fadcam.R.string.fadrec_screen_recording_resumed, 
                            Toast.LENGTH_SHORT).show();
                        break;
                        
                    case Constants.BROADCAST_ON_SCREEN_RECORDING_STATE_CALLBACK:
                        String stateStr = intent.getStringExtra("recordingState");
                        if (stateStr != null) {
                            try {
                                screenRecordingState = ScreenRecordingState.valueOf(stateStr);
                                persistRecordingState(screenRecordingState);
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
        
        // Update Start/Stop button with animation
        if (buttonStartStop != null) {
            if (screenRecordingState == ScreenRecordingState.NONE) {
                // IDLE STATE: Show only start button
                buttonStartStop.setText(com.fadcam.R.string.fadrec_start_screen_recording);
                buttonStartStop.setIcon(
                    AppCompatResources.getDrawable(requireContext(), com.fadcam.R.drawable.ic_play)
                );
                // Green color for start button
                animateButtonColor(buttonStartStop, android.graphics.Color.parseColor("#4CAF50"));
                
                // Hide pause button when idle
                if (buttonPauseResume != null) {
                    animateButtonVisibility(buttonPauseResume, false);
                }
            } else {
                // RECORDING STATE: Show stop button (red)
                buttonStartStop.setText(com.fadcam.R.string.button_stop);
                buttonStartStop.setIcon(
                    AppCompatResources.getDrawable(requireContext(), com.fadcam.R.drawable.ic_stop)
                );
                // Red color for stop button
                animateButtonColor(buttonStartStop, 
                    androidx.core.content.ContextCompat.getColor(requireContext(), com.fadcam.R.color.button_stop));
                
                // Show pause button when recording
                if (buttonPauseResume != null) {
                    animateButtonVisibility(buttonPauseResume, true);
                }
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

    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "FadRecHomeFragment resumed");
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
