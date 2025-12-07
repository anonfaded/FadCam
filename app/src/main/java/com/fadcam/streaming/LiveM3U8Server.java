package com.fadcam.streaming;

import android.util.Log;

import androidx.annotation.NonNull;

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
 */
public class LiveM3U8Server extends NanoHTTPD {
    private static final String TAG = "LiveM3U8Server";
    
    private final RemoteStreamManager streamManager;
    private final android.content.Context context;
    
    // HLS configuration
    private static final int TARGET_DURATION = 2; // Segment duration in seconds (1s actual + safety margin)
    
    public LiveM3U8Server(android.content.Context context, int port) throws IOException {
        super(port);
        this.context = context.getApplicationContext();
        this.streamManager = RemoteStreamManager.getInstance();
        Log.i(TAG, "LiveM3U8Server created on port " + port);
    }
    
    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();
        String userAgent = session.getHeaders().get("user-agent");
        
        Log.i(TAG, "📥 " + method + " " + uri + " from " + session.getRemoteIpAddress());
        Log.d(TAG, "   User-Agent: " + (userAgent != null ? userAgent : "unknown"));
        
        // Add CORS headers for web player compatibility
        Response response;
        
        if (Method.GET.equals(method)) {
            if ("/live.m3u8".equals(uri) || "/stream.m3u8".equals(uri)) {
                // HLS playlist - industry standard for live streaming
                response = servePlaylist();
            } else if ("/init.mp4".equals(uri)) {
                response = serveInitSegment();
            } else if (uri.startsWith("/seg-") && uri.endsWith(".m4s")) {
                response = serveFragment(uri);
            } else if ("/status".equals(uri)) {
                response = serveStatus();
            } else if ("/".equals(uri)) {
                response = serveLandingPage();
            } else {
                response = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found");
            }
        } else if (Method.POST.equals(method)) {
            if ("/torch/toggle".equals(uri)) {
                response = toggleTorch();
            } else {
                response = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found");
            }
        } else {
            response = newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Only GET and POST requests supported");
        }
        
        // Add CORS headers
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "Content-Type");
        
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
            // Generate M3U8 playlist
            StringBuilder m3u8 = new StringBuilder();
            m3u8.append("#EXTM3U\n");
            m3u8.append("#EXT-X-VERSION:7\n"); // fMP4 requires version 7
            m3u8.append("#EXT-X-INDEPENDENT-SEGMENTS\n");
            m3u8.append("#EXT-X-TARGETDURATION:2\n"); // 2-second max fragment duration (1s actual + buffer)
            
            // Reference to initialization segment (ftyp + moov)
            // Use absolute path for better player compatibility
            // Note: HEVC codec may not be supported in all browsers
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
            
            // REMOVED: 300ms age filter caused 503 errors when all fragments were fresh
            // Fragments from RemoteStreamManager are already complete and ready to serve
            java.util.List<RemoteStreamManager.FragmentData> stable = new java.util.ArrayList<>(fragments);

            // CRITICAL FIX FOR 404 ERRORS: Only show last 5 fragments at live edge!
            // Problem: If we show all 15 buffered fragments, by the time browser downloads oldest ones,
            // they're already evicted from the circular buffer (new fragments push old ones out).
            // Solution: Only reference the NEWEST 5 fragments (live edge) to ensure they're still
            // in buffer when requested. This is standard HLS live streaming practice.
            int LIVE_WINDOW_SIZE = 5; // Show only last 5 fragments (10 seconds at 2s/fragment)
            java.util.List<RemoteStreamManager.FragmentData> liveEdge = new java.util.ArrayList<>();
            int startIdx = Math.max(0, stable.size() - LIVE_WINDOW_SIZE);
            for (int i = startIdx; i < stable.size(); i++) {
                liveEdge.add(stable.get(i));
            }
            
            // CRITICAL FIX: The MEDIA-SEQUENCE must match the sequence number stored 
            // inside the moof box (mfhd), otherwise players will reject fragments
            // The sequence numbers in moof are absolute (1, 2, 3...), so playlist must match
            m3u8.append("#EXT-X-MEDIA-SEQUENCE:").append(liveEdge.get(0).sequenceNumber).append("\n");

            for (RemoteStreamManager.FragmentData fragment : liveEdge) {
                // Use exact duration from fragment (1.0 seconds)
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
    private Response serveInitSegment() {
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
            Log.d(TAG, "📋 Serving initialization segment (" + (initSegment.length / 1024) + " KB)");
            
            InputStream initStream = new java.io.ByteArrayInputStream(initSegment);
            Response response = newFixedLengthResponse(Response.Status.OK, "video/mp4", initStream, initSegment.length);
            response.addHeader("Cache-Control", "public, max-age=31536000"); // Cache init segment
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
    private Response serveFragment(String uri) {
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
                Log.d(TAG, "📦 Serving fragment #" + sequenceNumber + " (" + (fragment.sizeBytes / 1024) + " KB)");
                
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
            
            Log.i(TAG, "✅ Torch toggle intent sent");
            
            Response response = newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\": \"torch_toggle_sent\"}");
            response.addHeader("Cache-Control", "no-cache");
            return response;
        } catch (Exception e) {
            Log.e(TAG, "Error toggling torch", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"status\": \"error\", \"message\": \"" + e.getMessage() + "\"}");
        }
    }

    
    /**
     * Serve status JSON.
     */
    @NonNull
    private Response serveStatus() {
        String statusJson = streamManager.getStatusJson();
        Response response = newFixedLengthResponse(Response.Status.OK, "application/json", statusJson);
        response.addHeader("Cache-Control", "no-cache");
        return response;
    }
    
    /**
     * Serve landing page HTML from streaming/web/index.html (stored in assets)
     */
    @NonNull
    private Response serveLandingPage() {
        try {
            // Load from assets/web/index.html at runtime
            InputStream htmlStream = context.getAssets().open("web/index.html");
            Response response = newFixedLengthResponse(Response.Status.OK, "text/html", htmlStream, -1);
            response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            response.addHeader("Pragma", "no-cache");
            response.addHeader("Expires", "0");
            Log.i(TAG, "✅ Loaded index.html from assets/web");
            return response;
        } catch (IOException e) {
            Log.e(TAG, "Failed to load index.html from assets/web", e);
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
}
