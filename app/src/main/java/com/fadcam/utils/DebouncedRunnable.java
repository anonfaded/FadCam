package com.fadcam.utils;

import android.os.Handler;
import android.os.Looper;

/**
 * Utility class to debounce rapid execution of tasks.
 * Useful for preventing multiple identical calls in quick succession,
 * like when scrolling a RecyclerView.
 */
public class DebouncedRunnable {
    private final Runnable action;
    private final Handler handler;
    private final long delayMillis;
    private boolean pending = false;

    /**
     * Creates a new debounced runnable
     * 
     * @param action The action to execute
     * @param delayMillis The delay in milliseconds
     */
    public DebouncedRunnable(Runnable action, long delayMillis) {
        this.action = action;
        this.handler = new Handler(Looper.getMainLooper());
        this.delayMillis = delayMillis;
    }

    /**
     * Run the action, but only if it hasn't been run within the delay period
     */
    public synchronized void run() {
        if (pending) {
            return;
        }
        
        pending = true;
        handler.postDelayed(() -> {
            try {
                action.run();
            } finally {
                pending = false;
            }
        }, delayMillis);
    }

    /**
     * Cancel any pending execution
     */
    public synchronized void cancel() {
        handler.removeCallbacksAndMessages(null);
        pending = false;
    }
} 