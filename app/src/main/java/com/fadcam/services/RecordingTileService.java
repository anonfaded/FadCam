package com.fadcam.services;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import com.fadcam.CameraType;
import com.fadcam.Constants;
import com.fadcam.FLog;
import com.fadcam.R;
import com.fadcam.RecordingStartActivity;
import com.fadcam.RecordingStopActivity;
import com.fadcam.SharedPreferencesManager;

/**
 * RecordingTileService: Quick Settings Tile for start/stop recording control.
 * 
 * Use Cases:
 * - Quick recording control: Start or stop video recording from notification shade
 * - Live camera toggle: Switch cameras via quick double-tap on tile
 * - Camera preference update: Modify front/rear camera preference when idle
 * 
 * Features:
 * - Single tap start/stop recording control
 * - Double tap camera switch control (within 450ms)
 * - Android 14+ compatible (targetSDK 36 background FGS launch workaround)
 */
public class RecordingTileService extends TileService {

    private static final String TAG = "RecordingTileService";
    private static final long DOUBLE_TAP_WINDOW_MS = 450L;

    // Handler on main looper for processing double-tap gesture delays
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver stateReceiver;
    private long lastClickAt = 0L;
    private Runnable clickRunnable;
    private Runnable restoreActiveStateRunnable;

    @Override
    public void onStartListening() {
        super.onStartListening();
        FLog.d(TAG, "onStartListening");
        registerStateReceiver();
        refreshTile();
    }

    @Override
    public void onStopListening() {
        super.onStopListening();
        FLog.d(TAG, "onStopListening - performing lifecycle cleanups");
        unregisterStateReceiver();
        cancelRestoreRunnable();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cleanupPendingCallbacks();
    }

    @Override
    public void onClick() {
        super.onClick();
        long now = System.currentTimeMillis();
        boolean isDoubleTap = (now - lastClickAt) <= DOUBLE_TAP_WINDOW_MS;
        lastClickAt = now;

        FLog.d(TAG, "onClick - isDoubleTap: " + isDoubleTap);
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(this);
        boolean recording = prefs.isRecordingInProgress();

        // Handle double-tap to switch cameras
        if (isDoubleTap) {
            cancelClickRunnable();
            final SharedPreferencesManager finalPrefs = prefs;
            final boolean finalRecording = recording;
            Runnable switchRunnable = new Runnable() {
                @Override
                public void run() {
                    switchCamera(finalPrefs, finalRecording);
                    // Re-render inactive tile state immediately when idle
                    if (!finalRecording) {
                        refreshTile();
                    }
                }
            };

            if (isLocked()) {
                FLog.d(TAG, "Device is locked. Prompting for unlock before switching camera.");
                unlockAndRun(switchRunnable);
            } else {
                switchRunnable.run();
            }
            lastClickAt = 0L;
            return;
        }

        // Cancel previous pending single-tap action if exists
        cancelClickRunnable();

        // Post single-tap action to run after the double-tap window expires
        clickRunnable = new Runnable() {
            @Override
            public void run() {
                boolean currentRecording = SharedPreferencesManager.getInstance(RecordingTileService.this).isRecordingInProgress();
                FLog.i(TAG, "Executing single tap action. Recording state: " + currentRecording);
                if (currentRecording) {
                    stopRecording();
                } else {
                    startRecording();
                }
                clickRunnable = null;
            }
        };
        handler.postDelayed(clickRunnable, DOUBLE_TAP_WINDOW_MS);
    }

    private void startRecording() {
        FLog.i(TAG, "Launching RecordingStartActivity to start recording safely");
        Intent intent = new Intent(this, RecordingStartActivity.class);
        intent.putExtra(RecordingStartActivity.EXTRA_SHORTCUT_CAMERA_MODE, RecordingStartActivity.CAMERA_MODE_CURRENT);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        launchActivitySafely(intent);
    }

    private void stopRecording() {
        FLog.i(TAG, "Launching RecordingStopActivity to stop recording safely");
        Intent intent = new Intent(this, RecordingStopActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        launchActivitySafely(intent);
    }

    // Launch shortcut activity to bypass Android 14+ background FGS microphone/camera launch restrictions
    private void launchActivitySafely(final Intent intent) {
        Runnable launchRunnable = new Runnable() {
            @Override
            public void run() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    PendingIntent pendingIntent = PendingIntent.getActivity(
                        RecordingTileService.this,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                    );
                    startActivityAndCollapse(pendingIntent);
                } else {
                    startActivityAndCollapse(intent);
                }
            }
        };

