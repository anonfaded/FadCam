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
    
    // HLS configuration
    private static final int TARGET_DURATION = 3; // Segment duration in seconds (approximate)
    
    public LiveM3U8Server(int port) throws IOException {
        super(port);
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
        } else {
            response = newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Only GET requests supported");
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
        // Check if initialization segment is available
        byte[] initSegment = streamManager.getInitializationSegment();
        if (initSegment == null) {
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                MIME_PLAINTEXT,
                "Stream not ready. Waiting for initialization segment..."
            );
        }
        
        // Get buffered fragments
        java.util.List<RemoteStreamManager.FragmentData> fragments = streamManager.getBufferedFragments();
        
        if (fragments.isEmpty()) {
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                MIME_PLAINTEXT,
                "No fragments available yet. Recording started recently..."
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
            m3u8.append("#EXT-X-TARGETDURATION:3\n"); // 3-second max fragment duration
            
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
            
            // Add fragment references (only include stable fragments that are at least 300ms old)
            java.util.List<RemoteStreamManager.FragmentData> stable = new java.util.ArrayList<>();
            long currentTime = System.currentTimeMillis();
            for (RemoteStreamManager.FragmentData fragment : fragments) {
                long fragmentAge = currentTime - fragment.timestamp;
                if (fragmentAge >= 300) { // small guard to avoid serving partially written data
                    stable.add(fragment);
                }
            }

            // Must have at least one stable fragment to serve
            if (stable.isEmpty()) {
                return newFixedLengthResponse(
                    Response.Status.SERVICE_UNAVAILABLE,
                    MIME_PLAINTEXT,
                    "Fragments still being written, please wait..."
                );
            }

            // CRITICAL FIX: The MEDIA-SEQUENCE must match the sequence number stored 
            // inside the moof box (mfhd), otherwise players will reject fragments
            // The sequence numbers in moof are absolute (1, 2, 3...), so playlist must match
            m3u8.append("#EXT-X-MEDIA-SEQUENCE:").append(stable.get(0).sequenceNumber).append("\n");

            for (RemoteStreamManager.FragmentData fragment : stable) {
                // Use exact duration from fragment (2.0 seconds)
                m3u8.append("#EXTINF:").append(String.format("%.3f", fragment.getDurationSeconds())).append(",\n");
                m3u8.append("/seg-").append(fragment.sequenceNumber).append(".m4s\n");
            }
            
            Log.i(TAG, "📋 Generated M3U8 playlist:");
            Log.i(TAG, "   Total fragments: " + fragments.size());
            Log.i(TAG, "   Stable fragments: " + stable.size());
            Log.i(TAG, "   Sequence range: " + stable.get(0).sequenceNumber + " to " + stable.get(stable.size()-1).sequenceNumber);
            Log.d(TAG, "M3U8 Content:\n" + m3u8.toString());
            
            Response response = newFixedLengthResponse(
                Response.Status.OK,
                "application/vnd.apple.mpegurl",
                m3u8.toString()
            );
            
            // RFC 8216 compliant headers for HLS streaming
            response.addHeader("Content-Type", "application/vnd.apple.mpegurl; charset=utf-8");
            response.addHeader("Content-Disposition", "inline");
            response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            response.addHeader("Pragma", "no-cache");
            response.addHeader("Expires", "0");
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
            
            RemoteStreamManager.FragmentData fragment = streamManager.getFragment(sequenceNumber);
            
            if (fragment == null) {
                Log.w(TAG, "Fragment #" + sequenceNumber + " not found in buffer");
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Fragment not found");
            }
            
            // Only serve fragments that are at least 1 second old (ensures complete write + stability)
            long fragmentAge = System.currentTimeMillis() - fragment.timestamp;
            if (fragmentAge < 1000) {
                Log.d(TAG, "Fragment #" + sequenceNumber + " too fresh (" + fragmentAge + "ms), waiting...");
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Fragment not ready yet");
            }
            
            streamManager.incrementConnections();
            
            try {
                Log.d(TAG, "📦 Serving fragment #" + sequenceNumber + " (" + (fragment.sizeBytes / 1024) + " KB)");
                
                // Serve fragment bytes
                InputStream fragmentStream = new java.io.ByteArrayInputStream(fragment.data);
                Response response = newFixedLengthResponse(Response.Status.OK, "video/mp4", fragmentStream, fragment.sizeBytes);
                response.addHeader("Cache-Control", "public, max-age=3600"); // Cache fragments for 1 hour
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
     * Serve landing page with simple progressive streaming (works everywhere).
     */
    @NonNull
    private Response serveLandingPage() {
        String baseUrl = "http://" + getHostAddress() + ":" + getListeningPort();
        String hlsUrl = baseUrl + "/live.m3u8";
        
        // Professional HLS streaming with hls.js - industry standard
        String html = "<!DOCTYPE html>\n" +
            "<html>\n" +
            "<head>\n" +
            "    <title>FadCam Live</title>\n" +
            "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n" +
            "    <script src=\"https://cdn.jsdelivr.net/npm/hls.js@1.5.15/dist/hls.min.js\"></script>\n" +
            "    <style>\n" +
            "        body { margin: 0; background: #000; font-family: Arial, sans-serif; }\n" +
            "        video { width: 100%; height: auto; max-height: 90vh; display: block; }\n" +
            "        .info { color: white; padding: 20px; text-align: center; background: #1a1a1a; }\n" +
            "        .info h2 { margin: 10px 0; color: #4caf50; }\n" +
            "        .error { color: #f44336 !important; }\n" +
            "        .info p { margin: 10px 0; opacity: 0.9; font-size: 14px; }\n" +
            "        code { background: #333; padding: 5px 10px; border-radius: 3px; color: #4caf50; }\n" +
            "    </style>\n" +
            "</head>\n" +
            "<body>\n" +
            "    <video id=\"v\" controls muted></video>\n" +
            "    <div class=\"info\">\n" +
            "        <h2 id=\"s\">⏳ Loading stream...</h2>\n" +
            "        <p><strong>HLS Stream:</strong> <code>" + hlsUrl + "</code></p>\n" +
            "        <p>Or use VLC: <code>" + hlsUrl + "</code></p>\n" +
            "    </div>\n" +
            "    <script>\n" +
            "        var v = document.getElementById('v');\n" +
            "        var s = document.getElementById('s');\n" +
            "        var hlsUrl = '" + hlsUrl + "';\n" +
            "        \n" +
            "        console.log('🎬 FadCam Live Stream Initializing...');\n" +
            "        console.log('📡 Stream URL:', hlsUrl);\n" +
            "        console.log('✅ hls.js Supported:', Hls.isSupported());\n" +
            "        console.log('📊 hls.js Version:', Hls.version);\n" +
            "        \n" +
            "        if (Hls.isSupported()) {\n" +
            "            var hls = new Hls({\n" +
            "                debug: true,  // CRITICAL: Enable detailed debugging\n" +
            "                enableWorker: true,\n" +
            "                lowLatencyMode: true,\n" +
            "                backBufferLength: 90,\n" +
            "                maxBufferLength: 30,\n" +
            "                maxMaxBufferLength: 60,\n" +
            "                liveSyncDurationCount: 3,\n" +
            "                liveMaxLatencyDurationCount: 10\n" +
            "            });\n" +
            "            \n" +
            "            console.log('✅ Hls instance created with config:', hls.config);\n" +
            "            \n" +
            "            // Log ALL hls.js events for debugging\n" +
            "            Object.keys(Hls.Events).forEach(function(eventName) {\n" +
            "                hls.on(Hls.Events[eventName], function(event, data) {\n" +
            "                    if (eventName !== 'FRAG_LOADING' && eventName !== 'FRAG_LOADED') {\n" +
            "                        console.log('🎯 [' + eventName + ']', data);\n" +
            "                    }\n" +
            "                });\n" +
            "            });\n" +
            "            \n" +
            "            hls.loadSource(hlsUrl);\n" +
            "            console.log('⏳ Loading source:', hlsUrl);\n" +
            "            \n" +
            "            hls.attachMedia(v);\n" +
            "            console.log('🔗 Media attached to video element');\n" +
            "            \n" +
            "            hls.on(Hls.Events.MANIFEST_PARSED, function(event, data) {\n" +
            "                console.log('✅ MANIFEST_PARSED:', data);\n" +
            "                console.log('📺 Levels:', data.levels);\n" +
            "                console.log('🎵 Audio tracks:', data.audioTracks);\n" +
            "                console.log('📝 Video tracks:', data.videoTracks);\n" +
            "                s.textContent = '▶️ Live Stream Ready';\n" +
            "                s.className = '';\n" +
            "                v.play().catch(e => { \n" +
            "                    console.error('❌ Autoplay failed:', e);\n" +
            "                    s.textContent = '⏸️ Click to play'; \n" +
            "                });\n" +
            "            });\n" +
            "            \n" +
            "            hls.on(Hls.Events.FRAG_LOADED, function(event, data) {\n" +
            "                console.log('✅ Fragment loaded #' + data.frag.sn + ' (' + (data.frag.stats.total / 1024).toFixed(0) + ' KB)');\n" +
            "            });\n" +
            "            \n" +
            "            hls.on(Hls.Events.ERROR, function(event, data) {\n" +
            "                console.error('❌ HLS ERROR:', data);\n" +
            "                console.error('   Type:', data.type);\n" +
            "                console.error('   Details:', data.details);\n" +
            "                console.error('   Fatal:', data.fatal);\n" +
            "                console.error('   Response:', data.response);\n" +
            "                console.error('   Reason:', data.reason);\n" +
            "                \n" +
            "                if (data.fatal) {\n" +
            "                    s.textContent = '❌ ' + data.type + ': ' + data.details;\n" +
            "                    s.className = 'error';\n" +
            "                    \n" +
            "                    switch(data.type) {\n" +
            "                        case Hls.ErrorTypes.NETWORK_ERROR:\n" +
            "                            console.log('🔄 Network error, retrying in 3s...');\n" +
            "                            setTimeout(() => { hls.loadSource(hlsUrl); }, 3000);\n" +
            "                            break;\n" +
            "                        case Hls.ErrorTypes.MEDIA_ERROR:\n" +
            "                            console.log('🔄 Media error, attempting recovery...');\n" +
            "                            hls.recoverMediaError();\n" +
            "                            break;\n" +
            "                        default:\n" +
            "                            console.error('💀 Unrecoverable error, destroying hls instance');\n" +
            "                            hls.destroy();\n" +
            "                            break;\n" +
            "                    }\n" +
            "                }\n" +
            "            });\n" +
            "            \n" +
            "            v.addEventListener('playing', () => { s.textContent = '▶️ Live'; s.className = ''; });\n" +
            "            v.addEventListener('waiting', () => { s.textContent = '⏳ Buffering...'; });\n" +
            "        } else if (v.canPlayType('application/vnd.apple.mpegurl')) {\n" +
            "            v.src = hlsUrl;\n" +
            "            v.addEventListener('loadedmetadata', () => {\n" +
            "                s.textContent = '▶️ Live';\n" +
            "                v.play();\n" +
            "            });\n" +
            "        } else {\n" +
            "            s.textContent = '❌ HLS not supported in this browser';\n" +
            "            s.className = 'error';\n" +
            "        }\n" +
            "    </script>\n" +
            "</body>\n" +
            "</html>";
        
        Response response = newFixedLengthResponse(Response.Status.OK, "text/html", html);
        response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.addHeader("Pragma", "no-cache");
        response.addHeader("Expires", "0");
        return response;
    }
    
    /**
     * Detect if User-Agent is a web browser (not a media player like VLC).
     */
    private boolean isBrowserUserAgent(String userAgent) {
        if (userAgent == null) return false;
        String ua = userAgent.toLowerCase();
        // Detect common browsers, but not media players
        return (ua.contains("mozilla") || ua.contains("chrome") || ua.contains("safari") || 
                ua.contains("firefox") || ua.contains("edge") || ua.contains("opera")) &&
               !ua.contains("vlc") && !ua.contains("ffmpeg") && !ua.contains("lavf");
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
