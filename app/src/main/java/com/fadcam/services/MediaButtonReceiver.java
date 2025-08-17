package com.fadcam.services;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Minimal receiver so MediaSessionCompat has a concrete media button receiver component name.
 * We don't need to handle intents here because PlayerNotificationManager integrates with MediaSession.
 */
public class MediaButtonReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // No-op: handled via MediaSession/PNM.
    }
}
