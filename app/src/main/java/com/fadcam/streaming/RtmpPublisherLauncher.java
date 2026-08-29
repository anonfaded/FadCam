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

    /** Starts a publication using only a non-secret credential alias across the Intent boundary. */
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

    /** Compatibility convenience: persist first, then send only the alias through the Intent. */
    public static void start(@NonNull Context context,
                             @NonNull RtmpDestination destination,
                             @NonNull String serverUrl,
                             @NonNull String streamKey) {
        String alias = "default_" + destination.name().toLowerCase(java.util.Locale.US);
        saveCredentials(context, alias, destination, serverUrl, streamKey);
        start(context, alias);
    }

    public static void stop(@NonNull Context context) {
        context.stopService(new Intent(context, RtmpPublisherService.class));
    }
}
