package com.fadcam.ui.animations;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.view.Choreographer;

import java.util.ArrayList;
import java.util.List;

/**
 * One Choreographer-driven clock shared by every animated component on screen.
 *
 * <p>Instead of each preview owning a {@code ValueAnimator}, views subscribe
 * here. That means one VSYNC callback per frame no matter how many previews are
 * visible, and animation stops completely when nothing is subscribed or when
 * the host app is paused.</p>
 *
 * <p>Time is accumulated from frame deltas rather than read from the wall
 * clock. A preview therefore resumes exactly where it paused instead of
 * jumping forward after the screen was off.</p>
 */
public final class AnimationClock {

    /** Receives one callback per frame while the clock is running. */
    public interface FrameListener {
        /**
         * @param timeMs monotonic animation time, milliseconds
         */
        void onAnimFrame(long timeMs);
    }

    /** Frames longer than this are clamped so a stall never teleports motion. */
    private static final long MAX_FRAME_DELTA_MS = 64L;

    private static AnimationClock instance;

    /** Lazily created on the UI thread, which is where every preview lives. */
    public static AnimationClock get() {
        if (instance == null) {
            instance = new AnimationClock();
        }
        return instance;
    }

    private final Choreographer choreographer = Choreographer.getInstance();
    private final List<FrameListener> listeners = new ArrayList<>();

    private long timeMs;
    private long lastFrameNanos;
    private boolean appActive = true;
    private boolean running;
    private boolean lifecycleInstalled;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!running) return;
            if (lastFrameNanos != 0L) {
                long deltaMs = (frameTimeNanos - lastFrameNanos) / 1_000_000L;
                timeMs += Math.min(Math.max(deltaMs, 0L), MAX_FRAME_DELTA_MS);
            }
            lastFrameNanos = frameTimeNanos;
            // Reverse iteration tolerates listeners unsubscribing mid-frame.
            for (int i = listeners.size() - 1; i >= 0; i--) {
                listeners.get(i).onAnimFrame(timeMs);
            }
            if (running) {
                choreographer.postFrameCallback(this);
            }
        }
    };

    private AnimationClock() {
    }

    /**
     * Registers app foreground tracking once. Animation is paused whenever the
     * foreground activity pauses (home, screen off, permission prompts) and
     * resumes when it returns. Resumed/paused is deliberately used instead of
     * a start counter: a view may be inflated after its activity already
     * started, which makes a counter miss its own start and go negative when
     * the previous activity stops.
     */
    public void install(Context context) {
        if (lifecycleInstalled || context == null) return;
        Context appContext = context.getApplicationContext();
        if (!(appContext instanceof Application)) return;
        ((Application) appContext).registerActivityLifecycleCallbacks(
                new Application.ActivityLifecycleCallbacks() {
                    @Override
                    public void onActivityResumed(Activity activity) {
                        setAppActive(true);
                    }

                    @Override
                    public void onActivityPaused(Activity activity) {
                        setAppActive(false);
                    }

                    @Override
                    public void onActivityCreated(Activity activity, Bundle state) {
                    }

                    @Override
                    public void onActivityStarted(Activity activity) {
                    }

                    @Override
                    public void onActivityStopped(Activity activity) {
                    }

                    @Override
                    public void onActivitySaveInstanceState(Activity activity, Bundle state) {
                    }

                    @Override
                    public void onActivityDestroyed(Activity activity) {
                    }
                });
        lifecycleInstalled = true;
    }

    /** Current animation time in milliseconds. */
    public long timeMs() {
        return timeMs;
    }

    public void add(FrameListener listener) {
        if (listener == null || listeners.contains(listener)) return;
        listeners.add(listener);
        updateRunning();
    }

    public void remove(FrameListener listener) {
        if (listeners.remove(listener)) {
            updateRunning();
        }
    }

    /**
     * Pauses every animation when the host goes to the background. Call from
     * {@code Activity#onPause}/{@code onResume} (or the Compose equivalent).
     */
    public void setAppActive(boolean active) {
        if (appActive == active) return;
        appActive = active;
        updateRunning();
    }

    private void updateRunning() {
        boolean shouldRun = appActive && !listeners.isEmpty();
        if (shouldRun == running) return;
        running = shouldRun;
        // Never accumulate the gap spent stopped.
        lastFrameNanos = 0L;
        choreographer.removeFrameCallback(frameCallback);
        if (running) {
            choreographer.postFrameCallback(frameCallback);
        }
    }
}
