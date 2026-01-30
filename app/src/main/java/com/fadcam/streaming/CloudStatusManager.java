package com.fadcam.streaming;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CloudStatusManager handles status push and command polling for cloud mode.
 * 
 * This is SEPARATE from video streaming:
 * - Status push works when the local server is ON (regardless of recording/streaming)
 * - Dashboard can see device status and send commands even when phone isn't streaming video
 * 
 * Architecture:
 * - Singleton pattern
 * - Runs periodic status push to relay: PUT /api/status/{user_uuid}/{device_id}
 * - Polls commands from relay: GET /api/command/{user_uuid}/{device_id}/
 * - Executes commands via localhost HTTP to LiveM3U8Server
 * 
 * Lifecycle:
 * - Started by RemoteStreamService when server starts AND cloud mode is enabled
 * - Stopped by RemoteStreamService when server stops
 * 
 * Thread-safe: Uses Handler for scheduling, ExecutorService for HTTP calls.
 */
public class CloudStatusManager {
    private static final String TAG = "CloudStatusManager";
    
    // Push interval: 2 seconds for status, 3 seconds for commands
    private static final long STATUS_PUSH_INTERVAL_MS = 2000;
    private static final long COMMAND_POLL_INTERVAL_MS = 3000;
    
    // Singleton instance
    private static CloudStatusManager instance;
    
    private final Context context;
    private final CloudAuthManager authManager;
    private final Handler handler;
    private final ExecutorService executor;
    
    // Supabase Realtime for instant commands
    private SupabaseRealtimeClient realtimeClient;
    
    // State
    private boolean isRunning = false;
    private Runnable statusRunnable;
    private Runnable commandRunnable;
    private int statusPushCount = 0;
    private int realtimeCommandCount = 0;
    
    private CloudStatusManager(Context context) {
        this.context = context.getApplicationContext();
        this.authManager = CloudAuthManager.getInstance(context);
        this.handler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newSingleThreadExecutor();
    }
    
    /**
     * Get singleton instance
     */
    public static synchronized CloudStatusManager getInstance(Context context) {
        if (instance == null) {
            instance = new CloudStatusManager(context);
        }
        return instance;
    }
    
    /**
     * Check if cloud mode is enabled in preferences
     */
    private boolean isCloudModeEnabled() {
        SharedPreferences prefs = context.getSharedPreferences("FadCamCloudPrefs", Context.MODE_PRIVATE);
        int mode = prefs.getInt("streaming_mode", 0);
        return mode == 1; // MODE_CLOUD = 1
    }
    
    /**
     * Check if user is linked and has valid credentials
     */
    private boolean isCloudReady() {
        boolean hasToken = authManager.getJwtToken() != null;
        boolean hasRefresh = authManager.getRefreshToken() != null;
        boolean hasUuid = authManager.getUserId() != null;
        return (hasToken || hasRefresh) && hasUuid;
    }
    
    /**
     * Start status push and command polling.
     * Called by RemoteStreamService when server starts.
     * Will only actually start if cloud mode is enabled and user is linked.
     */
    public void start() {
        if (isRunning) {
            Log.d(TAG, "Already running, ignoring start");
            return;
        }
        
        if (!isCloudModeEnabled()) {
            Log.d(TAG, "Cloud mode not enabled, not starting");
            return;
        }
        
        if (!isCloudReady()) {
            Log.d(TAG, "Cloud not ready (no credentials), not starting");
            return;
        }
        
        isRunning = true;
        statusPushCount = 0;
        
        // Start status push loop
        statusRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isRunning) {
                    Log.w(TAG, "☁️ Status push loop stopped (isRunning=false)");
                    return;
                }
                
                try {
                    pushStatus();
                    statusPushCount++;
                } catch (Exception e) {
                    Log.e(TAG, "☁️ Exception in pushStatus (will retry): " + e.getMessage());
                }
                
