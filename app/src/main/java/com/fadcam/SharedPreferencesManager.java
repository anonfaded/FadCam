package com.fadcam;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Size;

import java.util.Locale;

public class SharedPreferencesManager {

    private static SharedPreferencesManager instance;
    public final SharedPreferences sharedPreferences;

    private SharedPreferencesManager(Context context) {
        this.sharedPreferences = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static SharedPreferencesManager getInstance(Context context) {
        if (instance == null) {
            instance = new SharedPreferencesManager(context.getApplicationContext());
        }
        return instance;
    }

    /**
     * Retrieves the currently selected camera type from shared preferences.
     * If no selection is found, the default value is set to the back camera.
     *
     * @return the selected camera type as a {@link CameraType} enum.
     */
    public CameraType getCameraSelection() {
        String cameraSelection = sharedPreferences.getString(Constants.PREF_CAMERA_SELECTION, String.valueOf(Constants.DEFAULT_CAMERA_TYPE));
        return CameraType.valueOf(cameraSelection);
    }

    /**
     * Retrieves the selected camera resolution from shared preferences.
     * If no resolution is found, a default resolution is returned.
     *
     * @return the selected resolution as a {@link Size} object, containing width and height.
     */
    public Size getCameraResolution() {
        return new Size(sharedPreferences.getInt(Constants.PREF_VIDEO_RESOLUTION_WIDTH, Constants.DEFAULT_VIDEO_RESOLUTION.getWidth()),
                sharedPreferences.getInt(Constants.PREF_VIDEO_RESOLUTION_HEIGHT, Constants.DEFAULT_VIDEO_RESOLUTION.getHeight()));
    }

    /**
     * Retrieves the video frame rate value from shared preferences.
     * If the frame rate is not set, the default frame rate is returned.
     *
     * @return the video frame rate stored in shared preferences, or the default frame rate if not set.
     */
    public Integer getVideoFrameRate() {
        return sharedPreferences.getInt(Constants.PREF_VIDEO_FRAME_RATE, Constants.DEFAULT_VIDEO_FRAME_RATE);
    }

    public VideoCodec getVideoCodec() {
        String videoCodec = sharedPreferences.getString(Constants.PREF_VIDEO_CODEC, Constants.DEFAULT_VIDEO_CODEC.toString());
        return VideoCodec.valueOf(videoCodec);
    }

    public boolean isVideoCodecExist() {
        String videoCodec = sharedPreferences.getString(Constants.PREF_VIDEO_CODEC, null);
        return videoCodec != null;
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
        // Check if recording is in progress by checking the recording state
        // This is a placeholder - you'll need to replace with actual implementation
        // You might want to store the recording state in SharedPreferences
        return sharedPreferences.getBoolean(Constants.PREF_IS_RECORDING_IN_PROGRESS, false);
    }

    public void setRecordingInProgress(boolean isInProgress) {
        // Update the recording state in SharedPreferences
        sharedPreferences.edit().putBoolean(Constants.PREF_IS_RECORDING_IN_PROGRESS, isInProgress).apply();
    }
}
