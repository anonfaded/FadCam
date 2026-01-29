package com.fadcam.streaming;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Manages cloud authentication for FadCam Remote.
 * Handles device ID generation, JWT token storage, and cloud account linking.
 * 
 * Security: Uses Android's ANDROID_ID which is unique per app+device combination.
 * JWT tokens are stored in SharedPreferences and used for authenticating with
 * the FadCam Remote cloud service (id.fadseclab.com).
 * 
 * Thread-safe singleton implementation.
 */
public class CloudAuthManager {
    private static final String TAG = "CloudAuthManager";
    private static final String PREFS_NAME = "FadCamCloudPrefs";
    
    // SharedPreferences keys
    private static final String KEY_JWT_TOKEN = "cloud_jwt_token";
    private static final String KEY_JWT_EXPIRY = "cloud_jwt_expiry";
    private static final String KEY_DEVICE_NAME = "cloud_device_name";
    private static final String KEY_USER_EMAIL = "cloud_user_email";
    private static final String KEY_IS_LINKED = "cloud_is_linked";
    private static final String KEY_LINKED_AT = "cloud_linked_at";
    
    // Cloud service URLs
    public static final String AUTH_BASE_URL = "https://id.fadseclab.com";
    public static final String DEVICE_LINK_URL = AUTH_BASE_URL + "/device-link";
    
    private static CloudAuthManager instance;
    private final Context context;
    private final SharedPreferences prefs;
    
    private CloudAuthManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
    
    /**
     * Get singleton instance
     */
    public static synchronized CloudAuthManager getInstance(Context context) {
        if (instance == null) {
            instance = new CloudAuthManager(context);
        }
        return instance;
    }
    
    /**
     * Get the unique device ID for this device.
     * Uses ANDROID_ID which is unique per app+device combination.
     * This ID is used to identify this device when registering with the cloud service.
     * 
     * @return Device ID string (16 character hex)
     */
    @NonNull
    public String getDeviceId() {
        return Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
    }
    
    /**
     * Get a shortened version of device ID for display (first 8 chars)
     */
    @NonNull
    public String getShortDeviceId() {
        String fullId = getDeviceId();
        return fullId.length() > 8 ? fullId.substring(0, 8).toUpperCase() : fullId.toUpperCase();
    }
    
    /**
     * Check if device is linked to a cloud account
     */
    public boolean isLinked() {
        return prefs.getBoolean(KEY_IS_LINKED, false);
    }
    
    /**
     * Store the JWT token received from the cloud service
     * 
     * @param token JWT token string
     * @param expiryMs Token expiry timestamp in milliseconds
     */
    public void setJwtToken(@NonNull String token, long expiryMs) {
        prefs.edit()
            .putString(KEY_JWT_TOKEN, token)
            .putLong(KEY_JWT_EXPIRY, expiryMs)
            .putBoolean(KEY_IS_LINKED, true)
            .putLong(KEY_LINKED_AT, System.currentTimeMillis())
            .apply();
        Log.i(TAG, "JWT token stored, expires at: " + expiryMs);
    }
    
    /**
     * Get the stored JWT token, or null if not available or expired
     */
    @Nullable
    public String getJwtToken() {
        if (!isLinked()) {
            return null;
        }
        
        long expiry = prefs.getLong(KEY_JWT_EXPIRY, 0);
        if (System.currentTimeMillis() > expiry) {
            Log.w(TAG, "JWT token expired, clearing");
            clearToken();
            return null;
        }
        
        return prefs.getString(KEY_JWT_TOKEN, null);
    }
    
    /**
     * Check if the JWT token is valid (exists and not expired)
     */
    public boolean hasValidToken() {
        return getJwtToken() != null;
    }
    
    /**
     * Get token expiry timestamp
     */
    public long getTokenExpiry() {
        return prefs.getLong(KEY_JWT_EXPIRY, 0);
    }
    
    /**
     * Store the device name (user-provided friendly name)
     */
    public void setDeviceName(@NonNull String name) {
        prefs.edit().putString(KEY_DEVICE_NAME, name).apply();
        Log.i(TAG, "Device name set: " + name);
    }
    
    /**
     * Get the stored device name
     */
    @Nullable
    public String getDeviceName() {
        return prefs.getString(KEY_DEVICE_NAME, null);
    }
    
    /**
     * Store the user email (for display purposes)
     */
    public void setUserEmail(@NonNull String email) {
        prefs.edit().putString(KEY_USER_EMAIL, email).apply();
    }
    
    /**
     * Get the stored user email
     */
    @Nullable
    public String getUserEmail() {
        return prefs.getString(KEY_USER_EMAIL, null);
    }
    
    /**
     * Get when the device was linked (timestamp)
     */
    public long getLinkedAt() {
        return prefs.getLong(KEY_LINKED_AT, 0);
    }
    
    /**
     * Clear the stored token (keeps device linked status for retry)
     */
    public void clearToken() {
        prefs.edit()
            .remove(KEY_JWT_TOKEN)
            .remove(KEY_JWT_EXPIRY)
            .apply();
        Log.i(TAG, "JWT token cleared");
    }
    
    /**
     * Completely unlink this device from cloud account
     */
    public void unlinkDevice() {
        prefs.edit()
            .remove(KEY_JWT_TOKEN)
            .remove(KEY_JWT_EXPIRY)
            .remove(KEY_DEVICE_NAME)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_IS_LINKED)
            .remove(KEY_LINKED_AT)
            .apply();
        Log.i(TAG, "Device unlinked from cloud account");
    }
    
    /**
     * Build the device link URL with parameters
     * 
     * @param deviceName User-provided device name
     * @return Complete URL for WebView to load
     */
    @NonNull
    public String buildDeviceLinkUrl(@NonNull String deviceName) {
        String deviceId = getDeviceId();
        // URL encode the device name
        String encodedName;
        try {
            encodedName = java.net.URLEncoder.encode(deviceName, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            encodedName = deviceName.replace(" ", "%20");
        }
        return DEVICE_LINK_URL + "?device_id=" + deviceId + "&device_name=" + encodedName;
    }
    
    /**
     * Listener interface for cloud auth events
     */
    public interface CloudAuthListener {
        void onLinkSuccess(String email, String deviceName);
        void onLinkFailed(String error);
        void onUnlinked();
    }
}
