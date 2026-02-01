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
    private static final String KEY_REFRESH_TOKEN = "cloud_refresh_token";
    private static final String KEY_USER_ID = "cloud_user_id";
    private static final String KEY_DEVICE_NAME = "cloud_device_name";
    private static final String KEY_USER_EMAIL = "cloud_user_email";
    private static final String KEY_IS_LINKED = "cloud_is_linked";
    private static final String KEY_LINKED_AT = "cloud_linked_at";
    
    // Stream token keys (for relay server uploads)
    private static final String KEY_STREAM_TOKEN = "cloud_stream_token";
    private static final String KEY_STREAM_TOKEN_EXPIRY = "cloud_stream_token_expiry";
    
    // Supabase API for token refresh (using new publishable key)
    private static final String SUPABASE_URL = "https://vfhehknmxxedvesdvpew.supabase.co";
    private static final String SUPABASE_PUBLISHABLE_KEY = "sb_publishable_PwOotJZQHwS9xnCFwUjHsQ_uXLNqkk9";
    
    // Cloud service URLs
    public static final String AUTH_BASE_URL = "https://id.fadseclab.com";
    public static final String DEVICE_LINK_URL = AUTH_BASE_URL + "/device-link";
    
    // Token refresh deduplication - prevents multiple simultaneous refresh calls
    private volatile boolean isRefreshing = false;
    private final java.util.List<TokenRefreshListener> pendingRefreshListeners = new java.util.ArrayList<>();
    private final Object refreshLock = new Object();
    
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
     * Store the JWT token with refresh token for seamless renewal.
     * 
     * @param token JWT access token string
     * @param expiryMs Token expiry timestamp in milliseconds
     * @param refreshToken Refresh token for renewal
     * @param userId User UUID
     */
    public void setJwtToken(@NonNull String token, long expiryMs, @NonNull String refreshToken, @NonNull String userId) {
        prefs.edit()
            .putString(KEY_JWT_TOKEN, token)
            .putLong(KEY_JWT_EXPIRY, expiryMs)
            .putBoolean(KEY_IS_LINKED, true)
            .putLong(KEY_LINKED_AT, System.currentTimeMillis())
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putString(KEY_USER_ID, userId)
            .apply();
        Log.i(TAG, "JWT token stored with refresh token, expires at: " + expiryMs);
    }
    
    /**
     * Get user UUID (stored during linking or extracted from token)
     */
    @Nullable
    public String getUserId() {
        return prefs.getString(KEY_USER_ID, null);
    }
    
    /**
     * Get the stored refresh token
     */
    @Nullable
    public String getRefreshToken() {
        return prefs.getString(KEY_REFRESH_TOKEN, null);
    }
    
    /**
     * Get the stored JWT token, or null if not available or expired.
     * Note: This does NOT auto-refresh. Call isTokenExpired() + refreshTokenAsync() for refresh.
     */
    @Nullable
    public String getJwtToken() {
        if (!isLinked()) {
            return null;
        }
        return prefs.getString(KEY_JWT_TOKEN, null);
    }
    
    /**
     * Check if the token has expired
     */
    public boolean isTokenExpired() {
        long expiry = prefs.getLong(KEY_JWT_EXPIRY, 0);
        return System.currentTimeMillis() > expiry;
    }
    
    /**
     * Check if token is about to expire (within 5 minutes)
     */
    public boolean isTokenNearExpiry() {
        long expiry = prefs.getLong(KEY_JWT_EXPIRY, 0);
        long fiveMinutes = 5 * 60 * 1000L;
        return System.currentTimeMillis() > (expiry - fiveMinutes);
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
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_USER_ID)
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
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_DEVICE_NAME)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_IS_LINKED)
            .remove(KEY_LINKED_AT)
            .apply();
        Log.i(TAG, "Device unlinked from cloud account");
    }
    
    /**
     * Listener for token refresh events
     */
    public interface TokenRefreshListener {
        void onRefreshSuccess(String newToken, long newExpiry);
        void onRefreshFailed(String error);
    }
    
    /**
     * Refresh the access token using the stored refresh token.
     * This calls Supabase auth.refreshSession() endpoint.
     * 
     * Uses deduplication: if a refresh is already in progress, the listener
     * will be queued and notified when the in-progress refresh completes.
     * This prevents 500+ auth requests per hour from multiple components
     * each triggering their own refresh.
     * 
     * @param listener Callback for success/error (called on main thread)
     */
    public void refreshTokenAsync(@Nullable TokenRefreshListener listener) {
        String refreshToken = getRefreshToken();
        if (refreshToken == null || refreshToken.isEmpty()) {
            Log.w(TAG, "No refresh token available");
            if (listener != null) {
                listener.onRefreshFailed("No refresh token available");
            }
            return;
        }
        
        // Deduplication: if refresh already in progress, queue this listener
        synchronized (refreshLock) {
            if (isRefreshing) {
                Log.d(TAG, "Token refresh already in progress, queuing listener");
                if (listener != null) {
                    pendingRefreshListeners.add(listener);
                }
                return;
            }
            isRefreshing = true;
            if (listener != null) {
                pendingRefreshListeners.add(listener);
            }
        }
        
        Log.i(TAG, "Starting token refresh...");
        
        new Thread(() -> {
            try {
                String url = SUPABASE_URL + "/auth/v1/token?grant_type=refresh_token";
                
                java.net.URI uri = java.net.URI.create(url);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) uri.toURL().openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY);
                
                // Request body
                String body = "{\"refresh_token\":\"" + refreshToken + "\"}";
                try (java.io.OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes("UTF-8"));
                }
                
                int responseCode = conn.getResponseCode();
                
                if (responseCode == 200) {
                    // Read response
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();
                    
                    // Parse JSON response
                    org.json.JSONObject json = new org.json.JSONObject(response.toString());
                    String newAccessToken = json.optString("access_token", null);
                    String newRefreshTokenFromResponse = json.optString("refresh_token", null);
                    long expiresIn = json.optLong("expires_in", 3600); // default 1 hour
                    org.json.JSONObject userObj = json.optJSONObject("user");
                    String userIdFromResponse = userObj != null ? userObj.optString("id", null) : null;
                    
                    // Use existing values if not in response
                    String finalRefreshToken = (newRefreshTokenFromResponse != null && !newRefreshTokenFromResponse.isEmpty()) 
                        ? newRefreshTokenFromResponse : refreshToken;
                    String finalUserId = (userIdFromResponse != null && !userIdFromResponse.isEmpty()) 
                        ? userIdFromResponse : getUserId();
                    
                    if (newAccessToken != null && !newAccessToken.isEmpty() && 
                        finalRefreshToken != null && finalUserId != null) {
                        // Calculate expiry timestamp
                        long newExpiry = System.currentTimeMillis() + (expiresIn * 1000);
                        
                        // Store new tokens
                        setJwtToken(newAccessToken, newExpiry, finalRefreshToken, finalUserId);
                        
                        Log.i(TAG, "Token refreshed successfully, expires in " + expiresIn + "s");
                        
                        // Notify all pending listeners
                        notifyRefreshListeners(true, newAccessToken, newExpiry, null);
                    } else {
                        String error = "Missing required token data in response";
                        Log.e(TAG, error + " (token=" + (newAccessToken != null) + ", refresh=" + (finalRefreshToken != null) + ", userId=" + (finalUserId != null) + ")");
                        notifyRefreshListeners(false, null, 0, error);
                    }
                } else {
                    // Error response
                    java.io.BufferedReader errorReader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getErrorStream()));
                    StringBuilder errorResponse = new StringBuilder();
                    String line;
                    while ((line = errorReader.readLine()) != null) {
                        errorResponse.append(line);
                    }
                    errorReader.close();
                    
                    String errorMsg = "HTTP " + responseCode + ": " + errorResponse;
                    Log.e(TAG, "Token refresh failed: " + errorMsg);
                    
                    notifyRefreshListeners(false, null, 0, errorMsg);
                }
                
                conn.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Error refreshing token", e);
                notifyRefreshListeners(false, null, 0, e.getMessage());
            }
        }).start();
    }
    
    /**
     * Notify all pending refresh listeners and reset refresh state.
     * Called on background thread, posts to main thread.
     */
    private void notifyRefreshListeners(boolean success, String token, long expiry, String error) {
        java.util.List<TokenRefreshListener> listeners;
        synchronized (refreshLock) {
            listeners = new java.util.ArrayList<>(pendingRefreshListeners);
            pendingRefreshListeners.clear();
            isRefreshing = false;
        }
        
        if (listeners.isEmpty()) {
            return;
        }
        
        Log.d(TAG, "Notifying " + listeners.size() + " pending refresh listeners (success=" + success + ")");
        
        android.os.Handler mainHandler = new android.os.Handler(context.getMainLooper());
        mainHandler.post(() -> {
            for (TokenRefreshListener l : listeners) {
                if (success) {
                    l.onRefreshSuccess(token, expiry);
                } else {
                    l.onRefreshFailed(error);
                }
            }
        });
    }
    
    /**
     * Get a valid token, refreshing if needed.
     * This is a convenience method that checks expiry and refreshes automatically.
     * 
     * @param listener Callback with valid token or error (called on main thread)
     */
    public void getValidTokenAsync(@NonNull TokenRefreshListener listener) {
        String currentToken = getJwtToken();
        
        if (currentToken == null) {
            listener.onRefreshFailed("Device not linked");
            return;
        }
        
        if (!isTokenNearExpiry()) {
            // Token is still valid
            listener.onRefreshSuccess(currentToken, getTokenExpiry());
            return;
        }
        
        // Token expired or near expiry, try refresh
        String refreshToken = getRefreshToken();
        if (refreshToken == null || refreshToken.isEmpty()) {
            listener.onRefreshFailed("Token expired and no refresh token available");
            return;
        }
        
        Log.i(TAG, "Token expired or near expiry, refreshing...");
        refreshTokenAsync(listener);
    }
    
    /**
     * Build the login URL for device linking.
     * Opens the login page directly with return URL containing device-link parameters.
     * After successful login, user is redirected to device-link page for registration.
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
        
        // Build the device-link return URL (where user goes after login)
        String deviceLinkUrl = "/device-link?device_id=" + deviceId + "&device_name=" + encodedName;
        
        // URL encode the return URL for the login page parameter
        String encodedReturnUrl;
        try {
            encodedReturnUrl = java.net.URLEncoder.encode(deviceLinkUrl, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            encodedReturnUrl = deviceLinkUrl.replace("?", "%3F").replace("&", "%26").replace("=", "%3D");
        }
        
        // Return the login URL with the device-link return path
        return AUTH_BASE_URL + "/login?return=" + encodedReturnUrl;
    }
    
    /**
     * Listener interface for cloud auth events
     */
    public interface CloudAuthListener {
        void onLinkSuccess(String email, String deviceName);
        void onLinkFailed(String error);
        void onUnlinked();
    }
    
    /**
     * Listener for device info sync
     */
    public interface DeviceInfoListener {
        void onSuccess(String name, String deviceType, boolean isActive);
        void onError(String error);
    }
    
    /**
     * Sync device info from the cloud server.
     * Fetches the latest device name/type from the server and updates local storage.
     * Call this when opening cloud account info to get the latest name if renamed on web.
     * 
     * @param listener Callback for success/error
     */
    public void syncDeviceInfo(DeviceInfoListener listener) {
        if (!isLinked()) {
            if (listener != null) {
                listener.onError("Device not linked");
            }
            return;
        }
        
        String deviceId = getDeviceId();
        String url = "https://vfhehknmxxedvesdvpew.supabase.co/functions/v1/get-device-info?device_id=" + deviceId;
        
        // Use a background thread for network call
        new Thread(() -> {
            try {
                java.net.URL apiUrl = new java.net.URL(url);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) apiUrl.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                
                int responseCode = conn.getResponseCode();
                
                if (responseCode == 200) {
                    // Read response
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();
                    
                    // Parse JSON response
                    org.json.JSONObject json = new org.json.JSONObject(response.toString());
                    String name = json.optString("name", null);
                    String deviceType = json.optString("device_type", null);
                    boolean isActive = json.optBoolean("is_active", true);
                    
                    // Update local storage if name changed
                    String currentName = getDeviceName();
                    if (name != null && !name.equals(currentName)) {
                        setDeviceName(name);
                        Log.i(TAG, "Device name synced from server: " + name);
                    }
                    
                    if (listener != null) {
                        // Callback on main thread
                        android.os.Handler mainHandler = new android.os.Handler(context.getMainLooper());
                        mainHandler.post(() -> listener.onSuccess(name, deviceType, isActive));
                    }
                } else {
                    // Error response
                    String errorMsg = "HTTP " + responseCode;
                    Log.w(TAG, "Failed to sync device info: " + errorMsg);
                    if (listener != null) {
                        android.os.Handler mainHandler = new android.os.Handler(context.getMainLooper());
                        mainHandler.post(() -> listener.onError(errorMsg));
                    }
                }
                
                conn.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Error syncing device info", e);
                if (listener != null) {
                    android.os.Handler mainHandler = new android.os.Handler(context.getMainLooper());
                    mainHandler.post(() -> listener.onError(e.getMessage()));
                }
            }
        }).start();
    }
    
    // ========================================================================
    // STREAM TOKEN METHODS (for relay server uploads)
    // ========================================================================
    
    /**
     * Listener for stream token fetch events
     */
    public interface StreamTokenListener {
        void onSuccess(String streamToken);
        void onError(String error);
    }
    
    /**
     * Get the stored stream token (for relay server uploads).
     * Returns null if not available or expired.
     */
    @Nullable
    public String getStreamToken() {
        if (!isLinked()) {
            return null;
        }
        long expiry = prefs.getLong(KEY_STREAM_TOKEN_EXPIRY, 0);
        if (System.currentTimeMillis() > expiry) {
            Log.d(TAG, "Stream token expired");
            return null;
        }
        return prefs.getString(KEY_STREAM_TOKEN, null);
    }
    
    /**
     * Check if stream token is about to expire (within 1 hour)
     */
    public boolean isStreamTokenNearExpiry() {
        long expiry = prefs.getLong(KEY_STREAM_TOKEN_EXPIRY, 0);
        long oneHour = 60 * 60 * 1000L;
        return System.currentTimeMillis() > (expiry - oneHour);
    }
    
    /**
     * Fetch a new stream_access_token from the get-stream-token Edge Function.
     * This token is used for authenticating uploads to the relay server.
     * 
     * Supports two auth methods:
     * 1. Bearer token (if valid Supabase access token available)
     * 2. Device-based auth (device_id + user_id) - works even with expired Supabase session
     * 
     * @param listener Callback for success/error (called on main thread)
     */
    public void fetchStreamTokenAsync(@Nullable StreamTokenListener listener) {
        String deviceId = getDeviceId();
        String userId = getUserId();
        String accessToken = getJwtToken();
        
        if (userId == null) {
            Log.w(TAG, "No user ID available for stream token fetch");
            if (listener != null) {
                listener.onError("Not linked to cloud account");
            }
            return;
        }
        
        String url = SUPABASE_URL + "/functions/v1/get-stream-token";
        
        Log.i(TAG, "Fetching stream token for device: " + deviceId.substring(0, 8) + "... (using " + (accessToken != null ? "Bearer token" : "device auth") + ")");
        
        new Thread(() -> {
            try {
                java.net.URI uri = java.net.URI.create(url);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) uri.toURL().openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                
                // Add Bearer token if available (might still be valid)
                if (accessToken != null && !accessToken.isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                }
                
                // Request body includes both device_id and user_id for fallback auth
                String body = "{\"device_id\":\"" + deviceId + "\",\"user_id\":\"" + userId + "\"}";
                try (java.io.OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes("UTF-8"));
                }
                
                int responseCode = conn.getResponseCode();
                Log.i(TAG, "Stream token response: HTTP " + responseCode);
                
                if (responseCode == 200) {
                    // Read response
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();
                    
                    // Parse JSON response
                    org.json.JSONObject json = new org.json.JSONObject(response.toString());
                    String streamToken = json.optString("stream_token", null);
                    long expiresAt = json.optLong("expires_at", 0);
                    
                    if (streamToken != null && !streamToken.isEmpty()) {
                        // Store stream token
                        prefs.edit()
                            .putString(KEY_STREAM_TOKEN, streamToken)
                            .putLong(KEY_STREAM_TOKEN_EXPIRY, expiresAt)
                            .apply();
                        
                        Log.i(TAG, "✅ Stream token fetched and stored, expires at: " + expiresAt);
                        
                        if (listener != null) {
                            android.os.Handler mainHandler = new android.os.Handler(context.getMainLooper());
                            mainHandler.post(() -> listener.onSuccess(streamToken));
                        }
                    } else {
                        String error = "Invalid stream token response";
                        Log.e(TAG, error);
                        if (listener != null) {
                            android.os.Handler mainHandler = new android.os.Handler(context.getMainLooper());
                            mainHandler.post(() -> listener.onError(error));
                        }
                    }
                } else {
                    // Error response
                    java.io.InputStream errorStream = conn.getErrorStream();
                    String errorMsg = "HTTP " + responseCode;
                    if (errorStream != null) {
                        java.io.BufferedReader errorReader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(errorStream));
                        StringBuilder errorResponse = new StringBuilder();
                        String line;
                        while ((line = errorReader.readLine()) != null) {
                            errorResponse.append(line);
                        }
                        errorReader.close();
                        errorMsg = "HTTP " + responseCode + ": " + errorResponse;
                    }
                    
                    Log.e(TAG, "Stream token fetch failed: " + errorMsg);
                    
                    final String finalErrorMsg = errorMsg;
                    if (listener != null) {
                        android.os.Handler mainHandler = new android.os.Handler(context.getMainLooper());
                        mainHandler.post(() -> listener.onError(finalErrorMsg));
                    }
                }
                
                conn.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Error fetching stream token", e);
                if (listener != null) {
                    android.os.Handler mainHandler = new android.os.Handler(context.getMainLooper());
                    mainHandler.post(() -> listener.onError(e.getMessage()));
                }
            }
        }).start();
    }
    
    /**
     * Get a valid stream token, fetching if needed.
     * This is the main method for CloudStreamUploader to call.
     * 
     * @param listener Callback with valid stream token or error (called on main thread)
     */
    public void getValidStreamTokenAsync(@NonNull StreamTokenListener listener) {
        String currentToken = getStreamToken();
        
        if (currentToken != null && !isStreamTokenNearExpiry()) {
            // Token is still valid
            listener.onSuccess(currentToken);
            return;
        }
        
        // Need to fetch new stream token
        Log.i(TAG, "Stream token expired or near expiry, fetching new one...");
        fetchStreamTokenAsync(listener);
    }
    
    /**
     * Clear stream token (called on unlink or token issues)
     */
    public void clearStreamToken() {
        prefs.edit()
            .remove(KEY_STREAM_TOKEN)
            .remove(KEY_STREAM_TOKEN_EXPIRY)
            .apply();
        Log.i(TAG, "Stream token cleared");
    }
}
