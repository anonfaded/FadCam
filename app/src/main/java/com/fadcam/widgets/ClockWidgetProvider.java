package com.fadcam.widgets;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.widget.RemoteViews;

import com.fadcam.MainActivity;
import com.fadcam.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Dark-themed clock App Widget provider that updates every minute.
 */
public class ClockWidgetProvider extends AppWidgetProvider {

    private static final String ACTION_UPDATE_CLOCK = "com.fadcam.widgets.ACTION_UPDATE_CLOCK";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        // -------------- Fix Start for this method(onUpdate)-----------
        for (int id : appWidgetIds) {
            updateClock(context, appWidgetManager, id);
        }
        scheduleMinuteUpdates(context);
        // -------------- Fix Ended for this method(onUpdate)-----------
    }

    @Override
    public void onEnabled(Context context) {
        // -------------- Fix Start for this method(onEnabled)-----------
        scheduleMinuteUpdates(context);
        // -------------- Fix Ended for this method(onEnabled)-----------
    }

    @Override
    public void onDisabled(Context context) {
        // -------------- Fix Start for this method(onDisabled)-----------
        cancelMinuteUpdates(context);
        // -------------- Fix Ended for this method(onDisabled)-----------
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager, int appWidgetId, android.os.Bundle newOptions) {
        // -------------- Fix Start for this method(onAppWidgetOptionsChanged)-----------
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions);
        // Update widget when size changes
        updateClock(context, appWidgetManager, appWidgetId);
        // -------------- Fix Ended for this method(onAppWidgetOptionsChanged)-----------
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        // -------------- Fix Start for this method(onReceive)-----------
        if (Intent.ACTION_TIME_CHANGED.equals(intent.getAction()) ||
                Intent.ACTION_TIME_TICK.equals(intent.getAction()) ||
                Intent.ACTION_TIMEZONE_CHANGED.equals(intent.getAction()) ||
                ACTION_UPDATE_CLOCK.equals(intent.getAction())) {
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            int[] ids = mgr.getAppWidgetIds(new ComponentName(context, ClockWidgetProvider.class));
            for (int id : ids) {
                updateClock(context, mgr, id);
            }
        }
        // -------------- Fix Ended for this method(onReceive)-----------
    }

    private void updateClock(Context context, AppWidgetManager manager, int appWidgetId) {
        // -------------- Fix Start for this method(updateClock)-----------
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_clock);
        WidgetPreferences prefs = new WidgetPreferences(context);

        // Get widget size for dynamic scaling
        android.util.Size widgetSize = getWidgetSize(context, manager, appWidgetId);
        float scaleFactor = calculateScaleFactor(widgetSize);

        // Apply dynamic font sizes with bigger base sizes for time
        float timeSize = 48f * scaleFactor;      // Even bigger time: 48sp
        float ampmSize = 14f * scaleFactor;      // Smaller AM/PM: 14sp  
        float dateSize = 18f * scaleFactor;      // Date size: 18sp
        float arabicDateSize = 16f * scaleFactor; // Arabic date: 16sp

        // Ensure minimum readable sizes
        timeSize = Math.max(timeSize, 36f);
        ampmSize = Math.max(ampmSize, 12f);
        dateSize = Math.max(dateSize, 14f);
        arabicDateSize = Math.max(arabicDateSize, 12f);

        // Set dynamic text sizes
        views.setTextViewTextSize(R.id.clock_time, android.util.TypedValue.COMPLEX_UNIT_SP, timeSize);
        views.setTextViewTextSize(R.id.clock_ampm, android.util.TypedValue.COMPLEX_UNIT_SP, ampmSize);
        views.setTextViewTextSize(R.id.clock_date, android.util.TypedValue.COMPLEX_UNIT_SP, dateSize);
        views.setTextViewTextSize(R.id.clock_date_arabic, android.util.TypedValue.COMPLEX_UNIT_SP, arabicDateSize);

        // Set background based on preferences
        if (prefs.hasBlackBackground() && prefs.showBranding()) {
            // Combined black background with branding overlay
            views.setInt(R.id.clock_root, "setBackgroundResource", R.drawable.widget_black_background_with_branding);
        } else if (prefs.hasBlackBackground()) {
            // Just black background
            views.setInt(R.id.clock_root, "setBackgroundResource", R.drawable.widget_black_background);
        } else {
            // Transparent background
            views.setInt(R.id.clock_root, "setBackgroundResource", android.R.color.transparent);
        }

        // Format time based on preference (default: 12-hour)
        if (prefs.is24HourFormat()) {
            String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
            views.setTextViewText(R.id.clock_time, time);
            views.setViewVisibility(R.id.clock_ampm, android.view.View.GONE);
        } else {
            String time = new SimpleDateFormat("h:mm", Locale.getDefault()).format(new Date());
            String ampm = new SimpleDateFormat("a", Locale.getDefault()).format(new Date());
            views.setTextViewText(R.id.clock_time, time);
            views.setTextViewText(R.id.clock_ampm, ampm);
            views.setViewVisibility(R.id.clock_ampm, android.view.View.VISIBLE);
        }
        
        // Show/hide date based on preference
        if (prefs.showDate()) {
            String datePattern = prefs.getDateFormat();
            String date = new SimpleDateFormat(datePattern, Locale.getDefault()).format(new Date());
            views.setTextViewText(R.id.clock_date, date);
            views.setViewVisibility(R.id.clock_date, android.view.View.VISIBLE);
        } else {
            views.setViewVisibility(R.id.clock_date, android.view.View.GONE);
        }
        
        // Arabic date (independent of regular date setting)
        if (prefs.showArabicDate()) {
            String arabicDateFormat = prefs.getArabicDateFormat();
            String arabicDate = ArabicDateUtils.getArabicDate(arabicDateFormat);
            views.setTextViewText(R.id.clock_date_arabic, arabicDate);
            views.setViewVisibility(R.id.clock_date_arabic, android.view.View.VISIBLE);
        } else {
            views.setViewVisibility(R.id.clock_date_arabic, android.view.View.GONE);
        }
        
        // Show date_container if either regular date OR Arabic date is enabled
        if (prefs.showDate() || prefs.showArabicDate()) {
            views.setViewVisibility(R.id.date_container, android.view.View.VISIBLE);
        } else {
            views.setViewVisibility(R.id.date_container, android.view.View.GONE);
        }

        // Click to open the app at Shortcuts & Widgets screen
        Intent launch = new Intent(context, com.fadcam.MainActivity.class);
        launch.putExtra("open_shortcuts_widgets", true);
        launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        views.setOnClickPendingIntent(R.id.clock_root, pi);

        manager.updateAppWidget(appWidgetId, views);
        // -------------- Fix Ended for this method(updateClock)-----------
    }

    private void scheduleMinuteUpdates(Context context) {
        // -------------- Fix Start for this method(scheduleMinuteUpdates)-----------
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(context, ClockWidgetProvider.class).setAction(ACTION_UPDATE_CLOCK);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, i, PendingIntent.FLAG_IMMUTABLE);
        long interval = 60_000L;
        long triggerAt = SystemClock.elapsedRealtime() + interval;
        am.cancel(pi);
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, interval, pi);
        // -------------- Fix Ended for this method(scheduleMinuteUpdates)-----------
    }

    private void cancelMinuteUpdates(Context context) {
        // -------------- Fix Start for this method(cancelMinuteUpdates)-----------
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(context, ClockWidgetProvider.class).setAction(ACTION_UPDATE_CLOCK);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, i, PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
        // -------------- Fix Ended for this method(cancelMinuteUpdates)-----------
    }

    private android.util.Size getWidgetSize(Context context, AppWidgetManager manager, int appWidgetId) {
        // -------------- Fix Start for this method(getWidgetSize)-----------
        android.os.Bundle options = manager.getAppWidgetOptions(appWidgetId);
        if (options == null) {
            // Return default size if options not available
            return new android.util.Size(250, 110);
        }
        
        int minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250);
        int minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110);
        int maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 250);
        int maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110);
        
        // Use max dimensions for better scaling
        return new android.util.Size(maxWidth, maxHeight);
        // -------------- Fix Ended for this method(getWidgetSize)-----------
    }

    private float calculateScaleFactor(android.util.Size widgetSize) {
        // -------------- Fix Start for this method(calculateScaleFactor)-----------
        // Base size is approximately a 4x1 widget (250x110 dp)
        int baseWidth = 250;
        int baseHeight = 110;
        
        float widthScale = (float) widgetSize.getWidth() / baseWidth;
        float heightScale = (float) widgetSize.getHeight() / baseHeight;
        
        // Use the smaller scale to ensure content fits
        float scale = Math.min(widthScale, heightScale);
        
        // Clamp scale between 0.7 and 2.0 for reasonable sizing
        return Math.max(0.7f, Math.min(2.0f, scale));
        // -------------- Fix Ended for this method(calculateScaleFactor)-----------
    }
}
