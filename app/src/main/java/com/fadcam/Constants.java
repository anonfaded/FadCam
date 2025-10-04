package com.fadcam;

import android.util.Size;

public abstract class Constants {

    public static final String PREFS_NAME = "app_prefs";
    public static final String LANGUAGE_KEY = "language";
    public static final String COMPLETED_ONBOARDING_KEY =
        "pref_completed_onboarding";
    public static final String FIRST_INSTALL_CHECKED_KEY =
        "first_install_checked";

    public static final String PREF_VIDEO_RESOLUTION_WIDTH =
        "video_resolution_width";
    public static final String PREF_VIDEO_RESOLUTION_HEIGHT =
        "video_resolution_height";
    public static final String PREF_VIDEO_FRAME_RATE = "video_frame_rate";
    // *** NEW: Specific FPS Prefs per Camera ***
    public static final String PREF_VIDEO_FRAME_RATE_FRONT =
        "video_frame_rate_front";
    public static final String PREF_VIDEO_FRAME_RATE_BACK =
        "video_frame_rate_back";
    // *** End New FPS Prefs ***

    // *** NEW: Zoom Ratio Prefs per Camera ***
    public static final String PREF_ZOOM_RATIO_FRONT = "zoom_ratio_front";
    public static final String PREF_ZOOM_RATIO_BACK = "zoom_ratio_back";
    // *** End New Zoom Ratio Prefs ***
    public static final String PREF_CAMERA_SELECTION = "camera_selection";
    public static final String PREF_IS_PREVIEW_ENABLED = "isPreviewEnabled";
    public static final String PREF_BOTH_TORCHES_ENABLED =
        "pref_both_torches_enabled";
    public static final String PREF_SELECTED_TORCH_SOURCE =
        "pref_selected_torch_source";
    public static final String PREF_TORCH_STATE = "pref_torch_state";
    public static final String PREF_LOCATION_DATA = "location_data";
    public static final String PREF_EMBED_LOCATION_DATA = "embed_location_data";
    public static final String PREF_DEBUG_DATA = "debug_data";
    public static final String PREF_WATERMARK_OPTION = "watermark_option";
    public static final String PREF_VIDEO_CODEC = "video_codec";
    public static final String PREF_APP_THEME = "app_theme";
    /**
     * Default theme used throughout the app.
     * This constant should be used as the fallback value whenever getting the theme
     * from SharedPreferences.
     * Previously hardcoded as "Midnight Dusk", now changed to "Crimson Bloom".
     * NOTE: Some files may still reference "Midnight Dusk" directly and should be
     * updated to use this constant.
     */
    public static final String DEFAULT_APP_THEME = "Crimson Bloom"; // Default theme changed from Midnight Dusk to
    // Crimson Bloom
    public static final String PREF_IS_RECORDING_IN_PROGRESS =
        "is_recording_in_progress";
    // Camera runtime control preferences (persisted when user changes controls
    // while not recording)
    public static final String PREF_EXPOSURE_COMPENSATION =
        "pref_exposure_compensation"; // int
    public static final String PREF_AE_LOCK = "pref_ae_lock"; // boolean
    public static final String PREF_AF_MODE = "pref_af_mode"; // int

    // -------------- Fix Start (ModeSwitcher Constants) --------------
    // Mode switcher constants
    public static final String MODE_FADCAM = "FADCAM";
    public static final String MODE_FADREC = "FADREC";
    public static final String MODE_FADMIC = "FADMIC";
    // -------------- Fix Ended (ModeSwitcher Constants) --------------

    // Fragment result keys used by PickerBottomSheetFragment listeners
    public static final String RK_EXPOSURE_COMPENSATION =
        "rk_exposure_compensation";
    public static final String RK_AE_LOCK = "rk_ae_lock";
    public static final String RK_AF_MODE = "rk_af_mode";
    public static final String RK_ZOOM_RATIO = "rk_zoom_ratio";
    public static final String PREF_RECORD_AUDIO = "pref_record_audio";
    public static final String PREF_AUDIO_BITRATE = "audio_bitrate";
    public static final String PREF_AUDIO_SAMPLING_RATE = "audio_sampling_rate";

