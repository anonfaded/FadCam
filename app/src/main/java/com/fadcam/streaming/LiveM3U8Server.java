package com.fadcam.streaming;

import android.util.Log;

import androidx.annotation.NonNull;

import com.fadcam.streaming.model.ClientEvent;
import com.fadcam.streaming.model.ClientMetrics;
import com.fadcam.streaming.model.StreamQuality;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import fi.iki.elonen.NanoHTTPD;

/**
 * LiveM3U8Server provides HTTP endpoints for HLS streaming of fMP4 segments.
 * 
 * Endpoints:
 * - GET /live.m3u8         - HLS playlist referencing buffered segments
 * - GET /seg-{id}.m4s      - Serves individual fMP4 segment bytes
 * - GET /status            - JSON status (fps, bitrate, resolution, connections)
 * - GET /                  - Simple HTML landing page with instructions
 * 
 * Architecture:
 * - Built on NanoHTTPD lightweight HTTP server
 * - Reads segment data from RemoteStreamManager circular buffer
 * - CORS-enabled for web player compatibility
 * - Connection tracking for active client monitoring
 * 
 * COMPRESSION NOTE: All JSON responses explicitly disable GZIP compression by manually
 * setting the Content-Length header. This is the official NanoHTTPD 2.3.1 approach
 * (see NanoHTTPD GZipIntegrationTest.java line 151). Manual Content-Length prevents
 * auto-compression when clients send Accept-Encoding: gzip headers, avoiding "Unexpected
 * token" JSON parse errors in browsers that receive compressed bytes but expect plain text.
 */
    public class LiveM3U8Server extends NanoHTTPD {
        private static final String TAG = "LiveM3U8Server";
        
        private final RemoteStreamManager streamManager;
        private final android.content.Context context;
        
        // HLS configuration
        private static final int TARGET_DURATION = 2; // Segment duration in seconds (1s actual + safety margin)
        
        public LiveM3U8Server(android.content.Context context, int port) throws IOException {
            super("0.0.0.0", port);  // CRITICAL FIX: Bind to ALL interfaces (0.0.0.0), not just localhost
            this.context = context.getApplicationContext();
            this.streamManager = RemoteStreamManager.getInstance();
            
            Log.i(TAG, "✅ [HTTP Server] Listening on ALL interfaces (0.0.0.0:" + port + ")");
            Log.i(TAG, "✅ [HTTP Server] Now ACCESSIBLE from other devices on hotspot!");
        }
    
    /**
     * Helper method to create JSON responses with proper charset encoding.
     * 
     * COMPRESSION CONTROL: Manually sets Content-Length header to prevent automatic
     * GZIP compression in NanoHTTPD 2.3.1. From GZipIntegrationTest.java line 151:
     * "Content should not be gzipped if Content-Length is added manually".
     * 
     * Without this, NanoHTTPD auto-compresses JSON when client sends Accept-Encoding: gzip,
     * which causes "Unexpected token" errors in browsers trying to parse gzipped bytes as JSON.
     * 
     * @param status HTTP response status
     * @param json JSON content as String
     * @return Response with UTF-8 charset, no compression
     */
    private Response jsonResponse(Response.Status status, String json) {
        byte[] jsonBytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Response response = newFixedLengthResponse(status, "application/json; charset=utf-8", json);
        // Setting Content-Length manually prevents NanoHTTPD from auto-gzipping (confirmed in official tests)
        response.addHeader("Content-Length", String.valueOf(jsonBytes.length));
        response.addHeader("Cache-Control", "no-cache");
        return response;
    }
    
    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();
        String userAgent = session.getHeaders().get("user-agent");
        String clientIP = session.getRemoteIpAddress();
        
        // OPTIMIZATION: Commented out HTTP request logging (called 10-30x per second during streaming)
        // Every request was being logged, massive I/O overhead during playback
        // Log.i(TAG, "📥 " + method + " " + uri + " from " + clientIP);
        // Log.d(TAG, "   User-Agent: " + (userAgent != null ? userAgent : "unknown"));
        
        // Track unique client IPs (only adds if new, Set handles duplicates)
        streamManager.trackClientIP(clientIP);
        
        // Track API calls (not fragment/HLS calls)
        boolean isApiCall = !uri.startsWith("/seg-") && !uri.startsWith("/live.m3u8") && 
                           !uri.startsWith("/stream.m3u8") && !uri.startsWith("/init.mp4") &&
                           !uri.startsWith("/css/") && !uri.startsWith("/js/") && !uri.startsWith("/assets/");
        
        // Log API requests (important ones that indicate client activity)
        if (isApiCall && (uri.equals("/") || uri.equals("/status") || uri.startsWith("/auth") || uri.startsWith("/api/"))) {
            Log.i(TAG, "📥 [API] " + method + " " + uri + " from " + clientIP);
        }
        
        if (isApiCall) {
            if (Method.GET.equals(method)) {
                streamManager.incrementClientGetRequests(clientIP);
            } else if (Method.POST.equals(method)) {
                streamManager.incrementClientPostRequests(clientIP);
            }
        }
        
        // Add CORS headers for web player compatibility
        Response response;
        
        // Handle OPTIONS preflight requests for CORS
        if (Method.OPTIONS.equals(method)) {
            response = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "");
            response.addHeader("Access-Control-Allow-Origin", "*");
            response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            response.addHeader("Access-Control-Allow-Headers", "Content-Type, Cache-Control, Pragma");
            response.addHeader("Access-Control-Max-Age", "3600");
            return response;
        }
        
        if (Method.GET.equals(method)) {
            if ("/live.m3u8".equals(uri) || "/stream.m3u8".equals(uri)) {
                // HLS playlist - industry standard for live streaming
                response = servePlaylist();
            } else if ("/init.mp4".equals(uri)) {
                response = serveInitSegment(clientIP);
            } else if (uri.startsWith("/seg-") && uri.endsWith(".m4s")) {
                response = serveFragment(uri, clientIP);
            } else if ("/status".equals(uri)) {
                Log.d(TAG, "🌐 [/status] Dashboard request from " + clientIP + " User-Agent: " + userAgent);
                response = serveStatus();
            } else if ("/auth/check".equals(uri)) {
                response = handleAuthCheck(session);
            } else if ("/audio/volume".equals(uri)) {
                response = getVolume();
            } else if ("/api/github/notification".equals(uri)) {
                // GitHub notification proxy - avoids CORS issues
                response = fetchGitHubNotification();
            } else if ("/api/notifications".equals(uri)) {
                response = getNotifications();
            } else if ("/".equals(uri)) {
                response = serveLandingPage();
            } else if (uri.startsWith("/css/") || uri.startsWith("/js/") || uri.startsWith("/assets/") || uri.startsWith("/fadex/")) {
                // Serve static web assets (CSS, JS, images, fonts, notification configs)
                response = serveStaticFile(uri);
            } else {
                response = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found");
            }
        } else if (Method.POST.equals(method)) {
            // Authentication endpoints (no token required)
            if ("/auth/login".equals(uri)) {
                response = handleLogin(session, clientIP);
            } else if ("/auth/logout".equals(uri)) {
                response = handleLogout(session);
            } else if ("/auth/changePassword".equals(uri)) {
                response = handleChangePassword(session);
            } else if ("/torch/toggle".equals(uri)) {
                response = toggleTorch();
            } else if ("/recording/toggle".equals(uri)) {
                response = toggleRecording();
            } else if ("/config/recordingMode".equals(uri)) {
                response = setRecordingMode(session);
            } else if ("/config/streamQuality".equals(uri)) {
                response = setStreamQuality(session);
            } else if ("/config/batteryWarning".equals(uri)) {
                response = setBatteryWarning(session);
            } else if ("/config/videoCodec".equals(uri)) {
                response = setVideoCodec(session);
            } else if ("/audio/volume".equals(uri)) {
                response = setVolume(session);
            } else if ("/alarm/ring".equals(uri)) {
                response = ringAlarm(session);
            } else if ("/alarm/stop".equals(uri)) {
                response = stopAlarm();
            } else if ("/alarm/schedule".equals(uri)) {
                response = scheduleAlarm(session);
            } else if ("/api/notifications".equals(uri)) {
                response = handleNotifications(session);
            } else {
                response = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found");
            }
        } else if (Method.GET.equals(method)) {
            if ("/api/notifications".equals(uri)) {
                response = getNotifications();
            } else {
                response = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found");
            }
        } else {
            response = newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Only GET, POST and OPTIONS requests supported");
        }
        
        // Add CORS headers to all responses
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Cache-Control, Pragma");
        response.addHeader("Access-Control-Max-Age", "3600");
        
        return response;
    }
    
    /**
     * Serve HLS playlist (M3U8) with fragment references.
     */
    @NonNull
    private Response servePlaylist() {
        // Check if streaming is enabled at all
        if (!streamManager.isStreamingEnabled()) {
            Log.w(TAG, "❌ Streaming is disabled - recording not started or streaming mode is DISABLED");
            String message = "❌ Streaming Disabled\n\n" +
                "Please start recording on FadCam with streaming enabled (STREAM_ONLY or STREAM_AND_SAVE mode).\n\n" +
                "Check /status endpoint for current state.";
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                MIME_PLAINTEXT,
                message
            );
        }
        
        // Check if initialization segment is available
        byte[] initSegment = streamManager.getInitializationSegment();
        if (initSegment == null) {
            Log.w(TAG, "❌ Init segment not available yet - recording just started or stopped");
            String message = "⏳ Stream Initializing\n\n" +
                "Recording is starting up. First fragments are being encoded.\n" +
                "This usually takes 2-3 seconds. Please wait...\n\n" +
                "Check /status endpoint for current state.";
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                MIME_PLAINTEXT,
                message
            );
        }
        
        // Get buffered fragments
        java.util.List<RemoteStreamManager.FragmentData> fragments = streamManager.getBufferedFragments();
        
        if (fragments.isEmpty()) {
            Log.w(TAG, "⏳ No fragments buffered yet - waiting for encoder output");
            String message = "⏳ Buffering Fragments\n\n" +
                "Init segment ready, waiting for first video fragments from encoder.\n" +
                "This usually takes 1-2 seconds. Please wait...\n\n" +
                "Check /status endpoint for current state.";
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                MIME_PLAINTEXT,
                message
            );
        }
        
        // Track connection
        streamManager.incrementConnections();
        
        try {
            // Generate M3U8 playlist - CORRECT ORDER FOR fMP4
            StringBuilder m3u8 = new StringBuilder();
            m3u8.append("#EXTM3U\n");
            m3u8.append("#EXT-X-VERSION:7\n"); // fMP4 requires version 7
            m3u8.append("#EXT-X-INDEPENDENT-SEGMENTS\n"); // MUST come early
            m3u8.append("#EXT-X-TARGETDURATION:4\n"); // 4-second max fragment duration (2s actual, with margin)
            
            // CRITICAL: Get buffered fragments before generating rest of playlist
            // Apple HLS spec requires minimum 6 segments, we use 8 for more buffer room
            java.util.List<RemoteStreamManager.FragmentData> stable = new java.util.ArrayList<>(fragments);
            int LIVE_WINDOW_SIZE = 8;
            java.util.List<RemoteStreamManager.FragmentData> liveEdge = new java.util.ArrayList<>();
            int startIdx = Math.max(0, stable.size() - LIVE_WINDOW_SIZE);
            for (int i = startIdx; i < stable.size(); i++) {
                liveEdge.add(stable.get(i));
            }
            
            // Media sequence BEFORE map
            m3u8.append("#EXT-X-MEDIA-SEQUENCE:").append(liveEdge.get(0).sequenceNumber).append("\n");
            
            // INIT SEGMENT - MUST be declared before fragments
            m3u8.append("#EXT-X-MAP:URI=\"/init.mp4\"\n");

            // Professional live streaming: minimum 2 fragments buffered before serving (lowered to reduce startup delay)
            if (fragments.size() < 2) {
                Log.d(TAG, "⏳ Buffering... Only " + fragments.size() + "/2 fragments available");
                return newFixedLengthResponse(
                    Response.Status.SERVICE_UNAVAILABLE,
                    MIME_PLAINTEXT,
                    "Buffering stream... Please wait (" + fragments.size() + "/2 fragments ready)"
                );
            }
            
            // Add fragments to playlist
            for (RemoteStreamManager.FragmentData fragment : liveEdge) {
                m3u8.append("#EXTINF:").append(String.format("%.3f", fragment.getDurationSeconds())).append(",\n");
                m3u8.append("/seg-").append(fragment.sequenceNumber).append(".m4s\n");
            }
            
            Log.i(TAG, "📋 Generated M3U8 playlist:");
            Log.i(TAG, "   Total fragments: " + fragments.size());
            Log.i(TAG, "   Stable fragments: " + stable.size());
            Log.i(TAG, "   Live edge fragments: " + liveEdge.size());
            Log.i(TAG, "   Sequence range: " + liveEdge.get(0).sequenceNumber + " to " + liveEdge.get(liveEdge.size()-1).sequenceNumber);
            Log.d(TAG, "M3U8 Content:\n" + m3u8.toString());
            
            Response response = newFixedLengthResponse(
                Response.Status.OK,
                "application/vnd.apple.mpegurl",
                m3u8.toString()
            );
            
            // ULTRA-AGGRESSIVE cache prevention for HLS playlist
            // Prevents browser/HLS.js from serving stale playlists with old fragment numbers
            response.addHeader("Content-Type", "application/vnd.apple.mpegurl; charset=utf-8");
            response.addHeader("Content-Disposition", "inline");
            response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate, max-age=0, s-maxage=0");
            response.addHeader("Pragma", "no-cache");
            response.addHeader("Expires", "0");
            response.addHeader("ETag", String.valueOf(System.currentTimeMillis())); // Force unique response each time
            response.addHeader("Last-Modified", new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US).format(new java.util.Date()));
            response.addHeader("Access-Control-Allow-Origin", "*");
            
            return response;
            
        } finally {
            streamManager.decrementConnections();
        }
    }
    
    /**
     * Serve initialization segment (ftyp + moov).
     */
    @NonNull
    private Response serveInitSegment(String clientIP) {
        byte[] initSegment = streamManager.getInitializationSegment();
        
        if (initSegment == null) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_PLAINTEXT,
                "Initialization segment not available yet"
            );
        }
        
        streamManager.incrementConnections();
        
        try {
            Log.d(TAG, "📋 Serving initialization segment (" + (initSegment.length / 1024) + " KB) to " + clientIP);
            
            // DEBUG: Log first few bytes of init segment to verify ftyp box
            if (initSegment.length > 8) {
                String firstBytes = String.format("%02X %02X %02X %02X %02X %02X %02X %02X",
                    initSegment[0] & 0xFF, initSegment[1] & 0xFF, initSegment[2] & 0xFF, initSegment[3] & 0xFF,
                    initSegment[4] & 0xFF, initSegment[5] & 0xFF, initSegment[6] & 0xFF, initSegment[7] & 0xFF);
                Log.d(TAG, "📋 Init segment header (hex): " + firstBytes + " (should start with ftyp signature)");
                
                // Check for ftyp box signature
                if (initSegment[4] == 'f' && initSegment[5] == 't' && initSegment[6] == 'y' && initSegment[7] == 'p') {
                    Log.d(TAG, "✅ Init segment has valid ftyp box");
                } else {
                    Log.w(TAG, "❌ Init segment missing ftyp box! May be corrupted");
                }
            }
            
            // Check if this is first request from this client
            ClientMetrics metrics = streamManager.getClientMetrics(clientIP);
            boolean isFirstRequest = (metrics == null || metrics.getTotalBytesServed() == 0);
            
            // Track data served to this client
            streamManager.addDataServed(clientIP, initSegment.length);
            
            // Log first request event
            if (isFirstRequest) {
                streamManager.logClientEvent(new ClientEvent(
                    clientIP,
                    ClientEvent.EventType.CONNECTED,
                    "First request - init segment"
                ));
            }
            
            InputStream initStream = new java.io.ByteArrayInputStream(initSegment);
            Response response = newFixedLengthResponse(Response.Status.OK, "video/mp4", initStream, initSegment.length);
            // CRITICAL: Do NOT cache init segment - it changes on each recording start/restart!
            // If cached, browser uses old init with new fragments = decode failure
            response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            response.addHeader("Pragma", "no-cache");
            response.addHeader("Expires", "0");
            response.addHeader("Content-Length", String.valueOf(initSegment.length));
            
            return response;
            
        } finally {
            streamManager.decrementConnections();
        }
    }
    
    /**
     * Serve individual fMP4 fragment (moof + mdat).
     * URI format: /seg-{sequenceNumber}.m4s
     */
    @NonNull
    private Response serveFragment(String uri, String clientIP) {
        try {
            // Extract sequence number from URI: "/seg-123.m4s" -> 123
            String seqNumStr = uri.substring(5, uri.length() - 4); // Remove "/seg-" and ".m4s"
            int sequenceNumber = Integer.parseInt(seqNumStr);
            
            int oldest = streamManager.getOldestSequenceNumber();
            int latest = streamManager.getLatestSequenceNumber();

            // Reject clearly stale requests early (e.g., cached player asking for old segments)
            if (sequenceNumber < oldest || sequenceNumber > latest) {
                Log.w(TAG, "❌ Fragment #" + sequenceNumber + " outside window [" + oldest + ", " + latest + "] - treating as stale request");
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Fragment outside live window");
            }

            RemoteStreamManager.FragmentData fragment = streamManager.getFragment(sequenceNumber);
            
            if (fragment == null) {
                Log.w(TAG, "Fragment #" + sequenceNumber + " not found in buffer");
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Fragment not found");
            }
            
            streamManager.incrementConnections();
            
            try {
                Log.d(TAG, "📦 Serving fragment #" + sequenceNumber + " (" + (fragment.sizeBytes / 1024) + " KB) to " + clientIP);
                
                // DEBUG: Log first few bytes of fragment to verify moof box
                if (fragment.data.length > 8) {
                    String firstBytes = String.format("%02X %02X %02X %02X %02X %02X %02X %02X",
                        fragment.data[0] & 0xFF, fragment.data[1] & 0xFF, fragment.data[2] & 0xFF, fragment.data[3] & 0xFF,
                        fragment.data[4] & 0xFF, fragment.data[5] & 0xFF, fragment.data[6] & 0xFF, fragment.data[7] & 0xFF);
                    Log.d(TAG, "📦 Fragment #" + sequenceNumber + " header (hex): " + firstBytes + " (should start with moof)");
                    
                    // Check for moof box signature
                    if (fragment.data[4] == 'm' && fragment.data[5] == 'o' && fragment.data[6] == 'o' && fragment.data[7] == 'f') {
                        Log.d(TAG, "✅ Fragment #" + sequenceNumber + " has valid moof box");
                    } else {
                        Log.w(TAG, "❌ Fragment #" + sequenceNumber + " missing moof box! May be corrupted");
                    }
                }
                
                // Get previous data before adding new
                ClientMetrics prevMetrics = streamManager.getClientMetrics(clientIP);
                long previousData = prevMetrics != null ? prevMetrics.getTotalBytesServed() : 0;
                long previousMB = previousData / (1024 * 1024);
                
                // Track new data served
                streamManager.addDataServed(clientIP, fragment.sizeBytes);
                
                // Get new total
                ClientMetrics newMetrics = streamManager.getClientMetrics(clientIP);
                long newData = newMetrics != null ? newMetrics.getTotalBytesServed() : 0;
                long newMB = newData / (1024 * 1024);
                
                // Log milestone every 10MB served to this client
                if (newMB > 0 && previousMB / 10 < newMB / 10) {
                    streamManager.logClientEvent(new ClientEvent(
                        clientIP,
                        ClientEvent.EventType.DATA_MILESTONE,
                        newMB + " MB served"
                    ));
                }
                
                // Serve fragment bytes
                InputStream fragmentStream = new java.io.ByteArrayInputStream(fragment.data);
                Response response = newFixedLengthResponse(Response.Status.OK, "video/mp4", fragmentStream, fragment.sizeBytes);
                // Never allow fragment caching; stale caches caused old video playback
                response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
                response.addHeader("Pragma", "no-cache");
                response.addHeader("Expires", "0");
                response.addHeader("Content-Length", String.valueOf(fragment.sizeBytes));
                
                return response;
                
            } finally {
                streamManager.decrementConnections();
            }
            
        } catch (NumberFormatException e) {
            Log.e(TAG, "Invalid sequence number in URI: " + uri, e);
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid fragment ID");
        }
    }
    
    /**
     * Handle torch toggle request from web interface.
     * Mirrors TorchToggleActivity logic: checks if recording is active and routes appropriately.
     */
    @NonNull
    private Response toggleTorch() {
        try {
            Log.i(TAG, "🔦 Torch toggle requested via web interface");
            
            // Toggle state in RemoteStreamManager
            RemoteStreamManager manager = RemoteStreamManager.getInstance();
            boolean newState = !manager.isTorchOn();
            manager.setTorchState(newState);
            
            // Use same logic as TorchToggleActivity
            com.fadcam.SharedPreferencesManager spManager = com.fadcam.SharedPreferencesManager.getInstance(context);
            android.content.Intent intent;
            
            if (spManager.isRecordingInProgress()) {
                // Recording active: send to RecordingService
                intent = new android.content.Intent(context, com.fadcam.services.RecordingService.class);
                intent.setAction(com.fadcam.Constants.INTENT_ACTION_TOGGLE_RECORDING_TORCH);
                Log.d(TAG, "Recording active - routing torch toggle to RecordingService");
            } else {
                // Not recording: send to TorchService for idle torch control
                intent = new android.content.Intent(context, com.fadcam.services.TorchService.class);
                intent.setAction(com.fadcam.Constants.INTENT_ACTION_TOGGLE_TORCH);
                Log.d(TAG, "Not recording - routing torch toggle to TorchService");
            }
            
            // Start the appropriate service
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            
            Log.i(TAG, "✅ Torch toggle intent sent. New state: " + newState);
            
            String responseJson = String.format("{\"status\": \"success\", \"torch_state\": %s}", newState);
            return jsonResponse(Response.Status.OK, responseJson);
        } catch (Exception e) {
            Log.e(TAG, "Error toggling torch", e);
            return jsonResponse(Response.Status.INTERNAL_ERROR, "{\"status\": \"error\", \"message\": \"" + e.getMessage() + "\"}");
        }
    }

    /**
     * Handle GET /audio/volume - Get current device volume level
     */
    @NonNull
    private Response getVolume() {
        try {
            Log.i(TAG, "🔊 Volume status requested via web interface");
            
            android.media.AudioManager audioManager = (android.media.AudioManager) context.getSystemService(android.content.Context.AUDIO_SERVICE);
            if (audioManager == null) {
                Log.e(TAG, "AudioManager not available");
                return jsonResponse(Response.Status.INTERNAL_ERROR, 
                    "{\"status\": \"error\", \"message\": \"AudioManager not available\"}");
            }
            
            int currentVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
            int maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            
            // Update RemoteStreamManager state
            RemoteStreamManager manager = RemoteStreamManager.getInstance();
            manager.setMediaVolume(currentVolume);
            
            String responseJson = String.format(
                "{\"status\": \"success\", \"volume\": %d, \"max_volume\": %d, \"percentage\": %.1f}",
                currentVolume, maxVolume, (currentVolume * 100.0f / maxVolume)
            );
            
            Log.i(TAG, "✅ Volume retrieved: " + currentVolume + "/" + maxVolume);
            
            return jsonResponse(Response.Status.OK, responseJson);
        } catch (Exception e) {
            Log.e(TAG, "Error getting volume", e);
            return jsonResponse(Response.Status.INTERNAL_ERROR, 
                "{\"status\": \"error\", \"message\": \"" + e.getMessage() + "\"}");
        }
    }

    /**
     * Handle POST /audio/volume - Set device volume level
     * Expects JSON body: {"volume": 0-15} or {"percentage": 0-100}
     */
    @NonNull
    private Response setVolume(IHTTPSession session) {
        try {
            Log.i(TAG, "🔊 Volume change requested via web interface");
            
            // Parse JSON body
            java.util.Map<String, String> files = new java.util.HashMap<>();
            session.parseBody(files);
            
            String postData = files.get("postData");
            if (postData == null || postData.isEmpty()) {
                return jsonResponse(Response.Status.BAD_REQUEST, 
                    "{\"status\": \"error\", \"message\": \"Missing request body\"}");
            }
            
            // Parse JSON to extract volume or percentage
            org.json.JSONObject json = new org.json.JSONObject(postData);
            
            android.media.AudioManager audioManager = (android.media.AudioManager) context.getSystemService(android.content.Context.AUDIO_SERVICE);
            if (audioManager == null) {
                Log.e(TAG, "AudioManager not available");
                return jsonResponse(Response.Status.INTERNAL_ERROR, 
                    "{\"status\": \"error\", \"message\": \"AudioManager not available\"}");
            }
            
            int maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            int targetVolume;
            
            if (json.has("volume")) {
                // Direct volume level (0-maxVolume)
                targetVolume = json.getInt("volume");
                targetVolume = Math.max(0, Math.min(targetVolume, maxVolume));
            } else if (json.has("percentage")) {
                // Percentage (0-100)
                float percentage = (float) json.getDouble("percentage");
                percentage = Math.max(0, Math.min(percentage, 100));
                targetVolume = Math.round(percentage * maxVolume / 100.0f);
            } else {
                return jsonResponse(Response.Status.BAD_REQUEST, 
                    "{\"status\": \"error\", \"message\": \"Missing 'volume' or 'percentage' field\"}");
            }
            
            // Set the volume
            audioManager.setStreamVolume(
                android.media.AudioManager.STREAM_MUSIC, 
                targetVolume, 
                0  // No UI flags (silent change)
            );
            
            // Update RemoteStreamManager state
            RemoteStreamManager manager = RemoteStreamManager.getInstance();
            manager.setMediaVolume(targetVolume);
            
            int actualVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
            float actualPercentage = actualVolume * 100.0f / maxVolume;
            
            Log.i(TAG, "✅ Volume set to: " + actualVolume + "/" + maxVolume + " (" + String.format("%.1f", actualPercentage) + "%)");
            
            String responseJson = String.format(
                "{\"status\": \"success\", \"volume\": %d, \"max_volume\": %d, \"percentage\": %.1f}",
                actualVolume, maxVolume, actualPercentage
            );
            
            return jsonResponse(Response.Status.OK, responseJson);
        } catch (org.json.JSONException e) {
            Log.e(TAG, "Invalid JSON in volume request", e);
            return jsonResponse(Response.Status.BAD_REQUEST, 
                "{\"status\": \"error\", \"message\": \"Invalid JSON: " + e.getMessage() + "\"}");
        } catch (Exception e) {
            Log.e(TAG, "Error setting volume", e);
            return jsonResponse(Response.Status.INTERNAL_ERROR, 
                "{\"status\": \"error\", \"message\": \"" + e.getMessage() + "\"}");
        }
    }

    /**
     * Handle POST /recording/toggle - Start/Stop recording based on current state.
     * Mirrors torch pattern: checks if recording is already in progress.
     */
    @NonNull
    private Response toggleRecording() {
        try {
            Log.i(TAG, "⏹️ Recording toggle requested via web interface");
            
            com.fadcam.SharedPreferencesManager spManager = com.fadcam.SharedPreferencesManager.getInstance(context);
            boolean isRecording = spManager.isRecordingInProgress();
            
            android.content.Intent intent = new android.content.Intent(context, com.fadcam.services.RecordingService.class);
            
            if (isRecording) {
                // Stop recording
                intent.setAction(com.fadcam.Constants.INTENT_ACTION_STOP_RECORDING);
                Log.d(TAG, "Recording in progress - sending STOP_RECORDING intent");
            } else {
                // Start recording
                intent.setAction(com.fadcam.Constants.INTENT_ACTION_START_RECORDING);
                Log.d(TAG, "Recording not in progress - sending START_RECORDING intent");
            }
            
            // Start the service
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            
            String action = isRecording ? "stop" : "start";
            String responseJson = String.format("{\"status\": \"success\", \"action\": \"%s\", \"isRecording\": %s}", 
                action, !isRecording);
            
            Log.i(TAG, "✅ Recording " + action + " intent sent");
            
            Response response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", responseJson);
            response.addHeader("Cache-Control", "no-cache");
            return response;
        } catch (Exception e) {
            Log.e(TAG, "Error toggling recording", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json; charset=utf-8", 
                "{\"status\": \"error\", \"message\": \"" + e.getMessage() + "\"}");
        }
    }

    /**
     * Handle POST /config/recordingMode - Set recording mode
     */
    @NonNull
    private Response setRecordingMode(IHTTPSession session) {
        try {
            // Parse JSON body
            int contentLength = 0;
            java.io.InputStream inputStream = session.getInputStream();
            if (inputStream != null) {
                contentLength = inputStream.available();
            }
            
            java.util.Map<String, String> files = new java.util.HashMap<>();
            session.parseBody(files);
            
            String body = files.get("postData");
            if (body == null || body.isEmpty()) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", "{\"error\": \"No body\"}");
            }
            
            // Parse JSON mode from body
            String mode = null;
            try {
                org.json.JSONObject json = new org.json.JSONObject(body);
                mode = json.getString("mode");
            } catch (Exception e) {
                Log.e(TAG, "Failed to parse recording mode JSON", e);
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", "{\"error\": \"Invalid JSON\"}");
            }
            
            // Convert mode string to StreamingMode enum
            RemoteStreamManager.StreamingMode streamingMode;
            if ("stream_only".equals(mode)) {
                streamingMode = RemoteStreamManager.StreamingMode.STREAM_ONLY;
            } else if ("stream_and_save".equals(mode)) {
                streamingMode = RemoteStreamManager.StreamingMode.STREAM_AND_SAVE;
            } else {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", 
                    "{\"error\": \"Invalid mode. Use 'stream_only' or 'stream_and_save'\"}");
            }
            
            // Set the mode in both RemoteStreamManager and preferences
            com.fadcam.SharedPreferencesManager spManager = com.fadcam.SharedPreferencesManager.getInstance(context);
            spManager.setStreamingMode(streamingMode);
            streamManager.setStreamingMode(streamingMode);
            
            Log.i(TAG, "✅ Recording mode set to: " + mode);
            
            Response response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", 
                "{\"status\": \"success\", \"message\": \"Recording mode set to " + mode + "\"}");
            response.addHeader("Cache-Control", "no-cache");
            return response;
        } catch (Exception e) {
            Log.e(TAG, "Error setting recording mode", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json; charset=utf-8", "{\"status\": \"error\", \"message\": \"" + e.getMessage() + "\"}");
        }
    }

    /**
     * Handle POST /config/streamQuality - Set stream quality
     */
    @NonNull
    private Response setStreamQuality(IHTTPSession session) {
        try {
            // Parse JSON body
            java.util.Map<String, String> files = new java.util.HashMap<>();
            session.parseBody(files);
            
            String body = files.get("postData");
            if (body == null || body.isEmpty()) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", "{\"error\": \"No body\"}");
            }
            
            // Parse JSON quality from body
            String quality = null;
            try {
                org.json.JSONObject json = new org.json.JSONObject(body);
                quality = json.getString("quality");
            } catch (Exception e) {
                Log.e(TAG, "Failed to parse stream quality JSON", e);
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", "{\"error\": \"Invalid JSON\"}");
            }
            
            // Convert string to preset and apply
            try {
                StreamQuality.Preset preset = StreamQuality.Preset.valueOf(quality.toUpperCase());
                streamManager.setStreamQuality(preset, context);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Invalid stream quality preset: " + quality);
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", "{\"error\": \"Invalid quality preset\"}");
            }
            
            Log.i(TAG, "✅ Stream quality set to: " + quality);
            
            Response response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", 
                "{\"status\": \"success\", \"message\": \"Stream quality set to " + quality + "\"}");
            response.addHeader("Cache-Control", "no-cache");
            return response;
        } catch (Exception e) {
            Log.e(TAG, "Error setting stream quality", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json; charset=utf-8", "{\"status\": \"error\", \"message\": \"" + e.getMessage() + "\"}");
        }
    }

    /**
     * Handle POST /config/batteryWarning - Set battery warning threshold
     */
    @NonNull
    private Response setBatteryWarning(IHTTPSession session) {
        try {
            // Parse JSON body
            java.util.Map<String, String> files = new java.util.HashMap<>();
            session.parseBody(files);
            
            String body = files.get("postData");
            if (body == null || body.isEmpty()) {
                Log.w(TAG, "❌ Battery warning: No body received");
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", "{\"error\": \"No body\"}");
            }
            
            Log.d(TAG, "[Battery] Received body: " + body);
            
            // Parse JSON threshold from body
            int threshold = 20; // Default
            try {
                org.json.JSONObject json = new org.json.JSONObject(body);
                threshold = json.getInt("threshold");
                Log.d(TAG, "[Battery] Parsed threshold: " + threshold);
            } catch (Exception e) {
                Log.e(TAG, "Failed to parse battery warning threshold JSON", e);
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", "{\"error\": \"Invalid JSON\"}");
            }
            
            // Validate threshold
            if (threshold < 5 || threshold > 100) {
                Log.w(TAG, "❌ Battery warning: Invalid threshold " + threshold);
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", "{\"error\": \"Threshold must be between 5 and 100\"}");
            }
            
            // Store battery warning threshold using SharedPreferencesManager
            com.fadcam.SharedPreferencesManager spManager = com.fadcam.SharedPreferencesManager.getInstance(context);
            spManager.setBatteryWarningThreshold(threshold);
            Log.i(TAG, "✅ Battery warning threshold set to: " + threshold + "%");
            
            // Verify it was stored
            int storedValue = spManager.getBatteryWarningThreshold();
            Log.d(TAG, "[Battery] Verified stored value: " + storedValue);
            
            Response response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", 
                "{\"status\": \"success\", \"message\": \"Battery warning set to " + threshold + "%\"}");
            response.addHeader("Cache-Control", "no-cache");
            return response;
        } catch (Exception e) {
            Log.e(TAG, "Error setting battery warning", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json; charset=utf-8", "{\"status\": \"error\", \"message\": \"" + e.getMessage() + "\"}");
        }
    }

    
    // START: VideoCodec Config Endpoint
    @NonNull
    private Response setVideoCodec(IHTTPSession session) {
        try {
            // Parse JSON body
            java.util.Map<String, String> files = new java.util.HashMap<>();
            session.parseBody(files);
            
            String body = files.get("postData");
            if (body == null || body.isEmpty()) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", 
                    "{\"status\": \"error\", \"message\": \"Missing request body\"}");
            }
            
            // Parse JSON: {"codec": "AVC"} or {"codec": "HEVC"}
            String codec = null;
            try {
                org.json.JSONObject json = new org.json.JSONObject(body);
                codec = json.getString("codec");
            } catch (Exception e) {
                Log.e(TAG, "Failed to parse video codec JSON", e);
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", 
                    "{\"status\": \"error\", \"message\": \"Invalid JSON\"}");
            }
            
            if (codec == null || codec.isEmpty()) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", 
                    "{\"status\": \"error\", \"message\": \"Missing or invalid codec field\"}");
            }
            
            // Validate codec is one of the supported values
            codec = codec.trim().toUpperCase();
            if (!codec.equals("AVC") && !codec.equals("HEVC")) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json; charset=utf-8", 
                    "{\"status\": \"error\", \"message\": \"Invalid codec. Must be AVC or HEVC\"}");
            }
            
            com.fadcam.SharedPreferencesManager spManager = com.fadcam.SharedPreferencesManager.getInstance(context);
            
            // Store in SharedPreferences
            android.content.SharedPreferences.Editor editor = spManager.sharedPreferences.edit();
            editor.putString(com.fadcam.Constants.PREF_VIDEO_CODEC, codec);
            editor.apply();
            
            Log.i(TAG, "✅ Video codec set to: " + codec);
            
            // Verify it was stored
            String storedCodec = spManager.getVideoCodec().toString();
            Log.d(TAG, "[VideoCodec] Verified stored codec: " + storedCodec);
            
            Response response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", 
                "{\"status\": \"success\", \"message\": \"Video codec set to " + codec + "\"}");
            response.addHeader("Cache-Control", "no-cache");
            return response;
        } catch (Exception e) {
            Log.e(TAG, "Error setting video codec", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json; charset=utf-8", 
                "{\"status\": \"error\", \"message\": \"" + e.getMessage() + "\"}");
        }
    }
    // END: VideoCodec Config Endpoint

    @NonNull
    private Response serveStatus() {
        try {
            long startTime = System.currentTimeMillis();
            Log.d(TAG, "📊 [/status] Request received");
            
            String statusJson = streamManager.getStatusJson();
            long jsonTime = System.currentTimeMillis();
            Log.d(TAG, "📊 [/status] JSON generated in " + (jsonTime - startTime) + "ms, size: " + statusJson.length() + " bytes");
            
            Log.d(TAG, "📊 [/status] Response prepared, sending to client");
            return jsonResponse(Response.Status.OK, statusJson);
        } catch (Exception e) {
            Log.e(TAG, "❌ [/status] Error in serveStatus: " + e.getMessage(), e);
            return jsonResponse(Response.Status.INTERNAL_ERROR, "{\"error\": \"Failed to generate status\"}");
        }
    }
    
    // START: Authentication Endpoints
    /**
     * Handle POST /auth/login - Authenticate and return session token
     */
    @NonNull
    private Response handleLogin(IHTTPSession session, String clientIP) {
        try {
            RemoteAuthManager authManager = RemoteAuthManager.getInstance(context);
            
            // If auth is disabled, return success without token
            if (!authManager.isAuthEnabled()) {
                com.fadcam.streaming.model.AuthResponse authResponse = 
                    com.fadcam.streaming.model.AuthResponse.authDisabled();
                return jsonResponse(Response.Status.OK, authResponse.toJson());
            }
            
            // Parse JSON body
            java.util.Map<String, String> files = new java.util.HashMap<>();
            session.parseBody(files);
            String body = files.get("postData");
            
            if (body == null || body.isEmpty()) {
                com.fadcam.streaming.model.AuthResponse authResponse = 
                    com.fadcam.streaming.model.AuthResponse.failure("Missing request body");
                return jsonResponse(Response.Status.BAD_REQUEST, authResponse.toJson());
            }
            
            // Extract password from JSON
            String password = null;
            try {
                org.json.JSONObject json = new org.json.JSONObject(body);
                password = json.getString("password");
                // Trim whitespace from password
                if (password != null) {
                    password = password.trim();
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to parse login JSON", e);
                com.fadcam.streaming.model.AuthResponse authResponse = 
                    com.fadcam.streaming.model.AuthResponse.failure("Invalid JSON");
                return jsonResponse(Response.Status.BAD_REQUEST, authResponse.toJson());
            }
            
            // Verify password
            if (!authManager.verifyPassword(password)) {
                Log.w(TAG, "Invalid login attempt from " + clientIP + " (password mismatch)");
                com.fadcam.streaming.model.AuthResponse authResponse = 
                    com.fadcam.streaming.model.AuthResponse.invalidCredentials();
                return jsonResponse(Response.Status.UNAUTHORIZED, authResponse.toJson());
            }
            
            // Create session
            String userAgent = session.getHeaders().get("user-agent");
            String deviceInfo = clientIP + " - " + (userAgent != null ? userAgent : "unknown");
            com.fadcam.streaming.model.SessionToken token = authManager.createSession(deviceInfo);
            
            Log.i(TAG, "✅ Successful login from " + clientIP);
            
            com.fadcam.streaming.model.AuthResponse authResponse = 
                com.fadcam.streaming.model.AuthResponse.success(token.getToken(), token.getExpiresAtMs());
            return jsonResponse(Response.Status.OK, authResponse.toJson());
            
        } catch (Exception e) {
            Log.e(TAG, "Error handling login", e);
            com.fadcam.streaming.model.AuthResponse authResponse = 
                com.fadcam.streaming.model.AuthResponse.failure("Internal error");
            return jsonResponse(Response.Status.INTERNAL_ERROR, authResponse.toJson());
        }
    }
    
    /**
     * Handle POST /auth/logout - Invalidate session token
     */
    @NonNull
    private Response handleLogout(IHTTPSession session) {
        try {
            String token = extractAuthToken(session);
            if (token != null) {
                RemoteAuthManager authManager = RemoteAuthManager.getInstance(context);
                authManager.revokeSession(token);
                Log.i(TAG, "Session logged out");
            }
            
            com.fadcam.streaming.model.AuthResponse authResponse = 
                new com.fadcam.streaming.model.AuthResponse(true, "Logged out successfully");
            return jsonResponse(Response.Status.OK, authResponse.toJson());
        } catch (Exception e) {
            Log.e(TAG, "Error handling logout", e);
            com.fadcam.streaming.model.AuthResponse authResponse = 
                com.fadcam.streaming.model.AuthResponse.failure("Internal error");
            return jsonResponse(Response.Status.INTERNAL_ERROR, authResponse.toJson());
        }
    }
    
    /**
     * Handle GET /auth/check - Check if auth is enabled and token is valid
     */
    @NonNull
    private Response handleAuthCheck(IHTTPSession session) {
        try {
            RemoteAuthManager authManager = RemoteAuthManager.getInstance(context);
            
            boolean authEnabled = authManager.isAuthEnabled();
            String token = extractAuthToken(session);
            boolean tokenValid = false;
            
            if (authEnabled && token != null) {
                com.fadcam.streaming.model.SessionToken sessionToken = authManager.validateToken(token);
                tokenValid = sessionToken != null && sessionToken.isValid();
            }
            
            String json = String.format(
                "{\"authEnabled\":%b,\"authenticated\":%b,\"tokenValid\":%b}",
                authEnabled, !authEnabled || tokenValid, tokenValid
            );
            
            return jsonResponse(Response.Status.OK, json);
        } catch (Exception e) {
            Log.e(TAG, "Error checking auth", e);
            return jsonResponse(Response.Status.INTERNAL_ERROR, 
                "{\"error\":\"Internal error\"}");
        }
    }
    
    /**
     * Handle POST /auth/changePassword - Change password (requires valid session)
     */
    @NonNull
    private Response handleChangePassword(IHTTPSession session) {
        try {
            // Validate current session
            if (!validateAuthToken(session)) {
                com.fadcam.streaming.model.AuthResponse authResponse = 
                    com.fadcam.streaming.model.AuthResponse.unauthorized();
                return jsonResponse(Response.Status.UNAUTHORIZED, authResponse.toJson());
            }
            
            // Parse JSON body
            java.util.Map<String, String> files = new java.util.HashMap<>();
            session.parseBody(files);
            String body = files.get("postData");
            
            if (body == null || body.isEmpty()) {
                com.fadcam.streaming.model.AuthResponse authResponse = 
                    com.fadcam.streaming.model.AuthResponse.failure("Missing request body");
                return jsonResponse(Response.Status.BAD_REQUEST, authResponse.toJson());
            }
            
            // Extract passwords from JSON
            String oldPassword, newPassword;
            try {
                org.json.JSONObject json = new org.json.JSONObject(body);
                oldPassword = json.getString("oldPassword");
                newPassword = json.getString("newPassword");
            } catch (Exception e) {
                Log.e(TAG, "Failed to parse change password JSON", e);
                com.fadcam.streaming.model.AuthResponse authResponse = 
                    com.fadcam.streaming.model.AuthResponse.failure("Invalid JSON");
                return jsonResponse(Response.Status.BAD_REQUEST, authResponse.toJson());
            }
            
            RemoteAuthManager authManager = RemoteAuthManager.getInstance(context);
            
            // Verify old password
            if (!authManager.verifyPassword(oldPassword)) {
                com.fadcam.streaming.model.AuthResponse authResponse = 
                    com.fadcam.streaming.model.AuthResponse.failure("Current password is incorrect");
                return jsonResponse(Response.Status.UNAUTHORIZED, authResponse.toJson());
            }
            
            // Set new password
            if (!authManager.setPassword(newPassword)) {
                com.fadcam.streaming.model.AuthResponse authResponse = 
                    com.fadcam.streaming.model.AuthResponse.failure("New password invalid (4-32 characters required)");
                return jsonResponse(Response.Status.BAD_REQUEST, authResponse.toJson());
            }
            
            Log.i(TAG, "Password changed successfully");
            com.fadcam.streaming.model.AuthResponse authResponse = 
                new com.fadcam.streaming.model.AuthResponse(true, "Password changed successfully");
            return jsonResponse(Response.Status.OK, authResponse.toJson());
            
        } catch (Exception e) {
            Log.e(TAG, "Error changing password", e);
            com.fadcam.streaming.model.AuthResponse authResponse = 
                com.fadcam.streaming.model.AuthResponse.failure("Internal error");
            return jsonResponse(Response.Status.INTERNAL_ERROR, authResponse.toJson());
        }
    }
    
    /**
     * Extract Bearer token from Authorization header
     */
    private String extractAuthToken(IHTTPSession session) {
        String authHeader = session.getHeaders().get("authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return null;
    }
    
    /**
     * Validate auth token for protected endpoints
     * Returns true if auth is disabled OR token is valid
     */
    private boolean validateAuthToken(IHTTPSession session) {
        RemoteAuthManager authManager = RemoteAuthManager.getInstance(context);
        
        // If auth is disabled, allow access
        if (!authManager.isAuthEnabled()) {
            return true;
        }
        
        // Extract and validate token
        String token = extractAuthToken(session);
        if (token == null) {
            return false;
        }
        
        com.fadcam.streaming.model.SessionToken sessionToken = authManager.validateToken(token);
        return sessionToken != null && sessionToken.isValid();
    }
    // END: Authentication Endpoints
    
    /**
     * Serve landing page HTML from streaming/web/index.html (stored in assets)
     */
    @NonNull
    /**
     * Serve static files (CSS, JS, images, fonts) from assets/web/
     */
    private Response serveStaticFile(String uri) {
        try {
            // Map URI to assets path: /css/theme.css -> web/css/theme.css
            String assetPath = "web" + uri;
            
            // Determine MIME type
            String mimeType = getMimeType(uri);
            
            // Load from assets
            Log.d(TAG, "🔍 Attempting to load asset: " + assetPath);
            InputStream fileStream = context.getAssets().open(assetPath);
            Response response = newFixedLengthResponse(Response.Status.OK, mimeType, fileStream, -1);
            
            // Cache static assets for 1 hour (except HTML)
            if (!mimeType.equals("text/html")) {
                response.addHeader("Cache-Control", "public, max-age=3600");
            }
            
            Log.d(TAG, "✅ Served static file: " + assetPath + " (" + mimeType + ")");
            return response;
        } catch (IOException e) {
            Log.e(TAG, "❌ Failed to load static file: " + uri + " (asset path: web" + uri + ")", e);
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found: " + uri);
        }
    }
    
    /**
     * Get MIME type from file extension
     */
    private String getMimeType(String uri) {
        if (uri.endsWith(".css")) return "text/css";
        if (uri.endsWith(".js")) return "application/javascript";
        if (uri.endsWith(".json") || uri.endsWith(".jsonc")) return "application/json; charset=utf-8";
        if (uri.endsWith(".png")) return "image/png";
        if (uri.endsWith(".jpg") || uri.endsWith(".jpeg")) return "image/jpeg";
        if (uri.endsWith(".svg")) return "image/svg+xml";
        if (uri.endsWith(".woff")) return "font/woff";
        if (uri.endsWith(".woff2")) return "font/woff2";
        if (uri.endsWith(".ttf")) return "font/ttf";
        if (uri.endsWith(".html")) return "text/html";
        return MIME_PLAINTEXT;
    }
    
    private Response serveLandingPage() {
        Log.i(TAG, "🏠 [/] Dashboard page requested");
        try {
            // Load from assets/web/index.html at runtime
            InputStream htmlStream = context.getAssets().open("web/index.html");
            Response response = newFixedLengthResponse(Response.Status.OK, "text/html", htmlStream, -1);
            response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            response.addHeader("Pragma", "no-cache");
            response.addHeader("Expires", "0");
            Log.i(TAG, "🏠 [/] Dashboard served successfully");
            return response;
        } catch (IOException e) {
            Log.e(TAG, "❌ [/] Failed to load index.html from assets/web", e);
            // Fallback to simple error message
            String html = "<!DOCTYPE html>\n" +
                    "<html><head><title>Error</title></head><body>\n" +
                    "<h1>Error Loading Player</h1>\n" +
                    "<p>Failed to load index.html from assets/web folder.</p>\n" +
                    "<p>Error: " + e.getMessage() + "</p>\n" +
                    "<p>Stream URL: <a href=\"/live.m3u8\">/live.m3u8</a></p>\n" +
                    "</body></html>";
            Response response = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/html", html);
            return response;
        }
    }
    
    /**
     * Get host address for display (gets WiFi IP address).
     */
    private String getHostAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface networkInterface = interfaces.nextElement();
                
                // Skip loopback and inactive interfaces
                if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                    continue;
                }
                
                java.util.Enumeration<java.net.InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress address = addresses.nextElement();
                    
                    // Get any IPv4 address that's not loopback (works with any private IP range)
                    if (!address.isLoopbackAddress() && address instanceof java.net.Inet4Address) {
                        String ip = address.getHostAddress();
                        Log.d(TAG, "Found network interface IP: " + ip);
                        return ip; // Return first valid local IP found
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting host address", e);
        }
        return "localhost";
    }

    /**
     * Ring alarm (security buzzer) with specified sound and duration.
     * Expects JSON: {"sound": "office_phone.mp3", "duration_ms": 10000} or {"sound": "office_phone.mp3", "duration_ms": -1} for infinite
     */
    private Response ringAlarm(IHTTPSession session) {
        try {
            Log.i(TAG, "🚨 Alarm ring requested via web interface");
            
            // Parse JSON body
            java.util.Map<String, String> files = new java.util.HashMap<>();
            session.parseBody(files);
            
            String postData = files.get("postData");
            if (postData == null || postData.isEmpty()) {
                return jsonResponse(Response.Status.BAD_REQUEST, 
                    "{\"status\": \"error\", \"message\": \"Missing request body\"}");
            }
            
            org.json.JSONObject json = new org.json.JSONObject(postData);
            
            // Get sound filename (required)
            String soundFile = json.optString("sound", "office_phone.mp3");
            if (soundFile.isEmpty()) {
                return jsonResponse(Response.Status.BAD_REQUEST, 
                    "{\"status\": \"error\", \"message\": \"Sound file not specified\"}");
            }
            
            // Get duration in milliseconds (-1 = infinite)
            long durationMs = json.optLong("duration_ms", -1);
            
            // Update stream manager state
            streamManager.setSelectedAlarmSound(soundFile);
            streamManager.setAlarmDurationMs(durationMs);
            streamManager.setAlarmRinging(true);
            
            // Start alarm playback via AlarmService
            android.content.Intent alarmIntent = new android.content.Intent(context, com.fadcam.services.AlarmService.class);
            alarmIntent.setAction("com.fadcam.action.RING_ALARM");
            alarmIntent.putExtra("sound", soundFile);
            alarmIntent.putExtra("duration_ms", durationMs);
            
            try {
                android.os.Build.VERSION_CODES versionCodes = null;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(alarmIntent);
                } else {
                    context.startService(alarmIntent);
                }
                Log.i(TAG, "AlarmService started");
            } catch (Exception e) {
                Log.e(TAG, "Error starting AlarmService", e);
                return jsonResponse(Response.Status.INTERNAL_ERROR, 
                    "{\"status\": \"error\", \"message\": \"Failed to start alarm service\"}");
            }
            
            return jsonResponse(Response.Status.OK, 
                "{\"status\": \"success\", \"message\": \"Alarm ringing\", \"sound\": \"" + soundFile + "\", \"duration_ms\": " + durationMs + "}");
            
        } catch (Exception e) {
            Log.e(TAG, "Error ringing alarm", e);
            return jsonResponse(Response.Status.INTERNAL_ERROR, 
                "{\"status\": \"error\", \"message\": \"" + e.getMessage() + "\"}");
        }
    }

    /**
     * Stop alarm (security buzzer).
     */
    private Response stopAlarm() {
        try {
            Log.i(TAG, "🔇 Alarm stop requested via web interface");
            
            // Update stream manager state
            streamManager.setAlarmRinging(false);
            
            // Stop alarm playback via AlarmService
            android.content.Intent alarmIntent = new android.content.Intent(context, com.fadcam.services.AlarmService.class);
            alarmIntent.setAction("com.fadcam.action.STOP_ALARM");
            context.startService(alarmIntent);
            
            Log.i(TAG, "Stop alarm service command sent");
            
            return jsonResponse(Response.Status.OK, 
                "{\"status\": \"success\", \"message\": \"Alarm stopped\"}");
            
        } catch (Exception e) {
            Log.e(TAG, "Error stopping alarm", e);
            return jsonResponse(Response.Status.INTERNAL_ERROR, 
                "{\"status\": \"error\", \"message\": \"" + e.getMessage() + "\"}");
        }
    }

    /**
     * Schedule alarm for future time.
     */
    private Response scheduleAlarm(IHTTPSession session) {
        try {
            Log.i(TAG, "📅 Alarm schedule requested via web interface");
            
            // Parse JSON body
            java.util.Map<String, String> files = new java.util.HashMap<>();
            session.parseBody(files);
            
            String postData = files.get("postData");
            if (postData == null || postData.isEmpty()) {
                return jsonResponse(Response.Status.BAD_REQUEST, 
                    "{\"status\": \"error\", \"message\": \"Missing request body\"}");
            }
            
            org.json.JSONObject json = new org.json.JSONObject(postData);
            
            // Get scheduled time (required)
            long scheduledTime = json.optLong("scheduledTime", -1);
            if (scheduledTime <= 0) {
                return jsonResponse(Response.Status.BAD_REQUEST, 
                    "{\"status\": \"error\", \"message\": \"Invalid scheduled time\"}");
            }
            
            // Get sound filename (required)
            String soundFile = json.optString("sound", "office_phone.mp3");
            if (soundFile.isEmpty()) {
                return jsonResponse(Response.Status.BAD_REQUEST, 
                    "{\"status\": \"error\", \"message\": \"Sound file not specified\"}");
            }
            
            // Get duration in milliseconds (-1 = infinite)
            long durationMs = json.optLong("duration_ms", 30000);
            
            // Calculate delay in milliseconds
            long currentTime = System.currentTimeMillis();
            long delayMs = scheduledTime - currentTime;
            
            if (delayMs <= 0) {
                return jsonResponse(Response.Status.BAD_REQUEST, 
                    "{\"status\": \"error\", \"message\": \"Scheduled time must be in the future\"}");
            }
            
            // Use AlarmManager to schedule the alarm
            android.app.AlarmManager alarmManager = (android.app.AlarmManager) context.getSystemService(android.content.Context.ALARM_SERVICE);
            android.content.Intent alarmIntent = new android.content.Intent(context, com.fadcam.services.AlarmService.class);
            alarmIntent.setAction("com.fadcam.action.RING_ALARM");
            alarmIntent.putExtra("sound", soundFile);
            alarmIntent.putExtra("duration_ms", durationMs);
            
            android.app.PendingIntent pendingIntent = android.app.PendingIntent.getService(
                context, 
                1001, // Unique ID for scheduled alarms
                alarmIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
            );
            
            // Schedule using setAndAllowWhileIdle for reliability even in doze mode
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                alarmManager.setAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    scheduledTime,
                    pendingIntent
                );
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    scheduledTime,
                    pendingIntent
                );
            }
            
            Log.i(TAG, "✅ Alarm scheduled for " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(scheduledTime)) + 
                " (in " + (delayMs / 1000) + " seconds)");
            
            return jsonResponse(Response.Status.OK, 
                "{\"status\": \"success\", \"message\": \"Alarm scheduled\", \"sound\": \"" + soundFile + 
                "\", \"duration_ms\": " + durationMs + ", \"scheduled_for\": " + scheduledTime + "}");
            
        } catch (Exception e) {
            Log.e(TAG, "Error scheduling alarm", e);
            return jsonResponse(Response.Status.INTERNAL_ERROR, 
                "{\"status\": \"error\", \"message\": \"" + e.getMessage() + "\"}");
        }
    }

    /**
     * Handle POST /api/notifications
     * Body: { "action": "save" | "clear", "history": JSON array (for save action) }
     */
    private Response handleNotifications(IHTTPSession session) {
        try {
            int contentLength = Integer.parseInt(session.getHeaders().getOrDefault("content-length", "0"));
            if (contentLength == 0) {
                return jsonResponse(Response.Status.BAD_REQUEST, 
                    "{\"status\": \"error\", \"message\": \"Empty request body\"}");
            }

            byte[] buffer = new byte[Math.min(contentLength, 1024 * 1024)]; // Max 1MB
            int bytesRead = session.getInputStream().read(buffer, 0, buffer.length);
            String body = new String(buffer, 0, bytesRead, "UTF-8");

            org.json.JSONObject request = new org.json.JSONObject(body);
            String action = request.optString("action");

            com.fadcam.SharedPreferencesManager prefs = com.fadcam.SharedPreferencesManager.getInstance(context);

            if ("save".equals(action)) {
                // Save notification history
                String history = request.optString("history", "[]");
                prefs.saveNotificationHistory(history);
                Log.d(TAG, "✅ Notification history saved from JS");
                return jsonResponse(Response.Status.OK, 
                    "{\"status\": \"success\", \"message\": \"Notifications saved\"}");
            } else if ("clear".equals(action)) {
                // Clear all notifications
                prefs.clearNotificationHistory();
                Log.d(TAG, "✅ Notification history cleared");
                return jsonResponse(Response.Status.OK, 
                    "{\"status\": \"success\", \"message\": \"Notifications cleared\"}");
            } else {
                return jsonResponse(Response.Status.BAD_REQUEST, 
                    "{\"status\": \"error\", \"message\": \"Unknown action\"}");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling notifications", e);
            return jsonResponse(Response.Status.INTERNAL_ERROR, 
                "{\"status\": \"error\", \"message\": \"" + e.getMessage() + "\"}");
        }
    }

    /**
     * Handle GET /api/notifications
     * Returns cached notification history
     */
    private Response getNotifications() {
        try {
            com.fadcam.SharedPreferencesManager prefs = com.fadcam.SharedPreferencesManager.getInstance(context);
            String history = prefs.getNotificationHistory();
            
            return jsonResponse(Response.Status.OK, 
                "{\"status\": \"success\", \"history\": " + history + "}");
        } catch (Exception e) {
            Log.e(TAG, "Error getting notifications", e);
            return jsonResponse(Response.Status.INTERNAL_ERROR, 
                "{\"status\": \"error\", \"message\": \"" + e.getMessage() + "\"}");
        }
    }

    /**
     * Proxy GitHub notification file fetch to avoid browser CORS issues
     * GET /api/github/notification - Fetches JSONC from GitHub and returns as JSON
     */
    private Response fetchGitHubNotification() {
        try {
            String githubUrl = "https://raw.githubusercontent.com/anonfaded/FadCam/master/app/src/main/assets/web/fadex/pushnotification.jsonc";
            
            // Create URL connection
            java.net.URL url = new java.net.URL(githubUrl);
            java.net.URLConnection conn = url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "FadCam-NotificationManager");
            
            // Read response
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line).append("\n");
            }
            reader.close();
            
            String jsonc = response.toString();
            
            // Parse JSONC (strip comments) to JSON
            String json = parseJSONCToJSON(jsonc);
            
            return jsonResponse(Response.Status.OK, json);
        } catch (Exception e) {
            Log.e(TAG, "❌ Error fetching GitHub notification", e);
            return jsonResponse(Response.Status.INTERNAL_ERROR, 
                "{\"status\": \"error\", \"message\": \"" + e.getMessage() + "\"}");
        }
    }

    /**
     * Parse JSONC (JSON with comments) to valid JSON
     * Strips single-line and multi-line comments before returning
     */
    private String parseJSONCToJSON(String jsonc) {
        // Remove multi-line comments first /* ... */
        String cleaned = jsonc.replaceAll("/\\*[\\s\\S]*?\\*/", "");
        
        // Remove single-line comments // ... (handle all line endings: \r\n, \n, \r)
        // Split by lines, remove comment from each line, rejoin
        String[] lines = cleaned.split("\r?\n|\r");
        StringBuilder result = new StringBuilder();
        
        for (String line : lines) {
            // Find // that's not inside a string
            int commentIndex = -1;
            boolean inString = false;
            boolean escaped = false;
            
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                
                if (escaped) {
                    escaped = false;
                    continue;
                }
                
                if (c == '\\') {
                    escaped = true;
                    continue;
                }
                
                if (c == '"') {
                    inString = !inString;
                    continue;
                }
                
                // If we find // outside a string, remove from here
                if (!inString && i < line.length() - 1 && c == '/' && line.charAt(i + 1) == '/') {
                    commentIndex = i;
                    break;
                }
            }
            
            // Add the line (without comment part if found)
            if (commentIndex >= 0) {
                result.append(line.substring(0, commentIndex));
            } else {
                result.append(line);
            }
            result.append("\n");
        }
        
        // Remove trailing commas before } and ]
        String finalCleaned = result.toString().replaceAll(",\\s*([}\\]])", "$1");
        
        return finalCleaned.trim();
    }
}

