package com.fadcam.streaming;

import androidx.annotation.NonNull;

/**
 * Supported RTMP/RTMPS publishing destinations.
 *
 * <p>Stream keys are never stored by this enum. The caller supplies the current
 * server URL and stream key when starting a publication.</p>
 */
public enum RtmpDestination {
    YOUTUBE("YouTube", true),
    TWITCH("Twitch", true),
    FACEBOOK("Facebook", true),
    CUSTOM("Custom RTMP", false);

    private final String displayName;
    private final boolean managedProfile;

    RtmpDestination(@NonNull String displayName, boolean managedProfile) {
        this.displayName = displayName;
        this.managedProfile = managedProfile;
    }

    @NonNull
    public String getDisplayName() {
        return displayName;
    }

    public boolean isManagedProfile() {
        return managedProfile;
    }

    /**
     * Builds a publish endpoint from a platform's server URL and stream key.
     * The server URL must come from the platform's current live-control settings
     * (especially YouTube/Facebook, where ingest endpoints can vary).
     */
    @NonNull
    public String buildEndpoint(@NonNull String serverUrl, @NonNull String streamKey) {
        String base = serverUrl.trim();
        String key = streamKey.trim();
        if (base.isEmpty()) throw new IllegalArgumentException("RTMP server URL is required");
        if (key.isEmpty()) throw new IllegalArgumentException("Stream key is required");
        if (!(base.startsWith("rtmp://") || base.startsWith("rtmps://")
                || base.startsWith("rtmpt://") || base.startsWith("rtmpts://"))) {
            throw new IllegalArgumentException("Server URL must use RTMP or RTMPS");
        }
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        while (key.startsWith("/")) key = key.substring(1);
        return base + "/" + key;
    }
}
