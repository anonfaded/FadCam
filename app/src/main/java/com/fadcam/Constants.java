package com.fadcam;

public abstract class Constants {

    public static final int DEFAULT_VIDEO_FRAME_RATE = 30;

    public static final String PREFS_NAME = "app_prefs";
    public static final String LANGUAGE_KEY = "language";

    public static final String PREF_VIDEO_QUALITY = "video_quality";
    public static final String PREF_VIDEO_FRAME_RATE = "video_framerate";
    public static final String PREF_CAMERA_SELECTION = "camera_selection";
    public static final String PREF_IS_PREVIEW_ENABLED = "isPreviewEnabled";
    public static final String PREF_BOTH_TORCHES_ENABLED = "both_torches_enabled";
    public static final String PREF_SELECTED_TORCH_SOURCE = "selected_torch_source";

    public static final String CAMERA_FRONT = "front";
    public static final String CAMERA_BACK = "back";

    public static final String QUALITY_SD = "SD";
    public static final String QUALITY_HD = "HD";
    public static final String QUALITY_FHD = "FHD";

    public static final String BROADCAST_ON_RECORDING_STARTED = "ON_RECORDING_STARTED";
    public static final String BROADCAST_ON_RECORDING_RESUMED = "ON_RECORDING_RESUMED";
    public static final String BROADCAST_ON_RECORDING_PAUSED = "ON_RECORDING_PAUSED";
    public static final String BROADCAST_ON_RECORDING_STOPPED = "ON_RECORDING_STOPPED";
    public static final String BROADCAST_ON_RECORDING_STATE_REQUEST = "ON_RECORDING_STATE_REQUEST";
    public static final String BROADCAST_ON_RECORDING_STATE_CALLBACK = "ON_RECORDING_STATE_CALLBACK";
    public static final String BROADCAST_ON_TORCH_STATE_CHANGED = "ON_TORCH_STATE_CHANGED";
    public static final String BROADCAST_ON_TORCH_STATE_REQUEST = "ON_TORCH_STATE_REQUEST";

    public static final String INTENT_ACTION_STOP_RECORDING = "ACTION_STOP_RECORDING";
    public static final String INTENT_ACTION_CHANGE_SURFACE = "ACTION_CHANGE_SURFACE";
    public static final String INTENT_ACTION_RESUME_RECORDING = "ACTION_RESUME_RECORDING";
    public static final String INTENT_ACTION_PAUSE_RECORDING = "ACTION_PAUSE_RECORDING";
    public static final String INTENT_ACTION_START_RECORDING = "ACTION_START_RECORDING";
    public static final String INTENT_ACTION_TOGGLE_TORCH = "ACTION_TOGGLE_TORCH";

    public static final String INTENT_EXTRA_RECORDING_STATE = "RECORDING_STATE";
    public static final String INTENT_EXTRA_RECORDING_START_TIME = "RECORDING_START_TIME";
    public static final String INTENT_EXTRA_TORCH_STATE = "TORCH_STATE";

    public static final String RECORDING_DIRECTORY = "FadCam";
    public static final String RECORDING_FILE_EXTENSION = "mp4";
}
