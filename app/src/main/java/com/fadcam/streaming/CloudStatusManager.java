package com.fadcam.streaming;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
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
    
    // Listener interface for auth/credential errors
    public interface AuthErrorListener {
        void onAuthError(String reason);
    }
    
    // Push interval: 2 seconds for status, 1.5 seconds for commands (Realtime disabled), 30 seconds for viewers
    private static final long STATUS_PUSH_INTERVAL_MS = 2000;
    private static final long COMMAND_POLL_INTERVAL_MS = 1500; // Reduced from 3000 since Realtime is disabled
    private static final long VIEWERS_POLL_INTERVAL_MS = 30000; // Poll cloud viewers every 30s
    
    // Failure tracking for robust recovery (Step 6.11)
    private static final int MAX_CONSECUTIVE_FAILURES = 10;
    private static final long MAX_BACKOFF_MS = 8000;  // Max delay after repeated failures
    private int consecutiveFailures = 0;
    private long currentBackoffMs = STATUS_PUSH_INTERVAL_MS;
    private long lastSuccessfulPushTime = 0;
    
    // Singleton instance
    private static CloudStatusManager instance;
    
    private final Context context;
    private final CloudAuthManager authManager;
    private final Handler handler;
    private final ExecutorService executor;
    
    // Error listener for UI feedback
    private AuthErrorListener authErrorListener;
    private boolean authErrorShown = false;  // Track if error already shown to prevent toast spam
    
    // Stream token fetch cooldown to prevent hammering after repeated failures
    private long lastStreamTokenFetchFailureTime = 0;
    private static final long STREAM_TOKEN_FETCH_COOLDOWN_MS = 30000;  // Wait 30s after failure before retrying
    
    // State
    private boolean isRunning = false;
    private Runnable statusRunnable;
    private Runnable commandRunnable;
    private Runnable viewersRunnable;
    private int statusPushCount = 0;
    
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
     * Set listener for authentication/credential errors
     * Called by RemoteFragment to display error messages to user
     */
    public void setAuthErrorListener(AuthErrorListener listener) {
        this.authErrorListener = listener;
    }
    
    /**
     * Notify listener of authentication error (called on main thread)
     * Only notifies once to avoid toast spam - subsequent errors are logged but not shown
     */
    private void notifyAuthError(String reason) {
        if (!authErrorShown && authErrorListener != null) {
            authErrorShown = true;  // Mark as shown - don't show again
            handler.post(() -> authErrorListener.onAuthError(reason));
        } else if (authErrorShown) {
            FLog.d(TAG, "☁️ Auth error already shown, suppressing additional notification: " + reason);
        }
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
        boolean hasStreamToken = authManager.getStreamToken() != null;
        boolean ready = ((hasToken || hasRefresh) || hasStreamToken) && hasUuid;
        
        // Debug logging to trace why cloud might not be ready
        FLog.d(TAG, "☁️ isCloudReady check: hasToken=" + hasToken 
            + ", hasRefresh=" + hasRefresh 
            + ", hasUuid=" + hasUuid 
            + ", hasStreamToken=" + hasStreamToken 
            + ", ready=" + ready);
        
        return ready;
    }
    
    /**
     * Start status push and command polling.
     * Called by RemoteStreamService when server starts.
     * Will only actually start if cloud mode is enabled and user is linked.
     */
    public void start() {
        FLog.i(TAG, "☁️ start() called");
        
        if (isRunning) {
            FLog.d(TAG, "☁️ Already running, ignoring start");
            return;
        }
        
        if (!isCloudModeEnabled()) {
            FLog.d(TAG, "☁️ Cloud mode not enabled, not starting");
            return;
        }
        
        if (!isCloudReady()) {
            FLog.d(TAG, "☁️ Cloud not ready (no credentials), not starting");
            return;
        }
        
        FLog.i(TAG, "☁️ Starting status push and command polling...");
        
        isRunning = true;
        statusPushCount = 0;
        
        // Start status push loop
        statusRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isRunning) {
                    FLog.w(TAG, "☁️ Status push loop stopped (isRunning=false)");
                    return;
                }
                
                try {
                    pushStatus();
                    statusPushCount++;
                } catch (Exception e) {
                    FLog.e(TAG, "☁️ Exception in pushStatus (will retry): " + e.getMessage());
                    onPushFailure("Exception: " + e.getMessage());
                }
                
                // Use dynamic delay with exponential backoff during failures
                handler.postDelayed(this, getCurrentPushDelay());
            }
        };
        handler.post(statusRunnable);
        
        // Reset failure counters on fresh start
        consecutiveFailures = 0;
        currentBackoffMs = STATUS_PUSH_INTERVAL_MS;
        lastSuccessfulPushTime = System.currentTimeMillis();
        
        // Start command poll loop (slightly offset from status push)
        commandRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isRunning) {
                    FLog.w(TAG, "☁️ Command poll loop stopped (isRunning=false)");
                    return;
                }
                
                try {
                    pollCommands();
                } catch (Exception e) {
                    FLog.e(TAG, "☁️ Exception in pollCommands (will retry): " + e.getMessage());
                }
                
                // Always reschedule regardless of errors
                handler.postDelayed(this, COMMAND_POLL_INTERVAL_MS);
            }
        };
        handler.postDelayed(commandRunnable, 500); // Start 500ms after status push
        
        // Start cloud viewers poll loop (every 30 seconds)
        viewersRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isRunning) {
                    FLog.w(TAG, "☁️ Viewers poll loop stopped (isRunning=false)");
                    return;
                }
                
                try {
                    pollCloudViewers();
                } catch (Exception e) {
                    FLog.e(TAG, "☁️ Exception in pollCloudViewers (will retry): " + e.getMessage());
                }
                
                // Reschedule regardless of errors
                handler.postDelayed(this, VIEWERS_POLL_INTERVAL_MS);
            }
        };
        handler.postDelayed(viewersRunnable, 2000); // Start 2s after server starts
        
        FLog.i(TAG, "☁️ Cloud status manager started (status: " + STATUS_PUSH_INTERVAL_MS + 
              "ms, commands: " + COMMAND_POLL_INTERVAL_MS + "ms, viewers: " + VIEWERS_POLL_INTERVAL_MS + "ms)");
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
        
        if (statusRunnable != null) {
            handler.removeCallbacks(statusRunnable);
            statusRunnable = null;
        }
        
        if (commandRunnable != null) {
            handler.removeCallbacks(commandRunnable);
            commandRunnable = null;
        }
        
        if (viewersRunnable != null) {
            handler.removeCallbacks(viewersRunnable);
            viewersRunnable = null;
        }
        
        // Reset cloud viewer count when stopping
        RemoteStreamManager.getInstance().setCloudViewerCount(0);
        
        FLog.i(TAG, "☁️ Cloud status manager stopped (pushed " + statusPushCount + " status updates)");
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
     * Uses stream_access_token for authentication.
     */
    private void pushStatus() {
        String statusJson = RemoteStreamManager.getInstance().getStatusJson();
        String userUuid = authManager.getUserId();
        String deviceId = getDeviceId();
        
        // Try to get stream token first
        String streamToken = authManager.getStreamToken();
        
        if (userUuid == null || deviceId == null) {
            FLog.w(TAG, "Missing credentials for status push: " +
                    "userUuid=" + (userUuid != null ? "OK" : "NULL") +
                    ", deviceId=" + (deviceId != null ? "OK" : "NULL"));
            return;
        }
        
        // If no stream token, try to fetch one
        if (streamToken == null || authManager.isStreamTokenNearExpiry()) {
            // Check cooldown - don't hammer Supabase if fetch recently failed
            long timeSinceLastFailure = System.currentTimeMillis() - lastStreamTokenFetchFailureTime;
            if (timeSinceLastFailure < STREAM_TOKEN_FETCH_COOLDOWN_MS) {
                long cooldownRemaining = STREAM_TOKEN_FETCH_COOLDOWN_MS - timeSinceLastFailure;
                FLog.d(TAG, "☁️ Stream token fetch on cooldown for " + (cooldownRemaining / 1000) + "s (waiting after recent failure)");
                onPushFailure("Token fetch throttled (cooldown)");
                return;
            }
            
            FLog.i(TAG, "Stream token missing/expired, fetching new one...");
            authManager.getValidStreamTokenAsync(new CloudAuthManager.StreamTokenListener() {
                @Override
                public void onSuccess(String newStreamToken) {
                    FLog.i(TAG, "Stream token fetched, will use on next push");
                    // Actually push now with the new token
                    doPushStatus(statusJson, userUuid, deviceId, newStreamToken);
                }
                
                @Override
                public void onError(String error) {
                    FLog.e(TAG, "Stream token fetch failed: " + error);
                    lastStreamTokenFetchFailureTime = System.currentTimeMillis();  // Record failure time
                    onPushFailure("Token fetch failed: " + error);
                    // Notify UI of auth error (only once)
                    if (error.contains("401") || error.contains("Unauthorized")) {
                        notifyAuthError("Cloud authentication failed. Device may not be properly linked. Try re-linking from Cloud Account.");
                    }
                }
            });
            return;
        }
        
        // Have a valid stream token, push directly
        doPushStatus(statusJson, userUuid, deviceId, streamToken);
    }
    
    /**
     * Handle successful status push - reset failure counters
     */
    private void onPushSuccess() {
        if (consecutiveFailures > 0) {
            FLog.i(TAG, "☁️ ✅ Status push RECOVERED after " + consecutiveFailures + " failures (was offline for " + 
                    ((System.currentTimeMillis() - lastSuccessfulPushTime) / 1000) + "s)");
        }
        consecutiveFailures = 0;
        currentBackoffMs = STATUS_PUSH_INTERVAL_MS;
        lastSuccessfulPushTime = System.currentTimeMillis();
    }
    
    /**
     * Handle failed status push - increment counters and log
     */
    private void onPushFailure(String reason) {
        consecutiveFailures++;
        
        // Log with escalating severity
        if (consecutiveFailures == 1) {
            FLog.w(TAG, "☁️ ⚠️ Status push failed (" + consecutiveFailures + "/" + MAX_CONSECUTIVE_FAILURES + "): " + reason);
        } else if (consecutiveFailures <= 3) {
            FLog.w(TAG, "☁️ ⚠️ Status push still failing (" + consecutiveFailures + "/" + MAX_CONSECUTIVE_FAILURES + "): " + reason);
        } else if (consecutiveFailures == MAX_CONSECUTIVE_FAILURES) {
            FLog.e(TAG, "☁️ ❌ Status push MAX FAILURES reached (" + consecutiveFailures + "). Dashboard will show offline.");
        } else if (consecutiveFailures % 10 == 0) {
            // Log every 10 failures after max to avoid log spam
            FLog.e(TAG, "☁️ ❌ Status push offline for " + consecutiveFailures + " cycles (~" + 
                    (consecutiveFailures * currentBackoffMs / 1000) + "s)");
        }
        
        // Exponential backoff: 2s → 4s → 8s max
        if (consecutiveFailures >= 3 && currentBackoffMs < MAX_BACKOFF_MS) {
            currentBackoffMs = Math.min(currentBackoffMs * 2, MAX_BACKOFF_MS);
            FLog.d(TAG, "☁️ Increasing push interval to " + currentBackoffMs + "ms due to failures");
        }
    }
    
    /**
     * Get current push delay (with exponential backoff if failing)
     */
    private long getCurrentPushDelay() {
        return currentBackoffMs;
    }
    
    /**
     * Actually perform the status push with the provided token.
     * Includes failure tracking and recovery logging for production robustness.
     */
    private void doPushStatus(String statusJson, String userUuid, String deviceId, String token) {
        String urlStr = CloudStreamUploader.RELAY_BASE_URL + "/api/status/" + userUuid + "/" + deviceId;
        
        // Log cloud viewer count from the status being pushed (every 10th push to reduce log spam)
        if (statusPushCount % 10 == 0) {
            try {
                JSONObject status = new JSONObject(statusJson);
                int cloudViewers = status.optInt("cloudViewers", -1);
                int activeConnections = status.optInt("activeConnections", -1);
                FLog.i(TAG, "☁️ 📤 Status push #" + statusPushCount + 
                    " | cloudViewers=" + cloudViewers + 
                    " | activeConnections=" + activeConnections);
            } catch (Exception e) {
                FLog.d(TAG, "☁️ 📤 Status push #" + statusPushCount);
            }
        }
        FLog.d(TAG, "☁️ 📤 Pushing status to: " + urlStr);
        
        // Debug logging for token and device info
        long tokenExpiry = android.content.SharedPreferences.class.isInstance(authManager) ? 0 : 0;
        if (token != null && token.length() > 0) {
            String tokenDebug = token.length() > 20 ? 
                token.substring(0, 10) + "..." + token.substring(token.length() - 10) : 
                "***";
            FLog.d(TAG, "☁️ 📤 Auth token: " + tokenDebug + 
                    " | Device: " + deviceId + 
                    " | User: " + userUuid.substring(0, 8) + "...");
        }
        
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) java.net.URI.create(urlStr).toURL().openConnection();
                conn.setRequestMethod("PUT");  // Use PUT for WebDAV file write
                conn.setRequestProperty("Authorization", "Bearer " + token);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(statusJson.getBytes("UTF-8"));
                }
                
                int responseCode = conn.getResponseCode();
                FLog.d(TAG, "☁️ 📤 Push response: HTTP " + responseCode);
                if (responseCode >= 200 && responseCode < 300) {
                    // Success - track recovery
                    onPushSuccess();
                } else if (responseCode == 401) {
                    // Unauthorized - authentication/credentials issue
                    // Try to get more details from response
                    String errorDetail = "";
                    try {
                        java.io.InputStream errorStream = conn.getErrorStream();
                        if (errorStream != null) {
                            java.io.BufferedReader reader = new java.io.BufferedReader(
                                new java.io.InputStreamReader(errorStream));
                            StringBuilder sb = new StringBuilder();
                            String line;
                            while ((line = reader.readLine()) != null) {
                                sb.append(line);
                            }
                            reader.close();
                            errorDetail = " | Response: " + sb.toString();
                        }
                    } catch (Exception e) {
                        // Ignore
                    }
                    
                    FLog.e(TAG, "☁️ 📤 AUTH ERROR (401): Device not properly authenticated" + errorDetail);
                    FLog.d(TAG, "☁️ 📤 DEBUG: Checking token validity...");
                    FLog.d(TAG, "☁️ 📤 Token present: " + (token != null && !token.isEmpty()) +
                            " | User UUID: " + (userUuid != null) +
                            " | Device ID: " + (deviceId != null));
                    
                    onPushFailure("HTTP " + responseCode);
                    notifyAuthError("Cloud authentication failed (401). Device may not be properly linked. Try re-linking from Cloud Account.");
                } else {
                    onPushFailure("HTTP " + responseCode);
                }
            } catch (java.net.SocketTimeoutException e) {
                FLog.e(TAG, "☁️ 📤 Push timeout: " + e.getMessage());
                onPushFailure("Timeout: " + e.getMessage());
            } catch (java.io.IOException e) {
                FLog.e(TAG, "☁️ 📤 Push IO error: " + e.getMessage());
                onPushFailure("IO: " + e.getMessage());
            } catch (Exception e) {
                FLog.e(TAG, "☁️ 📤 Push exception: " + e.getMessage());
                onPushFailure("Error: " + e.getMessage());
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
     * Uses stream_access_token for authentication.
     */
    private void pollCommands() {
        String userUuid = authManager.getUserId();
        String deviceId = getDeviceId();
        String streamToken = authManager.getStreamToken();
        
        if (userUuid == null || deviceId == null || streamToken == null) {
            // No stream token - let pushStatus() handle the fetch
            return;
        }
        
        String urlStr = CloudStreamUploader.RELAY_BASE_URL + "/api/command/" + userUuid + "/" + deviceId + "/";
        
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) java.net.URI.create(urlStr).toURL().openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "Bearer " + streamToken);
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
                        FLog.i(TAG, "☁️ Found " + commandCount + " pending cloud commands");
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
     * Uses stream_access_token for authentication.
     */
    private void fetchAndExecuteCommand(String cmdId) {
        String userUuid = authManager.getUserId();
        String deviceId = getDeviceId();
        String streamToken = authManager.getStreamToken();
        
        if (userUuid == null || deviceId == null || streamToken == null) {
            FLog.w(TAG, "☁️ Cannot fetch command " + cmdId + " - auth not ready");
            return;
        }
        
        String urlStr = CloudStreamUploader.RELAY_BASE_URL + "/api/command/" + userUuid + "/" + deviceId + "/" + cmdId + ".json";
        FLog.d(TAG, "☁️ Fetching command: " + cmdId);
        
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) java.net.URI.create(urlStr).toURL().openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "Bearer " + streamToken);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                
                int responseCode = conn.getResponseCode();
                FLog.d(TAG, "☁️ Command fetch response: " + responseCode);
                if (responseCode == 200) {
                    java.io.InputStream is = conn.getInputStream();
                    java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
                    String body = s.hasNext() ? s.next() : "";
                    is.close();
                    
                    JSONObject command = new JSONObject(body);
                    executeCommand(cmdId, command);
                }
            } catch (Exception e) {
                FLog.e(TAG, "Failed to fetch command " + cmdId + ": " + e.getMessage());
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }
    
    /**
     * Execute a command by calling Android services directly.
     * The HTTP server (LiveM3U8Server) is disabled in cloud mode for security,
     * so all commands must be dispatched via intents/direct method calls.
     */
    private void executeCommand(String cmdId, JSONObject command) {
        try {
            String action = command.getString("action");
            FLog.i(TAG, "☁️ Executing cloud command: " + action);

            RemoteStreamManager manager = RemoteStreamManager.getInstance();
            com.fadcam.SharedPreferencesManager spManager = com.fadcam.SharedPreferencesManager.getInstance(context);

            if ("torch_toggle".equals(action)) {
                // Update state in RemoteStreamManager so the dashboard reflects it
                boolean newState = !manager.isTorchOn();
                manager.setTorchState(newState);

                // Always delegate to TorchService. It already has the correct routing:
                //   - if recording is in progress → delegates to RecordingService (CaptureRequest)
                //   - otherwise → CameraManager.setTorchMode() directly
                // Sending to RecordingService directly fails when not recording because
                // RecordingService has no active camera session in stream_only mode.
                android.content.Intent intent = new android.content.Intent(
                        context, com.fadcam.services.TorchService.class);
                intent.setAction(com.fadcam.Constants.INTENT_ACTION_TOGGLE_TORCH);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent);
                } else {
                    context.startService(intent);
                }
                FLog.i(TAG, "✅ Cloud torch toggle sent to TorchService. New state: " + newState);

            } else if ("recording_toggle".equals(action)) {
                // Start / stop / resume-if-paused – mirrors LiveM3U8Server.toggleRecording()
                boolean isRecording = spManager.isRecordingInProgress();
                boolean isPaused = manager.isPaused();

                android.content.Intent intent = new android.content.Intent(
                        context, com.fadcam.services.RecordingService.class);
                if (isPaused) {
                    intent.setAction(com.fadcam.Constants.INTENT_ACTION_RESUME_RECORDING);
                    FLog.d(TAG, "☁️ recording_toggle: recording paused → RESUME");
                } else if (isRecording) {
                    intent.setAction(com.fadcam.Constants.INTENT_ACTION_STOP_RECORDING);
                    FLog.d(TAG, "☁️ recording_toggle: recording active → STOP");
                } else {
                    intent.setAction(com.fadcam.Constants.INTENT_ACTION_START_RECORDING);
                    FLog.d(TAG, "☁️ recording_toggle: not recording → START");
                }
                com.fadcam.utils.ServiceStartPolicy.startRecordingAction(context, intent);
                FLog.i(TAG, "✅ Cloud recording toggle executed");

            } else if ("recording_pause".equals(action)) {
                if (!spManager.isRecordingInProgress()) {
                    FLog.w(TAG, "☁️ recording_pause: not recording, ignoring");
                } else {
                    android.content.Intent intent = new android.content.Intent(
                            context, com.fadcam.services.RecordingService.class);
                    intent.setAction(com.fadcam.Constants.INTENT_ACTION_PAUSE_RECORDING);
                    com.fadcam.utils.ServiceStartPolicy.startRecordingAction(context, intent);
                    FLog.i(TAG, "✅ Cloud recording pause sent");
                }

            } else if ("recording_resume".equals(action)) {
                if (!manager.isPaused()) {
                    FLog.w(TAG, "☁️ recording_resume: not paused, ignoring");
                } else {
                    android.content.Intent intent = new android.content.Intent(
                            context, com.fadcam.services.RecordingService.class);
                    intent.setAction(com.fadcam.Constants.INTENT_ACTION_RESUME_RECORDING);
                    com.fadcam.utils.ServiceStartPolicy.startRecordingAction(context, intent);
                    FLog.i(TAG, "✅ Cloud recording resume sent");
                }

            } else if ("alarm_ring".equals(action)) {
                // Ring alarm – mirrors LiveM3U8Server.ringAlarm()
                String soundFile = "office_phone.mp3";
                long durationMs = -1;
                if (command.has("params")) {
                    org.json.JSONObject params = command.getJSONObject("params");
                    soundFile = params.optString("sound", soundFile);
                    durationMs = params.optLong("duration_ms", durationMs);
                }
                manager.setSelectedAlarmSound(soundFile);
                manager.setAlarmDurationMs(durationMs);
                manager.setAlarmRinging(true);

                android.content.Intent alarmIntent = new android.content.Intent(
                        context, com.fadcam.services.AlarmService.class);
                alarmIntent.setAction("com.fadcam.action.RING_ALARM");
                alarmIntent.putExtra("sound", soundFile);
                alarmIntent.putExtra("duration_ms", durationMs);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(alarmIntent);
                } else {
                    context.startService(alarmIntent);
                }
                FLog.i(TAG, "✅ Cloud alarm ring sent: " + soundFile + " for " + durationMs + "ms");

            } else if ("alarm_stop".equals(action)) {
                // Stop alarm – mirrors LiveM3U8Server.stopAlarm()
                manager.setAlarmRinging(false);
                android.content.Intent alarmIntent = new android.content.Intent(
                        context, com.fadcam.services.AlarmService.class);
                alarmIntent.setAction("com.fadcam.action.STOP_ALARM");
                context.startService(alarmIntent);
                FLog.i(TAG, "✅ Cloud alarm stop sent");

            } else if ("audio_volume".equals(action)) {
                // Set volume
                if (command.has("params")) {
                    org.json.JSONObject params = command.getJSONObject("params");
                    if (params.has("percentage")) {
                        float percentage = (float) params.getDouble("percentage");
                        
                        // Set system volume like LiveM3U8Server does
                        android.media.AudioManager audioManager = (android.media.AudioManager) context.getSystemService(android.content.Context.AUDIO_SERVICE);
                        if (audioManager != null) {
                            int maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
                            int targetVolume = Math.round(percentage * maxVolume / 100.0f);
                            targetVolume = Math.max(0, Math.min(targetVolume, maxVolume));
                            
                            audioManager.setStreamVolume(
                                android.media.AudioManager.STREAM_MUSIC, 
                                targetVolume, 
                                0  // No UI flags (silent change)
                            );
                            
                            // Update RemoteStreamManager state
                            manager.setMediaVolume(targetVolume);
                            
                            FLog.i(TAG, "✅ Cloud volume set to " + percentage + "% (" + targetVolume + "/" + maxVolume + ")");
                        } else {
                            FLog.e(TAG, "AudioManager not available for volume change");
                        }
                    }
                }
                
            } else if ("config_recordingMode".equals(action)) {
                // Set stream/recording mode – mirrors LiveM3U8Server.setRecordingMode()
                if (command.has("params")) {
                    org.json.JSONObject params = command.getJSONObject("params");
                    String mode = params.optString("mode", "");
                    RemoteStreamManager.StreamingMode streamingMode;
                    if ("stream_only".equals(mode)) {
                        streamingMode = RemoteStreamManager.StreamingMode.STREAM_ONLY;
                    } else if ("stream_and_save".equals(mode)) {
                        streamingMode = RemoteStreamManager.StreamingMode.STREAM_AND_SAVE;
                    } else {
                        FLog.w(TAG, "☁️ config_recordingMode: invalid mode: " + mode);
                        deleteCommand(cmdId);
                        return;
                    }
                    spManager.setStreamingMode(streamingMode);
                    manager.setStreamingMode(streamingMode);
                    FLog.i(TAG, "✅ Cloud recording mode set to: " + mode);
                }

            } else if ("config_streamQuality".equals(action)) {
                // Set stream quality preset – mirrors LiveM3U8Server.setStreamQuality()
                if (command.has("params")) {
                    org.json.JSONObject params = command.getJSONObject("params");
                    String quality = params.optString("quality", "").trim().toUpperCase();
                    try {
                        com.fadcam.streaming.model.StreamQuality.Preset preset =
                                com.fadcam.streaming.model.StreamQuality.Preset.valueOf(quality);
                        manager.setStreamQuality(preset, context);
                        FLog.i(TAG, "✅ Cloud stream quality set to: " + quality);
                    } catch (IllegalArgumentException e) {
                        FLog.w(TAG, "☁️ config_streamQuality: invalid preset: " + quality);
                    }
                }

            } else if ("config_batteryWarning".equals(action)) {
                // Set battery warning threshold – mirrors LiveM3U8Server.setBatteryWarning()
                if (command.has("params")) {
                    org.json.JSONObject params = command.getJSONObject("params");
                    int threshold = params.optInt("threshold", 20);
                    if (threshold >= 5 && threshold <= 100) {
                        spManager.setBatteryWarningThreshold(threshold);
                        FLog.i(TAG, "✅ Cloud battery warning threshold set to: " + threshold + "%");
                    } else {
                        FLog.w(TAG, "☁️ config_batteryWarning: invalid threshold (must be 5–100): " + threshold);
                    }
                }

            } else if ("config_videoCodec".equals(action)) {
                // Set video codec – mirrors LiveM3U8Server.setVideoCodec()
                if (command.has("params")) {
                    org.json.JSONObject params = command.getJSONObject("params");
                    String codec = params.optString("codec", "").trim().toUpperCase();
                    if ("AVC".equals(codec) || "HEVC".equals(codec)) {
                        spManager.sharedPreferences.edit()
                                .putString(com.fadcam.Constants.PREF_VIDEO_CODEC, codec)
                                .apply();
                        FLog.i(TAG, "✅ Cloud video codec set to: " + codec);
                    } else {
                        FLog.w(TAG, "☁️ config_videoCodec: invalid codec (must be AVC or HEVC): " + codec);
                    }
                }

            } else if ("camera_switch".equals(action)) {
                // Switch active camera – mirrors HomeFragment.switchCamera() exactly.
                // If recording is active the switch is performed live via RecordingService;
                // otherwise the preference is updated so the next recording uses the new camera.
                com.fadcam.CameraType currentType = spManager.getCameraSelection();

                // Determine target: explicit "to" param wins; otherwise toggle.
                com.fadcam.CameraType targetType;
                if (command.has("params")) {
                    org.json.JSONObject params = command.getJSONObject("params");
                    String targetStr = params.optString("to", "").trim().toUpperCase();
                    com.fadcam.CameraType parsed = null;
                    try {
                        parsed = com.fadcam.CameraType.valueOf(targetStr);
                    } catch (IllegalArgumentException ignored) { }
                    targetType = (parsed != null) ? parsed :
                            (currentType == com.fadcam.CameraType.BACK
                                    ? com.fadcam.CameraType.FRONT
                                    : com.fadcam.CameraType.BACK);
                } else {
                    targetType = (currentType == com.fadcam.CameraType.BACK)
                            ? com.fadcam.CameraType.FRONT
                            : com.fadcam.CameraType.BACK;
                }

                if (spManager.isRecordingInProgress()) {
                    // Live switch during active recording
                    android.content.Intent switchIntent = new android.content.Intent(
                            context, com.fadcam.services.RecordingService.class);
                    switchIntent.setAction(com.fadcam.Constants.INTENT_ACTION_SWITCH_CAMERA);
                    switchIntent.putExtra(com.fadcam.Constants.INTENT_EXTRA_CAMERA_TYPE_SWITCH,
                            targetType.toString());
                    com.fadcam.utils.ServiceStartPolicy.startRecordingAction(context, switchIntent);
                    FLog.i(TAG, "✅ Cloud camera switch (live): " + currentType + " → " + targetType);
                } else {
                    // Not recording – persist the preference for next session
                    spManager.sharedPreferences.edit()
                            .putString(com.fadcam.Constants.PREF_CAMERA_SELECTION, targetType.toString())
                            .apply();
                    FLog.i(TAG, "✅ Cloud camera switch (preference): " + currentType + " → " + targetType);
                }

            } else if ("config_zoom".equals(action)) {
                // Set zoom ratio (+ optional pan) – dispatched to RecordingService if active,
                // otherwise saved to prefs for next recording session.
                if (command.has("params")) {
                    org.json.JSONObject params = command.getJSONObject("params");
                    float ratio = (float) params.optDouble("ratio", 1.0);
                    com.fadcam.CameraType zoomCameraType = spManager.getCameraSelection();
                    float maxZoomRatio = spManager.getMaxSupportedZoomRatio(zoomCameraType);
                    ratio = Math.max(0.5f, Math.min(maxZoomRatio, ratio));

                    if (spManager.isRecordingInProgress()) {
                        android.content.Intent zoomIntent = new android.content.Intent(
                                context, com.fadcam.services.RecordingService.class);
                        zoomIntent.setAction(com.fadcam.Constants.INTENT_ACTION_SET_ZOOM_RATIO);
                        zoomIntent.putExtra(com.fadcam.Constants.EXTRA_ZOOM_RATIO, ratio);
                        if (params.has("panX") || params.has("panY")) {
                        float panX = ratio <= 1.0f ? 0.0f : (float) params.optDouble("panX", 0.0);
                        float panY = ratio <= 1.0f ? 0.0f : (float) params.optDouble("panY", 0.0);
                            zoomIntent.putExtra(com.fadcam.Constants.EXTRA_PAN_X,
                                    Math.max(-1.0f, Math.min(1.0f, panX)));
                            zoomIntent.putExtra(com.fadcam.Constants.EXTRA_PAN_Y,
                                    Math.max(-1.0f, Math.min(1.0f, panY)));
                        }
                        com.fadcam.utils.ServiceStartPolicy.startRecordingAction(context, zoomIntent);
                        FLog.i(TAG, "✅ Cloud zoom dispatched (live): ratio=" + ratio);
                    } else {
                        // Persist for next recording session
                        com.fadcam.CameraType cam = spManager.getCameraSelection();
                        spManager.setSpecificZoomRatio(cam, ratio);
                        if (params.has("panX") || params.has("panY")) {
                            float panX = ratio <= 1.0f ? 0.0f : (float) params.optDouble("panX", 0.0);
                            float panY = ratio <= 1.0f ? 0.0f : (float) params.optDouble("panY", 0.0);
                            spManager.setSpecificPan(cam, panX, panY);
                        }
                        FLog.i(TAG, "✅ Cloud zoom saved (preference): ratio=" + ratio);
                    }
                }

            } else if ("config_exposure".equals(action)) {
                // Set exposure compensation – dispatched live to RecordingService.
                // CONTROL_AE_EXPOSURE_COMPENSATION range is device-dependent; clamp −5…+5.
                if (command.has("params")) {
                    org.json.JSONObject params = command.getJSONObject("params");
                    int ev = params.optInt("ev", 0);
                    ev = Math.max(-5, Math.min(5, ev));

                    android.content.Intent evIntent = new android.content.Intent(
                            context, com.fadcam.services.RecordingService.class);
                    evIntent.setAction(com.fadcam.Constants.INTENT_ACTION_SET_EXPOSURE_COMPENSATION);
                    evIntent.putExtra(com.fadcam.Constants.EXTRA_EXPOSURE_COMPENSATION, ev);
                    com.fadcam.utils.ServiceStartPolicy.startRecordingAction(context, evIntent);
                    FLog.i(TAG, "✅ Cloud exposure compensation set to: " + ev);
                }

            } else if ("config_mirror".equals(action)) {
                // Toggle or explicitly set front-camera mirror flip.
                boolean enabled;
                if (command.has("params")) {
                    org.json.JSONObject params = command.getJSONObject("params");
                    // "enabled" param wins; default toggles current value
                    if (params.has("enabled")) {
                        enabled = params.optBoolean("enabled", false);
                    } else {
                        enabled = !spManager.isFrontVideoMirrorEnabled();
                    }
                } else {
                    enabled = !spManager.isFrontVideoMirrorEnabled();
                }

                android.content.Intent mirrorIntent = new android.content.Intent(
                        context, com.fadcam.services.RecordingService.class);
                mirrorIntent.setAction(com.fadcam.Constants.INTENT_ACTION_SET_FRONT_VIDEO_MIRROR);
                mirrorIntent.putExtra(com.fadcam.Constants.EXTRA_FRONT_VIDEO_MIRROR_ENABLED, enabled);
                com.fadcam.utils.ServiceStartPolicy.startRecordingAction(context, mirrorIntent);
                FLog.i(TAG, "✅ Cloud front mirror set to: " + enabled);

            } else if ("config_aeLock".equals(action)) {
                // Toggle AE lock on/off.
                boolean currentLocked = spManager.isAeLockedSaved();
                boolean newLocked = !currentLocked;
                android.content.Intent aeLockIntent = new android.content.Intent(
                        context, com.fadcam.services.RecordingService.class);
                aeLockIntent.setAction(com.fadcam.Constants.INTENT_ACTION_TOGGLE_AE_LOCK);
                aeLockIntent.putExtra(com.fadcam.Constants.EXTRA_AE_LOCK, newLocked);
                com.fadcam.utils.ServiceStartPolicy.startRecordingAction(context, aeLockIntent);
                FLog.i(TAG, "✅ Cloud AE lock set to: " + newLocked);

            } else {
                FLog.w(TAG, "☁️ Unknown command action: " + action);
            }
            
            // Delete command after execution
            deleteCommand(cmdId);
            
        } catch (Exception e) {
            FLog.e(TAG, "Failed to execute command " + cmdId + ": " + e.getMessage());
            deleteCommand(cmdId);
        }
    }
    
    /**
     * Execute command by calling local LiveM3U8Server
     */
    private void executeLocalCommand(String endpoint, String method, String body) {
        // Get server port from RemoteStreamService
        int port = getServerPort();
        FLog.d(TAG, "☁️ executeLocalCommand - port=" + port + ", endpoint=" + endpoint + ", body=" + body);
        if (port <= 0) {
            FLog.w(TAG, "☁️ Server port not available (port=" + port + ")");
            return;
        }
        
        String urlStr = "http://localhost:" + port + endpoint;
        FLog.i(TAG, "☁️ Calling local endpoint: " + urlStr);
        
        try {
            HttpURLConnection conn = (HttpURLConnection) java.net.URI.create(urlStr).toURL().openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            
            if (body != null && "POST".equals(method)) {
                conn.setDoOutput(true);
                // Use application/json for JSON payloads (alarm, etc.)
                // Use text/plain for simple values (volume level)
                if (body.startsWith("{") || body.startsWith("[")) {
                    conn.setRequestProperty("Content-Type", "application/json");
                } else {
                    conn.setRequestProperty("Content-Type", "text/plain");
                }
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes("UTF-8"));
                }
            }
            
            int responseCode = conn.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                FLog.i(TAG, "☁️ Command executed successfully: " + endpoint + " -> HTTP " + responseCode);
            } else {
                FLog.w(TAG, "☁️ Command execution failed: " + endpoint + " -> HTTP " + responseCode);
            }
            conn.disconnect();
            
        } catch (Exception e) {
            FLog.e(TAG, "☁️ Local command failed: " + endpoint + " - " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
    
    /**
     * Delete a command from the relay after execution
     * Uses stream_access_token for authentication.
     * NOTE: .json extension required to match nginx location pattern
     */
    private void deleteCommand(String cmdId) {
        String userUuid = authManager.getUserId();
        String deviceId = getDeviceId();
        String streamToken = authManager.getStreamToken();
        
        if (userUuid == null || deviceId == null || streamToken == null) {
            return;
        }
        
        // NOTE: Must include .json extension to match nginx pattern
        String urlStr = CloudStreamUploader.RELAY_BASE_URL + "/api/command/" + userUuid + "/" + deviceId + "/" + cmdId + ".json";
        
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) java.net.URI.create(urlStr).toURL().openConnection();
                conn.setRequestMethod("DELETE");
                conn.setRequestProperty("Authorization", "Bearer " + streamToken);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                
                int responseCode = conn.getResponseCode();
                if (responseCode >= 200 && responseCode < 300) {
                    FLog.d(TAG, "☁️ Command deleted from relay: " + cmdId);
                }
            } catch (Exception e) {
                FLog.w(TAG, "Failed to delete command: " + e.getMessage());
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }
    
    // =========================================================================
    // Cloud Viewers Polling
    // =========================================================================
    
    /**
     * Poll cloud viewer count from relay server.
     * Relay server parses nginx logs and provides unique viewer count.
     * Updates RemoteStreamManager.cloudViewerCount for inclusion in status push.
     */
    private void pollCloudViewers() {
        String userUuid = authManager.getUserId();
        String deviceId = getDeviceId();
        // Use stream_access_token for auth (same as uploads)
        String streamToken = authManager.getStreamToken();
        
        if (userUuid == null || deviceId == null || streamToken == null) {
            // No auth yet - skip
            FLog.d(TAG, "☁️ 👥 Skipping viewers poll: missing auth (userUuid=" + (userUuid != null) + ", deviceId=" + (deviceId != null) + ", streamToken=" + (streamToken != null) + ")");
            return;
        }
        
        String urlStr = CloudStreamUploader.RELAY_BASE_URL + "/api/viewers/" + userUuid + "/" + deviceId;
        FLog.i(TAG, "☁️ 👥 ====== POLLING CLOUD VIEWERS ======");
        FLog.i(TAG, "☁️ 👥 URL: " + urlStr);
        FLog.i(TAG, "☁️ 👥 Token: " + streamToken.substring(0, Math.min(20, streamToken.length())) + "...");
        
        long startTime = System.currentTimeMillis();
        
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) java.net.URI.create(urlStr).toURL().openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "Bearer " + streamToken);
                conn.setConnectTimeout(10000);  // 10s connect timeout
                conn.setReadTimeout(10000);     // 10s read timeout
                
                FLog.i(TAG, "☁️ 👥 Connecting to relay server...");
                
                int responseCode = conn.getResponseCode();
                long elapsed = System.currentTimeMillis() - startTime;
                FLog.i(TAG, "☁️ 👥 Response received in " + elapsed + "ms: HTTP " + responseCode);
                
                if (responseCode == 200) {
                    java.io.InputStream is = conn.getInputStream();
                    java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
                    String body = s.hasNext() ? s.next() : "{}";
                    is.close();
                    
                    FLog.i(TAG, "☁️ 👥 Response body: " + body);
                    
                    // Parse JSON: {"count":N,"bytesServed":M,"updated":timestamp}
                    // PRIVACY: No IP addresses are included in the response
                    JSONObject json = new JSONObject(body);
                    int viewerCount = json.optInt("count", 0);
                    long bytesServed = json.optLong("bytesServed", 0);
                    long updated = json.optLong("updated", 0);
                    
                    // Update RemoteStreamManager with count and bytes (no IPs for privacy)
                    int oldCount = RemoteStreamManager.getInstance().getCloudViewerCount();
                    RemoteStreamManager.getInstance().setCloudViewerCount(viewerCount, bytesServed);
                    
                    FLog.i(TAG, "☁️ 👥 ✅ Cloud viewers: " + viewerCount + " (was: " + oldCount + ", bytes: " + bytesServed + ", updated: " + updated + ")");
                } else if (responseCode == 404) {
                    // No viewers file yet - means no viewers, which is valid
                    RemoteStreamManager.getInstance().setCloudViewerCount(0);
                    FLog.i(TAG, "☁️ 👥 No viewers data available (404 - normal if no viewers yet)");
                } else {
                    // Read error response body for debugging
                    String errorBody = "";
                    try {
                        java.io.InputStream errorStream = conn.getErrorStream();
                        if (errorStream != null) {
                            java.util.Scanner s = new java.util.Scanner(errorStream).useDelimiter("\\A");
                            errorBody = s.hasNext() ? s.next() : "";
                            errorStream.close();
                        }
                    } catch (Exception ignored) {}
                    FLog.e(TAG, "☁️ 👥 ❌ Viewers poll failed: HTTP " + responseCode + " - " + errorBody);
                }
            } catch (java.net.SocketTimeoutException e) {
                long elapsed = System.currentTimeMillis() - startTime;
                FLog.e(TAG, "☁️ 👥 ❌ TIMEOUT after " + elapsed + "ms: " + e.getMessage());
                // Don't reset count on timeout - keep last known value
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - startTime;
                FLog.e(TAG, "☁️ 👥 ❌ ERROR after " + elapsed + "ms: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                // Don't reset count on error - keep last known value
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