    public static final String BROADCAST_ON_RECORDING_STARTED =
        "ON_RECORDING_STARTED";
    public static final String BROADCAST_ON_RECORDING_RESUMED =
        "ON_RECORDING_RESUMED";
    public static final String BROADCAST_ON_RECORDING_PAUSED =
        "ON_RECORDING_PAUSED";
    public static final String BROADCAST_ON_RECORDING_STOPPED =
        "ON_RECORDING_STOPPED";
    public static final String BROADCAST_ON_RECORDING_STATE_REQUEST =
        "ON_RECORDING_STATE_REQUEST";
    public static final String BROADCAST_ON_RECORDING_STATE_CALLBACK =
        "ON_RECORDING_STATE_CALLBACK";
    public static final String BROADCAST_ON_TORCH_STATE_CHANGED =
        "com.fadcam.ON_TORCH_STATE_CHANGED";
    public static final String BROADCAST_ON_TORCH_STATE_REQUEST =
        "ON_TORCH_STATE_REQUEST";
    public static final String BROADCAST_CAMERA_ERROR = "CAMERA_ACCESS_ERROR";

    public static final String INTENT_ACTION_STOP_RECORDING =
        "ACTION_STOP_RECORDING";
    public static final String INTENT_ACTION_CHANGE_SURFACE =
        "ACTION_CHANGE_SURFACE";
    public static final String INTENT_ACTION_RESUME_RECORDING =
        "ACTION_RESUME_RECORDING";
    // New intent actions for runtime camera controls (exposure/AE/AF)
    public static final String INTENT_ACTION_SET_EXPOSURE_COMPENSATION =
        "com.fadcam.ACTION_SET_EXPOSURE_COMPENSATION";
    public static final String INTENT_ACTION_TOGGLE_AE_LOCK =
        "com.fadcam.ACTION_TOGGLE_AE_LOCK";
    public static final String INTENT_ACTION_SET_AF_MODE =
        "com.fadcam.ACTION_SET_AF_MODE";
    public static final String INTENT_ACTION_TAP_TO_FOCUS =
        "com.fadcam.ACTION_TAP_TO_FOCUS";
    public static final String INTENT_ACTION_SET_ZOOM_RATIO =
        "com.fadcam.ACTION_SET_ZOOM_RATIO";

    // Extras for the above intents
    public static final String EXTRA_EXPOSURE_COMPENSATION =
        "com.fadcam.EXTRA_EXPOSURE_COMPENSATION"; // int
    public static final String EXTRA_AE_LOCK = "com.fadcam.EXTRA_AE_LOCK"; // boolean
    public static final String EXTRA_AF_MODE = "com.fadcam.EXTRA_AF_MODE"; // int (CaptureRequest.CONTROL_AF_MODE
    // values)

    // -------------- FadRec (Screen Recording) Constants Start --------------
    // Directory and file naming
    public static final String RECORDING_DIRECTORY_FADREC = "FadRec";
    public static final String RECORDING_FILE_PREFIX_FADREC = "FadRec_";

    // Screen recording intent actions
    public static final String INTENT_ACTION_START_SCREEN_RECORDING =
        "com.fadcam.ACTION_START_SCREEN_RECORDING";
    public static final String INTENT_ACTION_STOP_SCREEN_RECORDING =
        "com.fadcam.ACTION_STOP_SCREEN_RECORDING";
    public static final String INTENT_ACTION_PAUSE_SCREEN_RECORDING =
        "com.fadcam.ACTION_PAUSE_SCREEN_RECORDING";
    public static final String INTENT_ACTION_RESUME_SCREEN_RECORDING =
        "com.fadcam.ACTION_RESUME_SCREEN_RECORDING";

    // Screen recording broadcast actions
    public static final String BROADCAST_ON_SCREEN_RECORDING_STARTED =
        "com.fadcam.ON_SCREEN_RECORDING_STARTED";
    public static final String BROADCAST_ON_SCREEN_RECORDING_STOPPED =
        "com.fadcam.ON_SCREEN_RECORDING_STOPPED";
    public static final String BROADCAST_ON_SCREEN_RECORDING_PAUSED =
        "com.fadcam.ON_SCREEN_RECORDING_PAUSED";
    public static final String BROADCAST_ON_SCREEN_RECORDING_RESUMED =
        "com.fadcam.ON_SCREEN_RECORDING_RESUMED";
    public static final String BROADCAST_ON_SCREEN_RECORDING_STATE_CALLBACK =
        "com.fadcam.ON_SCREEN_RECORDING_STATE_CALLBACK";

