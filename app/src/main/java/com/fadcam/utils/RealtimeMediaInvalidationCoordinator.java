package com.fadcam.utils;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.fadcam.Constants;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Coalesces media/snapshot invalidations from broadcasts + file observers.
 * This keeps UI refreshes realtime without thrashing the main thread.
 */
public class RealtimeMediaInvalidationCoordinator {

    public interface Listener {
        void onInvalidated(@NonNull String reason);
    }

    private static final long DEFAULT_DEBOUNCE_MS = 180L;

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<FileObserver> fileObservers = new ArrayList<>();
    private final long debounceMs;
    private final Runnable dispatchRunnable;

    @Nullable
    private String pendingReason;
    private boolean started;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            invalidate(intent.getAction());
        }
    };

    public RealtimeMediaInvalidationCoordinator(@NonNull Context context) {
        this(context, DEFAULT_DEBOUNCE_MS);
    }

    public RealtimeMediaInvalidationCoordinator(@NonNull Context context, long debounceMs) {
        this.appContext = context.getApplicationContext();
        this.debounceMs = Math.max(60L, debounceMs);
        this.dispatchRunnable = () -> {
            String reason = pendingReason == null ? "unknown" : pendingReason;
            pendingReason = null;
            for (Listener listener : listeners) {
                listener.onInvalidated(reason);
            }
        };
    }

    public void addListener(@NonNull Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(@NonNull Listener listener) {
        listeners.remove(listener);
    }

    public void start() {
        if (started) return;
        started = true;
        registerBroadcasts();
        startFileObservers();
    }

    public void stop() {
        if (!started) return;
        started = false;
        try {
            appContext.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
        }
        stopFileObservers();
        mainHandler.removeCallbacks(dispatchRunnable);
        pendingReason = null;
    }

    public void invalidate(@NonNull String reason) {
        pendingReason = reason;
        mainHandler.removeCallbacks(dispatchRunnable);
        mainHandler.postDelayed(dispatchRunnable, debounceMs);
    }

    private void registerBroadcasts() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Constants.ACTION_RECORDING_COMPLETE);
        filter.addAction(Constants.ACTION_RECORDING_SEGMENT_COMPLETE);
        filter.addAction(Constants.ACTION_STORAGE_LOCATION_CHANGED);
        filter.addAction(Constants.ACTION_FILES_RESTORED);
        filter.addAction(Constants.ACTION_FORENSICS_SNAPSHOT_PERSISTED);
        ContextCompat.registerReceiver(
                appContext,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
    }

    private void startFileObservers() {
        File externalRoot = appContext.getExternalFilesDir(null);
        if (externalRoot == null) return;
        File fadCamRoot = new File(externalRoot, "FadCam");
        if (!fadCamRoot.exists()) return;

        List<File> watchRoots = new ArrayList<>();
        collectAllDirectories(fadCamRoot, watchRoots);

        for (File dir : watchRoots) {
            if (dir == null || !dir.exists() || !dir.isDirectory()) continue;
            FileObserver observer = new FileObserver(
                    dir.getAbsolutePath(),
                    FileObserver.CREATE
                            | FileObserver.CLOSE_WRITE
                            | FileObserver.MOVED_TO
                            | FileObserver.DELETE
                            | FileObserver.DELETE_SELF
            ) {
                @Override
                public void onEvent(int event, @Nullable String path) {
                    invalidate("file:" + dir.getName());
                }
            };
            observer.startWatching();
            fileObservers.add(observer);
        }
    }

    private void stopFileObservers() {
        for (FileObserver observer : fileObservers) {
            try {
                observer.stopWatching();
            } catch (Throwable ignored) {
            }
        }
        fileObservers.clear();
    }

    /**
     * Recursively collects all directories starting from the given root.
     * This ensures FileObservers cover nested subdirectories
     * (e.g. FadCam/FadShot/Back/, FadCam/FadShot/Selfie/).
     */
    private void collectAllDirectories(@NonNull File dir, @NonNull List<File> result) {
        result.add(dir);
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child != null && child.isDirectory()) {
                    collectAllDirectories(child, result);
                }
            }
        }
    }
}
