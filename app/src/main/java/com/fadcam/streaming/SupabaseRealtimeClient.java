package com.fadcam.streaming;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * SupabaseRealtimeClient - WebSocket client for Supabase Realtime.
 * 
 * Provides instant command delivery in cloud mode by subscribing to a 
 * Supabase Realtime broadcast channel. Commands sent from the dashboard
 * are received instantly (~200ms) instead of being polled every 3 seconds.
 * 
 * Architecture:
 * - Connects to wss://{project_id}.supabase.co/realtime/v1/websocket
 * - Joins channel: "device:{device_id}" 
 * - Listens for broadcast events: "command"
 * - Executes commands via callback
 * 
 * Thread-safe: WebSocket callbacks run on OkHttp's background thread,
 * commands are dispatched to main thread via Handler.
 */
public class SupabaseRealtimeClient {
    private static final String TAG = "SupabaseRealtime";
    
    // Supabase project configuration
    private static final String SUPABASE_PROJECT_ID = "vfhehknmxxedvesdvpew";
    private static final String SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InZmaGVoa25teHhlZHZlc2R2cGV3Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjY3NzgxMjYsImV4cCI6MjA4MjM1NDEyNn0.IRTO3qW5SpseCxrirsQRnFJ38IFj47dOfJxlHG2n9aI";
    
    // WebSocket URL format (log_level=info for verbose logging)
    private static final String REALTIME_URL = "wss://%s.supabase.co/realtime/v1/websocket?apikey=%s&log_level=info&vsn=1.0.0";
    
    // Heartbeat interval (30 seconds as per Phoenix protocol)
    private static final long HEARTBEAT_INTERVAL_MS = 30000;
    
    // Reconnect delay (exponential backoff)
    private static final long INITIAL_RECONNECT_DELAY_MS = 1000;
    private static final long MAX_RECONNECT_DELAY_MS = 30000;
    
    private final OkHttpClient client;
    private final Handler mainHandler;
    private final String deviceId;
    private final String userId;
    private final CommandCallback callback;
    
    private WebSocket webSocket;
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean shouldReconnect = new AtomicBoolean(true);
    private final AtomicInteger messageRef = new AtomicInteger(1);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    
    private String channelTopic;
    private boolean channelJoined = false;
    
    /**
     * Callback interface for command execution
     */
    public interface CommandCallback {
        /**
         * Called when a command is received from the dashboard.
         * Runs on main thread.
         * 
         * @param action Command action (e.g., "torch_toggle")
         * @param params Command parameters as JSON object
         */
        void onCommandReceived(String action, JSONObject params);
    }
    
    /**
     * Create a new Supabase Realtime client.
     * 
     * @param deviceId Device ID for channel subscription
     * @param userId User UUID for channel subscription
     * @param callback Callback for command execution
     */
    public SupabaseRealtimeClient(String deviceId, String userId, CommandCallback callback) {
        this.deviceId = deviceId;
        this.userId = userId;
        this.callback = callback;
        this.channelTopic = "realtime:device:" + deviceId;
        
        this.client = new OkHttpClient.Builder()
                .pingInterval(25, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MINUTES) // No read timeout for WebSocket
                .build();
        
        this.mainHandler = new Handler(Looper.getMainLooper());
        
        Log.i(TAG, "📡 SupabaseRealtimeClient created for device: " + deviceId);
    }
    
    /**
     * Connect to Supabase Realtime and subscribe to command channel.
     */
    public void connect() {
        if (isConnected.get()) {
            Log.w(TAG, "📡 Already connected");
            return;
        }
        
        shouldReconnect.set(true);
        
        String url = String.format(REALTIME_URL, SUPABASE_PROJECT_ID, SUPABASE_ANON_KEY);
        Request request = new Request.Builder()
                .url(url)
                .build();
        
        Log.i(TAG, "📡 Connecting to Supabase Realtime...");
        webSocket = client.newWebSocket(request, new RealtimeWebSocketListener());
    }
    
    /**
     * Disconnect from Supabase Realtime.
     */
    public void disconnect() {
        shouldReconnect.set(false);
        channelJoined = false;
        
        if (webSocket != null) {
            // Leave channel gracefully
            try {
                sendPhxLeave();
            } catch (Exception e) {
                // Ignore errors when leaving
            }
            
            webSocket.close(1000, "Client disconnecting");
            webSocket = null;
        }
        
        isConnected.set(false);
        Log.i(TAG, "📡 Disconnected from Supabase Realtime");
    }
    
    /**
     * Check if connected to Supabase Realtime.
     */
    public boolean isConnected() {
        return isConnected.get() && channelJoined;
    }
    
    // =========================================================================
    // WebSocket Listener
    // =========================================================================
    
    private class RealtimeWebSocketListener extends WebSocketListener {
        
