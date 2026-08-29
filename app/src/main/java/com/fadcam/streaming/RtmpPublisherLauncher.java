package com.fadcam.streaming;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

/**
 * Small application-facing API for starting and stopping the live camera/audio
 * RTMP pipeline. UI code does not need to know about service lifecycle details.
 *
 * <p>The caller must have CAMERA and RECORD_AUDIO runtime permissions before
 * starting a publication. Stream keys are passed only to the service for the
 * active session and are not persisted by this class.</p>
 */
public final class RtmpPublisherLauncher {
    private RtmpPublisherLauncher() { }

    public static void start(@NonNull Context context,
                             @NonNull RtmpDestination destination,
                             @NonNull String serverUrl,
                             @NonNull String streamKey) {
        String endpoint = destination.buildEndpoint(serverUrl, streamKey);
        startEndpoint(context, endpoint);
    }

    public static void startEndpoint(@NonNull Context context, @NonNull String endpoint) {
        if (endpoint.trim().isEmpty()) {
            throw new IllegalArgumentException("RTMP endpoint is required");
        }
        Intent intent = new Intent(context, RtmpPublisherService.class)
                .setAction(RtmpPublisherService.ACTION_START)
                .putExtra(RtmpPublisherService.EXTRA_ENDPOINT, endpoint.trim());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(@NonNull Context context) {
        context.stopService(new Intent(context, RtmpPublisherService.class));
    }
}
