package com.fadcam;

import android.content.Context;
import android.content.Intent;
import com.fadcam.services.RecordingService;

/**
 * Small helper to build Intents targeting RecordingService for runtime camera controls.
 */
public final class RecordingControlIntents {

    private RecordingControlIntents() {}

    public static Intent setExposureCompensation(Context ctx, int evIndex) {
        Intent i = new Intent(ctx, RecordingService.class);
        i.setAction(Constants.INTENT_ACTION_SET_EXPOSURE_COMPENSATION);
        i.putExtra(Constants.EXTRA_EXPOSURE_COMPENSATION, evIndex);
        return i;
    }

    public static Intent toggleAeLock(Context ctx, boolean lock) {
        Intent i = new Intent(ctx, RecordingService.class);
        i.setAction(Constants.INTENT_ACTION_TOGGLE_AE_LOCK);
        i.putExtra(Constants.EXTRA_AE_LOCK, lock);
        return i;
    }

    public static Intent setAfMode(Context ctx, int afMode) {
        Intent i = new Intent(ctx, RecordingService.class);
        i.setAction(Constants.INTENT_ACTION_SET_AF_MODE);
        i.putExtra(Constants.EXTRA_AF_MODE, afMode);
        return i;
    }

    public static Intent tapToFocus(Context ctx, float normX, float normY) {
        Intent i = new Intent(ctx, RecordingService.class);
        i.setAction(Constants.INTENT_ACTION_TAP_TO_FOCUS);
        i.putExtra(Constants.EXTRA_FOCUS_X, normX);
        i.putExtra(Constants.EXTRA_FOCUS_Y, normY);
        return i;
    }

    public static Intent setZoomRatio(Context ctx, float zoomRatio) {
        Intent i = new Intent(ctx, RecordingService.class);
        i.setAction(Constants.INTENT_ACTION_SET_ZOOM_RATIO);
        i.putExtra(Constants.EXTRA_ZOOM_RATIO, zoomRatio);
        return i;
    }
}
