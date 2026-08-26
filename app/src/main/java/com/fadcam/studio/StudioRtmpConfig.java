package com.fadcam.studio;

/** Central RTMP destination configuration used by the Studio. */
public final class StudioRtmpConfig {
    public static final String PREF_SERVER = "studio_rtmp_server";
    public static final String PREF_PLATFORM = "studio_rtmp_platform";
    public static final String PREF_SECRET = "studio_rtmp_stream_key";
    public static final String PREF_ACTIVE = "studio_rtmp_active";

    public static final String PLATFORM_YOUTUBE = "YouTube";
    public static final String PLATFORM_FACEBOOK = "Facebook";
    public static final String PLATFORM_TWITCH = "Twitch";
    public static final String PLATFORM_CUSTOM = "Custom RTMP";

    private StudioRtmpConfig() {}

    public static String defaultServer(String platform) {
        if (PLATFORM_YOUTUBE.equals(platform)) return "rtmps://a.rtmp.youtube.com/live2";
        if (PLATFORM_FACEBOOK.equals(platform)) return "rtmps://live-api-s.facebook.com:443/rtmp";
        if (PLATFORM_TWITCH.equals(platform)) return "rtmp://live.twitch.tv/app";
        return "";
    }
}
