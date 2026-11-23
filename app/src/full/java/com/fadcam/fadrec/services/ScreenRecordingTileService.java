package com.fadcam.fadrec.services;

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

import com.fadcam.Constants;
import com.fadcam.FLog;
import com.fadcam.R;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.fadrec.ScreenRecordingState;
import com.fadcam.fadrec.ui.TransparentPermissionActivity;
import com.fadcam.utils.ServiceUtils;

/**
 * ScreenRecordingTileService: Quick Settings tile for FadRec screen recording.
 *
 * Use Cases:
 * - Start screen recording from the notification shade. The MediaProjection consent
 *   dialog is shown by {@link TransparentPermissionActivity} without opening the app.
 * - Stop an active screen recording with a single tap.
 *
 * Features:
 * - Uses the same icon as FadRec's ongoing screen-recording notification
 * - State-aware title and subtitle ("Record screen" / "Recording screen...")
 * - Refreshes through screen-recording broadcasts while the shade is open
 * - Android 14+ background activity launch compatibility (same workaround as
 *   {@code RecordingTileService})
 */
public class ScreenRecordingTileService extends TileService {

    private static final String TAG = "ScreenRecordingTile";

    private BroadcastReceiver stateReceiver;

    // While a stop request is being finalized the service still reports IN_PROGRESS.
    // Ignore repeated taps in that short window so we never post two stop requests.
    private static final long STOP_DEBOUNCE_MS = 2500L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastStopRequestAt = 0L;
    private Runnable stopDebounceResetRunnable;

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        FLog.d(TAG, "onTileAdded");
        // Give the user the correct state as soon as the tile is placed.
        refreshTile();
    }

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
        cancelStopDebounceReset();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cleanupPendingCallbacks();
    }

    @Override
    public void onClick() {
        super.onClick();
        boolean active = isRecordingSessionActive();
        FLog.i(TAG, "onClick - screen recording active: " + active);

        if (active) {
            stopScreenRecording();
        } else {
            startScreenRecording();
        }
    }

    /**
     * Starts the permission flow. The transparent activity asks for MediaProjection
     * consent and then starts {@link ScreenRecordingService} directly, so this works
     * even when the app UI is not running.
     */
    private void startScreenRecording() {
        FLog.i(TAG, "Launching TransparentPermissionActivity to request screen capture consent");
        Intent intent = new Intent(this, TransparentPermissionActivity.class);
        intent.putExtra(TransparentPermissionActivity.EXTRA_START_RECORDING_ON_GRANT, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        launchActivitySafely(intent);
        // Clear any stale "recording" state left behind by a dead process.
        refreshTile();
    }

    private void stopScreenRecording() {
        long now = System.currentTimeMillis();
        if (lastStopRequestAt != 0L && (now - lastStopRequestAt) < STOP_DEBOUNCE_MS) {
            FLog.d(TAG, "Ignoring repeated stop tap while finalizing");
            return;
        }
        lastStopRequestAt = now;
        cancelStopDebounceReset();
        stopDebounceResetRunnable = new Runnable() {
            @Override
            public void run() {
                lastStopRequestAt = 0L;
                stopDebounceResetRunnable = null;
            }
        };
        handler.postDelayed(stopDebounceResetRunnable, STOP_DEBOUNCE_MS);

        FLog.i(TAG, "Sending stop request to ScreenRecordingService");
        try {
            Intent intent = new Intent(this, ScreenRecordingService.class);
            intent.setAction(Constants.INTENT_ACTION_STOP_SCREEN_RECORDING);
            startService(intent);
        } catch (Exception e) {
            FLog.e(TAG, "Failed to send stop request", e);
        }
        refreshTile();
    }

    // Launch activity to bypass Android 14+ background FGS microphone/projection launch
    // restrictions; mirrors RecordingTileService's approach.
    @SuppressWarnings("deprecation") // startActivityAndCollapse(Intent) is the only path below API 34
    private void launchActivitySafely(final Intent intent) {
        Runnable launchRunnable = new Runnable() {
            @Override
            public void run() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    PendingIntent pendingIntent = PendingIntent.getActivity(
                            ScreenRecordingTileService.this,
                            0,
                            intent,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    startActivityAndCollapse(pendingIntent);
                } else {
                    startActivityAndCollapse(intent);
                }
            }
        };

        // The MediaProjection consent dialog cannot be shown over the lockscreen.
        if (isLocked()) {
            FLog.d(TAG, "Device is locked. Prompting for unlock before screen recording.");
            unlockAndRun(launchRunnable);
        } else {
            launchRunnable.run();
        }
    }

    private boolean isRecordingSessionActive() {
        ScreenRecordingState state = readPersistedState();
        if (state != ScreenRecordingState.IN_PROGRESS
                && state != ScreenRecordingState.PAUSED) {
            return false;
        }
        // A stale preference left behind by a dead process must not leave the tile
        // stuck on "recording".
        return ServiceUtils.isServiceRunning(this, ScreenRecordingService.class);
    }

    private ScreenRecordingState readPersistedState() {
        String raw = SharedPreferencesManager.getInstance(this).getScreenRecordingState();
        try {
            return ScreenRecordingState.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            return ScreenRecordingState.NONE;
        }
    }

    private void registerStateReceiver() {
        if (stateReceiver != null) return;
        stateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                FLog.d(TAG, "State receiver received action: " + intent.getAction()
                        + ". Refreshing tile.");
                refreshTile();
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Constants.BROADCAST_ON_SCREEN_RECORDING_STARTED);
        filter.addAction(Constants.BROADCAST_ON_SCREEN_RECORDING_STOPPED);
        filter.addAction(Constants.BROADCAST_ON_SCREEN_RECORDING_PAUSED);
        filter.addAction(Constants.BROADCAST_ON_SCREEN_RECORDING_RESUMED);
        filter.addAction(Constants.BROADCAST_ON_SCREEN_RECORDING_STATE_CALLBACK);
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
        Tile tile = getQsTile();
        if (tile == null) return;

        ScreenRecordingState state = readPersistedState();
        boolean active = isRecordingSessionActive();
        boolean paused = active && state == ScreenRecordingState.PAUSED;
        FLog.d(TAG, "refreshTile - screen recording active: " + active + ", state: " + state);

        if (active) {
            tile.setState(Tile.STATE_ACTIVE);
            // Brand title plus a state subtitle: "FadRec / Recording"
            tile.setLabel(getString(R.string.qs_tile_fadrec_label));
            tile.setContentDescription(getString(R.string.fadrec_stop_screen_recording));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.setSubtitle(getString(paused
                        ? R.string.qs_tile_fadrec_paused
                        : R.string.qs_tile_fadrec_recording));
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                tile.setStateDescription(getString(paused
                        ? R.string.fadrec_screen_recording_paused
                        : R.string.fadrec_screen_recording_started));
            }
            tile.setIcon(Icon.createWithResource(this, R.drawable.screen_recorder));
        } else {
            tile.setState(Tile.STATE_INACTIVE);
            // Brand title plus the action, so the tile reads "FadRec / Record screen".
            tile.setLabel(getString(R.string.qs_tile_fadrec_label));
            tile.setContentDescription(getString(R.string.qs_tile_fadrec_subtitle));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.setSubtitle(getString(R.string.qs_tile_fadrec_subtitle));
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                tile.setStateDescription(getString(R.string.fadrec_ready_to_record));
            }
            tile.setIcon(Icon.createWithResource(this, R.drawable.screen_recorder));
        }
        tile.updateTile();
    }

    private void cancelStopDebounceReset() {
        if (stopDebounceResetRunnable != null) {
            handler.removeCallbacks(stopDebounceResetRunnable);
            stopDebounceResetRunnable = null;
        }
    }

    private void cleanupPendingCallbacks() {
        cancelStopDebounceReset();
        lastStopRequestAt = 0L;
    }
}
