package com.fadcam.streaming;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

/** Application-facing API for starting and stopping live RTMP publishing. */
public final class RtmpPublisherLauncher {
    private RtmpPublisherLauncher() { }

    /** Persist a destination credential encrypted with an Android Keystore key. */
    public static void saveCredentials(@NonNull Context context, @NonNull String alias,
                                       @NonNull RtmpDestination destination,
                                       @NonNull String serverUrl, @NonNull String streamKey) {
        new RtmpCredentialVault(context).put(alias, destination, serverUrl, streamKey);
    }

    /** Starts a publication using credentials loaded from the Keystore-backed vault. */
    public static void start(@NonNull Context context, @NonNull String credentialAlias) {
        if (credentialAlias.trim().isEmpty()) throw new IllegalArgumentException("Credential alias is required");
        Intent intent = new Intent(context, RtmpPublisherService.class)
                .setAction(RtmpPublisherService.ACTION_START)
                .putExtra(RtmpPublisherService.EXTRA_CREDENTIAL_ALIAS, credentialAlias.trim());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, intent);
        } else {
            context.startService(intent);
        }
    }

    /**
     * Legacy convenience overload. New callers should use saveCredentials()+start(alias)
     * so stream keys never cross an Intent boundary.
     */
    public static void start(@NonNull Context context,
                             @NonNull RtmpDestination destination,
                             @NonNull String serverUrl,
                             @NonNull String streamKey) {
        String alias = "default_" + destination.name().toLowerCase(java.util.Locale.US);
        saveCredentials(context, alias, destination, serverUrl, streamKey);
        start(context, alias);
    }

    /** Starts a direct endpoint for trusted internal callers; endpoints are not persisted. */
    public static void startEndpoint(@NonNull Context context, @NonNull String endpoint) {
        if (endpoint.trim().isEmpty()) throw new IllegalArgumentException("RTMP endpoint is required");
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
