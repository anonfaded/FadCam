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
import com.fadcam.dualcam.service.DualCameraRecordingService;
import com.fadcam.utils.ServiceUtils;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

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
    private BroadcastReceiver localStateReceiver;
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

    protected CameraType getDedicatedMode() {
        return null;
    }

    @Override
    public void onClick() {
        super.onClick();

        CameraType dedicated = getDedicatedMode();
        if (dedicated != null) {
            // Dedicated tiles trigger start/stop directly without double-tap delay
            SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(this);
            boolean thisTileActive = isTileActive(prefs);
            boolean hasActiveSession = hasActiveRecordingSession(prefs);
            FLog.i(TAG, "Dedicated tile (" + dedicated + ") clicked. Active: " + thisTileActive + ", hasSession: " + hasActiveSession);
            if (thisTileActive) {
                stopRecording();
            } else if (!hasActiveSession) {
                startRecording();
            }
            return;
        }

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

    /**
     * Requests that SystemUI bring active tiles into the listening state so a
     * pending state change is applied IMMEDIATELY, even when the tile isn't
     * currently visible/listening. Called on start/stop transitions.
     */
    public static void requestTileRefresh(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestListeningStateFor(context, RecordingTileService.class);
            requestListeningStateFor(context, Back.class);
            requestListeningStateFor(context, Front.class);
            requestListeningStateFor(context, Dual.class);
        }
    }

    private static void requestListeningStateFor(Context context, Class<?> serviceClass) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                TileService.requestListeningState(
                        context,
                        new android.content.ComponentName(context, serviceClass));
            } catch (Exception e) {
                FLog.w(TAG, "requestListeningState failed for " + serviceClass.getSimpleName(), e);
            }
        }
    }

    /**
     * Enables or disables Quick Settings tile components based on selected mode.
     */
    public static void applyTileMode(Context context, String mode) {
        android.content.pm.PackageManager pm = context.getPackageManager();
        boolean separate = Constants.QS_TILE_MODE_SEPARATE.equals(mode);

        setComponentEnabled(pm, context, RecordingTileService.class, !separate);
        setComponentEnabled(pm, context, Back.class, separate);
        setComponentEnabled(pm, context, Front.class, separate);
        setComponentEnabled(pm, context, Dual.class, separate);

        requestTileRefresh(context);
    }

    private static void setComponentEnabled(android.content.pm.PackageManager pm, Context context, Class<?> cls, boolean enabled) {
        try {
            android.content.ComponentName component = new android.content.ComponentName(context, cls);
            int newState = enabled
                    ? android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            pm.setComponentEnabledSetting(component, newState, android.content.pm.PackageManager.DONT_KILL_APP);
        } catch (Exception e) {
            FLog.e(TAG, "Error updating component enabled state for " + cls.getSimpleName(), e);
        }
    }

    /**
     * Prompts the system on Android 13+ (API 33+) to add the tile directly to the Quick Settings shade.
     */
    public static void requestAddTileToShade(Context context, Class<?> tileClass, CharSequence label, int iconRes) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.app.StatusBarManager statusBarManager = context.getSystemService(android.app.StatusBarManager.class);
            if (statusBarManager != null) {
                android.content.ComponentName componentName = new android.content.ComponentName(context, tileClass);
                android.graphics.drawable.Icon icon = android.graphics.drawable.Icon.createWithResource(context, iconRes);
                statusBarManager.requestAddTileService(
                        componentName,
                        label,
                        icon,
                        context.getMainExecutor(),
                        resultCode -> {
                            if (resultCode == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                                android.widget.Toast.makeText(context, R.string.qs_tile_already_added, android.widget.Toast.LENGTH_SHORT).show();
                            } else if (resultCode == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) {
                                android.widget.Toast.makeText(context, R.string.qs_tile_added_success, android.widget.Toast.LENGTH_SHORT).show();
                            }
                        }
                );
                return;
            }
        }
        android.widget.Toast.makeText(context, R.string.qs_tile_manual_add_instructions, android.widget.Toast.LENGTH_LONG).show();
    }

    private void startRecording() {
        CameraType dedicated = getDedicatedMode();
        FLog.i(TAG, "Launching RecordingStartActivity to start recording safely (dedicated: " + dedicated + ")");
        Intent intent = new Intent(this, RecordingStartActivity.class);
        if (dedicated == CameraType.BACK) {
            intent.putExtra(RecordingStartActivity.EXTRA_SHORTCUT_CAMERA_MODE, RecordingStartActivity.CAMERA_MODE_BACK);
        } else if (dedicated == CameraType.FRONT) {
            intent.putExtra(RecordingStartActivity.EXTRA_SHORTCUT_CAMERA_MODE, RecordingStartActivity.CAMERA_MODE_FRONT);
        } else if (dedicated == CameraType.DUAL_PIP) {
            intent.putExtra(RecordingStartActivity.EXTRA_SHORTCUT_CAMERA_MODE, RecordingStartActivity.CAMERA_MODE_DUAL);
        } else {
            intent.putExtra(RecordingStartActivity.EXTRA_SHORTCUT_CAMERA_MODE, RecordingStartActivity.CAMERA_MODE_CURRENT);
        }
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

        CameraType target;
        if (recording) {
            // Live switch during active single-camera recording toggles between front and back
            target = (current == CameraType.FRONT) ? CameraType.BACK : CameraType.FRONT;
        } else {
            // Idle switch: cycle BACK -> FRONT -> DUAL_PIP -> BACK
            if (current == CameraType.BACK) {
                target = CameraType.FRONT;
            } else if (current == CameraType.FRONT) {
                target = CameraType.DUAL_PIP;
            } else {
                target = CameraType.BACK;
            }
        }

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
            // Idle switch: Save selection to preferences immediately
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
            tile.setIcon(Icon.createWithResource(this, R.drawable.ic_qs_tile_videocam_front));
        } else if (target == CameraType.DUAL_PIP) {
            tile.setLabel(getString(R.string.shortcut_start_dual));
            tile.setIcon(Icon.createWithResource(this, R.drawable.ic_qs_tile_videocam_dual));
        } else {
            tile.setLabel(getString(R.string.back));
            tile.setIcon(Icon.createWithResource(this, R.drawable.ic_qs_tile_videocam_back));
        }
        tile.updateTile();

        cancelRestoreRunnable();

        // Restore active recording state after 1.5 seconds
        restoreActiveStateRunnable = new Runnable() {
            @Override
            public void run() {
                SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(RecordingTileService.this);
                boolean active = isTileActive(prefs);
                FLog.d(TAG, "Reverting switching feedback. Active status: " + active);
                setTileState(active);
                restoreActiveStateRunnable = null;
            }
        };
        handler.postDelayed(restoreActiveStateRunnable, 1500L);
    }

    private void registerStateReceiver() {
        if (stateReceiver == null) {
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
            filter.addAction(Constants.BROADCAST_ON_RECORDING_PAUSED);
            filter.addAction(Constants.BROADCAST_ON_RECORDING_RESUMED);
            filter.addAction(Constants.BROADCAST_ON_DUAL_RECORDING_STARTED);
            filter.addAction(Constants.BROADCAST_ON_DUAL_RECORDING_STOPPED);
            filter.addAction(Constants.BROADCAST_ON_DUAL_RECORDING_PAUSED);
            filter.addAction(Constants.BROADCAST_ON_DUAL_RECORDING_RESUMED);
            filter.addAction(Constants.BROADCAST_ON_DUAL_CAMERAS_SWAPPED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(stateReceiver, filter);
            }
        }

        if (localStateReceiver == null) {
            localStateReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    FLog.d(TAG, "Local state receiver received action: " + intent.getAction() + ". Refreshing tile.");
                    refreshTile();
                }
            };
            IntentFilter localFilter = new IntentFilter();
            localFilter.addAction(Constants.BROADCAST_ON_CAMERA_SWITCH_COMPLETE);
            LocalBroadcastManager.getInstance(this).registerReceiver(localStateReceiver, localFilter);
        }
    }

    private void unregisterStateReceiver() {
        if (stateReceiver != null) {
            try {
                unregisterReceiver(stateReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            stateReceiver = null;
        }
        if (localStateReceiver != null) {
            try {
                LocalBroadcastManager.getInstance(this).unregisterReceiver(localStateReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            localStateReceiver = null;
        }
    }

    private boolean hasActiveRecordingSession(SharedPreferencesManager prefs) {
        return prefs.isRecordingInProgress()
                || ServiceUtils.isServiceRunning(this, RecordingService.class)
                || ServiceUtils.isServiceRunning(this, DualCameraRecordingService.class);
    }

    private boolean isTileActive(SharedPreferencesManager prefs) {
        if (!hasActiveRecordingSession(prefs)) {
            return false;
        }
        CameraType dedicated = getDedicatedMode();
        if (dedicated == null) {
            // Universal tile reflects any recording state
            return true;
        }
        boolean isDualRunning = ServiceUtils.isServiceRunning(this, DualCameraRecordingService.class);
        if (dedicated == CameraType.DUAL_PIP) {
            return isDualRunning;
        }
        if (isDualRunning) {
            return false;
        }
        CameraType activeCamera = prefs.getCameraSelection();
        return activeCamera == dedicated;
    }

    private void refreshTile() {
        SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(this);
        boolean active = isTileActive(prefs);
        FLog.d(TAG, "refreshTile - Active status for tile (" + getDedicatedMode() + "): " + active);
        setTileState(active);
    }

    private void setTileState(boolean active) {
        cancelRestoreRunnable();
        Tile tile = getQsTile();
        if (tile == null) return;

        FLog.d(TAG, "setTileState - Setting tile state to: " + (active ? "ACTIVE" : "INACTIVE"));
        tile.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (active) {
            tile.setLabel(getString(R.string.stop_recording));
            tile.setIcon(Icon.createWithResource(this, R.drawable.ic_qs_tile_stop));
        } else {
            CameraType camera = getDedicatedMode() != null
                    ? getDedicatedMode()
                    : SharedPreferencesManager.getInstance(this).getCameraSelection();
            if (camera == CameraType.FRONT) {
                tile.setLabel(getString(R.string.shortcut_start_front));
                tile.setIcon(Icon.createWithResource(this, R.drawable.ic_qs_tile_videocam_front));
            } else if (camera == CameraType.DUAL_PIP) {
                tile.setLabel(getString(R.string.shortcut_start_dual));
                tile.setIcon(Icon.createWithResource(this, R.drawable.ic_qs_tile_videocam_dual));
            } else {
                tile.setLabel(getString(R.string.shortcut_start_back));
                tile.setIcon(Icon.createWithResource(this, R.drawable.ic_qs_tile_videocam_back));
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

    // ── Dedicated Sub-Tiles (reusing all base logic) ──

    /** Dedicated Back Camera Quick Settings Tile */
    public static class Back extends RecordingTileService {
        @Override
        protected CameraType getDedicatedMode() {
            return CameraType.BACK;
        }
    }

    /** Dedicated Front Camera Quick Settings Tile */
    public static class Front extends RecordingTileService {
        @Override
        protected CameraType getDedicatedMode() {
            return CameraType.FRONT;
        }
    }

    /** Dedicated Dual PiP Camera Quick Settings Tile */
    public static class Dual extends RecordingTileService {
        @Override
        protected CameraType getDedicatedMode() {
            return CameraType.DUAL_PIP;
        }
    }
}
