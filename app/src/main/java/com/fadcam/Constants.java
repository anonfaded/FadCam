package com.fadcam;

import android.util.Size;

public abstract class Constants {

    public static final String PREFS_NAME = "app_prefs";
    public static final String LANGUAGE_KEY = "language";

    public static final String PREF_VIDEO_RESOLUTION_WIDTH = "video_resolution_width";
    public static final String PREF_VIDEO_RESOLUTION_HEIGHT = "video_resolution_height";
    public static final String PREF_VIDEO_FRAME_RATE = "video_frame_rate";
    public static final String PREF_CAMERA_SELECTION = "camera_selection";
    public static final String PREF_IS_PREVIEW_ENABLED = "isPreviewEnabled";
    public static final String PREF_BOTH_TORCHES_ENABLED = "pref_both_torches_enabled";
    public static final String PREF_SELECTED_TORCH_SOURCE = "pref_selected_torch_source";
    public static final String PREF_TORCH_STATE = "pref_torch_state";
    public static final String PREF_LOCATION_DATA = "location_data";
    public static final String PREF_DEBUG_DATA = "debug_data";
    public static final String PREF_WATERMARK_OPTION = "watermark_option";
    public static final String PREF_VIDEO_CODEC = "video_codec";
    public static final String PREF_APP_THEME = "app_theme";
    public static final String PREF_IS_RECORDING_IN_PROGRESS = "is_recording_in_progress";

    public static final String BROADCAST_ON_RECORDING_STARTED = "ON_RECORDING_STARTED";
    public static final String BROADCAST_ON_RECORDING_RESUMED = "ON_RECORDING_RESUMED";
    public static final String BROADCAST_ON_RECORDING_PAUSED = "ON_RECORDING_PAUSED";
    public static final String BROADCAST_ON_RECORDING_STOPPED = "ON_RECORDING_STOPPED";
    public static final String BROADCAST_ON_RECORDING_STATE_REQUEST = "ON_RECORDING_STATE_REQUEST";
    public static final String BROADCAST_ON_RECORDING_STATE_CALLBACK = "ON_RECORDING_STATE_CALLBACK";
    public static final String BROADCAST_ON_TORCH_STATE_CHANGED = "ON_TORCH_STATE_CHANGED";
    public static final String BROADCAST_ON_TORCH_STATE_REQUEST = "ON_TORCH_STATE_REQUEST";
    public static final String BROADCAST_CAMERA_ERROR = "CAMERA_ACCESS_ERROR";

    public static final String INTENT_ACTION_STOP_RECORDING = "ACTION_STOP_RECORDING";
    public static final String INTENT_ACTION_CHANGE_SURFACE = "ACTION_CHANGE_SURFACE";
    public static final String INTENT_ACTION_RESUME_RECORDING = "ACTION_RESUME_RECORDING";
    public static final String INTENT_ACTION_PAUSE_RECORDING = "ACTION_PAUSE_RECORDING";
    public static final String INTENT_ACTION_START_RECORDING = "ACTION_START_RECORDING";
    public static final String INTENT_ACTION_TOGGLE_TORCH = "ACTION_TOGGLE_TORCH";

    public static final String INTENT_EXTRA_RECORDING_STATE = "RECORDING_STATE";
    public static final String INTENT_EXTRA_RECORDING_START_TIME = "RECORDING_START_TIME";
    public static final String INTENT_EXTRA_TORCH_STATE = "TORCH_STATE";
    public static final String INTENT_EXTRA_TORCH_STATE_CHANGED = "TORCH_STATE_CHANGED";

    public static final String RECORDING_DIRECTORY = "FadCam";
    public static final String RECORDING_FILE_EXTENSION = "mp4";
    public static final double RECORDING_COMPRESSION_FACTOR = 0.33;

    public static final CameraType DEFAULT_CAMERA_TYPE = CameraType.BACK;
    public static final Size DEFAULT_VIDEO_RESOLUTION = new Size(1920, 1080);
    public static final int DEFAULT_VIDEO_FRAME_RATE = 30;
    public static final VideoCodec DEFAULT_VIDEO_CODEC = VideoCodec.HEVC;

    public static final String DEFAULT_WATERMARK_OPTION = "timestamp_fadcam";
    public static final boolean DEFAULT_PREVIEW_ENABLED = true;

    public static final String INTENT_ACTION_TOGGLE_RECORDING_TORCH = "com.fadcam.TOGGLE_RECORDING_TORCH";

}
