package com.fadcam.ui;

/**
 * Tracks whether one of the app's own short-lived capture screens (FadShot) is currently
 * covering the main UI.
 *
 * <p>Those screens are separate activities, so launching one puts {@code MainActivity} and
 * the Home fragment through {@code onStop()}. Home unregisters its broadcast receivers
 * there, which used to remove the listener for {@code ACTION_RECORDING_COMPLETE} before the
 * capture screen got the chance to send it. The result: the home card's file count and used
 * size stayed stale until a tab change or restart.</p>
 *
 * <p>The capture screen sets {@link #inProgress} for its whole lifetime, so Home can skip
 * the teardown instead of guessing from the task stack.</p>
 */
public final class CaptureFlow {

    /** True while FadShot is capturing or saving. Volatile: written on the UI thread, read in onStop. */
    public static volatile boolean inProgress = false;

    private CaptureFlow() {
    }

    public static void begin() {
        inProgress = true;
    }

    public static void end() {
        inProgress = false;
    }
}
