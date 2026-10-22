package com.fadcam.pro;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Posts the weekly Pro reminder when the cycle alarm fires. */
public class ProReminderAlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!com.fadcam.BuildConfig.ENABLE_PRO_REMINDER) return;
        if (intent == null || !ProReminderScheduler.ACTION_WEEKLY_REMINDER.equals(intent.getAction())) {
            return;
        }
        if (ProReminderManager.shouldPostNotification(context)) {
            if (ProReminderNotification.post(context)) {
                ProReminderManager.markNotificationPosted(context);
            }
        }
        // If it was already handled, or permission is missing, make sure we do not leave a
        // stale alarm pointing at a cycle that has already passed.
        ProReminderScheduler.ensureScheduled(context);
    }
}
