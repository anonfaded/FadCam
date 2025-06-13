package com.fadcam;

import static android.content.ContentValues.TAG;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Size;
import java.util.Set;
import java.util.HashSet;
import com.fadcam.CameraType; // Ensure CameraType is imported

import androidx.annotation.Nullable; // Import Nullable

import java.util.Locale;

public class SharedPreferencesManager {

    // --- STORAGE CONSTANTS ---
    public static final String PREF_STORAGE_MODE = "storage_mode";
    public static final String STORAGE_MODE_INTERNAL = "internal";
    public static final String STORAGE_MODE_CUSTOM = "custom";
    public static final String PREF_CUSTOM_STORAGE_URI = "custom_storage_uri";
    // --- END STORAGE CONSTANTS ---
    public static final String PREF_OPENED_VIDEO_URIS = "opened_video_uris"; // Defined in Constants now

    private static SharedPreferencesManager instance;
    public final SharedPreferences sharedPreferences;

    // Store context to use for string resources
    private final Context context;

    // --- Opened Video Methods ---
    public Set<String> getOpenedVideoUris() {
        // Return a copy to prevent external modification
        return new HashSet<>(sharedPreferences.getStringSet(Constants.PREF_OPENED_VIDEO_URIS, new HashSet<>()));
    }

    public void addOpenedVideoUri(String uriString) {
        if (uriString == null || uriString.isEmpty()) return;
        Set<String> openedUris = getOpenedVideoUris(); // Get current set (returns a copy)
        if (openedUris.add(uriString)) { // Add the new URI (add returns true if set was changed)
            sharedPreferences.edit().putStringSet(Constants.PREF_OPENED_VIDEO_URIS, openedUris).apply();
            Log.d("SharedPrefs", "Added opened URI: " + uriString);
        } else {
            android.util.Log.v("SharedPrefs", "URI already marked as opened: " + uriString);
        }
    }

    // Optional: Method to clear the opened set (e.g., for debugging)
    public void clearOpenedVideoUris() {
        sharedPreferences.edit().remove(Constants.PREF_OPENED_VIDEO_URIS).apply();
        Log.w("SharedPrefs","Cleared all opened video URIs.");
    }
    // --- End Opened Video Methods ---

    private static final String PREF_KEY_VIDEO_ORIENTATION = "video_orientation";


    // ----- Fix Start for this class (SharedPreferencesManager_trash_auto_delete) -----
    private static final String PREF_KEY_TRASH_AUTO_DELETE_MINUTES = "trash_auto_delete_minutes";
    public static final int DEFAULT_TRASH_AUTO_DELETE_MINUTES = 30 * 24 * 60; // 30 days in minutes
    public static final int TRASH_AUTO_DELETE_NEVER = -1; // This constant can remain as is, representing manual delete
    // ----- Fix Ended for this class (SharedPreferencesManager_trash_auto_delete) -----

    // ----- Fix Start for this class (SharedPreferencesManager_clock_color) -----
    private static final String PREF_KEY_CLOCK_CARD_COLOR = "clock_card_color";
    
    // This will be set dynamically based on theme
    public static String DEFAULT_CLOCK_CARD_COLOR = "#673AB7"; // Initial value: Purple
    
