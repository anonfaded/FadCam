package com.fadcam.pro;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Schedules the weekly notification at the end of the current cycle. The alarm is deliberately
 * inexact: a promotional reminder does not need exact delivery, and exact alarms would require
 * a special permission the app does not hold.
 */
public final class ProReminderScheduler {

    static final String ACTION_WEEKLY_REMINDER =
            "com.fadcam.internal.action.PRO_REMINDER_WEEKLY";
    private static final int REQUEST_CODE = 4109;

    private ProReminderScheduler() {
    }

    public static void ensureScheduled(Context context) {
        if (!ProReminderManager.isSupported()
                || ProReminderManager.isDesignMode(context)
                || ProReminderManager.isUnsupportedSurface(context)) return;
        long next = ProReminderManager.getNextEligibleAt(context);
        long now = System.currentTimeMillis();

        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;

        PendingIntent pendingIntent = pendingIntent(context);
        if (next <= 0L || next <= now) {
            // First cycle (dialog-only) or already overdue: the next safe Home visit owns it.
            manager.cancel(pendingIntent);
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pendingIntent);
            } else {
                manager.set(AlarmManager.RTC_WAKEUP, next, pendingIntent);
            }
        } catch (Exception ignored) {
        }
    }

    public static void cancel(Context context) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager != null) {
            try {
                manager.cancel(pendingIntent(context));
            } catch (Exception ignored) {
            }
        }
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, ProReminderAlarmReceiver.class);
        intent.setAction(ACTION_WEEKLY_REMINDER);
        return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
