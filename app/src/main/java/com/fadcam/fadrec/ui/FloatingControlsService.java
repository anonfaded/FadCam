package com.fadcam.fadrec.ui;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
    private View quickMenuView;
    private TextView btnFloating;
    private View btnStart, btnPause, btnResume, btnStop;
    private View btnCloseMenu;
    
    private boolean isMenuExpanded = false;
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
        if (quickMenuView != null) return;
        
        // Inflate quick menu layout
        quickMenuView = LayoutInflater.from(this).inflate(R.layout.floating_quick_menu, null);
        
        btnStart = quickMenuView.findViewById(R.id.btnStartRec);
        btnPause = quickMenuView.findViewById(R.id.btnPauseRec);
        btnResume = quickMenuView.findViewById(R.id.btnResumeRec);
        btnStop = quickMenuView.findViewById(R.id.btnStopRec);
        btnCloseMenu = quickMenuView.findViewById(R.id.btnCloseMenu);
        
        // Set up click listeners
        btnStart.setOnClickListener(v -> {
            sendBroadcast(new Intent(Constants.ACTION_START_SCREEN_RECORDING_FROM_OVERLAY));
            hideQuickMenu();
        });
        
        btnPause.setOnClickListener(v -> {
            sendBroadcast(new Intent(Constants.ACTION_PAUSE_SCREEN_RECORDING));
            hideQuickMenu();
        });
        
        btnResume.setOnClickListener(v -> {
            sendBroadcast(new Intent(Constants.ACTION_RESUME_SCREEN_RECORDING));
            hideQuickMenu();
        });
        
        btnStop.setOnClickListener(v -> {
            sendBroadcast(new Intent(Constants.ACTION_STOP_SCREEN_RECORDING));
            hideQuickMenu();
        });
        
        btnCloseMenu.setOnClickListener(v -> {
            hideQuickMenu();
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
        
        windowManager.addView(quickMenuView, menuParams);
        isMenuExpanded = true;
        
        updateQuickMenuButtons();
    }

    private void hideQuickMenu() {
        if (quickMenuView != null) {
            windowManager.removeView(quickMenuView);
            quickMenuView = null;
            isMenuExpanded = false;
        }
    }

    private void updateFloatingButtonState() {
        if (btnFloating == null) return;
        
        switch (recordingState) {
            case NONE:
                btnFloating.setText("fiber_manual_record");
                btnFloating.setTextColor(getResources().getColor(android.R.color.white));
                break;
            case IN_PROGRESS:
                btnFloating.setText("stop_circle");
                btnFloating.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                break;
            case PAUSED:
                btnFloating.setText("play_circle");
                btnFloating.setTextColor(getResources().getColor(android.R.color.holo_orange_dark));
                break;
        }
    }

    private void updateQuickMenuButtons() {
        if (quickMenuView == null) return;
        
        btnStart.setVisibility(recordingState == ScreenRecordingState.NONE ? View.VISIBLE : View.GONE);
        btnPause.setVisibility(recordingState == ScreenRecordingState.IN_PROGRESS ? View.VISIBLE : View.GONE);
        btnResume.setVisibility(recordingState == ScreenRecordingState.PAUSED ? View.VISIBLE : View.GONE);
        btnStop.setVisibility(recordingState != ScreenRecordingState.NONE ? View.VISIBLE : View.GONE);
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
        registerReceiver(stateReceiver, filter);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "FloatingControlsService destroyed");
        
        if (floatingView != null) {
            windowManager.removeView(floatingView);
        }
        
        if (quickMenuView != null) {
            windowManager.removeView(quickMenuView);
        }
        
        if (stateReceiver != null) {
            unregisterReceiver(stateReceiver);
        }
    }
}
