package com.fadcam;

import static android.content.ContentValues.TAG;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Size;
import androidx.annotation.Nullable; // Import Nullable
import com.fadcam.CameraType; // Ensure CameraType is imported
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class SharedPreferencesManager {

    // --- STORAGE CONSTANTS ---
    public static final String PREF_STORAGE_MODE = "storage_mode";
    public static final String STORAGE_MODE_INTERNAL = "internal";
    public static final String STORAGE_MODE_CUSTOM = "custom";
    public static final String PREF_CUSTOM_STORAGE_URI = "custom_storage_uri";
    // --- END STORAGE CONSTANTS ---
    
    // --- UPDATE CHECK CONSTANTS ---
    public static final String PREF_AUTO_UPDATE_CHECK = "auto_update_check_enabled";
    // --- END UPDATE CHECK CONSTANTS ---
    
    // --- BATTERY WARNING CONSTANTS ---
    public static final String PREF_BATTERY_WARNING_THRESHOLD = "battery_warning_threshold";
    public static final int DEFAULT_BATTERY_WARNING_THRESHOLD = 20;
    // --- END BATTERY WARNING CONSTANTS ---
    
    public static final String PREF_OPENED_VIDEO_URIS = "opened_video_uris"; // Defined in Constants now

    private static SharedPreferencesManager instance;
    public final SharedPreferences sharedPreferences;

    // Store context to use for string resources
    private final Context context;

    // --- Opened Video Methods ---
    public Set<String> getOpenedVideoUris() {
        // Return a copy to prevent external modification
        return new HashSet<>(
            sharedPreferences.getStringSet(
                Constants.PREF_OPENED_VIDEO_URIS,
                new HashSet<>()
            )
        );
    }

    public void addOpenedVideoUri(String uriString) {
        if (uriString == null || uriString.isEmpty()) return;
        Set<String> openedUris = getOpenedVideoUris(); // Get current set (returns a copy)
        if (openedUris.add(uriString)) {
            // Add the new URI (add returns true if set was changed)
            sharedPreferences
                .edit()
                .putStringSet(Constants.PREF_OPENED_VIDEO_URIS, openedUris)
                .apply();
            Log.d("SharedPrefs", "Added opened URI: " + uriString);
        } else {
            android.util.Log.v(
                "SharedPrefs",
                "URI already marked as opened: " + uriString
            );
        }
    }

    // Optional: Method to clear the opened set (e.g., for debugging)
    public void clearOpenedVideoUris() {
        sharedPreferences
            .edit()
            .remove(Constants.PREF_OPENED_VIDEO_URIS)
            .apply();
        Log.w("SharedPrefs", "Cleared all opened video URIs.");
    }

    // --- End Opened Video Methods ---

    private static final String PREF_KEY_VIDEO_ORIENTATION =
        "video_orientation";

    // -----
    private static final String PREF_KEY_TRASH_AUTO_DELETE_MINUTES =
        "trash_auto_delete_minutes";
    public static final int DEFAULT_TRASH_AUTO_DELETE_MINUTES = 30 * 24 * 60; // 30 days in minutes
    public static final int TRASH_AUTO_DELETE_NEVER = -1; // This constant can remain as is, representing manual delete
    // -----

    private static final String PREF_KEY_CLOCK_CARD_COLOR = "clock_card_color";

    // This will be set dynamically based on theme
    public static String DEFAULT_CLOCK_CARD_COLOR = "#673AB7"; // Initial value: Purple

    /**
     * Gets the currently set clock card color.
     * For AMOLED theme, special handling ensures the clock card always uses Dark
     * Grey.
     */
    public String getClockCardColor() {
        String currentTheme = sharedPreferences.getString(
            com.fadcam.Constants.PREF_APP_THEME,
            Constants.DEFAULT_APP_THEME
        );

        // Special case for AMOLED theme - always return Dark Grey
        if (
            currentTheme != null &&
            (currentTheme.equalsIgnoreCase("AMOLED") ||
                currentTheme.equalsIgnoreCase("Amoled") ||
                currentTheme.equalsIgnoreCase("Faded Night"))
        ) {
            return "#424242"; // Dark Grey for AMOLED
        } else if (currentTheme.equals("Crimson Bloom")) {
            return "#F44336"; // Red for Red theme
        } else if (currentTheme.equals("Premium Gold")) {
            return "#FFD700"; // Gold for Premium Gold theme
        } else if (currentTheme.equals("Silent Forest")) {
            return "#26A69A"; // Green for Silent Forest theme
        } else if (currentTheme.equals("Shadow Alloy")) {
            return "#A5A9AB"; // Silver for Shadow Alloy theme
        } else if (currentTheme.equals("Pookie Pink")) {
            return "#F06292"; // Pink for Pookie Pink theme
        } else if (currentTheme.equals("Snow Veil")) {
            return "#E0E0E0"; // Light Grey for Snow Veil theme
        }

        // For other themes or default
        return sharedPreferences.getString(
            PREF_KEY_CLOCK_CARD_COLOR,
            DEFAULT_CLOCK_CARD_COLOR
        );
    }

    /**
     * Updates the default clock card color based on the current theme.
     * This should be called whenever the theme changes or on app startup.
     */
    public void updateDefaultClockColorForTheme() {
        String currentTheme = sharedPreferences.getString(
            com.fadcam.Constants.PREF_APP_THEME,
            Constants.DEFAULT_APP_THEME
        );

        // Only update if the theme changes and the color wasn't manually set by the
        // user
        if (currentTheme != null) {
            if (
                currentTheme.equalsIgnoreCase("AMOLED") ||
                currentTheme.equalsIgnoreCase("Amoled") ||
                currentTheme.equalsIgnoreCase("Faded Night")
            ) {
                setClockCardColor("#424242"); // Dark Grey for AMOLED
            } else if (currentTheme.equals("Crimson Bloom")) {
                setClockCardColor("#F44336"); // Red for Red theme
            } else if (currentTheme.equals("Premium Gold")) {
                setClockCardColor("#FFD700"); // Gold for Gold theme
            } else if (currentTheme.equals("Silent Forest")) {
                setClockCardColor("#26A69A"); // Green for Silent Forest theme
            } else if (currentTheme.equals("Shadow Alloy")) {
                setClockCardColor("#A5A9AB"); // Silver for Shadow Alloy theme
            } else if (currentTheme.equals("Pookie Pink")) {
                setClockCardColor("#F06292"); // Pink for Pookie Pink theme
            } else if (currentTheme.equals("Snow Veil")) {
                setClockCardColor("#E0E0E0"); // Light Grey for Snow Veil theme
            } else {
                setClockCardColor(DEFAULT_CLOCK_CARD_COLOR); // Default purple for other themes
            }
        }
    }

    public void setClockCardColor(String colorHex) {
        sharedPreferences
            .edit()
            .putString(PREF_KEY_CLOCK_CARD_COLOR, colorHex)
            .apply();
    }


    // ----- Battery Warning Threshold Methods -----
    /**
     * Get the battery warning threshold percentage.
     * Default: 20%
     */
    public int getBatteryWarningThreshold() {
        return sharedPreferences.getInt(
            PREF_BATTERY_WARNING_THRESHOLD,
            DEFAULT_BATTERY_WARNING_THRESHOLD
        );
    }

    /**
     * Set the battery warning threshold percentage.
     */
    public void setBatteryWarningThreshold(int percentage) {
        if (percentage < 5 || percentage > 100) {
            percentage = DEFAULT_BATTERY_WARNING_THRESHOLD;
        }
        sharedPreferences
            .edit()
            .putInt(PREF_BATTERY_WARNING_THRESHOLD, percentage)
            .apply();
    }
    // ----- End Battery Warning Threshold Methods -----

    /** Returns whether playback should start muted by default. Default: false (unmuted). */
    public boolean isPlaybackMuted() {
        return sharedPreferences.getBoolean(
            Constants.PREF_PLAYBACK_MUTED,
            false
        );
    }

    /** Sets the playback muted preference. */
    public void setPlaybackMuted(boolean muted) {
        sharedPreferences
            .edit()
            .putBoolean(Constants.PREF_PLAYBACK_MUTED, muted)
            .apply();
    }


    // -----
    public static final String PREF_VIDEO_SPLITTING_ENABLED =
        "video_splitting_enabled";
    public static final boolean DEFAULT_VIDEO_SPLITTING_ENABLED = false;
    public static final String PREF_VIDEO_SPLIT_SIZE_MB = "video_split_size_mb";
    public static final int DEFAULT_VIDEO_SPLIT_SIZE_MB = 2048; // 2GB
    // -----

    // -----
    private static final String PREF_KEY_AUDIO_INPUT_SOURCE =
        "audio_input_source";
    public static final String AUDIO_INPUT_SOURCE_PHONE = "phone_mic";
    public static final String AUDIO_INPUT_SOURCE_WIRED = "wired_mic";
    // -----

    // App Lock preferences
    private static final String PREF_APP_LOCK_ENABLED = "applock_enabled";

    private static final String KEY_APPLOCK_SESSION_UNLOCKED =
        "applock_session_unlocked";
    private volatile boolean sessionUnlockedCache = false;

    /**
     * Returns true if the AppLock has been unlocked for this session.
     */
    public boolean isAppLockSessionUnlocked() {
        if (sessionUnlockedCache) return true;
        sessionUnlockedCache = sharedPreferences.getBoolean(
            KEY_APPLOCK_SESSION_UNLOCKED,
            false
        );
        return sessionUnlockedCache;
    }

    /**
     * Sets the AppLock session unlock state.
     *
     * @param unlocked true if unlocked, false to reset
     */
    public void setAppLockSessionUnlocked(boolean unlocked) {
        sessionUnlockedCache = unlocked;
        sharedPreferences
            .edit()
            .putBoolean(KEY_APPLOCK_SESSION_UNLOCKED, unlocked)
            .apply();
    }


    private SharedPreferencesManager(Context context) {
        // Use PREFS_NAME from Constants class
        this.sharedPreferences = context.getSharedPreferences(
            Constants.PREFS_NAME,
            Context.MODE_PRIVATE
        );
        this.context = context;
    }

    public static SharedPreferencesManager getInstance(Context context) {
        if (instance == null) {
            // Use application context to prevent memory leaks
            instance = new SharedPreferencesManager(
                context.getApplicationContext()
            );
        }
        return instance;
    }

    // --- Camera / Video settings ---
    public CameraType getCameraSelection() {
        String cameraSelection = sharedPreferences.getString(
            Constants.PREF_CAMERA_SELECTION,
            String.valueOf(Constants.DEFAULT_CAMERA_TYPE)
        );
        try {
            return CameraType.valueOf(cameraSelection);
        } catch (IllegalArgumentException e) {
            // Fallback if stored value is invalid (e.g. after downgrade)
            return CameraType.BACK;
        }
    }

    public Size getCameraResolution() {
        return new Size(
            sharedPreferences.getInt(
                Constants.PREF_VIDEO_RESOLUTION_WIDTH,
                Constants.DEFAULT_VIDEO_RESOLUTION.getWidth()
            ),
            sharedPreferences.getInt(
                Constants.PREF_VIDEO_RESOLUTION_HEIGHT,
                Constants.DEFAULT_VIDEO_RESOLUTION.getHeight()
            )
        );
    }

    // --- Existing FPS method (Maybe deprecate later) ---
    @Deprecated
    public Integer getVideoFrameRate() {
        // Keep original default logic maybe? Or point to Back Camera?
        // For safety, maybe return Back camera's value if new ones exist
        if (sharedPreferences.contains(Constants.PREF_VIDEO_FRAME_RATE_BACK)) {
            return getSpecificVideoFrameRate(CameraType.BACK);
        }
        return sharedPreferences.getInt(
            Constants.PREF_VIDEO_FRAME_RATE,
            Constants.DEFAULT_VIDEO_FRAME_RATE
        );
    }

    // --- NEW: Specific Getters/Setters ---

    /**
     * Gets the saved frame rate preference for a specific camera type.
     * If no specific preference is saved, it falls back to the old generic
     * preference OR the default FPS.
     *
     * @param cameraType FRONT or BACK camera. DUAL_PIP inherits BACK camera settings.
     * @return The saved frame rate (int).
     */
    public int getSpecificVideoFrameRate(CameraType cameraType) {
        // DUAL_PIP inherits BACK camera frame rate settings
        String specificKey = (cameraType == CameraType.FRONT)
            ? Constants.PREF_VIDEO_FRAME_RATE_FRONT
            : Constants.PREF_VIDEO_FRAME_RATE_BACK;

        // 1. Check if the specific key exists
        if (sharedPreferences.contains(specificKey)) {
            return sharedPreferences.getInt(
                specificKey,
                Constants.DEFAULT_VIDEO_FRAME_RATE
            );
        }
        // 2. Specific key doesn't exist, check if the OLD generic key exists (for
        // migration)
        else if (sharedPreferences.contains(Constants.PREF_VIDEO_FRAME_RATE)) {
            int oldGenericValue = sharedPreferences.getInt(
                Constants.PREF_VIDEO_FRAME_RATE,
                Constants.DEFAULT_VIDEO_FRAME_RATE
            );
            Log.w(
                "SharedPrefs",
                "Migrating old FPS pref (" +
                oldGenericValue +
                ") to key: " +
                specificKey
            );
            // Save the migrated value to the new key
            setSpecificVideoFrameRate(cameraType, oldGenericValue);
            // Optionally remove the old key after migration
            // sharedPreferences.edit().remove(Constants.PREF_VIDEO_FRAME_RATE).apply();
            return oldGenericValue;
        }
        // 3. Neither specific nor old generic exists, return default
        else {
            return Constants.DEFAULT_VIDEO_FRAME_RATE;
        }
    }

    /**
     * Saves the frame rate preference for a specific camera type.
     *
     * @param cameraType FRONT or BACK camera.
     * @param frameRate  The frame rate value to save.
     */
    public void setSpecificVideoFrameRate(
        CameraType cameraType,
        int frameRate
    ) {
        String specificKey = (cameraType == CameraType.FRONT)
            ? Constants.PREF_VIDEO_FRAME_RATE_FRONT
            : Constants.PREF_VIDEO_FRAME_RATE_BACK;
        sharedPreferences.edit().putInt(specificKey, frameRate).apply();
    }

    // --- End New FPS Methods ---

    // --- NEW: Zoom Ratio Methods ---

    /**
     * Gets the saved zoom ratio preference for a specific camera type.
     * If no specific preference is saved, it returns the default zoom ratio.
     * For wide-angle cameras, uses a lower default zoom ratio to properly utilize
     * the wide field of view.
     *
     * @param cameraType FRONT, BACK, or DUAL_PIP camera. DUAL_PIP inherits BACK camera settings.
     * @return The saved zoom ratio (float).
     */
    public float getSpecificZoomRatio(CameraType cameraType) {
        // DUAL_PIP inherits BACK camera zoom settings
        String specificKey = (cameraType == CameraType.FRONT)
            ? Constants.PREF_ZOOM_RATIO_FRONT
            : Constants.PREF_ZOOM_RATIO_BACK;

        // Check if the specific key exists
        if (sharedPreferences.contains(specificKey)) {
            return sharedPreferences.getFloat(
                specificKey,
                getDefaultZoomRatioForCamera(cameraType)
            );
        }
        // No specific key exists, return camera-appropriate default
        else {
            return getDefaultZoomRatioForCamera(cameraType);
        }
    }

    /**
     * Gets the appropriate default zoom ratio based on camera type and selected
     * lens.
     * Wide-angle cameras get a lower default zoom ratio to properly utilize their
     * wide field of view.
     */
    private float getDefaultZoomRatioForCamera(CameraType cameraType) {
        if (cameraType == CameraType.BACK) {
            // For back camera, check if a wide-angle lens is selected
            String selectedCameraId = getSelectedBackCameraId();
            if (
                selectedCameraId != null &&
                !selectedCameraId.equals(Constants.DEFAULT_BACK_CAMERA_ID)
            ) {
                // Check camera characteristics to determine if it's wide-angle
                if (isWideAngleCamera(selectedCameraId)) {
                    return 0.5f; // Lower zoom for wide-angle cameras to show full field of view
                }
                // For telephoto or other cameras, use normal zoom
                return Constants.DEFAULT_ZOOM_RATIO;
            }
        }
        // Default zoom ratio for main cameras (front camera and main back camera)
        return Constants.DEFAULT_ZOOM_RATIO;
    }

    /**
     * Checks if the given camera ID corresponds to a wide-angle or ultra-wide
     * camera
     * by examining its focal length characteristics.
     */
    private boolean isWideAngleCamera(String cameraId) {
        try {
            android.hardware.camera2.CameraManager manager =
                (android.hardware.camera2.CameraManager) context.getSystemService(
                    Context.CAMERA_SERVICE
                );
            if (manager != null) {
                android.hardware.camera2.CameraCharacteristics characteristics =
                    manager.getCameraCharacteristics(cameraId);
                float[] focalLengths = characteristics.get(
                    android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                );
                if (focalLengths != null && focalLengths.length > 0) {
                    float focal = focalLengths[0];
                    // Wide-angle cameras typically have focal lengths <= 8mm
                    // Ultra-wide cameras typically have focal lengths <= 4mm
                    return focal <= 8f;
                }
            }
        } catch (Exception e) {
            android.util.Log.w(
                "SharedPrefs",
                "Error checking camera characteristics for ID: " + cameraId,
                e
            );
        }
        return false;
    }

    /**
     * Saves the zoom ratio preference for a specific camera type.
     *
     * @param cameraType FRONT or BACK camera.
     * @param zoomRatio  The zoom ratio value to save.
     */
    public void setSpecificZoomRatio(CameraType cameraType, float zoomRatio) {
        String specificKey = (cameraType == CameraType.FRONT)
            ? Constants.PREF_ZOOM_RATIO_FRONT
            : Constants.PREF_ZOOM_RATIO_BACK;
        sharedPreferences.edit().putFloat(specificKey, zoomRatio).commit();
    }

    // --- End New Zoom Ratio Methods ---

    public VideoCodec getVideoCodec() {
        String videoCodec = sharedPreferences.getString(
            Constants.PREF_VIDEO_CODEC,
            Constants.DEFAULT_VIDEO_CODEC.toString()
        );
        return VideoCodec.valueOf(videoCodec);
    }

    /** Returns whether the video player should keep the screen on during playback. Default: true. */
    public boolean isPlayerKeepScreenOn() {
        return sharedPreferences.getBoolean(
            Constants.PREF_PLAYER_KEEP_SCREEN_ON,
            true
        );
    }

    /** Sets whether the video player should keep the screen on during playback. */
    public void setPlayerKeepScreenOn(boolean keepOn) {
        sharedPreferences
            .edit()
            .putBoolean(Constants.PREF_PLAYER_KEEP_SCREEN_ON, keepOn)
            .apply();
    }
    
    // --- Remote Streaming preferences ---
    private static final String PREF_KEY_STREAMING_MODE = "pref_streaming_mode";
    
    /**
     * Get the current streaming mode (STREAM_ONLY or STREAM_AND_SAVE).
     * Defaults to STREAM_AND_SAVE.
     */
    public com.fadcam.streaming.RemoteStreamManager.StreamingMode getStreamingMode() {
        String mode = sharedPreferences.getString(PREF_KEY_STREAMING_MODE, "STREAM_AND_SAVE");
        try {
            return com.fadcam.streaming.RemoteStreamManager.StreamingMode.valueOf(mode);
        } catch (IllegalArgumentException e) {
            return com.fadcam.streaming.RemoteStreamManager.StreamingMode.STREAM_AND_SAVE;
        }
    }
    
    /**
     * Set the streaming mode (STREAM_ONLY or STREAM_AND_SAVE).
     */
    public void setStreamingMode(com.fadcam.streaming.RemoteStreamManager.StreamingMode mode) {
        sharedPreferences
            .edit()
            .putString(PREF_KEY_STREAMING_MODE, mode.toString())
            .apply();
    }

    // --- Player seek seconds preference ---
    private static final String PREF_KEY_PLAYER_SEEK_SECONDS =
        Constants.PREF_PLAYER_SEEK_SECONDS;

    /**
     * Returns the saved seek seconds used by rewind/forward buttons and double-tap.
     * Defaults to Constants.DEFAULT_PLAYER_SEEK_SECONDS.
     */
    public int getPlayerSeekSeconds() {
        return sharedPreferences.getInt(
            PREF_KEY_PLAYER_SEEK_SECONDS,
            Constants.DEFAULT_PLAYER_SEEK_SECONDS
        );
    }

    /**
     * Sets the seek seconds preference.
     */
    public void setPlayerSeekSeconds(int seconds) {
        if (seconds < 1) seconds = Constants.DEFAULT_PLAYER_SEEK_SECONDS;
        sharedPreferences
            .edit()
            .putInt(PREF_KEY_PLAYER_SEEK_SECONDS, seconds)
            .apply();
    }

    // --- Player controller auto-hide timeout preference ---
    private static final String PREF_KEY_PLAYER_CONTROLS_TIMEOUT_SECONDS =
        Constants.PREF_PLAYER_CONTROLS_TIMEOUT_SECONDS;

    /**
     * Returns the saved controller auto-hide timeout in seconds.
     * 0 = never auto-hide. Defaults to Constants.DEFAULT_PLAYER_CONTROLS_TIMEOUT_SECONDS.
     */
    public int getPlayerControlsTimeoutSeconds() {
        return sharedPreferences.getInt(
            PREF_KEY_PLAYER_CONTROLS_TIMEOUT_SECONDS,
            Constants.DEFAULT_PLAYER_CONTROLS_TIMEOUT_SECONDS
        );
    }

    /**
     * Sets the controller auto-hide timeout in seconds. Values less than 0 are coerced to default.
     * Use 0 to disable auto-hide (controls remain visible until user toggles).
     */
    public void setPlayerControlsTimeoutSeconds(int seconds) {
        if (seconds < 0) seconds =
            Constants.DEFAULT_PLAYER_CONTROLS_TIMEOUT_SECONDS;
        sharedPreferences
            .edit()
            .putInt(PREF_KEY_PLAYER_CONTROLS_TIMEOUT_SECONDS, seconds)
            .apply();
    }


    /** Returns whether background playback is enabled. Default: false. */
    public boolean isBackgroundPlaybackEnabled() {
        return sharedPreferences.getBoolean(
            Constants.PREF_PLAYER_BACKGROUND_PLAYBACK,
            false
        );
    }

    /** Enable/disable background playback. */
    public void setBackgroundPlaybackEnabled(boolean enabled) {
        sharedPreferences
            .edit()
            .putBoolean(Constants.PREF_PLAYER_BACKGROUND_PLAYBACK, enabled)
            .apply();
    }

    /** Returns whether fullscreen preview tap-to-focus is enabled. Default: true. */
    public boolean isFullscreenTapToFocusEnabled() {
        return sharedPreferences.getBoolean(
            Constants.PREF_FULLSCREEN_TAP_TO_FOCUS_ENABLED,
            true
        );
    }

    /** Sets fullscreen preview tap-to-focus behavior. */
    public void setFullscreenTapToFocusEnabled(boolean enabled) {
        sharedPreferences
            .edit()
            .putBoolean(Constants.PREF_FULLSCREEN_TAP_TO_FOCUS_ENABLED, enabled)
            .apply();
    }

    /** Returns whether home preview quick action icons stay visible while idle. Default: true. */
    public boolean isPreviewQuickActionsAlwaysVisible() {
        return sharedPreferences.getBoolean(
            Constants.PREF_PREVIEW_QUICK_ACTIONS_ALWAYS_VISIBLE,
            true
        );
    }

    /** Sets whether home preview quick action icons stay visible while idle. */
    public void setPreviewQuickActionsAlwaysVisible(boolean enabled) {
        sharedPreferences
            .edit()
            .putBoolean(Constants.PREF_PREVIEW_QUICK_ACTIONS_ALWAYS_VISIBLE, enabled)
            .apply();
    }

    // -------------- Background playback auto-stop timer (in seconds) --------------
    /** Returns the auto-stop time in seconds. 0 = disabled. Default: 0. */
    public int getBackgroundPlaybackTimerSeconds() {
        return sharedPreferences.getInt(
            Constants.PREF_PLAYER_BACKGROUND_TIMER_SECONDS,
            0
        );
    }

    /** Sets the auto-stop time in seconds. 0 disables the timer. */
    public void setBackgroundPlaybackTimerSeconds(int seconds) {
        sharedPreferences
            .edit()
            .putInt(Constants.PREF_PLAYER_BACKGROUND_TIMER_SECONDS, seconds)
            .apply();
    }

    /** Convenience get/put for long values. */
    public long getLong(String key, long defValue) {
        return sharedPreferences.getLong(key, defValue);
    }

    public void putLong(String key, long value) {
        sharedPreferences.edit().putLong(key, value).apply();
    }

    // --- Resume position helpers ---
    private static final String PREF_KEY_VIDEO_POS_PREFIX = "video_pos_"; // appended with encoded URI
    private static final String PREF_KEY_VIDEO_POS_FILENAME_PREFIX =
        "video_pos_fn_"; // appended with filename

    /**
     * Get saved playback position (ms) for a given video URI string. Returns 0 if none saved.
     */
    public long getSavedPlaybackPositionMs(String uriString) {
        if (uriString == null) return 0L;
        String key = PREF_KEY_VIDEO_POS_PREFIX + uriString;
        return sharedPreferences.getLong(key, 0L);
    }

    /**
     * Save playback position (ms) for a given video URI string.
     */
    public void setSavedPlaybackPositionMs(String uriString, long posMs) {
        if (uriString == null) return;
        String key = PREF_KEY_VIDEO_POS_PREFIX + uriString;
        sharedPreferences.edit().putLong(key, posMs).apply();
    }

    /**
     * Save playback position (ms) for a given video filename (fallback key).
     */
    public void setSavedPlaybackPositionMsByFilename(
        String filename,
        long posMs
    ) {
        if (filename == null) return;
        String key = PREF_KEY_VIDEO_POS_FILENAME_PREFIX + filename;
        sharedPreferences.edit().putLong(key, posMs).apply();
    }

    /**
     * Get saved playback position (ms) by filename fallback. Returns 0 if none saved.
     */
    public long getSavedPlaybackPositionMsByFilename(String filename) {
        if (filename == null) return 0L;
        String key = PREF_KEY_VIDEO_POS_FILENAME_PREFIX + filename;
        return sharedPreferences.getLong(key, 0L);
    }

    /**
     * Helper: check URI-keyed position first, then filename-keyed fallback.
     * @param uriString full URI string (may be null)
     * @param filename filename (may be null)
     * @return saved position in ms or 0
     */
    public long getSavedPlaybackPositionMsWithFilenameFallback(
        String uriString,
        String filename
    ) {
        long v = getSavedPlaybackPositionMs(uriString);
        if (v > 0) return v;
        return getSavedPlaybackPositionMsByFilename(filename);
    }

    // --- End Camera / Video settings ---

    // --- STORAGE METHODS ---
    public String getStorageMode() {
        return sharedPreferences.getString(
            PREF_STORAGE_MODE,
            STORAGE_MODE_INTERNAL
        ); // Default to internal
    }

    public void setStorageMode(String mode) {
        sharedPreferences.edit().putString(PREF_STORAGE_MODE, mode).apply();
    }

    @Nullable // Can be null if not set
    public String getCustomStorageUri() {
        return sharedPreferences.getString(PREF_CUSTOM_STORAGE_URI, null);
    }

    public void setCustomStorageUri(@Nullable String uriString) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        if (uriString == null) {
            editor.remove(PREF_CUSTOM_STORAGE_URI);
        } else {
            editor.putString(PREF_CUSTOM_STORAGE_URI, uriString);
        }
        editor.apply();
    }

    // --- END STORAGE METHODS ---

    // --- Other existing methods ---
    public boolean isVideoCodecExist() {
        return (
            sharedPreferences.getString(Constants.PREF_VIDEO_CODEC, null) !=
            null
        );
    }

    // --- Quick speed preference for press-and-hold fast playback ---
    public float getQuickSpeed() {
        return sharedPreferences.getFloat(
            Constants.PREF_QUICK_SPEED,
            Constants.DEFAULT_QUICK_SPEED
        );
    }

    public void setQuickSpeed(float speed) {
        sharedPreferences
            .edit()
            .putFloat(Constants.PREF_QUICK_SPEED, speed)
            .apply();
    }

    // --- Audio waveform visualization preference ---
    public boolean isAudioWaveformEnabled() {
        return sharedPreferences.getBoolean(
            "pref_audio_waveform_enabled",
            true
        ); // Default enabled
    }

    public void setAudioWaveformEnabled(boolean enabled) {
        sharedPreferences
            .edit()
            .putBoolean("pref_audio_waveform_enabled", enabled)
            .apply();
    }

    // ----- Hide thumbnails in Records preference -----
    private static final String PREF_KEY_HIDE_THUMBNAILS =
        "pref_hide_thumbnails";

    /** Returns whether thumbnails should be hidden in Records list. Default: false. */
    public boolean isHideThumbnailsEnabled() {
        return sharedPreferences.getBoolean(PREF_KEY_HIDE_THUMBNAILS, false);
    }

    /** Sets whether thumbnails should be hidden in Records list. */
    public void setHideThumbnailsEnabled(boolean enabled) {
        sharedPreferences
            .edit()
            .putBoolean(PREF_KEY_HIDE_THUMBNAILS, enabled)
            .apply();
    }

    // ----- Hide thumbnails in Lab (Forensics Gallery) preference -----
    private static final String PREF_KEY_LAB_HIDE_THUMBNAILS =
        "pref_lab_hide_thumbnails";

    /** Returns whether thumbnails should be hidden in Lab gallery. Default: false. */
    public boolean isLabHideThumbnailsEnabled() {
        return sharedPreferences.getBoolean(PREF_KEY_LAB_HIDE_THUMBNAILS, false);
    }

    /** Sets whether thumbnails should be hidden in Lab gallery. */
    public void setLabHideThumbnailsEnabled(boolean enabled) {
        sharedPreferences
            .edit()
            .putBoolean(PREF_KEY_LAB_HIDE_THUMBNAILS, enabled)
            .apply();
    }

    public boolean isLocalisationEnabled() {
        return sharedPreferences.getBoolean(
            Constants.PREF_LOCATION_DATA,
            false
        );
    }

    public boolean isLocationEmbeddingEnabled() {
        return sharedPreferences.getBoolean(
            Constants.PREF_EMBED_LOCATION_DATA,
            false
        );
    }

    public boolean isDebugLoggingEnabled() {
        return sharedPreferences.getBoolean(Constants.PREF_DEBUG_DATA, false);
    }

    public String getWatermarkOption() {
        return sharedPreferences.getString(
            Constants.PREF_WATERMARK_OPTION,
            Constants.DEFAULT_WATERMARK_OPTION
        );
    }

    /**
     * Get custom watermark text.
     * @return Custom watermark text or empty string if not set
     */
    public String getWatermarkCustomText() {
        return sharedPreferences.getString(Constants.PREF_WATERMARK_CUSTOM_TEXT, "");
    }

    /**
     * Set custom watermark text.
     * @param text Custom text to display on watermark (line 2)
     */
    public void setWatermarkCustomText(String text) {
        sharedPreferences.edit()
            .putString(Constants.PREF_WATERMARK_CUSTOM_TEXT, text != null ? text : "")
            .apply();
    }

    // Method to retrieve the preview state
    public Boolean isPreviewEnabled() {
        // Default to true if the preference doesn't exist yet
        return sharedPreferences.getBoolean(
            Constants.PREF_IS_PREVIEW_ENABLED,
            Constants.DEFAULT_PREVIEW_ENABLED
        );
    }

    // Method to save the preview state (often inline in the Fragment's
    // savePreviewState, but could be here too)
    public void setPreviewEnabled(boolean isEnabled) {
        sharedPreferences
            .edit()
            .putBoolean(Constants.PREF_IS_PREVIEW_ENABLED, isEnabled)
            .apply();
    }

    public String getLanguage() {
        return sharedPreferences.getString(
            Constants.LANGUAGE_KEY,
            Locale.getDefault().getLanguage()
        );
    }

    public boolean isRecordingInProgress() {
        return sharedPreferences.getBoolean(
            Constants.PREF_IS_RECORDING_IN_PROGRESS,
            false
        );
    }

    public void setRecordingInProgress(boolean isInProgress) {
        sharedPreferences
            .edit()
            .putBoolean(Constants.PREF_IS_RECORDING_IN_PROGRESS, isInProgress)
            .apply();
    }

    public boolean isRecordAudioEnabled() {
        return sharedPreferences.getBoolean(
            Constants.PREF_RECORD_AUDIO,
            Constants.DEFAULT_RECORD_AUDIO
        );
    }

    public void setRecordAudioEnabled(boolean enabled) {
        sharedPreferences
            .edit()
            .putBoolean(Constants.PREF_RECORD_AUDIO, enabled)
            .apply();
    }

    // ----- Camera runtime controls persistence -----
    public int getSavedExposureCompensation() {
        return sharedPreferences.getInt(
            Constants.PREF_EXPOSURE_COMPENSATION,
            0
        );
    }

    public void setSavedExposureCompensation(int evIndex) {
        sharedPreferences
            .edit()
            .putInt(Constants.PREF_EXPOSURE_COMPENSATION, evIndex)
            .commit();
    }

    public boolean isAeLockedSaved() {
        return sharedPreferences.getBoolean(Constants.PREF_AE_LOCK, false);
    }

    public void setSavedAeLock(boolean locked) {
        sharedPreferences
            .edit()
            .putBoolean(Constants.PREF_AE_LOCK, locked)
            .commit();
    }

    public int getSavedAfMode() {
        return sharedPreferences.getInt(
            Constants.PREF_AF_MODE,
            android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        );
    }

    public void setSavedAfMode(int afMode) {
        sharedPreferences
            .edit()
            .putInt(Constants.PREF_AF_MODE, afMode)
            .commit();
    }

    // --- End Other methods ---

    public static final String PREF_IS_PREVIEW_ENABLED = "isPreviewEnabled"; // Check constant exists
    public static final boolean DEFAULT_PREVIEW_ENABLED = true; // Check default exists

    /** Preference key controlling whether the app should cloak its snapshot in the Android Recents screen. */
    public static final String PREF_CLOAK_RECENTS_ENABLED =
        "cloak_recents_enabled";
    /** Default behavior is OFF to avoid confusion for new users. */
    public static final boolean DEFAULT_CLOAK_RECENTS_ENABLED = false;

    /** Returns whether cloaking recents snapshot is enabled. Default: true. */
    public boolean isCloakRecentsEnabled() {
        return sharedPreferences.getBoolean(
            PREF_CLOAK_RECENTS_ENABLED,
            DEFAULT_CLOAK_RECENTS_ENABLED
        );
    }

    /** Enables or disables cloaking the recents snapshot. */
    public void setCloakRecentsEnabled(boolean enabled) {
        sharedPreferences
            .edit()
            .putBoolean(PREF_CLOAK_RECENTS_ENABLED, enabled)
            .apply();
    }


    /**
     * Gets the preferred physical camera ID for the back camera.
     * Defaults to Constants.DEFAULT_BACK_CAMERA_ID if not set or invalid.
     *
     * @return The saved physical camera ID string.
     */
    public String getSelectedBackCameraId() {
        // Basic validation could be added here if needed (e.g., check if ID is purely
        // numeric?)
        return sharedPreferences.getString(
            Constants.PREF_SELECTED_BACK_CAMERA_ID,
            Constants.DEFAULT_BACK_CAMERA_ID
        );
    }

    /**
     * Saves the preferred physical camera ID for the back camera.
     *
     * @param cameraId The physical camera ID string to save.
     */
    public void setSelectedBackCameraId(String cameraId) {
        if (cameraId == null || cameraId.isEmpty()) {
            Log.w(
                "SharedPrefs",
                "Attempted to save null or empty back camera ID. Resetting to default."
            );
            cameraId = Constants.DEFAULT_BACK_CAMERA_ID; // Fallback to default if invalid ID provided
        }
        sharedPreferences
            .edit()
            .putString(Constants.PREF_SELECTED_BACK_CAMERA_ID, cameraId)
            .apply();
        Log.d("SharedPrefs", "Saved selected back camera ID: " + cameraId);
    }

    // --- Audio Settings ---
    public int getAudioBitrate() {
        return sharedPreferences.getInt(
            Constants.PREF_AUDIO_BITRATE,
            Constants.DEFAULT_AUDIO_BITRATE
        );
    }

    public void setAudioBitrate(int bitrate) {
        sharedPreferences
            .edit()
            .putInt(Constants.PREF_AUDIO_BITRATE, bitrate)
            .apply();
    }

    public int getAudioSamplingRate() {
        return sharedPreferences.getInt(
            Constants.PREF_AUDIO_SAMPLING_RATE,
            Constants.DEFAULT_AUDIO_SAMPLING_RATE
        );
    }

    public void setAudioSamplingRate(int samplingRate) {
        sharedPreferences
            .edit()
            .putInt(Constants.PREF_AUDIO_SAMPLING_RATE, samplingRate)
            .apply();
    }

    public void resetAudioSettingsToDefault() {
        setAudioBitrate(Constants.DEFAULT_AUDIO_BITRATE);
        setAudioSamplingRate(Constants.DEFAULT_AUDIO_SAMPLING_RATE);
    }

    // --- End Audio Settings ---

    // --- Video Orientation ---
    public static final String PREF_VIDEO_ORIENTATION = "video_orientation";
    public static final String ORIENTATION_PORTRAIT = "portrait";
    public static final String ORIENTATION_LANDSCAPE = "landscape";
    public static final String DEFAULT_VIDEO_ORIENTATION = ORIENTATION_PORTRAIT;

    public String getVideoOrientation() {
        return sharedPreferences.getString(
            PREF_VIDEO_ORIENTATION,
            DEFAULT_VIDEO_ORIENTATION
        );
    }

    public void setVideoOrientation(String orientation) {
        sharedPreferences
            .edit()
            .putString(PREF_VIDEO_ORIENTATION, orientation)
            .apply();
    }

    /**
     * Checks if the video orientation is set to landscape.
     *
     * @return true if the orientation is landscape, false otherwise.
     */
    public boolean isOrientationLandscape() {
        return ORIENTATION_LANDSCAPE.equals(getVideoOrientation());
    }

    // --- End Video Orientation ---

    // (SharedPreferencesManager_trash_auto_delete_methods) -----
    public void setTrashAutoDeleteMinutes(int minutes) {
        sharedPreferences
            .edit()
            .putInt(PREF_KEY_TRASH_AUTO_DELETE_MINUTES, minutes)
            .apply();
        Log.d(
            "SharedPrefsManager",
            "Trash auto-delete duration set to: " + minutes + " minutes."
        );
    }

    public int getTrashAutoDeleteMinutes() {
        int minutes = sharedPreferences.getInt(
            PREF_KEY_TRASH_AUTO_DELETE_MINUTES,
            DEFAULT_TRASH_AUTO_DELETE_MINUTES
        );
        Log.d(
            "SharedPrefsManager",
            "Retrieved trash auto-delete duration: " + minutes + " minutes."
        );
        return minutes;
    }

    // (SharedPreferencesManager_trash_auto_delete_methods) -----

    // (SharedPreferencesManager_video_splitting_methods) -----
    public boolean isVideoSplittingEnabled() {
        return sharedPreferences.getBoolean(
            PREF_VIDEO_SPLITTING_ENABLED,
            DEFAULT_VIDEO_SPLITTING_ENABLED
        );
    }

    public void setVideoSplittingEnabled(boolean enabled) {
        sharedPreferences
            .edit()
            .putBoolean(PREF_VIDEO_SPLITTING_ENABLED, enabled)
            .apply();
    }

    public int getVideoSplitSizeMb() {
        return sharedPreferences.getInt(
            PREF_VIDEO_SPLIT_SIZE_MB,
            DEFAULT_VIDEO_SPLIT_SIZE_MB
        );
    }

    public void setVideoSplitSizeMb(int sizeMb) {
        sharedPreferences
            .edit()
            .putInt(PREF_VIDEO_SPLIT_SIZE_MB, sizeMb)
            .apply();
    }

    // (SharedPreferencesManager_video_splitting_methods) -----

    // -----
    public void setAudioInputSource(String source) {
        sharedPreferences
            .edit()
            .putString(PREF_KEY_AUDIO_INPUT_SOURCE, source)
            .apply();
    }

    public String getAudioInputSource() {
        return sharedPreferences.getString(
            PREF_KEY_AUDIO_INPUT_SOURCE,
            AUDIO_INPUT_SOURCE_PHONE
        );
    }

    // -----

    // Using the proper constant from Constants class
    public boolean isShowOnboarding() {
        // Return true if onboarding hasn't been completed
        boolean completed = sharedPreferences.getBoolean(
            Constants.COMPLETED_ONBOARDING_KEY,
            false
        );
        return !completed;
    }

    public void setShowOnboarding(boolean show) {
        // Store the opposite value in COMPLETED_ONBOARDING_KEY
        // If show is true, it means onboarding is not completed
        // If show is false, it means onboarding is completed
        boolean completedValue = !show;
        sharedPreferences
            .edit()
            .putBoolean(Constants.COMPLETED_ONBOARDING_KEY, completedValue)
            .apply();
    }


    // -----
    /**
     * Sets whether location embedding is enabled
     *
     * @param enabled True to enable location embedding, false to disable
     */
    public void setLocationEmbeddingEnabled(boolean enabled) {
        sharedPreferences
            .edit()
            .putBoolean(Constants.PREF_EMBED_LOCATION_DATA, enabled)
            .apply();
    }

    /**
     * Sets whether location watermark is enabled
     *
     * @param enabled True to enable location watermark, false to disable
     */
    public void setLocationEnabled(boolean enabled) {
        sharedPreferences
            .edit()
            .putBoolean(Constants.PREF_LOCATION_DATA, enabled)
            .apply();
    }

    // -----

    /**
     * Checks if app lock is enabled
     *
     * @return true if app lock is enabled, false otherwise
     */
    public boolean isAppLockEnabled() {
        return sharedPreferences.getBoolean(PREF_APP_LOCK_ENABLED, false);
    }

    /**
     * Enables or disables app lock
     *
     * @param enabled true to enable app lock, false to disable
     */
    public void setAppLockEnabled(boolean enabled) {
        sharedPreferences
            .edit()
            .putBoolean(PREF_APP_LOCK_ENABLED, enabled)
            .apply();
    }


    // (SharedPreferencesManager_notification_customization) -----
    // Notification customization constants
    public static final String PREF_NOTIFICATION_PRESET = "notification_preset";
    public static final String NOTIFICATION_PRESET_DEFAULT = "default"; // Default FadCam notification
    public static final String NOTIFICATION_PRESET_SYSTEM_UPDATE =
        "system_update"; // System update notification
    public static final String NOTIFICATION_PRESET_DOWNLOADING = "downloading"; // Downloading notification
    public static final String NOTIFICATION_PRESET_SYNCING = "syncing"; // Syncing notification
    public static final String NOTIFICATION_PRESET_CUSTOM = "custom"; // User's custom text

    // Custom notification text preferences
    public static final String PREF_CUSTOM_NOTIFICATION_TITLE =
        "custom_notification_title";
    public static final String PREF_CUSTOM_NOTIFICATION_TEXT =
        "custom_notification_text";
    public static final String PREF_HIDE_NOTIFICATION_STOP_BUTTON =
        "hide_notification_stop_button";

    /**
     * Gets the selected notification preset.
     *
     * @return The preset key, default is "default"
     */
    public String getNotificationPreset() {
        return sharedPreferences.getString(
            PREF_NOTIFICATION_PRESET,
            NOTIFICATION_PRESET_DEFAULT
        );
    }

    /**
     * Sets the notification preset.
     *
     * @param preset The preset key to use
     */
    public void setNotificationPreset(String preset) {
        sharedPreferences
            .edit()
            .putString(PREF_NOTIFICATION_PRESET, preset)
            .apply();
    }

    /**
     * Gets the custom notification title if set by user.
     *
     * @return The custom title or null if not set
     */
    public String getCustomNotificationTitle() {
        return sharedPreferences.getString(
            PREF_CUSTOM_NOTIFICATION_TITLE,
            null
        );
    }

    /**
     * Sets the custom notification title.
     *
     * @param title The custom title text
     */
    public void setCustomNotificationTitle(String title) {
        sharedPreferences
            .edit()
            .putString(PREF_CUSTOM_NOTIFICATION_TITLE, title)
            .apply();
    }

    /**
     * Gets the custom notification text if set by user.
     *
     * @return The custom text or null if not set
     */
    public String getCustomNotificationText() {
        return sharedPreferences.getString(PREF_CUSTOM_NOTIFICATION_TEXT, null);
    }

    /**
     * Sets the custom notification text.
     *
     * @param text The custom notification text
     */
    public void setCustomNotificationText(String text) {
        sharedPreferences
            .edit()
            .putString(PREF_CUSTOM_NOTIFICATION_TEXT, text)
            .apply();
    }

    /**
     * Checks if the stop button should be hidden in the notification.
     *
     * @return true if button should be hidden, false otherwise
     */
    public boolean isNotificationStopButtonHidden() {
        return sharedPreferences.getBoolean(
            PREF_HIDE_NOTIFICATION_STOP_BUTTON,
            false
        );
    }

    /**
     * Sets whether to hide the stop button in the notification.
     *
     * @param hide true to hide the button, false to show it
     */
    public void setNotificationStopButtonHidden(boolean hide) {
        sharedPreferences
            .edit()
            .putBoolean(PREF_HIDE_NOTIFICATION_STOP_BUTTON, hide)
            .apply();
    }

    /**
     * Gets the notification title based on the selected preset or custom text.
     *
     * @return The notification title to display
     */
    public String getNotificationTitle() {
        String preset = getNotificationPreset();

        if (context == null) {
            return null; // Return null if context is not available
        }

        switch (preset) {
            case NOTIFICATION_PRESET_DEFAULT:
                return null; // Use default from strings.xml
            case NOTIFICATION_PRESET_SYSTEM_UPDATE:
                return context.getString(
                    R.string.notification_title_system_update
                );
            case NOTIFICATION_PRESET_DOWNLOADING:
                return context.getString(
                    R.string.notification_title_downloading
                );
            case NOTIFICATION_PRESET_SYNCING:
                return context.getString(R.string.notification_title_syncing);
            case NOTIFICATION_PRESET_CUSTOM:
                String custom = getCustomNotificationTitle();
                return (custom != null && !custom.isEmpty())
                    ? custom
                    : context.getString(
                        R.string.notification_title_custom_default
                    );
            default:
                return null; // Use default from strings.xml
        }
    }

    /**
     * Gets the notification channel name based on the selected preset
     *
     * @return The channel name to display
     */
    public String getNotificationChannelName() {
        if (context == null) {
            return null; // Return null if context is not available
        }

        String preset = getNotificationPreset();
        switch (preset) {
            case NOTIFICATION_PRESET_DEFAULT:
                return null; // Use default app name + " Recording"
            case NOTIFICATION_PRESET_SYSTEM_UPDATE:
                return context.getString(
                    R.string.notification_channel_system_updates
                );
            case NOTIFICATION_PRESET_DOWNLOADING:
                return context.getString(
                    R.string.notification_channel_downloads
                );
            case NOTIFICATION_PRESET_SYNCING:
                return context.getString(
                    R.string.notification_channel_data_sync
                );
            case NOTIFICATION_PRESET_CUSTOM:
                String custom = getCustomNotificationTitle();
                return (custom != null && !custom.isEmpty())
                    ? custom
                    : context.getString(R.string.notification_channel_generic);
            default:
                return null;
        }
    }

    /**
     * Gets the notification text based on the selected preset or custom text.
     *
     * @param isRecordingPaused Whether recording is currently paused
     * @return The notification text to display
     */
    public String getNotificationText(boolean isRecordingPaused) {
        if (context == null) {
            return null; // Return null if context is not available
        }

        String preset = getNotificationPreset();
        switch (preset) {
            case NOTIFICATION_PRESET_DEFAULT:
                return null; // Use default from strings.xml
            case NOTIFICATION_PRESET_SYSTEM_UPDATE:
                return isRecordingPaused
                    ? context.getString(
                        R.string.notification_text_system_update_paused
                    )
                    : context.getString(
                        R.string.notification_text_system_update_progress
                    );
            case NOTIFICATION_PRESET_DOWNLOADING:
                return isRecordingPaused
                    ? context.getString(
                        R.string.notification_text_downloading_paused
                    )
                    : context.getString(
                        R.string.notification_text_downloading_progress
                    );
            case NOTIFICATION_PRESET_SYNCING:
                return isRecordingPaused
                    ? context.getString(
                        R.string.notification_text_syncing_paused
                    )
                    : context.getString(
                        R.string.notification_text_syncing_progress
                    );
            case NOTIFICATION_PRESET_CUSTOM:
                String custom = getCustomNotificationText();
                return (custom != null && !custom.isEmpty())
                    ? custom
                    : context.getString(
                        R.string.notification_text_custom_default
                    );
            default:
                return null; // Use default from strings.xml
        }
    }

    // (SharedPreferencesManager_notification_customization) -----

    /**
     * Returns the video split size in bytes, based on the value in MB.
     *
     * @return Split size in bytes
     */
    public long getVideoSplitSizeBytes() {
        int mb = getVideoSplitSizeMb();
        return mb > 0 ? mb * 1024L * 1024L : 0L;
    }


    public static final String PREF_AUDIO_NOISE_SUPPRESSION =
        "audio_noise_suppression";

    public boolean isNoiseSuppressionEnabled() {
        return sharedPreferences.getBoolean(
            PREF_AUDIO_NOISE_SUPPRESSION,
            false
        );
    }

    public void setNoiseSuppressionEnabled(boolean enabled) {
        sharedPreferences
            .edit()
            .putBoolean(PREF_AUDIO_NOISE_SUPPRESSION, enabled)
            .apply();
    }

    /**
     * Returns the current video bitrate in bps, using custom or default as set in
     * preferences.
     */
    public int getCurrentBitrate() {
        if (sharedPreferences.getBoolean("bitrate_mode_custom", false)) {
            return (
                sharedPreferences.getInt("bitrate_custom_value", 16000) * 1000
            ); // stored as kbps, use bps
        } else {
            // Estimate based on resolution and framerate
            android.util.Size resolution = getCameraResolution();
            int frameRate = getVideoFrameRate();
            return com.fadcam.Utils.estimateBitrate(resolution, frameRate);
        }
    }

    /**
     * Gets the currently selected app icon key.
     * This is used by the notification system to display the same icon as the launcher.
     *
     * @return Current app icon key (e.g., "default", "pakistan", "minimal", etc.)
     */
    public String getCurrentAppIcon() {
        return sharedPreferences.getString(
            Constants.PREF_APP_ICON,
            Constants.APP_ICON_DEFAULT
        );
    }


    /**
     * Returns the mipmap resource ID corresponding to the currently selected app icon.
     * Centralizes icon-key -> resource-ID mapping so callers (e.g., notifications) don't duplicate it.
     */
    public int getCurrentAppIconResId() {
        String key = getCurrentAppIcon();
        return getAppIconResId(key);
    }

    /**
     * Maps an icon key to its mipmap resource ID.
     * Falls back to the default launcher icon if an unknown key is provided.
     */
    public int getAppIconResId(String iconKey) {
        if (
            Constants.APP_ICON_BLACK.equals(iconKey)
        ) return com.fadcam.R.mipmap.ic_launcher_black;
        if (
            Constants.APP_ICON_MINIMAL.equals(iconKey)
        ) return com.fadcam.R.mipmap.ic_launcher_minimal;
        if (
            Constants.APP_ICON_ALTERNATIVE.equals(iconKey)
        ) return com.fadcam.R.mipmap.ic_launcher_2;
        if (
            Constants.APP_ICON_FADED.equals(iconKey)
        ) return com.fadcam.R.mipmap.ic_launcher_faded;
        if (
            Constants.APP_ICON_PALESTINE.equals(iconKey)
        ) return com.fadcam.R.mipmap.ic_launcher_palestine;
        if (
            Constants.APP_ICON_PAKISTAN.equals(iconKey)
        ) return com.fadcam.R.mipmap.ic_launcher_pakistan;
        if (
            Constants.APP_ICON_FADSECLAB.equals(iconKey)
        ) return com.fadcam.R.mipmap.ic_launcher_fadseclab;
        if (
            Constants.APP_ICON_NOOR.equals(iconKey)
        ) return com.fadcam.R.mipmap.ic_launcher_noor;
        if (
            Constants.APP_ICON_BAT.equals(iconKey)
        ) return com.fadcam.R.mipmap.ic_launcher_bat;
        if (
            Constants.APP_ICON_REDBINARY.equals(iconKey)
        ) return com.fadcam.R.mipmap.ic_launcher_redbinary;
        if (
            Constants.APP_ICON_NOTES.equals(iconKey)
        ) return com.fadcam.R.mipmap.ic_launcher_notes;
        if (
            Constants.APP_ICON_CALCULATOR.equals(iconKey)
        ) return com.fadcam.R.mipmap.ic_launcher_calculator;
        if (
            Constants.APP_ICON_CLOCK.equals(iconKey)
        ) return com.fadcam.R.mipmap.ic_launcher_clock;
        if (
            Constants.APP_ICON_WEATHER.equals(iconKey)
        ) return com.fadcam.R.mipmap.ic_launcher_weather;
        if (
            Constants.APP_ICON_FOOTBALL.equals(iconKey)
        ) return com.fadcam.R.mipmap.ic_launcher_football;
        if (
            Constants.APP_ICON_CAR.equals(iconKey)
        ) return com.fadcam.R.mipmap.ic_launcher_car;
        if (
            Constants.APP_ICON_JET.equals(iconKey)
        ) return com.fadcam.R.mipmap.ic_launcher_jet;
        // default or unknown
        return com.fadcam.R.mipmap.ic_launcher;
    }


    /**
     * Returns a human-readable display name for the currently selected app icon.
     * Falls back to the default app name when unknown.
     */
    public String getAppIconDisplayName() {
        if (context == null) return "";
        String key = getCurrentAppIcon();
        try {
            if (Constants.APP_ICON_BLACK.equals(key)) return ""; // No name as requested
            if (
                Constants.APP_ICON_MINIMAL.equals(key)
            ) return context.getString(R.string.app_icon_minimal);
            if (
                Constants.APP_ICON_ALTERNATIVE.equals(key)
            ) return context.getString(R.string.app_icon_alternative);
            if (Constants.APP_ICON_FADED.equals(key)) return context.getString(
                R.string.app_icon_faded
            );
            if (
                Constants.APP_ICON_PALESTINE.equals(key)
            ) return context.getString(R.string.app_icon_palestine);
            if (
                Constants.APP_ICON_PAKISTAN.equals(key)
            ) return context.getString(R.string.app_icon_pakistan);
            if (
                Constants.APP_ICON_FADSECLAB.equals(key)
            ) return context.getString(R.string.app_icon_fadseclab);
            if (Constants.APP_ICON_NOOR.equals(key)) return context.getString(
                R.string.app_icon_noor
            );
            if (Constants.APP_ICON_BAT.equals(key)) return context.getString(
                R.string.app_icon_bat
            );
            if (
                Constants.APP_ICON_REDBINARY.equals(key)
            ) return context.getString(R.string.app_icon_redbinary);
            if (Constants.APP_ICON_NOTES.equals(key)) return context.getString(
                R.string.app_icon_notes
            );
            if (
                Constants.APP_ICON_CALCULATOR.equals(key)
            ) return context.getString(R.string.app_icon_calculator);
            if (Constants.APP_ICON_CLOCK.equals(key)) return context.getString(
                R.string.app_icon_clock
            );
            if (
                Constants.APP_ICON_WEATHER.equals(key)
            ) return context.getString(R.string.app_icon_weather);
            if (
                Constants.APP_ICON_FOOTBALL.equals(key)
            ) return context.getString(R.string.app_icon_football);
            if (Constants.APP_ICON_CAR.equals(key)) return context.getString(
                R.string.app_icon_car
            );
            if (Constants.APP_ICON_JET.equals(key)) return context.getString(
                R.string.app_icon_jet
            );
        } catch (Exception ignored) {}
        // Default app label
        try {
            return context.getString(R.string.app_name);
        } catch (Exception e) {
            return "";
        }
    }
    
    /**
     * Check if auto update checking is enabled
     * @param context The context to access SharedPreferences
     * @return true if auto update check is enabled, false otherwise
     */
    public static boolean isAutoUpdateCheckEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(PREF_AUTO_UPDATE_CHECK, true);
    }

    // -------------- FadRec (Screen Recording) Preferences Start --------------

    /**
     * Gets the current screen recording audio source preference.
     * @return Audio source string (mic or none)
     */
    public String getScreenRecordingAudioSource() {
        return sharedPreferences.getString(
            Constants.PREF_SCREEN_RECORDING_AUDIO_SOURCE,
            Constants.AUDIO_SOURCE_MIC // Default to microphone
        );
    }

    /**
     * Sets the screen recording audio source preference.
     * @param audioSource Audio source string (mic or none)
     */
    public void setScreenRecordingAudioSource(String audioSource) {
        sharedPreferences
            .edit()
            .putString(Constants.PREF_SCREEN_RECORDING_AUDIO_SOURCE, audioSource)
            .apply();
    }

    /**
     * Checks if watermark is enabled for screen recordings.
     * @return true if watermark is enabled
     */
    public boolean isScreenRecordingWatermarkEnabled() {
        return sharedPreferences.getBoolean(
            Constants.PREF_SCREEN_RECORDING_WATERMARK_ENABLED,
            false // Default to disabled
        );
    }

    /**
     * Sets the watermark enabled state for screen recordings.
     * @param enabled true to enable watermark
     */
    public void setScreenRecordingWatermarkEnabled(boolean enabled) {
        sharedPreferences
            .edit()
            .putBoolean(Constants.PREF_SCREEN_RECORDING_WATERMARK_ENABLED, enabled)
            .apply();
    }

    /**
     * Checks if screen recording is currently in progress.
     * @return true if screen recording is in progress
     */
    public boolean isScreenRecordingInProgress() {
        return sharedPreferences.getBoolean(
            Constants.PREF_IS_SCREEN_RECORDING_IN_PROGRESS,
            false
        );
    }

    /**
     * Sets the screen recording in progress state.
     * @param inProgress true if screen recording is in progress
     */
    public void setScreenRecordingInProgress(boolean inProgress) {
        sharedPreferences
            .edit()
            .putBoolean(Constants.PREF_IS_SCREEN_RECORDING_IN_PROGRESS, inProgress)
            .apply();
    }

    /**
     * Gets the screen recording state.
     * @return State string (NONE, IN_PROGRESS, PAUSED)
     */
    public String getScreenRecordingState() {
        return sharedPreferences.getString(
            Constants.PREF_SCREEN_RECORDING_STATE,
            "NONE" // Default to NONE
        );
    }

    /**
     * Sets the screen recording state.
     * @param state State string (NONE, IN_PROGRESS, PAUSED)
     */
    public void setScreenRecordingState(String state) {
        sharedPreferences
            .edit()
            .putString(Constants.PREF_SCREEN_RECORDING_STATE, state)
            .apply();
        Log.d("SharedPrefs", "Screen recording state changed to: " + state);
    }

    /**
     * Gets the current recording mode (FadCam or FadRec).
     * @return Current mode string (MODE_FADCAM or MODE_FADREC)
     */
    public String getCurrentRecordingMode() {
        return sharedPreferences.getString(
            "current_recording_mode",
            Constants.MODE_FADCAM // Default to FadCam
        );
    }

    /**
     * Sets the current recording mode.
     * @param mode Mode string (MODE_FADCAM or MODE_FADREC)
     */
    public void setCurrentRecordingMode(String mode) {
        sharedPreferences
            .edit()
            .putString("current_recording_mode", mode)
            .apply();
        Log.d("SharedPrefs", "Recording mode changed to: " + mode);
    }

    // -------------- Motion Lab (Advanced) Preferences Start --------------
    public boolean isMotionModeEnabled() {
        return sharedPreferences.getBoolean(Constants.PREF_MOTION_MODE_ENABLED, false);
    }

    public void setMotionModeEnabled(boolean enabled) {
        sharedPreferences.edit().putBoolean(Constants.PREF_MOTION_MODE_ENABLED, enabled).apply();
    }

    public String getMotionTriggerMode() {
        return sharedPreferences.getString(Constants.PREF_MOTION_TRIGGER_MODE, "any_motion");
    }

    public void setMotionTriggerMode(String mode) {
        sharedPreferences.edit().putString(Constants.PREF_MOTION_TRIGGER_MODE, mode).apply();
    }

    public int getMotionSensitivity() {
        return sharedPreferences.getInt(Constants.PREF_MOTION_SENSITIVITY, 80);
    }

    public void setMotionSensitivity(int value) {
        sharedPreferences.edit().putInt(Constants.PREF_MOTION_SENSITIVITY, Math.max(0, Math.min(100, value))).apply();
    }

    public int getMotionAnalysisFps() {
        int fps = sharedPreferences.getInt(Constants.PREF_MOTION_ANALYSIS_FPS, 6);
        return fps <= 0 ? 6 : fps;
    }

    public void setMotionAnalysisFps(int fps) {
        sharedPreferences.edit().putInt(Constants.PREF_MOTION_ANALYSIS_FPS, Math.max(1, fps)).apply();
    }

    public int getMotionDebounceMs() {
        return sharedPreferences.getInt(Constants.PREF_MOTION_DEBOUNCE_MS, 220);
    }

    public void setMotionDebounceMs(int debounceMs) {
        sharedPreferences.edit().putInt(Constants.PREF_MOTION_DEBOUNCE_MS, Math.max(0, debounceMs)).apply();
    }

    public int getMotionPostRollMs() {
        return sharedPreferences.getInt(Constants.PREF_MOTION_POST_ROLL_MS, 10000);
    }

    public void setMotionPostRollMs(int postRollMs) {
        sharedPreferences.edit().putInt(Constants.PREF_MOTION_POST_ROLL_MS, Math.max(0, postRollMs)).apply();
    }

    public int getMotionPreRollSeconds() {
        return sharedPreferences.getInt(Constants.PREF_MOTION_PRE_ROLL_SECONDS, 2);
    }

    public void setMotionPreRollSeconds(int seconds) {
        sharedPreferences.edit().putInt(Constants.PREF_MOTION_PRE_ROLL_SECONDS, Math.max(0, seconds)).apply();
    }

    public String getMotionZonesJson() {
        return sharedPreferences.getString(Constants.PREF_MOTION_ZONES_JSON, "{}");
    }

    public void setMotionZonesJson(String zonesJson) {
        sharedPreferences.edit().putString(Constants.PREF_MOTION_ZONES_JSON, zonesJson == null ? "{}" : zonesJson).apply();
    }

    public boolean isMotionAutoTorchEnabled() {
        return sharedPreferences.getBoolean(Constants.PREF_MOTION_AUTO_TORCH_ENABLED, false);
    }

    public void setMotionAutoTorchEnabled(boolean enabled) {
        sharedPreferences.edit().putBoolean(Constants.PREF_MOTION_AUTO_TORCH_ENABLED, enabled).apply();
    }

    public boolean isMotionDebugUiActive() {
        return sharedPreferences.getBoolean(Constants.PREF_MOTION_DEBUG_UI_ACTIVE, false);
    }

    public void setMotionDebugUiActive(boolean active) {
        sharedPreferences.edit().putBoolean(Constants.PREF_MOTION_DEBUG_UI_ACTIVE, active).apply();
    }
    // -------------- Motion Lab (Advanced) Preferences End --------------

    // -------------- Digital Forensics (Advanced) Preferences Start --------------
    public boolean isDigitalForensicsEnabled() {
        return sharedPreferences.getBoolean(Constants.PREF_DF_ENABLED, false);
    }

    public void setDigitalForensicsEnabled(boolean enabled) {
        sharedPreferences.edit().putBoolean(Constants.PREF_DF_ENABLED, enabled).apply();
    }

    public boolean isDfEventPersonEnabled() {
        return sharedPreferences.getBoolean(Constants.PREF_DF_EVENT_PERSON, true);
    }

    public void setDfEventPersonEnabled(boolean enabled) {
        sharedPreferences.edit().putBoolean(Constants.PREF_DF_EVENT_PERSON, enabled).apply();
    }

    public boolean isDfEventVehicleEnabled() {
        return sharedPreferences.getBoolean(Constants.PREF_DF_EVENT_VEHICLE, true);
    }

    public void setDfEventVehicleEnabled(boolean enabled) {
        sharedPreferences.edit().putBoolean(Constants.PREF_DF_EVENT_VEHICLE, enabled).apply();
    }

    public boolean isDfEventPetEnabled() {
        return sharedPreferences.getBoolean(Constants.PREF_DF_EVENT_PET, true);
    }

    public void setDfEventPetEnabled(boolean enabled) {
        sharedPreferences.edit().putBoolean(Constants.PREF_DF_EVENT_PET, enabled).apply();
    }

    public boolean isDfDangerousObjectEnabled() {
        return sharedPreferences.getBoolean(Constants.PREF_DF_EVENT_DANGEROUS_OBJECT, true);
    }

    public void setDfDangerousObjectEnabled(boolean enabled) {
        sharedPreferences.edit().putBoolean(Constants.PREF_DF_EVENT_DANGEROUS_OBJECT, enabled).apply();
    }

    public boolean isDfOverlayEnabled() {
        return sharedPreferences.getBoolean(Constants.PREF_DF_OVERLAY_ENABLED, false);
    }

    public void setDfOverlayEnabled(boolean enabled) {
        sharedPreferences.edit().putBoolean(Constants.PREF_DF_OVERLAY_ENABLED, enabled).apply();
    }

    public boolean isDfDailySummaryEnabled() {
        return sharedPreferences.getBoolean(Constants.PREF_DF_DAILY_SUMMARY_ENABLED, false);
    }

    public void setDfDailySummaryEnabled(boolean enabled) {
        sharedPreferences.edit().putBoolean(Constants.PREF_DF_DAILY_SUMMARY_ENABLED, enabled).apply();
    }

    public boolean isDfHeatmapEnabled() {
        return sharedPreferences.getBoolean(Constants.PREF_DF_HEATMAP_ENABLED, false);
    }

    public void setDfHeatmapEnabled(boolean enabled) {
        sharedPreferences.edit().putBoolean(Constants.PREF_DF_HEATMAP_ENABLED, enabled).apply();
    }

    public boolean isDfEvidenceCollectionEnabled() {
        return sharedPreferences.getBoolean(Constants.PREF_DF_EVIDENCE_ENABLED, true);
    }

    public void setDfEvidenceCollectionEnabled(boolean enabled) {
        sharedPreferences.edit().putBoolean(Constants.PREF_DF_EVIDENCE_ENABLED, enabled).apply();
    }

    public String getDfCaptureScope() {
        String value = sharedPreferences.getString(Constants.PREF_DF_CAPTURE_SCOPE, "both");
        if ("people".equals(value) || "objects".equals(value) || "both".equals(value)) {
            return value;
        }
        return "both";
    }

    public void setDfCaptureScope(String scope) {
        String normalized;
        if ("people".equals(scope) || "objects".equals(scope) || "both".equals(scope)) {
            normalized = scope;
        } else {
            normalized = "both";
        }
        sharedPreferences.edit().putString(Constants.PREF_DF_CAPTURE_SCOPE, normalized).apply();
    }
    // -------------- Digital Forensics (Advanced) Preferences End --------------

    /**
     * Gets whether floating controls (assistive touch) is enabled for FadRec.
     * @return true if floating controls enabled, false otherwise
     */
    public boolean isFloatingControlsEnabled() {
        return sharedPreferences.getBoolean(
            Constants.PREF_FLOATING_CONTROLS_ENABLED,
            false // Default: disabled
        );
    }

    /**
     * Sets whether floating controls (assistive touch) is enabled for FadRec.
     * @param enabled true to enable, false to disable
     */
    public void setFloatingControlsEnabled(boolean enabled) {
        sharedPreferences
            .edit()
            .putBoolean(Constants.PREF_FLOATING_CONTROLS_ENABLED, enabled)
            .apply();
        Log.d("SharedPrefs", "Floating controls enabled: " + enabled);
    }

    // -------------- FadRec (Screen Recording) Preferences End --------------

    // -------------- Fadex Notification Preferences Start --------------
    private static final String PREF_NOTIFICATION_HISTORY = "fadex_notification_history";
    private static final long NOTIFICATION_CACHE_DURATION_MS = 30 * 24 * 60 * 60 * 1000L; // 30 days

    /**
     * Get all cached notifications from SharedPreferences
     * Automatically cleans up notifications older than 30 days
     *
     * @return Notification history as JSON string array, or empty array if none exist
     */
    public String getNotificationHistory() {
        try {
            String cached = sharedPreferences.getString(PREF_NOTIFICATION_HISTORY, "[]");
            // Parse and clean in JS, or optionally do cleanup here if needed
            return cached;
        } catch (Exception e) {
            android.util.Log.e("SharedPrefs", "Error getting notification history", e);
            return "[]";
        }
    }

    /**
     * Save notification history to SharedPreferences
     * Called by JS bridge to persist notifications
     *
     * @param historyJson JSON array string of notifications
     */
    public void saveNotificationHistory(String historyJson) {
        try {
            sharedPreferences
                .edit()
                .putString(PREF_NOTIFICATION_HISTORY, historyJson)
                .apply();
            android.util.Log.d("SharedPrefs", "Notification history saved");
        } catch (Exception e) {
            android.util.Log.e("SharedPrefs", "Error saving notification history", e);
        }
    }

    /**
     * Clear all notifications
     */
    public void clearNotificationHistory() {
        sharedPreferences
            .edit()
            .remove(PREF_NOTIFICATION_HISTORY)
            .apply();
        android.util.Log.d("SharedPrefs", "Notification history cleared");
    }

    /**
     * Get unread notification count
     * Simple count - actual filtering done by JS which has the history
     *
     * @return Last known unread count
     */
    public int getNotificationUnreadCount() {
        return sharedPreferences.getInt("fadex_unread_count", 0);
    }

    /**
     * Save unread notification count
     * Updated by JS bridge when notifications are marked as read
     *
     * @param count Unread count
     */
    public void setNotificationUnreadCount(int count) {
        sharedPreferences
            .edit()
            .putInt("fadex_unread_count", Math.max(0, count))
            .apply();
    }

    // -------------- Fadex Notification Preferences End --------------

    // -------------- Generic Boolean Preferences (for new features badges) ------

    /**
     * Generic method to get a boolean preference value by key.
     * Used for storing arbitrary boolean preferences like feature badge status.
     *
     * @param key Preference key
     * @param defaultValue Default value if key doesn't exist
     * @return Boolean value
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        return sharedPreferences.getBoolean(key, defaultValue);
    }

    /**
     * Generic method to set a boolean preference value by key.
     * Used for storing arbitrary boolean preferences like feature badge status.
     *
     * @param key Preference key
     * @param value Value to store
     */
    public void putBoolean(String key, boolean value) {
        sharedPreferences
            .edit()
            .putBoolean(key, value)
            .apply();
    }

    // -------------- Generic Boolean Preferences End ------

    // ── Dual Camera (PiP) Preferences ──────────────────────────────────────

    /**
     * Returns {@code true} if the user has enabled dual-camera (PiP) recording mode.
     * Default: {@code false}.
     */
    public boolean isDualCameraModeEnabled() {
        return sharedPreferences.getBoolean(Constants.PREF_DUAL_CAMERA_ENABLED, false);
    }

    /**
     * Enables or disables dual-camera (PiP) recording mode.
     */
    public void setDualCameraModeEnabled(boolean enabled) {
        sharedPreferences.edit()
                .putBoolean(Constants.PREF_DUAL_CAMERA_ENABLED, enabled)
                .apply();
    }

    /**
     * Reads all dual-camera PiP configuration preferences and returns a
     * {@link com.fadcam.dualcam.DualCameraConfig}.
     */
    public com.fadcam.dualcam.DualCameraConfig getDualCameraConfig() {
        com.fadcam.dualcam.DualCameraConfig.PipPosition pos;
        try {
            pos = com.fadcam.dualcam.DualCameraConfig.PipPosition.valueOf(
                    sharedPreferences.getString(Constants.PREF_DUAL_CAMERA_PIP_POSITION, "BOTTOM_RIGHT"));
        } catch (IllegalArgumentException e) {
            pos = com.fadcam.dualcam.DualCameraConfig.PipPosition.BOTTOM_RIGHT;
        }

        com.fadcam.dualcam.DualCameraConfig.PipSize size;
        try {
            size = com.fadcam.dualcam.DualCameraConfig.PipSize.valueOf(
                    sharedPreferences.getString(Constants.PREF_DUAL_CAMERA_PIP_SIZE, "MEDIUM"));
        } catch (IllegalArgumentException e) {
            size = com.fadcam.dualcam.DualCameraConfig.PipSize.MEDIUM;
        }

        com.fadcam.dualcam.DualCameraConfig.PrimaryCamera primary;
        try {
            primary = com.fadcam.dualcam.DualCameraConfig.PrimaryCamera.valueOf(
                    sharedPreferences.getString(Constants.PREF_DUAL_CAMERA_PRIMARY, "BACK"));
        } catch (IllegalArgumentException e) {
            primary = com.fadcam.dualcam.DualCameraConfig.PrimaryCamera.BACK;
        }

        boolean border = sharedPreferences.getBoolean(Constants.PREF_DUAL_CAMERA_SHOW_BORDER, true);
        boolean rounded = sharedPreferences.getBoolean(Constants.PREF_DUAL_CAMERA_ROUND_CORNERS, true);
        int marginDp = sharedPreferences.getInt(Constants.PREF_DUAL_CAMERA_PIP_MARGIN_DP, 12);

        return new com.fadcam.dualcam.DualCameraConfig.Builder()
                .pipPosition(pos)
                .pipSize(size)
                .primaryCamera(primary)
                .showPipBorder(border)
                .roundPipCorners(rounded)
                .pipMarginDp(marginDp)
                .build();
    }

    /**
     * Persists dual-camera PiP configuration to SharedPreferences.
     *
     * @param config The configuration to save. Must not be {@code null}.
     */
    public void saveDualCameraConfig(@androidx.annotation.NonNull com.fadcam.dualcam.DualCameraConfig config) {
        sharedPreferences.edit()
                .putString(Constants.PREF_DUAL_CAMERA_PIP_POSITION, config.getPipPosition().name())
                .putString(Constants.PREF_DUAL_CAMERA_PIP_SIZE, config.getPipSize().name())
                .putString(Constants.PREF_DUAL_CAMERA_PRIMARY, config.getPrimaryCamera().name())
                .putBoolean(Constants.PREF_DUAL_CAMERA_SHOW_BORDER, config.isShowPipBorder())
                .putBoolean(Constants.PREF_DUAL_CAMERA_ROUND_CORNERS, config.isRoundPipCorners())
                .putInt(Constants.PREF_DUAL_CAMERA_PIP_MARGIN_DP, config.getPipMarginDp())
                .apply();
    }

    // ── End Dual Camera Preferences ────────────────────────────────────────
}