        @Override
        public void onOpen(WebSocket socket, Response response) {
            Log.i(TAG, "📡 WebSocket connected");
            isConnected.set(true);
            reconnectAttempts.set(0);
            
            // Join the device channel for commands
            joinChannel();
            
            // Start heartbeat
            startHeartbeat();
        }
        
        @Override
        public void onMessage(WebSocket socket, String text) {
            try {
                handleMessage(text);
            } catch (Exception e) {
                Log.e(TAG, "📡 Error handling message: " + e.getMessage());
            }
        }
        
        @Override
        public void onClosing(WebSocket socket, int code, String reason) {
            Log.i(TAG, "📡 WebSocket closing: " + code + " - " + reason);
        }
        
        @Override
        public void onClosed(WebSocket socket, int code, String reason) {
            Log.i(TAG, "📡 WebSocket closed: " + code + " - " + reason);
            isConnected.set(false);
            channelJoined = false;
            
            if (shouldReconnect.get()) {
                scheduleReconnect();
            }
        }
        
        @Override
        public void onFailure(WebSocket socket, Throwable t, Response response) {
            Log.e(TAG, "📡 WebSocket failure: " + t.getMessage());
            isConnected.set(false);
            channelJoined = false;
            
            if (shouldReconnect.get()) {
                scheduleReconnect();
            }
        }
    }
    
    // =========================================================================
    // Phoenix Protocol Handling
    // =========================================================================
    
    /**
     * Handle incoming Phoenix protocol message.
     * 
     * Supabase Realtime sends messages in mixed formats:
     * - Replies (phx_reply) come as JSON objects (v1.0.0 format)
     * - Broadcasts come as JSON arrays (v2.0.0 format): [join_ref, ref, topic, event, payload]
     * 
     * We need to detect which format and parse accordingly.
     */
    private void handleMessage(String text) throws Exception {
        Log.d(TAG, "📡 Raw message: " + text);
        
        String topic;
        String event;
        JSONObject payload;
        
        // Detect format: starts with '[' = array (v2.0.0), starts with '{' = object (v1.0.0)
        if (text.trim().startsWith("[")) {
            // V2.0.0 Array format: [join_ref, ref, topic, event, payload]
            JSONArray message = new JSONArray(text);
            topic = message.getString(2);
            event = message.getString(3);
            Object payloadObj = message.get(4);
            payload = (payloadObj instanceof JSONObject) ? (JSONObject) payloadObj : new JSONObject();
            Log.d(TAG, "📡 Received (v2 array): event=" + event + ", topic=" + topic);
        } else {
            // V1.0.0 Object format: {"topic": "...", "event": "...", "payload": {...}}
            JSONObject message = new JSONObject(text);
            topic = message.optString("topic");
            event = message.optString("event");
            Object payloadObj = message.opt("payload");
            payload = (payloadObj instanceof JSONObject) ? (JSONObject) payloadObj : new JSONObject();
            Log.d(TAG, "📡 Received (v1 object): event=" + event + ", topic=" + topic);
        }
        
        switch (event) {
            case "phx_reply":
                handlePhxReply(topic, payload);
                break;
                
            case "broadcast":
                handleBroadcast(topic, payload);
                break;
                
            case "phx_error":
                Log.e(TAG, "📡 Channel error: " + payload);
                break;
                
            case "phx_close":
                Log.i(TAG, "📡 Channel closed");
                channelJoined = false;
                break;
                
            default:
                Log.d(TAG, "📡 Unhandled event: " + event);
                break;
        }
    }
    
    /**
     * Handle phx_reply (response to our requests).
     */
    private void handlePhxReply(String topic, JSONObject payload) {
        String status = payload.optString("status");
        JSONObject response = payload.optJSONObject("response");
        
        if ("ok".equals(status)) {
            if (topic.equals(channelTopic)) {
                channelJoined = true;
                Log.i(TAG, "📡 ✅ Joined channel: " + channelTopic);
            }
        } else if ("error".equals(status)) {
            String reason = response != null ? response.optString("reason", "unknown") : "unknown";
            Log.e(TAG, "📡 ❌ Reply error: status=" + status + ", reason=" + reason + ", payload=" + payload);
            // Don't retry join on error - server explicitly rejected
        } else {
            Log.e(TAG, "📡 Reply status: " + status + " - " + payload);
        }
    }
    
