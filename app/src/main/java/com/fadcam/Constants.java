package com.fadcam;

import android.util.Size;

public abstract class Constants {

    public static final String PREFS_NAME = "app_prefs";
    public static final String LANGUAGE_KEY = "language";

    public static final String PREF_VIDEO_RESOLUTION_WIDTH = "video_resolution_width";
    public static final String PREF_VIDEO_RESOLUTION_HEIGHT = "video_resolution_height";
    public static final String PREF_VIDEO_FRAME_RATE = "video_frame_rate";
    // *** NEW: Specific FPS Prefs per Camera ***
    public static final String PREF_VIDEO_FRAME_RATE_FRONT = "video_frame_rate_front";
    public static final String PREF_VIDEO_FRAME_RATE_BACK = "video_frame_rate_back";
    // *** End New FPS Prefs ***
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
    public static final String PREF_RECORD_AUDIO = "pref_record_audio";
    public static final String PREF_AUDIO_BITRATE = "audio_bitrate";
    public static final String PREF_AUDIO_SAMPLING_RATE = "audio_sampling_rate";

    public static final String BROADCAST_ON_RECORDING_STARTED = "ON_RECORDING_STARTED";
    public static final String BROADCAST_ON_RECORDING_RESUMED = "ON_RECORDING_RESUMED";
    public static final String BROADCAST_ON_RECORDING_PAUSED = "ON_RECORDING_PAUSED";
    public static final String BROADCAST_ON_RECORDING_STOPPED = "ON_RECORDING_STOPPED";
    public static final String BROADCAST_ON_RECORDING_STATE_REQUEST = "ON_RECORDING_STATE_REQUEST";
    public static final String BROADCAST_ON_RECORDING_STATE_CALLBACK = "ON_RECORDING_STATE_CALLBACK";
    public static final String BROADCAST_ON_TORCH_STATE_CHANGED = "com.fadcam.ON_TORCH_STATE_CHANGED";
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
    public static final String INTENT_EXTRA_TORCH_STATE = "torch_state";
    public static final String INTENT_EXTRA_TORCH_STATE_CHANGED = "TORCH_STATE_CHANGED";
    public static final String INTENT_EXTRA_INITIAL_TORCH_STATE = "com.fadcam.EXTRA_INITIAL_TORCH_STATE";

    public static final String RECORDING_DIRECTORY = "FadCam";
    public static final String RECORDING_FILE_EXTENSION = "mp4";
    public static final double RECORDING_COMPRESSION_FACTOR = 0.33;

    public static final CameraType DEFAULT_CAMERA_TYPE = CameraType.BACK;
    public static final Size DEFAULT_VIDEO_RESOLUTION = new Size(1920, 1080);
    public static final int DEFAULT_VIDEO_FRAME_RATE = 30;
    public static final VideoCodec DEFAULT_VIDEO_CODEC = VideoCodec.HEVC;

    public static final String DEFAULT_WATERMARK_OPTION = "timestamp_fadcam";
    public static final boolean DEFAULT_PREVIEW_ENABLED = true;
    public static final boolean DEFAULT_RECORD_AUDIO = true;
    public static final int DEFAULT_AUDIO_BITRATE = 192000; // 192 kbps stereo AAC
    public static final int DEFAULT_AUDIO_SAMPLING_RATE = 48000; // 48 kHz

    public static final String INTENT_ACTION_TOGGLE_RECORDING_TORCH = "com.fadcam.TOGGLE_RECORDING_TORCH";
    // Broadcast action sent by RecordingService when video processing is done
    public static final String ACTION_RECORDING_COMPLETE = "com.fadcam.RECORDING_COMPLETE";
    // Extra key for the URI of the final or temporary file (as String)
    public static final String EXTRA_RECORDING_URI_STRING = "com.fadcam.EXTRA_RECORDING_URI_STRING";
    // Extra key indicating if processing was successful
    public static final String EXTRA_RECORDING_SUCCESS = "com.fadcam.EXTRA_RECORDING_SUCCESS";

    // Broadcast action sent by SettingsFragment when storage location pref changes
    public static final String ACTION_STORAGE_LOCATION_CHANGED = "com.fadcam.STORAGE_LOCATION_CHANGED";

    // Broadcast Actions for Video Processing State
    public static final String ACTION_PROCESSING_STARTED = "com.fadcam.PROCESSING_STARTED";
    public static final String ACTION_PROCESSING_FINISHED = "com.fadcam.PROCESSING_FINISHED"; // Can replace COMPLETE if always sent AFTER processing

    // Extra key for the URI of the file being processed (usually the temp file)
    public static final String EXTRA_PROCESSING_URI_STRING = "com.fadcam.EXTRA_PROCESSING_URI_STRING";

    // ----- Fix Start for this class (Constants_video_splitting_broadcast) -----
    // Broadcast action sent by RecordingService when a video segment is complete (due to splitting)
    public static final String ACTION_RECORDING_SEGMENT_COMPLETE = "com.fadcam.RECORDING_SEGMENT_COMPLETE";
    // Extra key for the URI of the completed segment file (as String) - can be content:// or file://
    public static final String INTENT_EXTRA_FILE_URI = "com.fadcam.EXTRA_FILE_URI";
    // Extra key for the absolute path of the completed segment file (if internal storage) - use with caution
    public static final String INTENT_EXTRA_FILE_PATH = "com.fadcam.EXTRA_FILE_PATH";
    // Extra key for the segment number that just completed
    public static final String INTENT_EXTRA_SEGMENT_NUMBER = "com.fadcam.EXTRA_SEGMENT_NUMBER";
    // ----- Fix Ended for this class (Constants_video_splitting_broadcast) -----

    // SharedPreferences key for opened videos
    public static final String PREF_OPENED_VIDEO_URIS = "opened_video_uris";

    public static final String PREF_SELECTED_BACK_CAMERA_ID = "selected_back_camera_id";
    public static final String DEFAULT_BACK_CAMERA_ID = "0"; // Default physical ID for back cameras is often "0"

    // Trash Feature Constants
    public static final String TRASH_DIRECTORY_NAME = "Trash";
    public static final String TRASH_METADATA_FILENAME = "trash_metadata.json";

    // Request codes
    public static final int REQUEST_CODE_OPEN_DOCUMENT_TREE_FOR_SAF = 1001; // Added request code

    public static final String EXTRA_ORIGINAL_TEMP_SAF_URI_STRING = "com.fadcam.EXTRA_ORIGINAL_TEMP_SAF_URI_STRING"; // For SAF processing replacement tracking

}