                // Always reschedule regardless of errors
                handler.postDelayed(this, STATUS_PUSH_INTERVAL_MS);
            }
        };
        handler.post(statusRunnable);
        
        // Start command poll loop (slightly offset from status push)
        commandRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isRunning) {
                    Log.w(TAG, "☁️ Command poll loop stopped (isRunning=false)");
                    return;
                }
                
                try {
                    pollCommands();
                } catch (Exception e) {
                    Log.e(TAG, "☁️ Exception in pollCommands (will retry): " + e.getMessage());
                }
                
                // Always reschedule regardless of errors
                handler.postDelayed(this, COMMAND_POLL_INTERVAL_MS);
            }
        };
        handler.postDelayed(commandRunnable, 500); // Start 500ms after status push
        
        // Start Supabase Realtime for instant command delivery
        startSupabaseRealtime();
        
        Log.i(TAG, "☁️ Cloud status manager started (status: " + STATUS_PUSH_INTERVAL_MS + 
              "ms, commands: " + COMMAND_POLL_INTERVAL_MS + "ms, realtime: enabled)");
    }
    
    /**
     * Start Supabase Realtime connection for instant command delivery.
     * This provides <200ms command latency vs 3s polling.
     */
    private void startSupabaseRealtime() {
        String deviceId = getDeviceId();
        String userId = authManager.getUserId();
        
        if (deviceId == null || userId == null) {
            Log.w(TAG, "☁️ Cannot start Realtime: missing deviceId or userId");
            return;
        }
        
        try {
            realtimeClient = new SupabaseRealtimeClient(deviceId, userId, (action, params) -> {
                // Command received instantly via WebSocket
                realtimeCommandCount++;
                Log.i(TAG, "☁️ ⚡ INSTANT COMMAND #" + realtimeCommandCount + ": " + action);
                
                // Execute command via local server (same as polling)
                executeCommandFromRealtime(action, params);
            });
            
            realtimeClient.connect();
            Log.i(TAG, "☁️ Supabase Realtime connecting for device: " + deviceId);
            
        } catch (Exception e) {
            Log.e(TAG, "☁️ Failed to start Supabase Realtime: " + e.getMessage());
            // Fallback to polling (already running)
        }
    }
    
    /**
     * Execute command received via Supabase Realtime.
     * Maps action to local endpoint and calls LiveM3U8Server.
     */
    private void executeCommandFromRealtime(String action, org.json.JSONObject params) {
        try {
            Log.i(TAG, "☁️ Executing realtime command: " + action);
            
            // Map action to local endpoint (same mapping as executeCommand)
            String endpoint = null;
            String method = "POST";
            String requestBody = null;
            
            switch (action) {
                // Torch commands
                case "torch_toggle":
                    endpoint = "/torch/toggle";
                    break;
                case "torch_on":
                    endpoint = "/torch/on";
                    break;
                case "torch_off":
                    endpoint = "/torch/off";
                    break;
                    
                // Camera commands
                case "camera_switch":
                    endpoint = "/camera/switch";
                    break;
                case "camera_front":
                    endpoint = "/camera/set";
                    requestBody = "front";
                    break;
                case "camera_back":
                    endpoint = "/camera/set";
                    requestBody = "back";
                    break;
                    
                // Alarm commands (dashboard sends alarm_ring, alarm_stop)
                case "alarm_ring":
                    endpoint = "/alarm/ring";
                    if (params != null && params.length() > 0) {
                        requestBody = params.toString();
                    }
                    break;
                case "alarm_stop":
                    endpoint = "/alarm/stop";
                    break;
                    
                // Volume command (dashboard sends audio_volume)
                case "audio_volume":
                    endpoint = "/audio/volume";
                    if (params != null) {
                        requestBody = params.toString();
                    }
                    break;
                    
                // Recording commands
                case "recording_toggle":
                    endpoint = "/recording/toggle";
                    break;
                case "recording_start":
                    endpoint = "/recording/start";
                    break;
                case "recording_stop":
                    endpoint = "/recording/stop";
                    break;
                    
                // Config commands
                case "config_recordingMode":
                    endpoint = "/config/recordingMode";
                    if (params != null) {
                        requestBody = params.toString();
                    }
                    break;
                case "config_streamQuality":
                    endpoint = "/config/streamQuality";
                    if (params != null) {
                        requestBody = params.toString();
                    }
                    break;
                    
                // Server commands
                case "server_toggle":
                    endpoint = "/server/toggle";
                    break;
                    
                default:
                    Log.w(TAG, "☁️ Unknown realtime command action: " + action);
                    return;
            }
            
            // Execute command via localhost (run on background thread)
            final String finalEndpoint = endpoint;
            final String finalBody = requestBody;
            executor.execute(() -> {
                executeLocalCommand(finalEndpoint, method, finalBody);
            });
            
        } catch (Exception e) {
            Log.e(TAG, "☁️ Failed to execute realtime command: " + e.getMessage());
        }
    }

    /**
     * Stop status push and command polling.
     * Called by RemoteStreamService when server stops.
     */
    public void stop() {
        if (!isRunning) {
            return;
        }
        
        isRunning = false;
        
        // Stop Supabase Realtime
        if (realtimeClient != null) {
            realtimeClient.disconnect();
            realtimeClient = null;
            Log.i(TAG, "☁️ Supabase Realtime disconnected (received " + realtimeCommandCount + " instant commands)");
        }
        
        if (statusRunnable != null) {
            handler.removeCallbacks(statusRunnable);
            statusRunnable = null;
        }
        
        if (commandRunnable != null) {
            handler.removeCallbacks(commandRunnable);
            commandRunnable = null;
        }
        
        Log.i(TAG, "☁️ Cloud status manager stopped (pushed " + statusPushCount + " status updates)");
    }
    
    /**
     * Check if the manager is currently running
     */
    public boolean isRunning() {
        return isRunning;
    }
    
    // =========================================================================
    // Status Push
    // =========================================================================
    
    /**
     * Push current device status to relay server.
     * Uses RemoteStreamManager to get status JSON.
     */
    private void pushStatus() {
        String statusJson = RemoteStreamManager.getInstance().getStatusJson();
        String userUuid = authManager.getUserId();
        String deviceId = getDeviceId();
        
        // Check token expiry BEFORE getting token
        boolean tokenExpired = authManager.isTokenExpired();
        String jwt = authManager.getJwtToken();
        
        // Debug logging for missing or expired credentials
        if (userUuid == null || deviceId == null || jwt == null || tokenExpired) {
            Log.w(TAG, "Missing/expired credentials for status push: " +
                    "userUuid=" + (userUuid != null ? "OK" : "NULL") +
                    ", deviceId=" + (deviceId != null ? "OK" : "NULL") +
                    ", jwt=" + (jwt != null ? "OK" : "NULL") +
                    ", expired=" + tokenExpired);
            
            // Try to refresh token if we have a refresh token
            if ((jwt == null || tokenExpired) && authManager.getRefreshToken() != null) {
                Log.i(TAG, "Attempting token refresh (expired=" + tokenExpired + ")...");
                authManager.refreshTokenAsync(new CloudAuthManager.TokenRefreshListener() {
                    @Override
                    public void onRefreshSuccess(String newToken, long newExpiry) {
                        Log.i(TAG, "Token refreshed successfully, will retry on next push");
                    }
                    
                    @Override
                    public void onRefreshFailed(String error) {
                        Log.e(TAG, "Token refresh failed: " + error);
                    }
                });
            }
            return;
        }
        
        String urlStr = CloudStreamUploader.RELAY_BASE_URL + "/api/status/" + userUuid + "/" + deviceId;
        
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) java.net.URI.create(urlStr).toURL().openConnection();
                conn.setRequestMethod("PUT");
                conn.setRequestProperty("Authorization", "Bearer " + jwt);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(statusJson.getBytes("UTF-8"));
                }
                
                int responseCode = conn.getResponseCode();
                if (responseCode >= 200 && responseCode < 300) {
                    // Success - silent
                } else {
                    Log.w(TAG, "Status push failed: HTTP " + responseCode);
                }
            } catch (Exception e) {
                Log.w(TAG, "Status push error: " + e.getMessage());
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }
    
    // =========================================================================
    // Command Polling
    // =========================================================================
    
    /**
     * Poll for pending commands from relay server.
     */
    private void pollCommands() {
        String userUuid = authManager.getUserId();
        String deviceId = getDeviceId();
        boolean tokenExpired = authManager.isTokenExpired();
        String jwt = authManager.getJwtToken();
        
        if (userUuid == null || deviceId == null || jwt == null || tokenExpired) {
            // Token expired - let pushStatus() handle the refresh
            return;
        }
        
        String urlStr = CloudStreamUploader.RELAY_BASE_URL + "/api/command/" + userUuid + "/" + deviceId + "/";
        
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) java.net.URI.create(urlStr).toURL().openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "Bearer " + jwt);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                
                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    // Parse directory listing for command files
                    java.io.InputStream is = conn.getInputStream();
                    java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
                    String body = s.hasNext() ? s.next() : "";
                    is.close();
                    
                    // Parse nginx autoindex JSON: [{"name":"file.json","type":"file",...}, ...]
                    JSONArray files = new JSONArray(body);
                    int commandCount = 0;
                    for (int i = 0; i < files.length(); i++) {
                        JSONObject fileEntry = files.getJSONObject(i);
                        String cmdFile = fileEntry.getString("name");
                        if (cmdFile.endsWith(".json")) {
                            commandCount++;
                        }
                    }
                    
                    if (commandCount > 0) {
                        Log.i(TAG, "☁️ Found " + commandCount + " pending cloud commands");
                        for (int i = 0; i < files.length(); i++) {
                            JSONObject fileEntry = files.getJSONObject(i);
                            String cmdFile = fileEntry.getString("name");
                            if (cmdFile.endsWith(".json")) {
                                String cmdId = cmdFile.replace(".json", "");
                                fetchAndExecuteCommand(cmdId);
                            }
                        }
                    }
                } else if (responseCode == 404) {
                    // No commands - expected when directory doesn't exist
                }
            } catch (Exception e) {
                // Silent - commands might not exist yet
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }
    
    /**
     * Fetch a specific command and execute it
     */
    private void fetchAndExecuteCommand(String cmdId) {
        String userUuid = authManager.getUserId();
        String deviceId = getDeviceId();
        boolean tokenExpired = authManager.isTokenExpired();
        String jwt = authManager.getJwtToken();
        
        if (userUuid == null || deviceId == null || jwt == null || tokenExpired) {
            Log.w(TAG, "☁️ Cannot fetch command " + cmdId + " - auth not ready");
            return;
        }
        
        String urlStr = CloudStreamUploader.RELAY_BASE_URL + "/api/command/" + userUuid + "/" + deviceId + "/" + cmdId + ".json";
        Log.d(TAG, "☁️ Fetching command: " + cmdId);
        
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) java.net.URI.create(urlStr).toURL().openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "Bearer " + jwt);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                
                int responseCode = conn.getResponseCode();
                Log.d(TAG, "☁️ Command fetch response: " + responseCode);
                if (responseCode == 200) {
                    java.io.InputStream is = conn.getInputStream();
                    java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
                    String body = s.hasNext() ? s.next() : "";
                    is.close();
                    
                    JSONObject command = new JSONObject(body);
                    executeCommand(cmdId, command);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to fetch command " + cmdId + ": " + e.getMessage());
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }
    
    /**
     * Execute a command by calling the local LiveM3U8Server endpoint
     */
    private void executeCommand(String cmdId, JSONObject command) {
        try {
            String action = command.getString("action");
            Log.i(TAG, "☁️ Executing cloud command: " + action);
            
            // Map action to local endpoint
            String endpoint = null;
            String method = "POST";
            String requestBody = null;
            
            switch (action) {
                case "torch_toggle":
                    endpoint = "/torch/toggle";
                    break;
                case "torch_on":
                    endpoint = "/torch/on";
                    break;
                case "torch_off":
                    endpoint = "/torch/off";
                    break;
                case "camera_switch":
                    endpoint = "/camera/switch";
                    break;
                case "camera_front":
                    endpoint = "/camera/set";
                    requestBody = "front";
                    break;
                case "camera_back":
                    endpoint = "/camera/set";
                    requestBody = "back";
                    break;
                case "alarm_start":
                    endpoint = "/alarm/start";
                    // Check for params
                    if (command.has("params")) {
                        JSONObject params = command.getJSONObject("params");
                        requestBody = params.toString();
                    }
                    break;
                case "alarm_stop":
                    endpoint = "/alarm/stop";
                    break;
                case "volume_set":
                    endpoint = "/volume/set";
                    if (command.has("params")) {
                        JSONObject params = command.getJSONObject("params");
                        requestBody = String.valueOf(params.optInt("level", 50));
                    }
                    break;
                case "recording_start":
                    endpoint = "/recording/start";
                    break;
                case "recording_stop":
                    endpoint = "/recording/stop";
                    break;
                default:
                    Log.w(TAG, "Unknown cloud command action: " + action);
                    deleteCommand(cmdId);
                    return;
            }
            
            // Execute command via localhost
            executeLocalCommand(endpoint, method, requestBody);
            
            // Delete command after execution
            deleteCommand(cmdId);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to execute command " + cmdId + ": " + e.getMessage());
            deleteCommand(cmdId);
        }
    }
    
    /**
     * Execute command by calling local LiveM3U8Server
     */
    private void executeLocalCommand(String endpoint, String method, String body) {
        // Get server port from RemoteStreamService
        int port = getServerPort();
        Log.d(TAG, "☁️ executeLocalCommand - port=" + port + ", endpoint=" + endpoint);
        if (port <= 0) {
            Log.w(TAG, "☁️ Server port not available (port=" + port + ")");
            return;
        }
        
        String urlStr = "http://localhost:" + port + endpoint;
        Log.i(TAG, "☁️ Calling local endpoint: " + urlStr);
        
        try {
            HttpURLConnection conn = (HttpURLConnection) java.net.URI.create(urlStr).toURL().openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            
            if (body != null && "POST".equals(method)) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "text/plain");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes("UTF-8"));
                }
            }
            
            int responseCode = conn.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                Log.i(TAG, "☁️ Command executed successfully: " + endpoint + " -> HTTP " + responseCode);
            } else {
                Log.w(TAG, "☁️ Command execution failed: " + endpoint + " -> HTTP " + responseCode);
            }
            conn.disconnect();
            
        } catch (Exception e) {
            Log.e(TAG, "☁️ Local command failed: " + endpoint + " - " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
    
    /**
     * Delete a command from the relay after execution
     */
    private void deleteCommand(String cmdId) {
        String userUuid = authManager.getUserId();
        String deviceId = getDeviceId();
        boolean tokenExpired = authManager.isTokenExpired();
        String jwt = authManager.getJwtToken();
        
        if (userUuid == null || deviceId == null || jwt == null || tokenExpired) {
            return;
        }
        
        String urlStr = CloudStreamUploader.RELAY_BASE_URL + "/api/command/" + userUuid + "/" + deviceId + "/" + cmdId;
        
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) java.net.URI.create(urlStr).toURL().openConnection();
                conn.setRequestMethod("DELETE");
                conn.setRequestProperty("Authorization", "Bearer " + jwt);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                
                int responseCode = conn.getResponseCode();
                if (responseCode >= 200 && responseCode < 300) {
                    Log.d(TAG, "☁️ Command deleted from relay: " + cmdId);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to delete command: " + e.getMessage());
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }
    
    // =========================================================================
    // Helpers
    // =========================================================================
    
    /**
     * Get device ID from CloudAuthManager (uses ANDROID_ID)
     */
    private String getDeviceId() {
        return authManager.getDeviceId();
    }
    
    /**
     * Get current server port from RemoteStreamService
     */
    private int getServerPort() {
        // Try to get from preferences where RemoteStreamService saves it
        SharedPreferences prefs = context.getSharedPreferences("FadCamPrefs", Context.MODE_PRIVATE);
        return prefs.getInt("stream_server_port", 8080);
    }
}
