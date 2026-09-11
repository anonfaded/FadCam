package com.fadcam;

import android.app.Application;
import android.app.ActivityManager;
import androidx.lifecycle.ProcessLifecycleOwner;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import android.content.Intent;
import android.content.ComponentName;

public class FadCamApplication extends Application implements DefaultLifecycleObserver {
    @Override
    public void onCreate() {
        super.onCreate();
        installCrashLogger();
        com.fadcam.services.UpdateCheckService.init(this);
        ProcessLifecycleOwner.get().getLifecycle().addObserver(this);
        // Room DB open + invalidation observer registration is deferred off the
        // main thread: cold start must not block on SQLite open. The observer
        // still catches post-kill index writes (invocation is on Room's own
        // background invalidation thread either way).
        new Thread(this::registerSelfHealingScanObserver, "selfheal-observer").start();
    }

    /**
     * Captures uncaught exceptions into the app's own log (FLog) and a cache
     * file, then delegates to the platform handler so logcat still records the
     * crash. This makes remote-tester crash reports visible in the in-app
     * debug dossier instead of only in `adb logcat`.
     */
    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                throwable.printStackTrace(new java.io.PrintWriter(sw));
                String stack = sw.toString();
                FLog.e("CrashLogger", "FATAL EXCEPTION on thread '" + thread.getName()
                        + "'\n" + stack);
                java.io.File crashFile = new java.io.File(getCacheDir(), "last_crash.txt");
                try (java.io.FileWriter fw = new java.io.FileWriter(crashFile, false)) {
                    fw.write("Thread: " + thread.getName() + "\n\n" + stack);
                } catch (Exception ignored) {
                }
            } catch (Exception ignored) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    /**
     * Native, instant self-healing trigger (issue #332): Room fires this callback
     * the moment ANY row is inserted/updated in the video index — e.g. an
     * abandoned recording being indexed after the process was killed. No polling,
     * no timers: the scan runs exactly when a new file enters the index and only
     * touches rows still marked pending (finalized=0). Single-flight coalescing
     * in the scan itself absorbs bursts.
     */
    private void registerSelfHealingScanObserver() {
        try {
            final android.content.Context app = this;
            androidx.room.RoomDatabase db = com.fadcam.data.VideoIndexDatabase.getInstance(this);
            db.getInvalidationTracker().addObserver(new androidx.room.InvalidationTracker.Observer(
                    new String[]{"video_index"}) {
                @Override
                public void onInvalidated(@androidx.annotation.NonNull java.util.Set<String> tables) {
                    // Runs on Room's invalidation thread (background).
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

    @Override
    public void onStop(@androidx.annotation.NonNull LifecycleOwner owner) {
        // App is in background, reset AppLock session
        SharedPreferencesManager.getInstance(this).setAppLockSessionUnlocked(false);
        Intent intent = new Intent(this, com.fadcam.services.RecordingService.class);
        intent.setAction("ACTION_APP_BACKGROUND");
        startService(intent);
    }

    @Override
    @SuppressWarnings("deprecation") // getRunningTasks is deprecated; only used to identify the focused activity
    public void onStart(@androidx.annotation.NonNull LifecycleOwner owner) {
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