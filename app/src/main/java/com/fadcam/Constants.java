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
    public static final String PREF_FRONT_VIDEO_MIRROR_ENABLED =
        "pref_front_video_mirror_enabled";
    public static final String PREF_IS_PREVIEW_ENABLED = "isPreviewEnabled";
    public static final String PREF_RECORDING_START_TIME = "recording_start_time"; // Stores recording start timestamp for orientation changes
    public static final String PREF_FLOATING_CONTROLS_ENABLED = "floating_controls_enabled"; // Enable floating quick menu for FadRec
    public static final String PREF_BOTH_TORCHES_ENABLED =
        "pref_both_torches_enabled";
    public static final String PREF_SELECTED_TORCH_SOURCE =
        "pref_selected_torch_source";
    public static final String PREF_TORCH_STATE = "pref_torch_state";
    public static final String PREF_LOCATION_DATA = "location_data";
    public static final String PREF_EMBED_LOCATION_DATA = "embed_location_data";
    public static final String PREF_DEBUG_DATA = "debug_data";
    public static final String PREF_WATERMARK_OPTION = "watermark_option";
    public static final String PREF_WATERMARK_CUSTOM_TEXT = "watermark_custom_text";
    public static final String PREF_VIDEO_CODEC = "video_codec";
    public static final String PREF_APP_THEME = "app_theme";
    
    // Remote Authentication Constants
    public static final String PREF_REMOTE_AUTH_ENABLED = "remote_auth_enabled";
    public static final String PREF_REMOTE_AUTH_PASSWORD_HASH = "remote_auth_password_hash";
    public static final String PREF_REMOTE_AUTH_AUTO_LOCK_TIMEOUT = "remote_auth_auto_lock_timeout"; // in minutes, 0 = never
    public static final String PREF_REMOTE_AUTH_SESSIONS = "remote_auth_sessions"; // JSON string of active sessions
    public static final long REMOTE_AUTH_TOKEN_EXPIRY_MS = 24L * 60 * 60 * 1000; // 24 hours
    public static final int REMOTE_AUTH_MIN_PASSWORD_LENGTH = 4;
    public static final int REMOTE_AUTH_MAX_PASSWORD_LENGTH = 32;
    
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

    // Mode switcher constants
    public static final String MODE_FADCAM = "FADCAM";
    public static final String MODE_FADREC = "FADREC";
    public static final String MODE_FADMIC = "FADMIC";

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
    public static final String BROADCAST_ON_PREVIEW_ONLY_STARTED =
        "ON_PREVIEW_ONLY_STARTED";
    public static final String BROADCAST_ON_PREVIEW_ONLY_STOPPED =
        "ON_PREVIEW_ONLY_STOPPED";
    public static final String BROADCAST_ON_RECORDING_STATE_REQUEST =
        "ON_RECORDING_STATE_REQUEST";
    public static final String BROADCAST_ON_RECORDING_STATE_CALLBACK =
        "ON_RECORDING_STATE_CALLBACK";
    /**
     * Broadcast action sent when camera switch starts during recording.
     * Includes extras: BROADCAST_EXTRA_CAMERA_TYPE_FROM, BROADCAST_EXTRA_CAMERA_TYPE_TO
     */
    public static final String BROADCAST_ON_CAMERA_SWITCH_STARTED =
        "ON_CAMERA_SWITCH_STARTED";
    /**
     * Broadcast action sent when camera switch completes successfully during recording.
     * Includes extras: BROADCAST_EXTRA_CAMERA_TYPE_FROM, BROADCAST_EXTRA_CAMERA_TYPE_TO
     */
    public static final String BROADCAST_ON_CAMERA_SWITCH_COMPLETE =
        "ON_CAMERA_SWITCH_COMPLETE";
    /**
     * Broadcast action sent when camera switch fails during recording.
     * Includes extras: BROADCAST_EXTRA_SWITCH_ERROR_REASON, BROADCAST_EXTRA_CAMERA_TYPE_ATTEMPTED
     */
    public static final String BROADCAST_ON_CAMERA_SWITCH_FAILED =
        "ON_CAMERA_SWITCH_FAILED";
    public static final String BROADCAST_ON_TORCH_STATE_CHANGED =
        "com.fadcam.ON_TORCH_STATE_CHANGED";
    public static final String BROADCAST_ON_TORCH_STATE_REQUEST =
        "ON_TORCH_STATE_REQUEST";
    public static final String BROADCAST_MOTION_LAB_DEBUG =
        "com.fadcam.MOTION_LAB_DEBUG";
    public static final String EXTRA_MOTION_DEBUG_SCORE = "motion_debug_score";
    public static final String EXTRA_MOTION_DEBUG_RAW_SCORE =
        "motion_debug_raw_score";
    public static final String EXTRA_MOTION_DEBUG_START_THRESHOLD =
        "motion_debug_start_threshold";
    public static final String EXTRA_MOTION_DEBUG_STOP_THRESHOLD =
        "motion_debug_stop_threshold";
    public static final String EXTRA_MOTION_DEBUG_STATE = "motion_debug_state";
    public static final String EXTRA_MOTION_DEBUG_ACTION = "motion_debug_action";
    public static final String EXTRA_MOTION_DEBUG_PERSON_CONF =
        "motion_debug_person_conf";
    public static final String EXTRA_MOTION_DEBUG_PERSON = "motion_debug_person";
    public static final String EXTRA_MOTION_DEBUG_CLASS_NAME =
        "motion_debug_class_name";
    public static final String EXTRA_MOTION_DEBUG_CLASS_CONF =
        "motion_debug_class_conf";
    public static final String EXTRA_MOTION_DEBUG_EVENT_TYPE =
        "motion_debug_event_type";
    public static final String EXTRA_MOTION_DEBUG_FRAME_JPEG =
        "motion_debug_frame_jpeg";
    public static final String EXTRA_MOTION_DEBUG_CHANGED_AREA =
        "motion_debug_changed_area";
    public static final String EXTRA_MOTION_DEBUG_STRONG_AREA =
        "motion_debug_strong_area";
    public static final String EXTRA_MOTION_DEBUG_MEAN_DELTA =
        "motion_debug_mean_delta";
    public static final String EXTRA_MOTION_DEBUG_BG_DELTA =
        "motion_debug_bg_delta";
    public static final String EXTRA_MOTION_DEBUG_MAX_DELTA =
        "motion_debug_max_delta";
    public static final String EXTRA_MOTION_DEBUG_GLOBAL_SUPPRESSED =
        "motion_debug_global_suppressed";
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
    public static final String INTENT_ACTION_SET_FRONT_VIDEO_MIRROR =
        "com.fadcam.ACTION_SET_FRONT_VIDEO_MIRROR";
    public static final String INTENT_ACTION_START_PREVIEW_ONLY =
        "com.fadcam.ACTION_START_PREVIEW_ONLY";
    public static final String INTENT_ACTION_STOP_PREVIEW_ONLY =
        "com.fadcam.ACTION_STOP_PREVIEW_ONLY";

    // Extras for the above intents
    public static final String EXTRA_EXPOSURE_COMPENSATION =
        "com.fadcam.EXTRA_EXPOSURE_COMPENSATION"; // int
    public static final String EXTRA_AE_LOCK = "com.fadcam.EXTRA_AE_LOCK"; // boolean
    public static final String EXTRA_AF_MODE = "com.fadcam.EXTRA_AF_MODE"; // int (CaptureRequest.CONTROL_AF_MODE
    // values)
    public static final String EXTRA_FRONT_VIDEO_MIRROR_ENABLED =
        "com.fadcam.EXTRA_FRONT_VIDEO_MIRROR_ENABLED"; // boolean
    public static final String EXTRA_PREVIEW_ONLY_ACTIVE =
        "com.fadcam.EXTRA_PREVIEW_ONLY_ACTIVE"; // boolean

    // Camera switch broadcast extras
    /** Extra for camera switch broadcasts: source camera type (CameraType enum as string) */
    public static final String BROADCAST_EXTRA_CAMERA_TYPE_FROM =
        "com.fadcam.EXTRA_CAMERA_TYPE_FROM";
    /** Extra for camera switch broadcasts: target camera type (CameraType enum as string) */
    public static final String BROADCAST_EXTRA_CAMERA_TYPE_TO =
        "com.fadcam.EXTRA_CAMERA_TYPE_TO";
    /** Extra for camera switch failed broadcast: reason for failure */
    public static final String BROADCAST_EXTRA_SWITCH_ERROR_REASON =
        "com.fadcam.EXTRA_SWITCH_ERROR_REASON";
    /** Extra for camera switch failed broadcast: which camera was being attempted */
    public static final String BROADCAST_EXTRA_CAMERA_TYPE_ATTEMPTED =
        "com.fadcam.EXTRA_CAMERA_TYPE_ATTEMPTED";

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
    public static final String INTENT_ACTION_SET_SCREEN_RECORDING_MUTE =
        "com.fadcam.ACTION_SET_SCREEN_RECORDING_MUTE";

    // Query current screen recording state (service will respond via state broadcast)
    public static final String INTENT_ACTION_QUERY_SCREEN_RECORDING_STATE =
        "com.fadcam.ACTION_QUERY_SCREEN_RECORDING_STATE";
    
    // Floating controls actions (from overlay)
    public static final String ACTION_START_SCREEN_RECORDING_FROM_OVERLAY =
        "com.fadcam.ACTION_START_SCREEN_RECORDING_FROM_OVERLAY";
    public static final String ACTION_PAUSE_SCREEN_RECORDING =
        "com.fadcam.ACTION_PAUSE_SCREEN_RECORDING";
    public static final String ACTION_RESUME_SCREEN_RECORDING =
        "com.fadcam.ACTION_RESUME_SCREEN_RECORDING";
    public static final String ACTION_STOP_SCREEN_RECORDING =
        "com.fadcam.ACTION_STOP_SCREEN_RECORDING";
    
    // Transparent permission activity actions
    public static final String ACTION_SCREEN_RECORDING_PERMISSION_GRANTED =
        "com.fadcam.ACTION_SCREEN_RECORDING_PERMISSION_GRANTED";
    public static final String ACTION_SCREEN_RECORDING_PERMISSION_DENIED =
        "com.fadcam.ACTION_SCREEN_RECORDING_PERMISSION_DENIED";

    // Batch media actions (Records tab automations)
    public static final String INTENT_ACTION_BATCH_EXPORT_STANDARD_MP4 =
        "com.fadcam.ACTION_BATCH_EXPORT_STANDARD_MP4";
    public static final String INTENT_ACTION_BATCH_MERGE_VIDEOS =
        "com.fadcam.ACTION_BATCH_MERGE_VIDEOS";
    public static final String EXTRA_BATCH_INPUT_URIS =
        "com.fadcam.EXTRA_BATCH_INPUT_URIS";
    public static final String EXTRA_BATCH_CUSTOM_TREE_URI =
        "com.fadcam.EXTRA_BATCH_CUSTOM_TREE_URI";
    public static final String EXTRA_BATCH_OUTPUT_MODE =
        "com.fadcam.EXTRA_BATCH_OUTPUT_MODE";
    public static final String BATCH_OUTPUT_MODE_DEFAULT_FADITOR =
        "DEFAULT_FADITOR";
    public static final String BATCH_OUTPUT_MODE_CUSTOM_TREE_URI =
        "CUSTOM_TREE_URI";
    public static final String ACTION_BATCH_MEDIA_COMPLETED =
        "com.fadcam.ACTION_BATCH_MEDIA_COMPLETED";
    public static final String EXTRA_BATCH_COMPLETED_MESSAGE =
        "com.fadcam.EXTRA_BATCH_COMPLETED_MESSAGE";

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
    public static final String BROADCAST_ON_SCREEN_RECORDING_MUTE_CHANGED =
        "com.fadcam.ON_SCREEN_RECORDING_MUTE_CHANGED";

    // Screen recording preferences
    public static final String PREF_SCREEN_RECORDING_AUDIO_SOURCE =
        "pref_screen_recording_audio_source"; // mic/none
    public static final String PREF_SCREEN_RECORDING_MUTED =
        "pref_screen_recording_muted"; // boolean
    public static final String PREF_SCREEN_RECORDING_WATERMARK_ENABLED =
        "pref_screen_recording_watermark_enabled"; // boolean
    public static final String PREF_IS_SCREEN_RECORDING_IN_PROGRESS =
        "pref_is_screen_recording_in_progress"; // boolean
    public static final String PREF_SCREEN_RECORDING_STATE =
        "pref_screen_recording_state"; // NONE/IN_PROGRESS/PAUSED

    // Default screen recording quality settings
    public static final int DEFAULT_SCREEN_RECORDING_WIDTH = 1920; // FHD
    public static final int DEFAULT_SCREEN_RECORDING_HEIGHT = 1080; // FHD
    public static final int DEFAULT_SCREEN_RECORDING_FPS = 30;
    public static final int DEFAULT_SCREEN_RECORDING_BITRATE = 8_000_000; // 8 Mbps

    // Screen recording audio source options
    public static final String AUDIO_SOURCE_NONE = "none";
    public static final String AUDIO_SOURCE_MIC = "microphone";
    public static final String EXTRA_SCREEN_RECORDING_MUTED =
        "com.fadcam.EXTRA_SCREEN_RECORDING_MUTED";
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
    /**
     * Intent action to switch cameras during active recording.
     * Expected extra: INTENT_EXTRA_CAMERA_TYPE_SWITCH (CameraType enum value)
     */
    public static final String INTENT_ACTION_SWITCH_CAMERA =
        "ACTION_SWITCH_CAMERA";

    public static final String INTENT_EXTRA_RECORDING_STATE = "RECORDING_STATE";
    public static final String INTENT_EXTRA_RECORDING_START_TIME =
        "RECORDING_START_TIME";
    public static final String INTENT_EXTRA_TORCH_STATE = "torch_state";
    public static final String INTENT_EXTRA_TORCH_STATE_CHANGED =
        "TORCH_STATE_CHANGED";
    public static final String INTENT_EXTRA_INITIAL_TORCH_STATE =
        "com.fadcam.EXTRA_INITIAL_TORCH_STATE";
    /**
     * Extra for INTENT_ACTION_SWITCH_CAMERA: the target CameraType enum value as string.
     * Example: CameraType.FRONT.toString() or CameraType.BACK.toString()
     */
    public static final String INTENT_EXTRA_CAMERA_TYPE_SWITCH =
        "com.fadcam.EXTRA_CAMERA_TYPE_SWITCH";

    public static final String RECORDING_DIRECTORY = "FadCam";
    public static final String RECORDING_SUBDIR_CAMERA = "Camera";
    public static final String RECORDING_SUBDIR_CAMERA_BACK = "Back";
    public static final String RECORDING_SUBDIR_CAMERA_FRONT = "Front";
    public static final String RECORDING_SUBDIR_CAMERA_DUAL = "Dual";
    public static final String RECORDING_SUBDIR_DUAL = "Dual";
    public static final String RECORDING_SUBDIR_SCREEN = "Screen";
    public static final String RECORDING_SUBDIR_FADITOR = "Faditor";
    public static final String RECORDING_SUBDIR_FADITOR_CONVERTED = "Converted";
    public static final String RECORDING_SUBDIR_FADITOR_MERGE = "Merge";
    public static final String RECORDING_SUBDIR_STREAM = "Stream";
    public static final String RECORDING_SUBDIR_SHOT = "FadShot";
    public static final String RECORDING_SUBDIR_SHOT_BACK = "Back";
    public static final String RECORDING_SUBDIR_SHOT_SELFIE = "Selfie";
    public static final String RECORDING_SUBDIR_SHOT_FADREC = "FadRec";
    public static final String RECORDING_FILE_EXTENSION = "mp4";
    public static final String RECORDING_IMAGE_EXTENSION = "jpg";
    public static final String RECORDING_FILE_PREFIX_FADSHOT = "FadShot_";
    public static final String RECORDING_FILE_PREFIX_FADITOR_STANDARD =
        "Faditor_Std_";
    public static final String RECORDING_FILE_PREFIX_FADITOR_MERGE =
        "Faditor_Merge_";
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
    // class(Constants_video_bitrate_defaults)-----------
    public static final String PREF_VIDEO_BITRATE = "video_bitrate"; // stored in raw bps
    public static final int DEFAULT_VIDEO_BITRATE = 8_000_000; // 8 Mbps default
    // class(Constants_video_bitrate_defaults)-----------

    public static final String INTENT_ACTION_TOGGLE_RECORDING_TORCH =
        "com.fadcam.TOGGLE_RECORDING_TORCH";
    public static final String INTENT_ACTION_CAPTURE_PHOTO =
        "com.fadcam.CAPTURE_PHOTO";
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
    public static final String ACTION_FORENSICS_SNAPSHOT_PERSISTED =
        "com.fadcam.ACTION_FORENSICS_SNAPSHOT_PERSISTED";

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

    // -----------
    // Broadcast action sent by RecordingService when it fails to start
    public static final String ACTION_RECORDING_FAILED =
        "com.fadcam.RECORDING_FAILED";
    public static final String EXTRA_ERROR_MESSAGE =
        "com.fadcam.EXTRA_ERROR_MESSAGE";
    public static final String EXTRA_STACK_TRACE =
        "com.fadcam.EXTRA_STACK_TRACE";

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

    // SharedPreferences key for opened videos
    public static final String PREF_OPENED_VIDEO_URIS = "opened_video_uris";

    public static final String PREF_SELECTED_BACK_CAMERA_ID =
        "selected_back_camera_id";
    public static final String DEFAULT_BACK_CAMERA_ID = "0"; // Default physical ID for back cameras is often "0"

    // Trash Feature Constants
    public static final String TRASH_DIRECTORY_NAME = "Trash";
    public static final String TRASH_METADATA_FILENAME = "trash_metadata.json";
    public static final String TRASH_SUBDIR_VIDEO_RECORDINGS = "VideoRecordings";
    public static final String TRASH_SUBDIR_FORENSICS_EVIDENCE = "ForensicsEvidence";

    // Request codes
    public static final int REQUEST_CODE_OPEN_DOCUMENT_TREE_FOR_SAF = 1001; // Added request code

    public static final String EXTRA_ORIGINAL_TEMP_SAF_URI_STRING =
        "com.fadcam.EXTRA_ORIGINAL_TEMP_SAF_URI_STRING"; // For
    // SAF
    // processing
    // replacement
    // tracking

    // Broadcast for camera resource availability status
    public static final String ACTION_CAMERA_RESOURCE_AVAILABILITY =
        "com.fadcam.ACTION_CAMERA_RESOURCE_AVAILABILITY";
    public static final String EXTRA_CAMERA_RESOURCES_AVAILABLE =
        "com.fadcam.EXTRA_CAMERA_RESOURCES_AVAILABLE";
    public static final int CAMERA_RESOURCE_COOLDOWN_MS = 1500; // 1.5 seconds cooldown for camera resources

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
    // Fullscreen preview tap-to-focus toggle (when disabled, taps only toggle controls)
    public static final String PREF_FULLSCREEN_TAP_TO_FOCUS_ENABLED =
        "pref_fullscreen_tap_to_focus_enabled";
    // Show preview quick actions (FadShot + Fullscreen) even when recording is not active.
    public static final String PREF_PREVIEW_QUICK_ACTIONS_ALWAYS_VISIBLE =
        "pref_preview_quick_actions_always_visible";
    // Player controller auto-hide timeout in seconds. Controls disappear after this many seconds of inactivity.
    // 0 = never auto-hide (controls stay visible until user toggles).
    public static final String PREF_PLAYER_CONTROLS_TIMEOUT_SECONDS =
        "pref_player_controls_timeout_seconds";
    public static final int DEFAULT_PLAYER_CONTROLS_TIMEOUT_SECONDS = 5; // default 5 seconds
    // Motion Lab (advanced) preferences
    public static final String PREF_MOTION_MODE_ENABLED = "pref_motion_mode_enabled";
    public static final String PREF_MOTION_TRIGGER_MODE = "pref_motion_trigger_mode"; // any_motion|person_confirmed
    public static final String PREF_MOTION_SENSITIVITY = "pref_motion_sensitivity"; // 0-100
    public static final String PREF_MOTION_ANALYSIS_FPS = "pref_motion_analysis_fps"; // 2/3/5
    public static final String PREF_MOTION_DEBOUNCE_MS = "pref_motion_debounce_ms";
    public static final String PREF_MOTION_POST_ROLL_MS = "pref_motion_post_roll_ms";
    public static final String PREF_MOTION_PRE_ROLL_SECONDS = "pref_motion_pre_roll_seconds";
    public static final String PREF_MOTION_ZONES_JSON = "pref_motion_zones_json";
    public static final String PREF_MOTION_AUTO_TORCH_ENABLED = "pref_motion_auto_torch_enabled";
    public static final String PREF_MOTION_DEBUG_UI_ACTIVE = "pref_motion_debug_ui_active";
    // Digital Forensics (advanced) preferences
    public static final String PREF_DF_ENABLED = "pref_df_enabled";
    public static final String PREF_DF_EVENT_PERSON = "pref_df_event_person";
    public static final String PREF_DF_EVENT_VEHICLE = "pref_df_event_vehicle";
    public static final String PREF_DF_EVENT_PET = "pref_df_event_pet";
    public static final String PREF_DF_EVENT_DANGEROUS_OBJECT = "pref_df_event_dangerous_object";
    public static final String PREF_DF_OVERLAY_ENABLED = "pref_df_overlay_enabled";
    public static final String PREF_DF_DAILY_SUMMARY_ENABLED = "pref_df_daily_summary_enabled";
    public static final String PREF_DF_HEATMAP_ENABLED = "pref_df_heatmap_enabled";
    public static final String PREF_DF_EVIDENCE_ENABLED = "pref_df_evidence_enabled";
    public static final String PREF_DF_CAPTURE_SCOPE = "pref_df_capture_scope"; // people|objects|both

    // ── Dual Camera (PiP) Constants ────────────────────────────────────────
    public static final String PREF_DUAL_CAMERA_ENABLED = "dual_camera_enabled";
    public static final String PREF_DUAL_CAMERA_PIP_POSITION = "dual_camera_pip_position";
    public static final String PREF_DUAL_CAMERA_PIP_SIZE = "dual_camera_pip_size";
    public static final String PREF_DUAL_CAMERA_PRIMARY = "dual_camera_primary";
    public static final String PREF_DUAL_CAMERA_SHOW_BORDER = "dual_camera_show_border";
    public static final String PREF_DUAL_CAMERA_ROUND_CORNERS = "dual_camera_round_corners";
    public static final String PREF_DUAL_CAMERA_PIP_MARGIN_DP = "dual_camera_pip_margin_dp";

    // Dual camera intent actions
    public static final String INTENT_ACTION_START_DUAL_RECORDING =
        "com.fadcam.ACTION_START_DUAL_RECORDING";
    public static final String INTENT_ACTION_STOP_DUAL_RECORDING =
        "com.fadcam.ACTION_STOP_DUAL_RECORDING";
    public static final String INTENT_ACTION_PAUSE_DUAL_RECORDING =
        "com.fadcam.ACTION_PAUSE_DUAL_RECORDING";
    public static final String INTENT_ACTION_RESUME_DUAL_RECORDING =
        "com.fadcam.ACTION_RESUME_DUAL_RECORDING";
    public static final String INTENT_ACTION_SWAP_DUAL_CAMERAS =
        "com.fadcam.ACTION_SWAP_DUAL_CAMERAS";
    public static final String INTENT_ACTION_UPDATE_PIP_CONFIG =
        "com.fadcam.ACTION_UPDATE_PIP_CONFIG";

    // Dual camera broadcast actions
    public static final String BROADCAST_ON_DUAL_RECORDING_STARTED =
        "com.fadcam.ON_DUAL_RECORDING_STARTED";
    public static final String BROADCAST_ON_DUAL_RECORDING_STOPPED =
        "com.fadcam.ON_DUAL_RECORDING_STOPPED";
    public static final String BROADCAST_ON_DUAL_RECORDING_PAUSED =
        "com.fadcam.ON_DUAL_RECORDING_PAUSED";
    public static final String BROADCAST_ON_DUAL_RECORDING_RESUMED =
        "com.fadcam.ON_DUAL_RECORDING_RESUMED";
    public static final String BROADCAST_ON_DUAL_CAMERA_ERROR =
        "com.fadcam.ON_DUAL_CAMERA_ERROR";
    public static final String BROADCAST_ON_DUAL_CAMERAS_SWAPPED =
        "com.fadcam.ON_DUAL_CAMERAS_SWAPPED";
    // Accessibility screenshot trigger action (FadRec shortcut).
    public static final String ACTION_TRIGGER_FADREC_SCREENSHOT =
        "com.fadcam.ACTION_TRIGGER_FADREC_SCREENSHOT";
    // ── End Dual Camera Constants ──────────────────────────────────────────
}
