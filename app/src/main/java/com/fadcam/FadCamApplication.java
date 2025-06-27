package com.fadcam;

import android.app.Application;
import androidx.lifecycle.ProcessLifecycleOwner;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;
import androidx.lifecycle.Lifecycle;
import android.content.Intent;

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
        // ----- Fix Start: Notify RecordingService to release GL resources -----
        Intent intent = new Intent(this, com.fadcam.services.RecordingService.class);
        intent.setAction("ACTION_APP_BACKGROUND");
        startService(intent);
        // ----- Fix End -----
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    public void onAppForegrounded() {
        // ----- Fix Start: Notify RecordingService to re-initialize if needed -----
        Intent intent = new Intent(this, com.fadcam.services.RecordingService.class);
        intent.setAction("ACTION_APP_FOREGROUND");
        startService(intent);
        // ----- Fix End -----
    }
} 