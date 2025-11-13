package com.fadcam.fadrec.ui;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.fadcam.Constants;
import com.fadcam.R;
import com.fadcam.fadrec.ScreenRecordingState;

/**
 * Floating assistive touch overlay for FadRec screen recording controls.
 * Provides a draggable button that expands to show recording controls.
 */
public class FloatingControlsService extends Service {
    private static final String TAG = "FloatingControlsService";
    
    private WindowManager windowManager;
    private View floatingView;
    private View unifiedMenuView;
    private TextView btnFloating;
    private View btnStartStop, btnPauseResume;
    private View btnCloseMenu;
    private TextView iconStartStop, labelStartStop;
    private TextView iconPauseResume, labelPauseResume;
    
    // Annotations section
    private View annotationsHeader;
    private TextView annotationsExpandIcon;
    private View annotationsContent;
    private androidx.appcompat.widget.SwitchCompat annotationSwitch;
    private androidx.appcompat.widget.SwitchCompat snapGuidesSwitch;
    private View btnAddText, btnAddShape;
    
    private boolean isMenuExpanded = false;
    private boolean isAnnotationsExpanded = false;
    private boolean isAnnotationActive = false;
    private ScreenRecordingState recordingState = ScreenRecordingState.NONE;
    
    private int initialX, initialY;
    private float initialTouchX, initialTouchY;
    