        // If lockscreen is active, prompt the user to unlock before starting the activity
        if (isLocked()) {
            FLog.d(TAG, "Device is locked. Prompting for unlock before starting activity.");
            unlockAndRun(launchRunnable);
        } else {
            launchRunnable.run();
        }
    }

    private void switchCamera(SharedPreferencesManager prefs, boolean recording) {
        CameraType current = prefs.getCameraSelection();
        if (recording && current.isDual()) {
            FLog.w(TAG, "switchCamera ignored: camera switching is not supported during active Dual recording");
            return;
        }
        CameraType target = (current == CameraType.FRONT) ? CameraType.BACK : CameraType.FRONT;
        FLog.i(TAG, "switchCamera requested: " + current + " -> " + target + " (Recording active: " + recording + ")");

        // Only switch live if recording is active and we are in a single-camera mode
        if (recording && !current.isDual()) {
            // Live switch: Send switch action directly to RecordingService; the service itself will update preferences
            Intent switchIntent = new Intent(this, RecordingService.class);
            switchIntent.setAction(Constants.INTENT_ACTION_SWITCH_CAMERA);
            switchIntent.putExtra(Constants.INTENT_EXTRA_CAMERA_TYPE_SWITCH, target.name());
            startService(switchIntent);
            showSwitchingFeedback(target);
        } else {
            // Idle switch (or during dual-recording): Save selection to preferences immediately
            prefs.sharedPreferences.edit()
                    .putString(Constants.PREF_CAMERA_SELECTION, target.name())
                    .apply();
        }
    }

    // Briefly show target camera type on active tile as double-tap feedback
    private void showSwitchingFeedback(CameraType target) {
        Tile tile = getQsTile();
        if (tile == null) return;

        FLog.d(TAG, "Displaying temporary switching visual feedback for: " + target);
        tile.setState(Tile.STATE_INACTIVE);
        if (target == CameraType.FRONT) {
            tile.setLabel(getString(R.string.front));
            tile.setIcon(Icon.createWithResource(this, R.drawable.start_front_shortcut));
        } else {
            tile.setLabel(getString(R.string.back));
            tile.setIcon(Icon.createWithResource(this, R.drawable.start_back_shortcut));
        }
        tile.updateTile();

        cancelRestoreRunnable();

        // Restore active recording state after 1.5 seconds
        restoreActiveStateRunnable = new Runnable() {
            @Override
            public void run() {
                boolean recording = SharedPreferencesManager.getInstance(RecordingTileService.this).isRecordingInProgress();
                FLog.d(TAG, "Reverting switching feedback. Active recording status: " + recording);
                setTileState(recording);
                restoreActiveStateRunnable = null;
            }
        };
        handler.postDelayed(restoreActiveStateRunnable, 1500L);
    }

    private void registerStateReceiver() {
        if (stateReceiver != null) return;
        stateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                FLog.d(TAG, "State receiver received action: " + intent.getAction() + ". Refreshing tile.");
                refreshTile();
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Constants.BROADCAST_ON_RECORDING_STARTED);
        filter.addAction(Constants.BROADCAST_ON_RECORDING_STOPPED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
    }

    private void unregisterStateReceiver() {
        if (stateReceiver == null) return;
        try {
            unregisterReceiver(stateReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        stateReceiver = null;
    }

    private void refreshTile() {
        boolean recording = SharedPreferencesManager.getInstance(this).isRecordingInProgress();
        FLog.d(TAG, "refreshTile - Active recording: " + recording);
        setTileState(recording);
    }

    private void setTileState(boolean active) {
        cancelRestoreRunnable();
        Tile tile = getQsTile();
        if (tile == null) return;

        FLog.d(TAG, "setTileState - Setting tile state to: " + (active ? "ACTIVE" : "INACTIVE"));
        tile.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (active) {
            tile.setLabel(getString(R.string.stop_recording));
            tile.setIcon(Icon.createWithResource(this, R.drawable.stop_shortcut));
        } else {
            CameraType camera = SharedPreferencesManager.getInstance(this).getCameraSelection();
            if (camera == CameraType.FRONT) {
                tile.setLabel(getString(R.string.shortcut_start_front));
                tile.setIcon(Icon.createWithResource(this, R.drawable.start_front_shortcut));
            } else if (camera == CameraType.DUAL_PIP) {
                tile.setLabel(getString(R.string.shortcut_start_dual));
                tile.setIcon(Icon.createWithResource(this, R.drawable.start_dual_shortcut));
            } else {
                tile.setLabel(getString(R.string.shortcut_start_back));
                tile.setIcon(Icon.createWithResource(this, R.drawable.start_back_shortcut));
            }
        }
        tile.updateTile();
    }

    private void cancelClickRunnable() {
        if (clickRunnable != null) {
            handler.removeCallbacks(clickRunnable);
            clickRunnable = null;
        }
    }

    private void cancelRestoreRunnable() {
        if (restoreActiveStateRunnable != null) {
            handler.removeCallbacks(restoreActiveStateRunnable);
            restoreActiveStateRunnable = null;
        }
    }

    private void cleanupPendingCallbacks() {
        cancelClickRunnable();
        cancelRestoreRunnable();
    }
}
