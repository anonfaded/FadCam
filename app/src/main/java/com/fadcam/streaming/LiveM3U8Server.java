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
        
        Log.d(TAG, method + " " + uri);
        
        // Add CORS headers for web player compatibility
        Response response;
        
        if (Method.GET.equals(method)) {
            if ("/live.m3u8".equals(uri)) {
                response = servePlaylist();
            } else if (uri.startsWith("/seg-") && uri.endsWith(".m4s")) {
                response = serveSegment(uri);
            } else if ("/status".equals(uri)) {
                response = serveStatus();
            } else if ("/".equals(uri)) {
                response = serveLandingPage();
            } else {
                response = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404 Not Found");
            }
        } else {
            response = newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method not allowed");
        }
        
        // Add CORS headers
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "Content-Type");
        
        return response;
    }
    
    /**
     * Serve HLS playlist (M3U8).
     * FOR NOW: Serve the recording file directly as a single-file MP4 stream.
     * TODO: Parse fMP4 fragments and create proper HLS with multiple segments.
     */
    @NonNull
    private Response servePlaylist() {
        File recordingFile = streamManager.getActiveRecordingFile();
        
        if (recordingFile == null || !recordingFile.exists()) {
            Log.w(TAG, "No active recording file for streaming");
            return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, 
                "Stream not available. Start recording to begin streaming.");
        }
        
        // Track connection
        streamManager.incrementConnections();
        
        // TEMPORARY: Redirect to direct MP4 file
        // This allows testing while we implement fragment parsing
        try {
            long fileSize = recordingFile.length();
            Log.i(TAG, "Serving recording file directly: " + recordingFile.getName() + " (" + (fileSize / 1024) + " KB)");
            
            java.io.FileInputStream fis = new java.io.FileInputStream(recordingFile);
            Response response = newFixedLengthResponse(Response.Status.OK, "video/mp4", fis, fileSize);
            response.addHeader("Cache-Control", "no-cache");
            response.addHeader("Accept-Ranges", "bytes");
            response.addHeader("Content-Disposition", "inline; filename=\"stream.mp4\"");
            
            return response;
        } catch (Exception e) {
            Log.e(TAG, "Error serving recording file", e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, 
                "Error serving stream: " + e.getMessage());
        } finally {
            streamManager.decrementConnections();
        }
    }
    
    /**
     * Serve individual fMP4 segment.
     * URI format: /seg-{segmentNumber}.m4s
     */
    @NonNull
    private Response serveSegment(String uri) {
        try {
            // Extract segment number from URI: "/seg-123.m4s" -> 123
            String segNumStr = uri.substring(5, uri.length() - 4); // Remove "/seg-" prefix and ".m4s" suffix
            int segmentNumber = Integer.parseInt(segNumStr);
            
            RemoteStreamManager.SegmentData segment = streamManager.getSegment(segmentNumber);
            
            if (segment == null) {
                Log.w(TAG, "Segment " + segmentNumber + " not found in buffer");
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Segment not found");
            }
            
            // Track connection
            streamManager.incrementConnections();
            
            Log.d(TAG, "Serving segment " + segmentNumber + " (" + (segment.size / 1024) + " KB)");
            
            // Serve segment bytes
            InputStream segmentStream = new ByteArrayInputStream(segment.data);
            Response response = newFixedLengthResponse(Response.Status.OK, "video/mp4", segmentStream, segment.size);
            response.addHeader("Cache-Control", "public, max-age=31536000"); // Cache segments for 1 year
            response.addHeader("Accept-Ranges", "bytes");
            
            return response;
            
        } catch (NumberFormatException e) {
            Log.e(TAG, "Invalid segment number in URI: " + uri, e);
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid segment ID");
        } finally {
            // Decrement connection after serving
            streamManager.decrementConnections();
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
     * Serve landing page with instructions.
     */
    @NonNull
    private Response serveLandingPage() {
        String html = "<!DOCTYPE html>\n" +
            "<html>\n" +
            "<head>\n" +
            "    <title>FadCam Stream</title>\n" +
            "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n" +
            "    <style>\n" +
            "        body { font-family: Arial, sans-serif; margin: 20px; background: #f5f5f5; }\n" +
            "        .container { max-width: 600px; margin: 0 auto; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }\n" +
            "        h1 { color: #333; }\n" +
            "        .info { background: #e3f2fd; padding: 15px; border-radius: 4px; margin: 15px 0; }\n" +
            "        code { background: #f5f5f5; padding: 2px 6px; border-radius: 3px; }\n" +
            "        a { color: #1976d2; text-decoration: none; }\n" +
            "        .status { margin-top: 20px; }\n" +
            "    </style>\n" +
            "</head>\n" +
            "<body>\n" +
            "    <div class=\"container\">\n" +
            "        <h1>🎥 FadCam Live Stream</h1>\n" +
            "        <div class=\"info\">\n" +
            "            <strong>Stream URL:</strong><br>\n" +
            "            <code>http://" + getHostAddress() + ":" + getListeningPort() + "/live.m3u8</code>\n" +
            "        </div>\n" +
            "        <p><strong>How to watch:</strong></p>\n" +
            "        <ul>\n" +
            "            <li>VLC: Open Network Stream → Paste URL</li>\n" +
            "            <li>Web: Use HLS.js player</li>\n" +
            "            <li>FFplay: <code>ffplay http://...live.m3u8</code></li>\n" +
            "        </ul>\n" +
            "        <div class=\"status\">\n" +
            "            <a href=\"/status\">View Status JSON</a>\n" +
            "        </div>\n" +
            "    </div>\n" +
            "</body>\n" +
            "</html>";
        
        return newFixedLengthResponse(Response.Status.OK, "text/html", html);
    }
    
    /**
     * Get host address for display (attempts to get local IP).
     */
    private String getHostAddress() {
        try {
            return java.net.InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "localhost";
        }
    }
}
