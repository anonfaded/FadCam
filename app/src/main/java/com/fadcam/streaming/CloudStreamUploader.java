package com.fadcam.streaming;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * CloudStreamUploader handles uploading HLS segments to the cloud relay server.
 * 
 * Architecture:
 * - Singleton pattern for efficient connection pooling
 * - Uses OkHttp for async HTTP uploads
 * - Uploads to: https://live.fadseclab.com:8443/upload/{user_uuid}/{device_id}/{filename}
 * - Authorization via JWT Bearer token
 * 
 * Upload format:
 * - PUT /upload/{user_uuid}/{device_id}/init.mp4 - Initialization segment (ftyp+moov)
 * - PUT /upload/{user_uuid}/{device_id}/seg-{n}.m4s - Media segments (moof+mdat)
 * - PUT /upload/{user_uuid}/{device_id}/live.m3u8 - Playlist (generated on server or uploaded)
 * 
 * Thread-safe: All uploads are async and don't block the calling thread.
 */
public class CloudStreamUploader {
    private static final String TAG = "CloudStreamUploader";
    
    // Relay server configuration
    public static final String RELAY_BASE_URL = "https://live.fadseclab.com:8443";
    
    // Media types
    private static final MediaType MEDIA_TYPE_MP4 = MediaType.parse("video/mp4");
    private static final MediaType MEDIA_TYPE_M4S = MediaType.parse("video/iso.segment");
    private static final MediaType MEDIA_TYPE_M3U8 = MediaType.parse("application/vnd.apple.mpegurl");
    private static final MediaType MEDIA_TYPE_JSON = MediaType.parse("application/json");
    
    // Retry limits to prevent infinite loops and excessive auth requests
    private static final int MAX_401_RETRIES = 2;
    private static final long BACKOFF_AFTER_AUTH_FAILURE_MS = 30000; // 30s backoff after auth failure
    
    private static CloudStreamUploader instance;
    
    private final Context context;
    private final OkHttpClient httpClient;
    private final CloudAuthManager authManager;
    
    // Cached user UUID (extracted from JWT)
    private String cachedUserUuid = null;
    
    // Upload state
    private boolean isEnabled = false;
    private boolean initSegmentUploaded = false;
    
    // Auth failure tracking (to prevent excessive refresh calls)
    private int consecutive401Count = 0;
    private long lastAuthFailureTime = 0;
    private boolean authBackoffActive = false;
    
    // Stats
    private long totalBytesUploaded = 0;
    private int successfulUploads = 0;
    private int failedUploads = 0;
    
    private CloudStreamUploader(Context context) {
        this.context = context.getApplicationContext();
        this.authManager = CloudAuthManager.getInstance(context);
        
        // Configure OkHttp with generous timeouts for poor mobile connections
        // User reported latency ~419ms and 0.06 Mbps upload - needs longer timeouts
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)  // Increased from 5s for slow connections
            .writeTimeout(30, TimeUnit.SECONDS)    // Increased from 10s for large segments
            .readTimeout(15, TimeUnit.SECONDS)     // Increased from 5s
            .retryOnConnectionFailure(true)
            .build();
        