    // Screen recording preferences
    public static final String PREF_SCREEN_RECORDING_AUDIO_SOURCE =
        "pref_screen_recording_audio_source"; // mic/none
    public static final String PREF_SCREEN_RECORDING_WATERMARK_ENABLED =
        "pref_screen_recording_watermark_enabled"; // boolean
    public static final String PREF_IS_SCREEN_RECORDING_IN_PROGRESS =
        "pref_is_screen_recording_in_progress"; // boolean

    // Default screen recording quality settings
    public static final int DEFAULT_SCREEN_RECORDING_WIDTH = 1920; // FHD
    public static final int DEFAULT_SCREEN_RECORDING_HEIGHT = 1080; // FHD
    public static final int DEFAULT_SCREEN_RECORDING_FPS = 30;
    public static final int DEFAULT_SCREEN_RECORDING_BITRATE = 8_000_000; // 8 Mbps

    // Screen recording audio source options
    public static final String AUDIO_SOURCE_NONE = "none";
    public static final String AUDIO_SOURCE_MIC = "microphone";
    // -------------- FadRec (Screen Recording) Constants End --------------
    public static final String EXTRA_FOCUS_X = "com.fadcam.EXTRA_FOCUS_X"; // float (normalized 0..1)
    public static final String EXTRA_FOCUS_Y = "com.fadcam.EXTRA_FOCUS_Y"; // float (normalized 0..1)
    public static final String EXTRA_ZOOM_RATIO = "com.fadcam.EXTRA_ZOOM_RATIO"; // float
    public static final String INTENT_ACTION_PAUSE_RECORDING =
        "ACTION_PAUSE_RECORDING";
    public static final String INTENT_ACTION_START_RECORDING =
        "ACTION_START_RECORDING";
    public static final String INTENT_ACTION_TOGGLE_TORCH =
        "ACTION_TOGGLE_TORCH";

    public static final String INTENT_EXTRA_RECORDING_STATE = "RECORDING_STATE";
    public static final String INTENT_EXTRA_RECORDING_START_TIME =
        "RECORDING_START_TIME";
    public static final String INTENT_EXTRA_TORCH_STATE = "torch_state";
    public static final String INTENT_EXTRA_TORCH_STATE_CHANGED =
        "TORCH_STATE_CHANGED";
    public static final String INTENT_EXTRA_INITIAL_TORCH_STATE =
        "com.fadcam.EXTRA_INITIAL_TORCH_STATE";

    public static final String RECORDING_DIRECTORY = "FadCam";
    public static final String RECORDING_FILE_EXTENSION = "mp4";
    public static final double RECORDING_COMPRESSION_FACTOR = 0.33;

    public static final CameraType DEFAULT_CAMERA_TYPE = CameraType.BACK;
    public static final Size DEFAULT_VIDEO_RESOLUTION = new Size(1920, 1080);
    public static final int DEFAULT_VIDEO_FRAME_RATE = 30;
    public static final float DEFAULT_ZOOM_RATIO = 1.0f; // Default zoom ratio (no zoom)
    public static final VideoCodec DEFAULT_VIDEO_CODEC = VideoCodec.HEVC;

    public static final String DEFAULT_WATERMARK_OPTION = "timestamp_fadcam";
    public static final boolean DEFAULT_PREVIEW_ENABLED = true;
    public static final boolean DEFAULT_RECORD_AUDIO = true;
    public static final int DEFAULT_AUDIO_BITRATE = 192000; // 192 kbps stereo AAC
    public static final int DEFAULT_AUDIO_SAMPLING_RATE = 48000; // 48 kHz
    // -------------- Fix Start for this
    // class(Constants_video_bitrate_defaults)-----------
    public static final String PREF_VIDEO_BITRATE = "video_bitrate"; // stored in raw bps
    public static final int DEFAULT_VIDEO_BITRATE = 8_000_000; // 8 Mbps default
    // -------------- Fix Ended for this
    // class(Constants_video_bitrate_defaults)-----------

