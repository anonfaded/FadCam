package com.fadcam;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Size;

import androidx.annotation.Nullable; // Import Nullable

import java.util.Locale;

public class SharedPreferencesManager {

    // --- STORAGE CONSTANTS ---
    public static final String PREF_STORAGE_MODE = "storage_mode";
    public static final String STORAGE_MODE_INTERNAL = "internal";
    public static final String STORAGE_MODE_CUSTOM = "custom";
    public static final String PREF_CUSTOM_STORAGE_URI = "custom_storage_uri";
    // --- END STORAGE CONSTANTS ---


    private static SharedPreferencesManager instance;
    public final SharedPreferences sharedPreferences;

    private SharedPreferencesManager(Context context) {
        // Use PREFS_NAME from Constants class
        this.sharedPreferences = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
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

    public Integer getVideoFrameRate() {
        return sharedPreferences.getInt(Constants.PREF_VIDEO_FRAME_RATE, Constants.DEFAULT_VIDEO_FRAME_RATE);
    }

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

    public boolean isDebugLoggingEnabled() {
        return sharedPreferences.getBoolean(Constants.PREF_DEBUG_DATA, false);
    }

    public String getWatermarkOption() {
        return sharedPreferences.getString(Constants.PREF_WATERMARK_OPTION, Constants.DEFAULT_WATERMARK_OPTION);
    }

    public Boolean isPreviewEnabled() {
        return sharedPreferences.getBoolean(Constants.PREF_IS_PREVIEW_ENABLED, Constants.DEFAULT_PREVIEW_ENABLED);
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
    // --- End Other methods ---
}