        // Initialize enabled state from preferences (in case app restarted with cloud mode already enabled)
        android.content.SharedPreferences prefs = this.context.getSharedPreferences("FadCamCloudPrefs", Context.MODE_PRIVATE);
        int streamingMode = prefs.getInt("streaming_mode", 0); // 0=Local, 1=Cloud
        this.isEnabled = (streamingMode == 1);
        Log.i(TAG, "Initialized from preferences: isEnabled=" + isEnabled + " (streaming_mode=" + streamingMode + ")");
    }
    
    /**
     * Get singleton instance
     */
    public static synchronized CloudStreamUploader getInstance(Context context) {
        if (instance == null) {
            instance = new CloudStreamUploader(context);
        }
        return instance;
    }
    
    /**
     * Enable or disable cloud streaming upload
     */
    public void setEnabled(boolean enabled) {
        this.isEnabled = enabled;
        if (enabled) {
            // Reset state for new stream session
            initSegmentUploaded = false;
            cachedUserUuid = null;
            // Reset auth failure tracking (fresh start)
            consecutive401Count = 0;
            authBackoffActive = false;
            Log.i(TAG, "Cloud streaming enabled");
        } else {
            Log.i(TAG, "Cloud streaming disabled");
        }
    }
    
    /**
     * Check if cloud streaming is enabled
     */
    public boolean isEnabled() {
        return isEnabled;
    }
    
    /**
     * Get user UUID from the JWT token.
     * JWT format: header.payload.signature (each base64 encoded)
     * Payload contains "sub" field with user UUID.
     * 
     * @return User UUID or null if token invalid/missing
     */
    @Nullable
    public String getUserUuid() {
        if (cachedUserUuid != null) {
            return cachedUserUuid;
        }
        
        // First check if we have a stored user ID
        String storedUserId = authManager.getUserId();
        if (storedUserId != null && !storedUserId.isEmpty()) {
            cachedUserUuid = storedUserId;
            return cachedUserUuid;
        }
        
        // Fallback to extracting from JWT
        String token = authManager.getJwtToken();
        if (token == null) {
            Log.w(TAG, "No JWT token available");
            return null;
        }
        
        try {
            // JWT has 3 parts: header.payload.signature
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                Log.e(TAG, "Invalid JWT format: expected 3 parts, got " + parts.length);
                return null;
            }
            
            // Decode the payload (middle part)
            String payloadJson = new String(Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP));
            JSONObject payload = new JSONObject(payloadJson);
            
            // Supabase uses "sub" for user ID
            cachedUserUuid = payload.optString("sub", null);
            
            if (cachedUserUuid != null) {
                Log.i(TAG, "User UUID extracted: " + cachedUserUuid.substring(0, 8) + "...");
            }
            
            return cachedUserUuid;
        } catch (Exception e) {
            Log.e(TAG, "Failed to decode JWT", e);
            return null;
        }
    }
    
    /**
     * Upload initialization segment (ftyp + moov).
     * Must be called before uploading media segments.
     * 
     * @param initData Init segment bytes
     * @param callback Optional callback for upload result
     */
    public void uploadInitSegment(byte[] initData, @Nullable UploadCallback callback) {
        if (!isEnabled) {
            Log.d(TAG, "Cloud streaming disabled, skipping init upload");
            return;
        }
        
        String url = buildUploadUrl("init.mp4");
        if (url == null) {
            if (callback != null) callback.onError("Failed to build upload URL");
            return;
        }
        
        Log.i(TAG, "Uploading init segment: " + initData.length + " bytes");
        uploadBytes(url, initData, MEDIA_TYPE_MP4, new UploadCallback() {
            @Override
            public void onSuccess() {
                initSegmentUploaded = true;
                Log.i(TAG, "✅ Init segment uploaded successfully");
                if (callback != null) callback.onSuccess();
            }
            
            @Override
            public void onError(String error) {
                Log.e(TAG, "❌ Init segment upload failed: " + error);
                if (callback != null) callback.onError(error);
            }
        });
    }
    
    /**
     * Upload a media segment (moof + mdat).
     * 
     * @param sequenceNumber Segment sequence number
     * @param segmentData Segment bytes
     * @param callback Optional callback for upload result
     */
    public void uploadSegment(int sequenceNumber, byte[] segmentData, @Nullable UploadCallback callback) {
        if (!isEnabled) {
            return;
        }
        
        if (!initSegmentUploaded) {
            Log.w(TAG, "Init segment not uploaded yet, skipping segment " + sequenceNumber);
            return;
        }
        
        String filename = "seg-" + sequenceNumber + ".m4s";
        String url = buildUploadUrl(filename);
        if (url == null) {
            if (callback != null) callback.onError("Failed to build upload URL");
            return;
        }
        
        // Log.d(TAG, "Uploading segment " + sequenceNumber + ": " + segmentData.length + " bytes");
        uploadBytes(url, segmentData, MEDIA_TYPE_M4S, callback);
    }
    
    /**
     * Upload the HLS playlist.
     * 
     * @param playlistContent M3U8 playlist content
     * @param callback Optional callback for upload result
     */
    public void uploadPlaylist(String playlistContent, @Nullable UploadCallback callback) {
        if (!isEnabled) {
            return;
        }
        
        String url = buildUploadUrl("live.m3u8");
        if (url == null) {
            if (callback != null) callback.onError("Failed to build upload URL");
            return;
        }
        
        uploadBytes(url, playlistContent.getBytes(), MEDIA_TYPE_M3U8, callback);
    }
    
    /**
     * Build the upload URL for a file.
     * Format: https://live.fadseclab.com:8443/upload/{user_uuid}/{device_id}/{filename}
     */
    @Nullable
    private String buildUploadUrl(String filename) {
        String userUuid = getUserUuid();
        if (userUuid == null) {
            Log.e(TAG, "Cannot build URL: no user UUID");
            return null;
        }
        
        String deviceId = authManager.getDeviceId();
        return RELAY_BASE_URL + "/upload/" + userUuid + "/" + deviceId + "/" + filename;
    }
    
    /**
     * Perform the actual HTTP PUT upload.
     * Uses stream_access_token for authentication (not the Supabase JWT).
     * Will fetch a new stream token if needed.
     */
    private void uploadBytes(String url, byte[] data, MediaType mediaType, @Nullable UploadCallback callback) {
        // Check if we have a valid stream token
        String streamToken = authManager.getStreamToken();
        
        if (streamToken == null || authManager.isStreamTokenNearExpiry()) {
            // Need to fetch stream token first
            Log.i(TAG, "Stream token missing or near expiry, fetching...");
            authManager.getValidStreamTokenAsync(new CloudAuthManager.StreamTokenListener() {
                @Override
                public void onSuccess(String newStreamToken) {
                    // Now perform the upload with the new token
                    doUploadWithToken(url, data, mediaType, newStreamToken, callback);
                }
                
                @Override
                public void onError(String error) {
                    Log.e(TAG, "Failed to get stream token: " + error);
                    failedUploads++;
                    if (callback != null) callback.onError("Stream token error: " + error);
                }
            });
        } else {
            // Have a valid stream token, upload directly
            doUploadWithToken(url, data, mediaType, streamToken, callback);
        }
    }
    
    /**
     * Actually perform the HTTP upload with the provided stream token.
     */
    private void doUploadWithToken(String url, byte[] data, MediaType mediaType, String token, @Nullable UploadCallback callback) {
        // Check if we're in auth backoff mode
        if (authBackoffActive) {
            long timeSinceFailure = System.currentTimeMillis() - lastAuthFailureTime;
            if (timeSinceFailure < BACKOFF_AFTER_AUTH_FAILURE_MS) {
                Log.d(TAG, "Auth backoff active, skipping upload (wait " + 
                    ((BACKOFF_AFTER_AUTH_FAILURE_MS - timeSinceFailure) / 1000) + "s)");
                return; // Silently skip, don't call callback
            }
            // Backoff period expired, reset and try again
            Log.i(TAG, "Auth backoff period expired, resuming uploads");
            authBackoffActive = false;
            consecutive401Count = 0;
        }
        
        RequestBody body = RequestBody.create(data, mediaType);
        Request request = new Request.Builder()
            .url(url)
            .put(body)
            .addHeader("Authorization", "Bearer " + token)
            .build();
        
        Log.i(TAG, "📤 Sending HTTP PUT to: " + url.substring(0, Math.min(80, url.length())) + "...");
        long startTime = System.currentTimeMillis();
        
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                long elapsed = System.currentTimeMillis() - startTime;
                failedUploads++;
                String errorType = e.getClass().getSimpleName();
                Log.e(TAG, "❌ Upload network failure after " + elapsed + "ms: " + errorType + ": " + e.getMessage());
                
                // Log specific timeout types for debugging
                if (e instanceof java.net.SocketTimeoutException) {
                    Log.e(TAG, "   ⏱️ TIMEOUT: Connection or read timeout - network may be too slow");
                } else if (e instanceof java.net.ConnectException) {
                    Log.e(TAG, "   🔌 CONNECT: Failed to connect to server - check network connectivity");
                } else if (e instanceof java.net.UnknownHostException) {
                    Log.e(TAG, "   🌐 DNS: Failed to resolve hostname - no internet?");
                }
                
                if (callback != null) callback.onError(errorType + ": " + e.getMessage());
            }
            
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                long elapsed = System.currentTimeMillis() - startTime;
                Log.i(TAG, "📥 Upload response received: HTTP " + response.code() + " after " + elapsed + "ms");
                try {
                    if (response.isSuccessful()) {
                        successfulUploads++;
                        totalBytesUploaded += data.length;
                        // Reset 401 counter on success
                        consecutive401Count = 0;
                        if (callback != null) callback.onSuccess();
                    } else {
                        failedUploads++;
                        String error = "HTTP " + response.code() + ": " + response.message();
                        Log.e(TAG, "Upload error: " + error);
                        
                        // Handle 401 - clear stream token and retry with new one
                        if (response.code() == 401) {
                            consecutive401Count++;
                            Log.w(TAG, "Stream token 401 error (attempt " + consecutive401Count + "/" + MAX_401_RETRIES + ")");
                            
                            // Check if we've exceeded retry limit
                            if (consecutive401Count > MAX_401_RETRIES) {
                                Log.e(TAG, "Max 401 retries exceeded, entering backoff mode");
                                lastAuthFailureTime = System.currentTimeMillis();
                                authBackoffActive = true;
                                if (callback != null) callback.onError("Auth failed after " + MAX_401_RETRIES + " attempts");
                                return;
                            }
                            
                            // Clear stream token and fetch a new one
                            authManager.clearStreamToken();
                            authManager.fetchStreamTokenAsync(new CloudAuthManager.StreamTokenListener() {
                                @Override
                                public void onSuccess(String newStreamToken) {
                                    Log.i(TAG, "New stream token fetched after 401, retrying upload...");
                                    cachedUserUuid = null; // Clear cache
                                    // Retry upload with new token
                                    doUploadWithToken(url, data, mediaType, newStreamToken, callback);
                                }
                                
                                @Override
                                public void onError(String fetchError) {
                                    Log.e(TAG, "Stream token fetch failed after 401: " + fetchError);
                                    lastAuthFailureTime = System.currentTimeMillis();
                                    authBackoffActive = true;
                                    if (callback != null) callback.onError("Auth failed: " + fetchError);
                                }
                            });
                            return; // Don't call callback yet
                        }
                        
                        if (callback != null) callback.onError(error);
                    }
                } finally {
                    response.close();
                }
            }
        });
    }
    
    /**
     * Get upload statistics
     */
    public String getStats() {
        return String.format("Uploads: %d success, %d failed, %.2f MB total",
            successfulUploads, failedUploads, totalBytesUploaded / (1024.0 * 1024.0));
    }
    
    /**
     * Get total bytes uploaded to cloud relay (for data tracking in cloud mode)
     */
    public long getTotalBytesUploaded() {
        return totalBytesUploaded;
    }
    
    /**
     * Reset statistics
     */
    public void resetStats() {
        successfulUploads = 0;
        failedUploads = 0;
        totalBytesUploaded = 0;
    }
    
    /**
     * Check if ready for upload (has token or refresh token, and user UUID available)
     */
    public boolean isReady() {
        // If we have a valid token OR a refresh token, we can proceed
        boolean hasToken = authManager.getJwtToken() != null;
        boolean hasRefresh = authManager.getRefreshToken() != null;
        boolean hasUuid = getUserUuid() != null || authManager.getUserId() != null;
        
        return (hasToken || hasRefresh) && hasUuid;
    }
    
    /**
     * Callback interface for upload results
     */
    public interface UploadCallback {
        void onSuccess();
        void onError(String error);
    }
    
    // =========================================================================
    // Status API - Push status to relay for dashboard to read
    // =========================================================================
    
    /**
     * Upload status JSON to relay.
     * Dashboard reads this to display device state.
     * URL: PUT /api/status/{user_uuid}/{device_id}
     * 
     * @param statusJson Status JSON string (from RemoteStreamManager.getStatusJson())
     * @param callback Optional callback for upload result
     */
    public void uploadStatus(String statusJson, @Nullable UploadCallback callback) {
        if (!isEnabled) {
            return;
        }
        
        String url = buildStatusUrl();
        if (url == null) {
            if (callback != null) callback.onError("Failed to build status URL");
            return;
        }
        
        uploadBytes(url, statusJson.getBytes(), MEDIA_TYPE_JSON, callback);
    }
    
    /**
     * Build the status API URL.
     * Format: https://live.fadseclab.com:8443/api/status/{user_uuid}/{device_id}
     */
    @Nullable
    private String buildStatusUrl() {
        String userUuid = getUserUuid();
        if (userUuid == null) {
            Log.e(TAG, "Cannot build status URL: no user UUID");
            return null;
        }
        
        String deviceId = authManager.getDeviceId();
        return RELAY_BASE_URL + "/api/status/" + userUuid + "/" + deviceId;
    }
    
    // =========================================================================
    // Command API - Poll commands from relay, execute, then delete
    // =========================================================================
    
    /**
     * Poll for pending commands from relay.
     * Dashboard sends commands here for phone to execute.
     * URL: GET /api/command/{user_uuid}/{device_id}
     * 
     * Returns list of command file names (e.g., ["1234567890.json", "1234567891.json"])
     * Use fetchCommand() to get the actual command content.
     * 
     * @param callback Callback with list of command IDs or error
     */
    public void pollCommands(@NonNull CommandListCallback callback) {
        String url = buildCommandUrl();
        if (url == null) {
            callback.onError("Failed to build command URL");
            return;
        }
        
        String token = authManager.getJwtToken();
        if (token == null) {
            callback.onError("No valid JWT token");
            return;
        }
        
        Request request = new Request.Builder()
            .url(url)
            .get()
            .addHeader("Authorization", "Bearer " + token)
            .build();
        
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callback.onError("Network error: " + e.getMessage());
            }
            
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try {
                    if (response.isSuccessful()) {
                        String body = response.body() != null ? response.body().string() : "[]";
                        List<String> commandIds = parseCommandList(body);
                        callback.onSuccess(commandIds);
                    } else if (response.code() == 404) {
                        // No commands directory yet - normal case
                        callback.onSuccess(new ArrayList<>());
                    } else {
                        callback.onError("HTTP " + response.code());
                    }
                } catch (Exception e) {
                    callback.onError("Parse error: " + e.getMessage());
                } finally {
                    response.close();
                }
            }
        });
    }
    
    /**
     * Parse nginx autoindex JSON response to extract command file names.
     * Format: [{"name":"1234567890.json","type":"file",...}, ...]
     */
    private List<String> parseCommandList(String json) {
        List<String> commands = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                String name = item.optString("name", "");
                String type = item.optString("type", "");
                if ("file".equals(type) && name.endsWith(".json")) {
                    // Extract command ID (remove .json extension)
                    String cmdId = name.replace(".json", "");
                    commands.add(cmdId);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse command list: " + e.getMessage());
        }
        return commands;
    }
    
    /**
     * Fetch a specific command's content.
     * URL: GET /api/command/{user_uuid}/{device_id}/{cmd_id}.json
     * 
     * @param commandId The command ID (millisecond timestamp)
     * @param callback Callback with command JSON or error
     */
    public void fetchCommand(String commandId, @NonNull CommandCallback callback) {
        String url = buildCommandUrl() + "/" + commandId + ".json";
        
        String token = authManager.getJwtToken();
        if (token == null) {
            callback.onError("No valid JWT token");
            return;
        }
        
        Request request = new Request.Builder()
            .url(url)
            .get()
            .addHeader("Authorization", "Bearer " + token)
            .build();
        
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callback.onError("Network error: " + e.getMessage());
            }
            
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try {
                    if (response.isSuccessful()) {
                        String body = response.body() != null ? response.body().string() : "{}";
                        JSONObject command = new JSONObject(body);
                        callback.onSuccess(commandId, command);
                    } else {
                        callback.onError("HTTP " + response.code());
                    }
                } catch (Exception e) {
                    callback.onError("Parse error: " + e.getMessage());
                } finally {
                    response.close();
                }
            }
        });
    }
    
    /**
     * Delete a command after execution.
     * URL: DELETE /api/command/{user_uuid}/{device_id}/{cmd_id}
     * 
     * @param commandId The command ID to delete
     * @param callback Optional callback for result
     */
    public void deleteCommand(String commandId, @Nullable UploadCallback callback) {
        // Build URL without .json extension (as per nginx config)
        String url = buildCommandUrl() + "/" + commandId;
        
        String token = authManager.getJwtToken();
        if (token == null) {
            if (callback != null) callback.onError("No valid JWT token");
            return;
        }
        
        Request request = new Request.Builder()
            .url(url)
            .delete()
            .addHeader("Authorization", "Bearer " + token)
            .build();
        
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (callback != null) callback.onError("Network error: " + e.getMessage());
            }
            
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try {
                    if (response.isSuccessful() || response.code() == 404) {
                        // 404 is OK - command might already be deleted
                        if (callback != null) callback.onSuccess();
                    } else {
                        if (callback != null) callback.onError("HTTP " + response.code());
                    }
                } finally {
                    response.close();
                }
            }
        });
    }
    
    /**
     * Build the command API URL.
     * Format: https://live.fadseclab.com:8443/api/command/{user_uuid}/{device_id}
     */
    @Nullable
    private String buildCommandUrl() {
        String userUuid = getUserUuid();
        if (userUuid == null) {
            Log.e(TAG, "Cannot build command URL: no user UUID");
            return null;
        }
        
        String deviceId = authManager.getDeviceId();
        return RELAY_BASE_URL + "/api/command/" + userUuid + "/" + deviceId;
    }
    
    /**
     * Callback interface for command list polling
     */
    public interface CommandListCallback {
        void onSuccess(List<String> commandIds);
        void onError(String error);
    }
    
    /**
     * Callback interface for single command fetch
     */
    public interface CommandCallback {
        void onSuccess(String commandId, JSONObject command);
        void onError(String error);
    }
}