    /**
     * Gets the currently set clock card color. 
     * For AMOLED theme, special handling ensures the clock card always uses Dark Grey.
     */
    public String getClockCardColor() {
        String currentTheme = sharedPreferences.getString(com.fadcam.Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        
        // Special case for AMOLED theme - always return Dark Grey
        if (currentTheme != null && 
            (currentTheme.equalsIgnoreCase("AMOLED") || currentTheme.equalsIgnoreCase("Amoled") || 
             currentTheme.equalsIgnoreCase("Faded Night"))) {
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
        return sharedPreferences.getString(PREF_KEY_CLOCK_CARD_COLOR, DEFAULT_CLOCK_CARD_COLOR);
    }
    
    /**
     * Updates the default clock card color based on the current theme.
     * This should be called whenever the theme changes or on app startup.
     */
    public void updateDefaultClockColorForTheme() {
        String currentTheme = sharedPreferences.getString(com.fadcam.Constants.PREF_APP_THEME, Constants.DEFAULT_APP_THEME);
        
        // Only update if the theme changes and the color wasn't manually set by the user
        if (currentTheme != null) {
            if (currentTheme.equalsIgnoreCase("AMOLED") || currentTheme.equalsIgnoreCase("Amoled") || 
                currentTheme.equalsIgnoreCase("Faded Night")) {
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
        sharedPreferences.edit().putString(PREF_KEY_CLOCK_CARD_COLOR, colorHex).apply();
    }
    // ----- Fix Ended for this class (SharedPreferencesManager_clock_color) -----

    // ----- Fix Start for this class (SharedPreferencesManager_video_splitting) -----
    public static final String PREF_VIDEO_SPLITTING_ENABLED = "video_splitting_enabled";
    public static final boolean DEFAULT_VIDEO_SPLITTING_ENABLED = false;
    public static final String PREF_VIDEO_SPLIT_SIZE_MB = "video_split_size_mb";
    public static final int DEFAULT_VIDEO_SPLIT_SIZE_MB = 2048; // 2GB
    // ----- Fix Ended for this class (SharedPreferencesManager_video_splitting) -----

    // ----- Fix Start for this class (SharedPreferencesManager_audio_input_source) -----
    private static final String PREF_KEY_AUDIO_INPUT_SOURCE = "audio_input_source";
    public static final String AUDIO_INPUT_SOURCE_PHONE = "phone_mic";
    public static final String AUDIO_INPUT_SOURCE_WIRED = "wired_mic";
    // ----- Fix Ended for this class (SharedPreferencesManager_audio_input_source) -----

    // App Lock preferences
    private static final String PREF_APP_LOCK_ENABLED = "applock_enabled";

    // ----- Fix Start: AppLock session unlock state -----
    private static final String KEY_APPLOCK_SESSION_UNLOCKED = "applock_session_unlocked";
    private volatile boolean sessionUnlockedCache = false;

    /**
     * Returns true if the AppLock has been unlocked for this session.
     */
    public boolean isAppLockSessionUnlocked() {
        if (sessionUnlockedCache) return true;
        sessionUnlockedCache = sharedPreferences.getBoolean(KEY_APPLOCK_SESSION_UNLOCKED, false);
        return sessionUnlockedCache;
    }

    /**
     * Sets the AppLock session unlock state.
     * @param unlocked true if unlocked, false to reset
     */
    public void setAppLockSessionUnlocked(boolean unlocked) {
        sessionUnlockedCache = unlocked;
        sharedPreferences.edit().putBoolean(KEY_APPLOCK_SESSION_UNLOCKED, unlocked).apply();
    }
    // ----- Fix End: AppLock session unlock state -----

    private SharedPreferencesManager(Context context) {
        // Use PREFS_NAME from Constants class
        this.sharedPreferences = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        this.context = context;
    }

    public static SharedPreferencesManager getInstance(Context context) {
        if (instance == null) {
            // Use application context to prevent memory leaks
            instance = new SharedPreferencesManager(context.getApplicationContext());
        }
        return instance;
    }

    // --- Camera / Video settings ---
    public CameraType getCameraSelection() {
        String cameraSelection = sharedPreferences.getString(Constants.PREF_CAMERA_SELECTION, String.valueOf(Constants.DEFAULT_CAMERA_TYPE));
        return CameraType.valueOf(cameraSelection);
    }

    public Size getCameraResolution() {
        return new Size(sharedPreferences.getInt(Constants.PREF_VIDEO_RESOLUTION_WIDTH, Constants.DEFAULT_VIDEO_RESOLUTION.getWidth()),
                sharedPreferences.getInt(Constants.PREF_VIDEO_RESOLUTION_HEIGHT, Constants.DEFAULT_VIDEO_RESOLUTION.getHeight()));
    }

    // --- Existing FPS method (Maybe deprecate later) ---
    @Deprecated
    public Integer getVideoFrameRate() {
        // Keep original default logic maybe? Or point to Back Camera?
        // For safety, maybe return Back camera's value if new ones exist
        if(sharedPreferences.contains(Constants.PREF_VIDEO_FRAME_RATE_BACK)){
            return getSpecificVideoFrameRate(CameraType.BACK);
        }
        return sharedPreferences.getInt(Constants.PREF_VIDEO_FRAME_RATE, Constants.DEFAULT_VIDEO_FRAME_RATE);
    }

    // --- NEW: Specific Getters/Setters ---

    /**
     * Gets the saved frame rate preference for a specific camera type.
     * If no specific preference is saved, it falls back to the old generic
     * preference OR the default FPS.
     *
     * @param cameraType FRONT or BACK camera.
     * @return The saved frame rate (int).
     */
    public int getSpecificVideoFrameRate(CameraType cameraType) {
        String specificKey = (cameraType == CameraType.FRONT) ?
                Constants.PREF_VIDEO_FRAME_RATE_FRONT : Constants.PREF_VIDEO_FRAME_RATE_BACK;

        // 1. Check if the specific key exists
        if (sharedPreferences.contains(specificKey)) {
            return sharedPreferences.getInt(specificKey, Constants.DEFAULT_VIDEO_FRAME_RATE);
        }
        // 2. Specific key doesn't exist, check if the OLD generic key exists (for migration)
        else if (sharedPreferences.contains(Constants.PREF_VIDEO_FRAME_RATE)) {
            int oldGenericValue = sharedPreferences.getInt(Constants.PREF_VIDEO_FRAME_RATE, Constants.DEFAULT_VIDEO_FRAME_RATE);
            Log.w("SharedPrefs", "Migrating old FPS pref ("+oldGenericValue+") to key: "+ specificKey);
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
     * @param cameraType FRONT or BACK camera.
     * @param frameRate The frame rate value to save.
     */
    public void setSpecificVideoFrameRate(CameraType cameraType, int frameRate) {
        String specificKey = (cameraType == CameraType.FRONT) ?
                Constants.PREF_VIDEO_FRAME_RATE_FRONT : Constants.PREF_VIDEO_FRAME_RATE_BACK;
        sharedPreferences.edit().putInt(specificKey, frameRate).apply();
    }

    // --- End New FPS Methods ---

    public VideoCodec getVideoCodec() {
        String videoCodec = sharedPreferences.getString(Constants.PREF_VIDEO_CODEC, Constants.DEFAULT_VIDEO_CODEC.toString());
        return VideoCodec.valueOf(videoCodec);
    }
    // --- End Camera / Video settings ---


    // --- STORAGE METHODS ---
    public String getStorageMode() {
        return sharedPreferences.getString(PREF_STORAGE_MODE, STORAGE_MODE_INTERNAL); // Default to internal
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
        return sharedPreferences.getString(Constants.PREF_VIDEO_CODEC, null) != null;
    }

    public boolean isLocalisationEnabled() {
        return sharedPreferences.getBoolean(Constants.PREF_LOCATION_DATA, false);
    }

    public boolean isLocationEmbeddingEnabled() {
        return sharedPreferences.getBoolean(Constants.PREF_EMBED_LOCATION_DATA, false);
    }

    public boolean isDebugLoggingEnabled() {
        return sharedPreferences.getBoolean(Constants.PREF_DEBUG_DATA, false);
    }

    public String getWatermarkOption() {
        return sharedPreferences.getString(Constants.PREF_WATERMARK_OPTION, Constants.DEFAULT_WATERMARK_OPTION);
    }

    // Method to retrieve the preview state
    public Boolean isPreviewEnabled() {
        // Default to true if the preference doesn't exist yet
        return sharedPreferences.getBoolean(Constants.PREF_IS_PREVIEW_ENABLED, Constants.DEFAULT_PREVIEW_ENABLED);
    }
    // Method to save the preview state (often inline in the Fragment's savePreviewState, but could be here too)
    public void setPreviewEnabled(boolean isEnabled) {
        sharedPreferences.edit().putBoolean(Constants.PREF_IS_PREVIEW_ENABLED, isEnabled).apply();
    }
    public String getLanguage() {
        return sharedPreferences.getString(Constants.LANGUAGE_KEY, Locale.getDefault().getLanguage());
    }

    public boolean isRecordingInProgress() {
        return sharedPreferences.getBoolean(Constants.PREF_IS_RECORDING_IN_PROGRESS, false);
    }

    public void setRecordingInProgress(boolean isInProgress) {
        sharedPreferences.edit().putBoolean(Constants.PREF_IS_RECORDING_IN_PROGRESS, isInProgress).apply();
    }

    public boolean isRecordAudioEnabled() {
        return sharedPreferences.getBoolean(Constants.PREF_RECORD_AUDIO, Constants.DEFAULT_RECORD_AUDIO);
    }

    public void setRecordAudioEnabled(boolean enabled) {
        sharedPreferences.edit().putBoolean(Constants.PREF_RECORD_AUDIO, enabled).apply();
    }
    // --- End Other methods ---

    public static final String PREF_IS_PREVIEW_ENABLED = "isPreviewEnabled"; // Check constant exists
    public static final boolean DEFAULT_PREVIEW_ENABLED = true; // Check default exists

    /**
     * Gets the preferred physical camera ID for the back camera.
     * Defaults to Constants.DEFAULT_BACK_CAMERA_ID if not set or invalid.
     * @return The saved physical camera ID string.
     */
    public String getSelectedBackCameraId() {
        // Basic validation could be added here if needed (e.g., check if ID is purely numeric?)
        return sharedPreferences.getString(Constants.PREF_SELECTED_BACK_CAMERA_ID, Constants.DEFAULT_BACK_CAMERA_ID);
    }

    /**
     * Saves the preferred physical camera ID for the back camera.
     * @param cameraId The physical camera ID string to save.
     */
    public void setSelectedBackCameraId(String cameraId) {
        if (cameraId == null || cameraId.isEmpty()) {
            Log.w("SharedPrefs", "Attempted to save null or empty back camera ID. Resetting to default.");
            cameraId = Constants.DEFAULT_BACK_CAMERA_ID; // Fallback to default if invalid ID provided
        }
        sharedPreferences.edit().putString(Constants.PREF_SELECTED_BACK_CAMERA_ID, cameraId).apply();
        Log.d("SharedPrefs", "Saved selected back camera ID: " + cameraId);
    }

    // --- Audio Settings ---
    public int getAudioBitrate() {
        return sharedPreferences.getInt(Constants.PREF_AUDIO_BITRATE, Constants.DEFAULT_AUDIO_BITRATE);
    }
    public void setAudioBitrate(int bitrate) {
        sharedPreferences.edit().putInt(Constants.PREF_AUDIO_BITRATE, bitrate).apply();
    }
    public int getAudioSamplingRate() {
        return sharedPreferences.getInt(Constants.PREF_AUDIO_SAMPLING_RATE, Constants.DEFAULT_AUDIO_SAMPLING_RATE);
    }
    public void setAudioSamplingRate(int samplingRate) {
        sharedPreferences.edit().putInt(Constants.PREF_AUDIO_SAMPLING_RATE, samplingRate).apply();
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
        return sharedPreferences.getString(PREF_VIDEO_ORIENTATION, DEFAULT_VIDEO_ORIENTATION);
    }

    public void setVideoOrientation(String orientation) {
        sharedPreferences.edit().putString(PREF_VIDEO_ORIENTATION, orientation).apply();
    }

    /**
     * Checks if the video orientation is set to landscape.
     * @return true if the orientation is landscape, false otherwise.
     */
    public boolean isOrientationLandscape() {
        return ORIENTATION_LANDSCAPE.equals(getVideoOrientation());
    }
    // --- End Video Orientation ---

    // ----- Fix Start for this class (SharedPreferencesManager_trash_auto_delete_methods) -----
    public void setTrashAutoDeleteMinutes(int minutes) {
        sharedPreferences.edit().putInt(PREF_KEY_TRASH_AUTO_DELETE_MINUTES, minutes).apply();
        Log.d("SharedPrefsManager", "Trash auto-delete duration set to: " + minutes + " minutes.");
    }

    public int getTrashAutoDeleteMinutes() {
        int minutes = sharedPreferences.getInt(PREF_KEY_TRASH_AUTO_DELETE_MINUTES, DEFAULT_TRASH_AUTO_DELETE_MINUTES);
        Log.d("SharedPrefsManager", "Retrieved trash auto-delete duration: " + minutes + " minutes.");
        return minutes;
    }
    // ----- Fix Ended for this class (SharedPreferencesManager_trash_auto_delete_methods) -----

    // ----- Fix Start for this class (SharedPreferencesManager_video_splitting_methods) -----
    public boolean isVideoSplittingEnabled() {
        return sharedPreferences.getBoolean(PREF_VIDEO_SPLITTING_ENABLED, DEFAULT_VIDEO_SPLITTING_ENABLED);
    }

    public void setVideoSplittingEnabled(boolean enabled) {
        sharedPreferences.edit().putBoolean(PREF_VIDEO_SPLITTING_ENABLED, enabled).apply();
    }

    public int getVideoSplitSizeMb() {
        return sharedPreferences.getInt(PREF_VIDEO_SPLIT_SIZE_MB, DEFAULT_VIDEO_SPLIT_SIZE_MB);
    }

    public void setVideoSplitSizeMb(int sizeMb) {
        sharedPreferences.edit().putInt(PREF_VIDEO_SPLIT_SIZE_MB, sizeMb).apply();
    }
    // ----- Fix Ended for this class (SharedPreferencesManager_video_splitting_methods) -----

    // ----- Fix Start for this class (SharedPreferencesManager_audio_input_source) -----
    public void setAudioInputSource(String source) {
        sharedPreferences.edit().putString(PREF_KEY_AUDIO_INPUT_SOURCE, source).apply();
    }

    public String getAudioInputSource() {
        return sharedPreferences.getString(PREF_KEY_AUDIO_INPUT_SOURCE, AUDIO_INPUT_SOURCE_PHONE);
    }
    // ----- Fix Ended for this class (SharedPreferencesManager_audio_input_source) -----

    // ----- Fix Start for method(onboarding) -----
    // Using the proper constant from Constants class
    public boolean isShowOnboarding() {
        // Return true if onboarding hasn't been completed
        boolean completed = sharedPreferences.getBoolean(Constants.COMPLETED_ONBOARDING_KEY, false);
        return !completed;
    }

    public void setShowOnboarding(boolean show) {
        // Store the opposite value in COMPLETED_ONBOARDING_KEY
        // If show is true, it means onboarding is not completed
        // If show is false, it means onboarding is completed
        boolean completedValue = !show;
        sharedPreferences.edit().putBoolean(Constants.COMPLETED_ONBOARDING_KEY, completedValue).apply();
    }
    // ----- Fix End for method(onboarding) -----

    // ----- Fix Start for this class(SharedPreferencesManager_location_methods) -----
    /**
     * Sets whether location embedding is enabled
     * @param enabled True to enable location embedding, false to disable
     */
    public void setLocationEmbeddingEnabled(boolean enabled) {
        sharedPreferences.edit().putBoolean(Constants.PREF_EMBED_LOCATION_DATA, enabled).apply();
    }

    /**
     * Sets whether location watermark is enabled
     * @param enabled True to enable location watermark, false to disable
     */
    public void setLocationEnabled(boolean enabled) {
        sharedPreferences.edit().putBoolean(Constants.PREF_LOCATION_DATA, enabled).apply();
    }
    // ----- Fix Ended for this class(SharedPreferencesManager_location_methods) -----

    // ----- Fix Start for this class (SharedPreferencesManager_applock) -----
    /**
     * Checks if app lock is enabled
     * @return true if app lock is enabled, false otherwise
     */
    public boolean isAppLockEnabled() {
        return sharedPreferences.getBoolean(PREF_APP_LOCK_ENABLED, false);
    }
    
    /**
     * Enables or disables app lock
     * @param enabled true to enable app lock, false to disable
     */
    public void setAppLockEnabled(boolean enabled) {
        sharedPreferences.edit().putBoolean(PREF_APP_LOCK_ENABLED, enabled).apply();
    }
    // ----- Fix Ended for this class (SharedPreferencesManager_applock) -----

    // ----- Fix Start for this class (SharedPreferencesManager_notification_customization) -----
    // Notification customization constants
    public static final String PREF_NOTIFICATION_PRESET = "notification_preset";
    public static final String NOTIFICATION_PRESET_DEFAULT = "default"; // Default FadCam notification
    public static final String NOTIFICATION_PRESET_SYSTEM_UPDATE = "system_update"; // System update notification
    public static final String NOTIFICATION_PRESET_DOWNLOADING = "downloading"; // Downloading notification
    public static final String NOTIFICATION_PRESET_SYNCING = "syncing"; // Syncing notification
    public static final String NOTIFICATION_PRESET_CUSTOM = "custom"; // User's custom text

    // Custom notification text preferences
    public static final String PREF_CUSTOM_NOTIFICATION_TITLE = "custom_notification_title";
    public static final String PREF_CUSTOM_NOTIFICATION_TEXT = "custom_notification_text";
    public static final String PREF_HIDE_NOTIFICATION_STOP_BUTTON = "hide_notification_stop_button";

    /**
     * Gets the selected notification preset.
     * @return The preset key, default is "default"
     */
    public String getNotificationPreset() {
        return sharedPreferences.getString(PREF_NOTIFICATION_PRESET, NOTIFICATION_PRESET_DEFAULT);
    }

    /**
     * Sets the notification preset.
     * @param preset The preset key to use
     */
    public void setNotificationPreset(String preset) {
        sharedPreferences.edit().putString(PREF_NOTIFICATION_PRESET, preset).apply();
    }

    /**
     * Gets the custom notification title if set by user.
     * @return The custom title or null if not set
     */
    public String getCustomNotificationTitle() {
        return sharedPreferences.getString(PREF_CUSTOM_NOTIFICATION_TITLE, null);
    }

    /**
     * Sets the custom notification title.
     * @param title The custom title text
     */
    public void setCustomNotificationTitle(String title) {
        sharedPreferences.edit().putString(PREF_CUSTOM_NOTIFICATION_TITLE, title).apply();
    }

    /**
     * Gets the custom notification text if set by user.
     * @return The custom text or null if not set
     */
    public String getCustomNotificationText() {
        return sharedPreferences.getString(PREF_CUSTOM_NOTIFICATION_TEXT, null);
    }

    /**
     * Sets the custom notification text.
     * @param text The custom notification text
     */
    public void setCustomNotificationText(String text) {
        sharedPreferences.edit().putString(PREF_CUSTOM_NOTIFICATION_TEXT, text).apply();
    }

    /**
     * Checks if the stop button should be hidden in the notification.
     * @return true if button should be hidden, false otherwise
     */
    public boolean isNotificationStopButtonHidden() {
        return sharedPreferences.getBoolean(PREF_HIDE_NOTIFICATION_STOP_BUTTON, false);
    }

    /**
     * Sets whether to hide the stop button in the notification.
     * @param hide true to hide the button, false to show it
     */
    public void setNotificationStopButtonHidden(boolean hide) {
        sharedPreferences.edit().putBoolean(PREF_HIDE_NOTIFICATION_STOP_BUTTON, hide).apply();
    }

    /**
     * Gets the notification title based on the selected preset or custom text.
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
                return context.getString(R.string.notification_title_system_update);
            case NOTIFICATION_PRESET_DOWNLOADING:
                return context.getString(R.string.notification_title_downloading);
            case NOTIFICATION_PRESET_SYNCING:
                return context.getString(R.string.notification_title_syncing);
            case NOTIFICATION_PRESET_CUSTOM:
                String custom = getCustomNotificationTitle();
                return (custom != null && !custom.isEmpty()) ? custom : context.getString(R.string.notification_title_custom_default);
            default:
                return null; // Use default from strings.xml
        }
    }
    
    /**
     * Gets the notification channel name based on the selected preset
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
                return context.getString(R.string.notification_channel_system_updates);
            case NOTIFICATION_PRESET_DOWNLOADING:
                return context.getString(R.string.notification_channel_downloads);
            case NOTIFICATION_PRESET_SYNCING:
                return context.getString(R.string.notification_channel_data_sync);
            case NOTIFICATION_PRESET_CUSTOM:
                String custom = getCustomNotificationTitle();
                return (custom != null && !custom.isEmpty()) ? custom : context.getString(R.string.notification_channel_generic);
            default:
                return null;
        }
    }

    /**
     * Gets the notification text based on the selected preset or custom text.
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
                return isRecordingPaused ? 
                    context.getString(R.string.notification_text_system_update_paused) : 
                    context.getString(R.string.notification_text_system_update_progress);
            case NOTIFICATION_PRESET_DOWNLOADING:
                return isRecordingPaused ? 
                    context.getString(R.string.notification_text_downloading_paused) : 
                    context.getString(R.string.notification_text_downloading_progress);
            case NOTIFICATION_PRESET_SYNCING:
                return isRecordingPaused ? 
                    context.getString(R.string.notification_text_syncing_paused) : 
                    context.getString(R.string.notification_text_syncing_progress);
            case NOTIFICATION_PRESET_CUSTOM:
                String custom = getCustomNotificationText();
                return (custom != null && !custom.isEmpty()) ? custom : 
                    context.getString(R.string.notification_text_custom_default);
            default:
                return null; // Use default from strings.xml
        }
    }
    // ----- Fix Ended for this class (SharedPreferencesManager_notification_customization) -----

}