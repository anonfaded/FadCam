package com.fadcam.ui;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.fadcam.Constants;
import com.fadcam.services.RecordingService;
import com.fadcam.utils.ServiceStartPolicy;

/** Alarm receiver for one-shot production recordings. */
public class ProductionScheduleReceiver extends BroadcastReceiver {
    private static final String ACTION_START = "com.fadcam.action.PRODUCTION_SCHEDULE_START";
    private static final String ACTION_STOP = "com.fadcam.action.PRODUCTION_SCHEDULE_STOP";
    private static final String EXTRA_ID = "schedule_id";

    public static void schedule(Context context, long startAt, long durationMs) {
        int id = (int) (startAt ^ (startAt >>> 32));
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        setAlarm(alarms, pending(context, ACTION_START, id), startAt);
        Intent stopIntent = new Intent(context, ProductionScheduleReceiver.class).setAction(ACTION_STOP).putExtra(EXTRA_ID, id);
        PendingIntent stop = PendingIntent.getBroadcast(context, id + 1, stopIntent, flags());
        setAlarm(alarms, stop, startAt + Math.max(60_000L, durationMs));
    }

    private static void setAlarm(AlarmManager alarms, PendingIntent pi, long at) {
        if (alarms == null) return;
        if (Build.VERSION.SDK_INT >= 23) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        else alarms.setExact(AlarmManager.RTC_WAKEUP, at, pi);
    }

    private static PendingIntent pending(Context c, String action, int id) {
        Intent i = new Intent(c, ProductionScheduleReceiver.class).setAction(action).putExtra(EXTRA_ID, id);
        return PendingIntent.getBroadcast(c, id, i, flags());
    }

    private static int flags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    @Override public void onReceive(Context context, Intent intent) {
        try {
            Intent recording = new Intent(context, RecordingService.class);
            if (ACTION_START.equals(intent.getAction())) {
                recording.setAction(Constants.INTENT_ACTION_START_RECORDING);
                ServiceStartPolicy.startRecordingAction(context, recording);
            } else if (ACTION_STOP.equals(intent.getAction())) {
                recording.setAction(Constants.INTENT_ACTION_STOP_RECORDING);
                ServiceStartPolicy.startRecordingAction(context, recording);
            }
        } catch (Exception ignored) {
            // RecordingService owns the recording/notification recovery path.
        }
    }
}
