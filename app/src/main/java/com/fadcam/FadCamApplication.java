package com.fadcam;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;
import androidx.lifecycle.ProcessLifecycleOwner;

import com.fadcam.ui.BrandingManager;
import com.fadcam.ui.ProductionFeatureUnlocker;
import com.fadcam.production.ProductionOnlineTvInstaller;

public class FadCamApplication extends Application implements LifecycleObserver {
    @Override
    public void onCreate() {
        super.onCreate();
        ProcessLifecycleOwner.get().getLifecycle().addObserver(this);
        registerBrandingLifecycle();
        ProductionOnlineTvInstaller.register(this);
        new Thread(this::registerSelfHealingScanObserver, "selfheal-observer").start();
    }

    /** Keeps branding and the production-tool unlock wiring applied after navigation/rotation. */
    private void registerBrandingLifecycle() {
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) {
                BrandingManager.applyToActivity(activity);
                wireProductionTools(activity);
            }
            @Override public void onActivityResumed(Activity activity) {
                BrandingManager.applyToActivity(activity);
                wireProductionTools(activity);
            }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
    }

    private void wireProductionTools(Activity activity) {
        if (activity == null) return;
        activity.getWindow().getDecorView().postDelayed(() -> {
            try {
                ProductionFeatureUnlocker.install(activity.getWindow().getDecorView());
            } catch (Throwable t) {
                FLog.w("FadCamApplication", "Production tool wiring failed: " + t.getMessage());
            }
        }, 120L);
    }

    private void registerSelfHealingScanObserver() {
        try {
            final android.content.Context app = this;
            androidx.room.RoomDatabase db = com.fadcam.data.VideoIndexDatabase.getInstance(this);
            db.getInvalidationTracker().addObserver(new androidx.room.InvalidationTracker.Observer(
                    new String[]{"video_index"}) {
                @Override
                public void onInvalidated(@androidx.annotation.NonNull java.util.Set<String> tables) {
                    try {
                        com.fadcam.services.RecordingService.runSelfHealingScan(app, null);
                    } catch (Exception e) {
                        com.fadcam.FLog.w("FadCamApplication", "Self-healing scan trigger failed", e);
                    }
                }
            });
            com.fadcam.FLog.d("FadCamApplication", "Self-healing scan observer registered (video_index)");
        } catch (Exception e) {
            com.fadcam.FLog.w("FadCamApplication", "Failed to register self-healing scan observer", e);
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    public void onAppBackgrounded() {
        SharedPreferencesManager.getInstance(this).setAppLockSessionUnlocked(false);
        Intent intent = new Intent(this, com.fadcam.services.RecordingService.class);
        intent.setAction("ACTION_APP_BACKGROUND");
        startService(intent);
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    public void onAppForegrounded() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am != null) {
            java.util.List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            if (!tasks.isEmpty()) {
                ComponentName topActivity = tasks.get(0).topActivity;
                if (topActivity != null) {
                    String activityClassName = topActivity.getClassName();
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
        Intent intent = new Intent(this, com.fadcam.services.RecordingService.class);
        intent.setAction("ACTION_APP_FOREGROUND");
        startService(intent);
    }
}