    /**
     * Handle broadcast message (command from dashboard).
     * 
     * Supabase broadcast payload format:
     * {
     *   "event": "command",          // User-defined event type
     *   "type": "broadcast",
     *   "payload": {                 // User-defined payload
     *     "action": "torch_toggle",
     *     "params": {...},
     *     "timestamp": 123456789
     *   }
     * }
     */
    private void handleBroadcast(String topic, JSONObject payload) {
        Log.i(TAG, "📡 Broadcast received: " + payload);
        
        // Extract command from broadcast payload
        // The "event" field is the user-defined event name (we use "command")
        String eventType = payload.optString("event");
        
        // The "payload" field contains the actual command data
        JSONObject commandPayload = payload.optJSONObject("payload");
        
        if (!"command".equals(eventType)) {
            Log.d(TAG, "📡 Ignoring non-command broadcast event: " + eventType);
            return;
        }
        
        if (commandPayload == null) {
            Log.w(TAG, "📡 Command broadcast missing payload");
            return;
        }
        
        String action = commandPayload.optString("action");
        JSONObject params = commandPayload.optJSONObject("params");
        
        if (action == null || action.isEmpty()) {
            Log.w(TAG, "📡 Command missing action field");
            return;
        }
        
        Log.i(TAG, "📡 ⚡ INSTANT COMMAND: " + action);
        
        // Dispatch to main thread
        mainHandler.post(() -> {
            try {
                callback.onCommandReceived(action, params != null ? params : new JSONObject());
            } catch (Exception e) {
                Log.e(TAG, "📡 Error executing command: " + e.getMessage());
            }
        });
    }
    
    // =========================================================================
    // Phoenix Protocol Messages
    // =========================================================================
    
    /**
     * Join the device channel for receiving commands.
     * Uses Protocol Version 1.0.0 (JSON object format).
     */
    private void joinChannel() {
        try {
            int ref = messageRef.getAndIncrement();
            
            // Build channel config
            JSONObject config = new JSONObject();
            
            // Broadcast config - we want to receive broadcasts
            JSONObject broadcastConfig = new JSONObject();
            broadcastConfig.put("ack", false);  // No acknowledgment needed
            broadcastConfig.put("self", false); // Don't receive our own broadcasts
            config.put("broadcast", broadcastConfig);
            
            // Presence config (disabled)
            JSONObject presenceConfig = new JSONObject();
            presenceConfig.put("enabled", false);
            config.put("presence", presenceConfig);
            
            // No postgres_changes needed
            config.put("postgres_changes", new JSONArray());
            
            // Public channel (uses anon key from URL)
            config.put("private", false);
            
            // Build payload with config
            JSONObject payload = new JSONObject();
            payload.put("config", config);
            
            // Version 1.0.0 format: JSON object with topic, event, payload, ref, join_ref
            JSONObject message = new JSONObject();
            message.put("topic", channelTopic);
            message.put("event", "phx_join");
            message.put("payload", payload);
            message.put("ref", String.valueOf(ref));
            message.put("join_ref", String.valueOf(ref));
            
            String messageStr = message.toString();
            Log.i(TAG, "📡 Joining channel: " + channelTopic);
            Log.d(TAG, "📡 Join message: " + messageStr);
            
            webSocket.send(messageStr);
            
        } catch (Exception e) {
            Log.e(TAG, "📡 Failed to join channel: " + e.getMessage());
        }
    }
    
    /**
     * Leave the channel gracefully.
     * Uses Protocol Version 1.0.0 (JSON object format).
     */
    private void sendPhxLeave() {
        try {
            int ref = messageRef.getAndIncrement();
            
            JSONObject message = new JSONObject();
            message.put("topic", channelTopic);
            message.put("event", "phx_leave");
            message.put("payload", new JSONObject());
            message.put("ref", String.valueOf(ref));
            message.put("join_ref", JSONObject.NULL);
            
            webSocket.send(message.toString());
        } catch (Exception e) {
            // Ignore
        }
    }
    
    /**
     * Send heartbeat to keep connection alive.
     * Uses Protocol Version 1.0.0 (JSON object format).
     */
    private void sendHeartbeat() {
        if (!isConnected.get() || webSocket == null) {
            return;
        }
        
        try {
            int ref = messageRef.getAndIncrement();
            
            JSONObject message = new JSONObject();
            message.put("topic", "phoenix");
            message.put("event", "heartbeat");
            message.put("payload", new JSONObject());
            message.put("ref", String.valueOf(ref));
            message.put("join_ref", JSONObject.NULL);
            
            webSocket.send(message.toString());
            Log.d(TAG, "📡 Heartbeat sent");
        } catch (Exception e) {
            Log.e(TAG, "📡 Heartbeat failed: " + e.getMessage());
        }
    }
    
    /**
     * Start periodic heartbeat.
     */
    private void startHeartbeat() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isConnected.get()) {
                    sendHeartbeat();
                    mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
                }
            }
        }, HEARTBEAT_INTERVAL_MS);
    }
    
    /**
     * Schedule reconnection with exponential backoff.
     */
    private void scheduleReconnect() {
        int attempts = reconnectAttempts.incrementAndGet();
        long delay = Math.min(
                INITIAL_RECONNECT_DELAY_MS * (long) Math.pow(2, attempts - 1),
                MAX_RECONNECT_DELAY_MS
        );
        
        Log.i(TAG, "📡 Scheduling reconnect in " + delay + "ms (attempt " + attempts + ")");
        
        mainHandler.postDelayed(() -> {
            if (shouldReconnect.get() && !isConnected.get()) {
                connect();
            }
        }, delay);
    }
}