    public static final String INTENT_ACTION_TOGGLE_RECORDING_TORCH =
        "com.fadcam.TOGGLE_RECORDING_TORCH";
    // Broadcast action sent by RecordingService when video processing is done
    public static final String ACTION_RECORDING_COMPLETE =
        "com.fadcam.RECORDING_COMPLETE";
    // Extra key for the URI of the final or temporary file (as String)
    public static final String EXTRA_RECORDING_URI_STRING =
        "com.fadcam.EXTRA_RECORDING_URI_STRING";
    // Extra key indicating if processing was successful
    public static final String EXTRA_RECORDING_SUCCESS =
        "com.fadcam.EXTRA_RECORDING_SUCCESS";

    // Action to navigate to Records tab from external sources
    public static final String ACTION_SHOW_RECORDS =
        "com.fadcam.ACTION_SHOW_RECORDS";

    // Broadcast action sent by SettingsFragment when storage location pref changes
    public static final String ACTION_STORAGE_LOCATION_CHANGED =
        "com.fadcam.STORAGE_LOCATION_CHANGED";

    // Broadcast action sent when files are restored from trash
    public static final String ACTION_FILES_RESTORED =
        "com.fadcam.FILES_RESTORED";

    // Broadcast Actions for Video Processing State
    public static final String ACTION_PROCESSING_STARTED =
        "com.fadcam.PROCESSING_STARTED";
    public static final String ACTION_PROCESSING_FINISHED =
        "com.fadcam.PROCESSING_FINISHED"; // Can replace COMPLETE if
    // always sent AFTER
    // processing

    // Extra key for the URI of the file being processed (usually the temp file)
    public static final String EXTRA_PROCESSING_URI_STRING =
        "com.fadcam.EXTRA_PROCESSING_URI_STRING";

    // -------------- Fix Start: Add constant for recording failure broadcast
    // -----------
    // Broadcast action sent by RecordingService when it fails to start
    public static final String ACTION_RECORDING_FAILED =
        "com.fadcam.RECORDING_FAILED";
    public static final String EXTRA_ERROR_MESSAGE =
        "com.fadcam.EXTRA_ERROR_MESSAGE";
    public static final String EXTRA_STACK_TRACE =
        "com.fadcam.EXTRA_STACK_TRACE";
    // -------------- Fix Ended for this constant -----------

    // ----- Fix Start for this class (Constants_video_splitting_broadcast) -----
    // Broadcast action sent by RecordingService when a video segment is complete
    // (due to splitting)
    public static final String ACTION_RECORDING_SEGMENT_COMPLETE =
        "com.fadcam.RECORDING_SEGMENT_COMPLETE";
    // Extra key for the URI of the completed segment file (as String) - can be
    // content:// or file://
    public static final String INTENT_EXTRA_FILE_URI =
        "com.fadcam.EXTRA_FILE_URI";
    // Extra key for the absolute path of the completed segment file (if internal
    // storage) - use with caution
    public static final String INTENT_EXTRA_FILE_PATH =
        "com.fadcam.EXTRA_FILE_PATH";
    // Extra key for the segment number that just completed
    public static final String INTENT_EXTRA_SEGMENT_NUMBER =
        "com.fadcam.EXTRA_SEGMENT_NUMBER";
    // ----- Fix Ended for this class (Constants_video_splitting_broadcast) -----

    // SharedPreferences key for opened videos
    public static final String PREF_OPENED_VIDEO_URIS = "opened_video_uris";

    public static final String PREF_SELECTED_BACK_CAMERA_ID =
        "selected_back_camera_id";
    public static final String DEFAULT_BACK_CAMERA_ID = "0"; // Default physical ID for back cameras is often "0"

    // Trash Feature Constants
    public static final String TRASH_DIRECTORY_NAME = "Trash";
    public static final String TRASH_METADATA_FILENAME = "trash_metadata.json";

    // Request codes
    public static final int REQUEST_CODE_OPEN_DOCUMENT_TREE_FOR_SAF = 1001; // Added request code

    public static final String EXTRA_ORIGINAL_TEMP_SAF_URI_STRING =
        "com.fadcam.EXTRA_ORIGINAL_TEMP_SAF_URI_STRING"; // For
    // SAF
    // processing
    // replacement
    // tracking

