package com.fadcam;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;

/**
 * No-UI entry point that routes a launcher shortcut to the existing recording
 * start or stop activity.
 */
public class RecordingToggleActivity extends Activity {
    private static final String TAG = "RecordingToggleActivity";
    private static final RecordingToggleGuard TOGGLE_GUARD = new RecordingToggleGuard();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            if (!TOGGLE_GUARD.tryAcquire(SystemClock.elapsedRealtime())) {
                FLog.w(TAG, "Ignoring duplicate recording toggle request");
                return;
            }

            SharedPreferencesManager prefs = SharedPreferencesManager.getInstance(this);
            boolean hasActiveSession = prefs.isRecordingInProgress()
                    || getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                    .getLong(Constants.PREF_RECORDING_START_TIME, 0L) > 0L;

            Intent controlIntent;
            if (hasActiveSession) {
                FLog.i(TAG, "Routing toggle request to recording stop");
                controlIntent = new Intent(this, RecordingStopActivity.class);
            } else {
                FLog.i(TAG, "Routing toggle request to recording start");
                controlIntent = new Intent(this, RecordingStartActivity.class)
                        .putExtra(
                                RecordingStartActivity.EXTRA_SHORTCUT_CAMERA_MODE,
                                RecordingStartActivity.CAMERA_MODE_CURRENT);
            }
            controlIntent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(controlIntent);
        } catch (Exception e) {
            FLog.e(TAG, "Error toggling recording via shortcut", e);
        } finally {
            finish();
        }
    }
}
