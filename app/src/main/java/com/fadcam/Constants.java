package com.fadcam;

public abstract class Constants {

    public static final int DEFAULT_VIDEO_FRAME_RATE = 30;

    public static final String PREFS_NAME = "app_prefs";
    public static final String LANGUAGE_KEY = "language";

    public static final String PREF_VIDEO_QUALITY = "video_quality";
    public static final String PREF_VIDEO_FRAME_RATE = "video_framerate";
    public static final String PREF_CAMERA_SELECTION = "camera_selection";
    public static final String PREF_IS_PREVIEW_ENABLED = "isPreviewEnabled";

    public static final String CAMERA_FRONT = "front";
    public static final String CAMERA_BACK = "back";

    public static final String QUALITY_SD = "SD";
    public static final String QUALITY_HD = "HD";
    public static final String QUALITY_FHD = "FHD";

    public static final String BROADCAST_ON_RECORDING_STARTED = "com.fadcam.ON_RECORDING_STARTED";
    public static final String BROADCAST_ON_RECORDING_RESUMED = "com.fadcam.ON_RECORDING_RESUMED";
    public static final String BROADCAST_ON_RECORDING_PAUSED = "com.fadcam.ON_RECORDING_PAUSED";
    public static final String BROADCAST_ON_RECORDING_STOPPED = "com.fadcam.ON_RECORDING_STOPPED";
    public static final String BROADCAST_ON_RECORDING_STATE_REQUEST = "com.fadcam.ON_RECORDING_STATE_REQUEST";
    public static final String BROADCAST_ON_RECORDING_STATE_CALLBACK = "com.fadcam.ON_RECORDING_STATE_CALLBACK";

    public static final String BROADCAST_EXTRA_RECORDING_STATE = "RECORDING_STATE";
    public static final String BROADCAST_EXTRA_RECORDING_START_TIME = "RECORDING_START_TIME";

    public static final String INTENT_ACTION_STOP_RECORDING = "ACTION_STOP_RECORDING";
    public static final String INTENT_ACTION_CHANGE_SURFACE = "ACTION_CHANGE_SURFACE";
    public static final String INTENT_ACTION_RESUME_RECORDING = "ACTION_RESUME_RECORDING";
    public static final String INTENT_ACTION_PAUSE_RECORDING = "ACTION_PAUSE_RECORDING";
    public static final String INTENT_ACTION_START_RECORDING = "ACTION_START_RECORDING";
    public static final String INTENT_ACTION_TOGGLE_TORCH = "com.fadcam.action.TOGGLE_TORCH";
    public static final String INTENT_ACTION_TORCH_RECORDING = "com.fadcam.action.TORCH_RECORDING";

    public static final String RECORDING_DIRECTORY = "FadCam";
    public static final String RECORDING_FILE_EXTENSION = "mp4";

    public static final String BROADCAST_TORCH_STATE_CHANGED = "com.fadcam.TORCH_STATE_CHANGED";

    public static final String INTENT_ACTION_TORCH_CONFIG = "com.fadcam.action.TORCH_CONFIG";
}