    // ----- Fix Start for camera resource availability -----
    // Broadcast for camera resource availability status
    public static final String ACTION_CAMERA_RESOURCE_AVAILABILITY =
        "com.fadcam.ACTION_CAMERA_RESOURCE_AVAILABILITY";
    public static final String EXTRA_CAMERA_RESOURCES_AVAILABLE =
        "com.fadcam.EXTRA_CAMERA_RESOURCES_AVAILABLE";
    public static final int CAMERA_RESOURCE_COOLDOWN_MS = 1500; // 1.5 seconds cooldown for camera resources
    // ----- Fix End for camera resource availability -----

    // Add the location reinitialize intent action
    public static final String INTENT_ACTION_REINITIALIZE_LOCATION =
        "com.fadcam.INTENT_ACTION_REINITIALIZE_LOCATION";

    // Camera interruption broadcasts
    public static final String BROADCAST_ON_CAMERA_INTERRUPTED =
        "com.fadcam.ON_CAMERA_INTERRUPTED";
    public static final String BROADCAST_ON_CAMERA_RECONNECTED =
        "com.fadcam.ON_CAMERA_RECONNECTED";

    // ----- App Icon Preference -----
    public static final String PREF_APP_ICON = "app_icon";
    public static final String APP_ICON_DEFAULT = "default"; // Original icon
    public static final String APP_ICON_ALTERNATIVE = "alternative"; // Detective icon
    public static final String APP_ICON_FADED = "faded"; // Faded icon
    public static final String APP_ICON_PALESTINE = "palestine"; // Sumud icon
    public static final String APP_ICON_PAKISTAN = "pakistan"; // MadeInPK icon
    public static final String APP_ICON_FADSECLAB = "fadseclab"; // r00t icon
    public static final String APP_ICON_NOOR = "noor"; // Noor icon
    public static final String APP_ICON_BAT = "bat"; // FadBat icon
    public static final String APP_ICON_REDBINARY = "redbinary"; // RedBinary icon
    public static final String APP_ICON_NOTES = "notes"; // Notes icon
    public static final String APP_ICON_CALCULATOR = "calculator"; // Calculator icon
    public static final String APP_ICON_CLOCK = "clock"; // Clock icon
    public static final String APP_ICON_WEATHER = "weather"; // Weather icon
    public static final String APP_ICON_FOOTBALL = "football"; // Football game icon
    public static final String APP_ICON_CAR = "car"; // Car icon
    public static final String APP_ICON_JET = "jet"; // Jet fighter icon
    public static final String APP_ICON_MINIMAL = "minimal";
    public static final String APP_ICON_BLACK = "black"; // Full black icon
    // Quick speed preference key for press-and-hold quick speed
    public static final String PREF_QUICK_SPEED = "pref_quick_speed";
    public static final float DEFAULT_QUICK_SPEED = 2.0f;
    // Mute playback preference
    public static final String PREF_PLAYBACK_MUTED = "pref_playback_muted";
    // Keep screen awake during video playback
    public static final String PREF_PLAYER_KEEP_SCREEN_ON =
        "pref_player_keep_screen_on"; // default true
    // Background playback preference
    public static final String PREF_PLAYER_BACKGROUND_PLAYBACK =
        "pref_player_background_playback"; // default false
    // Background playback auto-stop timer (seconds). 0 = disabled
    public static final String PREF_PLAYER_BACKGROUND_TIMER_SECONDS =
        "pref_player_background_timer_seconds";
    // Seek amount (seconds) used by rewind/forward buttons and double-tap
    public static final String PREF_PLAYER_SEEK_SECONDS =
        "pref_player_seek_seconds";
    public static final int DEFAULT_PLAYER_SEEK_SECONDS = 10; // default 10 seconds
    // Player controller auto-hide timeout in seconds. Controls disappear after this many seconds of inactivity.
    // 0 = never auto-hide (controls stay visible until user toggles).
    public static final String PREF_PLAYER_CONTROLS_TIMEOUT_SECONDS =
        "pref_player_controls_timeout_seconds";
    public static final int DEFAULT_PLAYER_CONTROLS_TIMEOUT_SECONDS = 5; // default 5 seconds
}
