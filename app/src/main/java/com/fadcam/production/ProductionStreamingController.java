package com.fadcam.production;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.core.content.ContextCompat;

import com.fadcam.Constants;
import com.fadcam.studio.StudioRtmpConfig;
import com.fadcam.studio.StudioRtmpSecretStore;
import com.fadcam.studio.StudioRtmpStreamService;

/**
 * Small production-room facade over the existing verified RTMP service.
 * It keeps destination configuration in one place and never stores a stream
 * key in ordinary SharedPreferences.
 */
public final class ProductionStreamingController {
    private static final String PREFS = "FadCamPrefs";
    private static final String DEFAULT_PLATFORM = StudioRtmpConfig.PLATFORM_YOUTUBE;

    private ProductionStreamingController() {}

    public static String getPlatform(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(StudioRtmpConfig.PREF_PLATFORM, DEFAULT_PLATFORM);
    }

    public static String getServer(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(StudioRtmpConfig.PREF_SERVER, StudioRtmpConfig.defaultServer(getPlatform(context)));
    }

    public static boolean isLive(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(StudioRtmpConfig.PREF_ACTIVE, false);
    }

    public static boolean hasRecording(Context context) {
        return context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(Constants.PREF_IS_RECORDING_IN_PROGRESS, false);
    }

    public static boolean hasStreamKey(Context context) {
        try {
            String key = StudioRtmpSecretStore.load(context);
            return key != null && !key.trim().isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void saveDestination(Context context, String platform, String server, String streamKey) throws Exception {
        String normalizedPlatform = platform == null || platform.trim().isEmpty()
                ? DEFAULT_PLATFORM : platform.trim();
        String normalizedServer = server == null ? "" : server.trim();
        String normalizedKey = streamKey == null ? "" : streamKey.trim();
        if (normalizedServer.isEmpty()) throw new IllegalArgumentException("RTMP server URL is required");
        if (!isRtmpServer(normalizedServer)) throw new IllegalArgumentException("Use an RTMP or RTMPS server URL");
        if (normalizedKey.isEmpty()) throw new IllegalArgumentException("Stream key is required");

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(StudioRtmpConfig.PREF_PLATFORM, normalizedPlatform)
                .putString(StudioRtmpConfig.PREF_SERVER, normalizedServer)
                .apply();
        StudioRtmpSecretStore.save(context, normalizedKey);
    }

    public static void start(Context context) {
        if (!hasRecording(context)) throw new IllegalStateException("Start RECORD before going LIVE");
        String server = getServer(context);
        if (server == null || server.trim().isEmpty()) throw new IllegalStateException("Configure a streaming destination first");
        if (!hasStreamKey(context)) throw new IllegalStateException("Configure a stream key first");

        Intent intent = new Intent(context, StudioRtmpStreamService.class)
                .setAction(StudioRtmpStreamService.ACTION_START)
                .putExtra(StudioRtmpStreamService.EXTRA_SERVER, server)
                .putExtra(StudioRtmpStreamService.EXTRA_PLATFORM, getPlatform(context));
        ContextCompat.startForegroundService(context, intent);
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, StudioRtmpStreamService.class)
                .setAction(StudioRtmpStreamService.ACTION_STOP);
        context.startService(intent);
    }

    public static boolean isRtmpServer(String server) {
        if (server == null) return false;
        String value = server.trim().toLowerCase(java.util.Locale.US);
        return value.startsWith("rtmp://") || value.startsWith("rtmps://");
    }
}
