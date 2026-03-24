package com.fadcam.services;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
/**
 * Receives the alarm to stop background playback. Uses the PlaybackService stop action.
 */
public class AutoStopReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try{
            Intent stop = new Intent(context, PlaybackService.class).setAction(PlaybackService.ACTION_STOP);
            // Start service to handle stop
            context.startService(stop);
        }catch(Exception e){ FLog.w("AutoStopReceiver", "failed to stop playback", e); }
    }
}