    private BroadcastReceiver stateReceiver;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "FloatingControlsService created");
        
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        // Create floating button
        createFloatingButton();
        
        // Register state receiver
        registerStateReceiver();
    }

    private void createFloatingButton() {
        // Inflate floating button layout
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_controls_button, null);
        btnFloating = floatingView.findViewById(R.id.btnFloating);
        
        // Set up window parameters
        int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 100;
        
        // Add view to window
        windowManager.addView(floatingView, params);
        
        // Set up touch listener for dragging and clicking
        floatingView.setOnTouchListener(new View.OnTouchListener() {
            private static final int CLICK_DRAG_TOLERANCE = 10;
            private long touchStartTime = 0;
            
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        touchStartTime = System.currentTimeMillis();
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                        
                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(floatingView, params);
                        return true;
                        
                    case MotionEvent.ACTION_UP:
                        long touchDuration = System.currentTimeMillis() - touchStartTime;
                        float deltaX = Math.abs(event.getRawX() - initialTouchX);
                        float deltaY = Math.abs(event.getRawY() - initialTouchY);
                        
                        // If it's a click (not a drag)
                        if (touchDuration < 200 && deltaX < CLICK_DRAG_TOLERANCE && deltaY < CLICK_DRAG_TOLERANCE) {
                            toggleQuickMenu();
                        }
                        return true;
                }
                return false;
            }
        });
        
        updateFloatingButtonState();
    }

    private void toggleQuickMenu() {
        if (isMenuExpanded) {
            hideQuickMenu();
        } else {
            showQuickMenu();
        }
    }

    private void showQuickMenu() {
        if (unifiedMenuView != null) return;
        
        // Inflate unified menu layout
        unifiedMenuView = LayoutInflater.from(this).inflate(R.layout.floating_unified_menu, null);
        
        // Recording controls
        btnStartStop = unifiedMenuView.findViewById(R.id.btnStartStopRec);
        btnPauseResume = unifiedMenuView.findViewById(R.id.btnPauseResumeRec);
        btnCloseMenu = unifiedMenuView.findViewById(R.id.btnCloseMenu);
        
        iconStartStop = unifiedMenuView.findViewById(R.id.iconStartStop);
        labelStartStop = unifiedMenuView.findViewById(R.id.labelStartStop);
        iconPauseResume = unifiedMenuView.findViewById(R.id.iconPauseResume);
        labelPauseResume = unifiedMenuView.findViewById(R.id.labelPauseResume);
        
        // Annotations section
        annotationsHeader = unifiedMenuView.findViewById(R.id.annotationsHeader);
        annotationsExpandIcon = unifiedMenuView.findViewById(R.id.annotationsExpandIcon);
        annotationsContent = unifiedMenuView.findViewById(R.id.annotationsContent);
        annotationSwitch = unifiedMenuView.findViewById(R.id.annotationSwitch);
        snapGuidesSwitch = unifiedMenuView.findViewById(R.id.snapGuidesSwitch);
        btnAddText = unifiedMenuView.findViewById(R.id.btnAddText);
        btnAddShape = unifiedMenuView.findViewById(R.id.btnAddShape);
        
        // Set up recording control listeners
        btnStartStop.setOnClickListener(v -> {
            if (btnStartStop.isEnabled()) {
                if (recordingState == ScreenRecordingState.NONE) {
                    // Start recording
                    sendBroadcast(new Intent(Constants.ACTION_START_SCREEN_RECORDING_FROM_OVERLAY));
                } else {
                    // Stop recording (works for both IN_PROGRESS and PAUSED)
                    sendBroadcast(new Intent(Constants.ACTION_STOP_SCREEN_RECORDING));
                }
            }
        });
        
        btnPauseResume.setOnClickListener(v -> {
            if (btnPauseResume.isEnabled()) {
                if (recordingState == ScreenRecordingState.IN_PROGRESS) {
                    // Pause recording
                    sendBroadcast(new Intent(Constants.ACTION_PAUSE_SCREEN_RECORDING));
                } else if (recordingState == ScreenRecordingState.PAUSED) {
                    // Resume recording
                    sendBroadcast(new Intent(Constants.ACTION_RESUME_SCREEN_RECORDING));
                }
            }
        });
        
        btnCloseMenu.setOnClickListener(v -> {
            hideQuickMenu();
        });
        
        // Annotations header toggle
        annotationsHeader.setOnClickListener(v -> {
            toggleAnnotationsSection();
        });
        
        // Annotation switch listener
        annotationSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                startAnnotations();
            } else {
                stopAnnotations();
            }
        });
        
        // Snap guides switch listener
        snapGuidesSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            // Send broadcast to AnnotationView to toggle snap guides
            Intent intent = new Intent("com.fadcam.fadrec.TOGGLE_SNAP_GUIDES");
            intent.putExtra("enabled", isChecked);
            sendBroadcast(intent);
        });
        
        // Add text button
        btnAddText.setOnClickListener(v -> {
            Intent intent = new Intent("com.fadcam.fadrec.ADD_TEXT");
            sendBroadcast(intent);
        });
        
        // Add shape button
        btnAddShape.setOnClickListener(v -> {
            Intent intent = new Intent("com.fadcam.fadrec.ADD_SHAPE");
            sendBroadcast(intent);
        });
        
        // Set up window parameters for menu
        int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        
        WindowManager.LayoutParams menuParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        
        menuParams.gravity = Gravity.CENTER;
        
        windowManager.addView(unifiedMenuView, menuParams);
        isMenuExpanded = true;
        
        updateQuickMenuButtons();
    }

    private void toggleAnnotationsSection() {
        isAnnotationsExpanded = !isAnnotationsExpanded;
        
        if (isAnnotationsExpanded) {
            annotationsContent.setVisibility(View.VISIBLE);
            annotationsExpandIcon.setText("expand_less");
        } else {
            annotationsContent.setVisibility(View.GONE);
            annotationsExpandIcon.setText("expand_more");
        }
    }

    private void hideQuickMenu() {
        if (unifiedMenuView != null) {
            windowManager.removeView(unifiedMenuView);
            unifiedMenuView = null;
            isMenuExpanded = false;
            isAnnotationsExpanded = false;
        }
    }

    private void updateFloatingButtonState() {
        if (btnFloating == null) return;
        
        // Always show chevron_right icon - menu expands on click
        btnFloating.setText("chevron_right");
        btnFloating.setTextColor(getResources().getColor(android.R.color.white));
    }

    private void updateQuickMenuButtons() {
        if (unifiedMenuView == null) return;
        
        switch (recordingState) {
            case NONE:
                // Start/Stop button shows "Start" (enabled)
                btnStartStop.setEnabled(true);
                iconStartStop.setText("fiber_manual_record");
                iconStartStop.setTextColor(getResources().getColor(android.R.color.holo_green_light));
                labelStartStop.setText(R.string.floating_menu_start_short);
                
                // Pause/Resume button is disabled
                btnPauseResume.setEnabled(false);
                iconPauseResume.setText("pause");
                iconPauseResume.setTextColor(getResources().getColor(android.R.color.darker_gray));
                labelPauseResume.setText(R.string.floating_menu_pause);
                break;
                
            case IN_PROGRESS:
                // Start/Stop button shows "Stop" (enabled)
                btnStartStop.setEnabled(true);
                iconStartStop.setText("stop");
                iconStartStop.setTextColor(getResources().getColor(android.R.color.holo_red_light));
                labelStartStop.setText(R.string.floating_menu_stop_short);
                
                // Pause/Resume button shows "Pause" (enabled)
                btnPauseResume.setEnabled(true);
                iconPauseResume.setText("pause");
                iconPauseResume.setTextColor(getResources().getColor(android.R.color.holo_orange_light));
                labelPauseResume.setText(R.string.floating_menu_pause);
                break;
                
            case PAUSED:
                // Start/Stop button shows "Stop" (enabled)
                btnStartStop.setEnabled(true);
                iconStartStop.setText("stop");
                iconStartStop.setTextColor(getResources().getColor(android.R.color.holo_red_light));
                labelStartStop.setText(R.string.floating_menu_stop_short);
                
                // Pause/Resume button shows "Resume" (enabled)
                btnPauseResume.setEnabled(true);
                iconPauseResume.setText("play_arrow");
                iconPauseResume.setTextColor(getResources().getColor(android.R.color.holo_green_light));
                labelPauseResume.setText(R.string.floating_menu_resume);
                break;
        }
    }
    
    private void startAnnotations() {
        Intent intent = new Intent(this, AnnotationService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        isAnnotationActive = true;
    }
    
    private void stopAnnotations() {
        stopService(new Intent(this, AnnotationService.class));
        isAnnotationActive = false;
    }
    
    private void registerStateReceiver() {
        stateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String stateStr = intent.getStringExtra("recordingState");
                if (stateStr != null) {
                    try {
                        recordingState = ScreenRecordingState.valueOf(stateStr);
                        updateFloatingButtonState();
                        updateQuickMenuButtons();
                    } catch (IllegalArgumentException e) {
                        Log.e(TAG, "Invalid state: " + stateStr, e);
                    }
                }
            }
        };
        
        IntentFilter filter = new IntentFilter(Constants.BROADCAST_ON_SCREEN_RECORDING_STATE_CALLBACK);
        // Use LocalBroadcastManager for guaranteed delivery on Android 12+
        LocalBroadcastManager.getInstance(this).registerReceiver(stateReceiver, filter);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "FloatingControlsService destroyed");
        
        if (floatingView != null) {
            windowManager.removeView(floatingView);
        }
        
        if (unifiedMenuView != null) {
            windowManager.removeView(unifiedMenuView);
        }
        
        if (stateReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver);
        }
    }
}
