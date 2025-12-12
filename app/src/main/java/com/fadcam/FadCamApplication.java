package com.fadcam;

import android.app.Application;
import android.app.ActivityManager;
import androidx.lifecycle.ProcessLifecycleOwner;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;
import androidx.lifecycle.Lifecycle;
import android.content.Intent;
import android.content.ComponentName;

public class FadCamApplication extends Application implements LifecycleObserver {
    @Override
    public void onCreate() {
        super.onCreate();
        ProcessLifecycleOwner.get().getLifecycle().addObserver(this);
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    public void onAppBackgrounded() {
        // App is in background, reset AppLock session
        SharedPreferencesManager.getInstance(this).setAppLockSessionUnlocked(false);
        Intent intent = new Intent(this, com.fadcam.services.RecordingService.class);
        intent.setAction("ACTION_APP_BACKGROUND");
        startService(intent);
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    public void onAppForegrounded() {
        // Don't send it for TextEditorActivity, TransparentPermissionActivity, etc.
        // which are transparent/standalone and shouldn't wake up the main app
        
        // Get the currently focused activity
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am != null) {
            java.util.List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (!tasks.isEmpty()) {
                ComponentName topActivity = tasks.get(0).topActivity;
                if (topActivity != null) {
                    String activityClassName = topActivity.getClassName();
                    
                    // Only send ACTION_APP_FOREGROUND for MainActivity (camera) or FadRecHomeFragment
                    // Skip for transparent activities like TextEditorActivity, TransparentPermissionActivity
                    boolean isRecordingRelated = activityClassName.contains("MainActivity") || 
                                               activityClassName.contains("FadRecHomeActivity") ||
                                               activityClassName.contains("RecordingActivity");
                    
                    if (isRecordingRelated) {
                        Intent intent = new Intent(this, com.fadcam.services.RecordingService.class);
                        intent.setAction("ACTION_APP_FOREGROUND");
                        startService(intent);
                    }
                    return;
                }
            }
        }
        
        // Fallback: send the broadcast anyway (error case)
        Intent intent = new Intent(this, com.fadcam.services.RecordingService.class);
        intent.setAction("ACTION_APP_FOREGROUND");
        startService(intent);
    }
} 