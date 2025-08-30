package com.fadcam.ui.faditor.processors.opengl;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.net.Uri;
import android.media.MediaMetadataRetriever;
import com.fadcam.Log;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Utility class for validating video formats and detecting hardware codec support.
 * This class provides methods to check format compatibility and hardware acceleration availability.
 */
public class VideoFormatValidator {
    private static final String TAG = "VideoFormatValidator";
    
    // Supported video MIME types for hardware decoding
    public static final String[] SUPPORTED_VIDEO_FORMATS = {
        MediaFormat.MIMETYPE_VIDEO_AVC,    // H.264
        MediaFormat.MIMETYPE_VIDEO_HEVC,   // H.265
        MediaFormat.MIMETYPE_VIDEO_VP9,    // VP9
        MediaFormat.MIMETYPE_VIDEO_VP8,    // VP8 (basic support)
        MediaFormat.MIMETYPE_VIDEO_MPEG4,  // MPEG-4
        MediaFormat.MIMETYPE_VIDEO_H263    // H.263 (legacy support)
    };
    
    // Preferred formats for optimal performance
    public static final String[] PREFERRED_FORMATS = {
        MediaFormat.MIMETYPE_VIDEO_AVC,    // H.264 - most widely supported
        MediaFormat.MIMETYPE_VIDEO_HEVC,   // H.265 - better compression
        MediaFormat.MIMETYPE_VIDEO_VP9     // VP9 - good for web content
    };
    
    // Container formats that support lossless operations
    public static final String[] LOSSLESS_CONTAINERS = {
        "mp4", "mov", "m4v"
    };
    
    /**
     * Validates if a video format is supported for hardware decoding.
     * 
     * @param mimeType The MIME type of the video format
     * @return true if the format is supported, false otherwise
     */
    public static boolean isSupportedFormat(String mimeType) {
        if (mimeType == null || mimeType.isEmpty()) {
            return false;
        }
        
        for (String supportedFormat : SUPPORTED_VIDEO_FORMATS) {
            if (supportedFormat.equalsIgnoreCase(mimeType)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Checks if a video format is preferred for optimal performance.
     * 
     * @param mimeType The MIME type of the video format
     * @return true if the format is preferred, false otherwise
     */
    public static boolean isPreferredFormat(String mimeType) {
        if (mimeType == null || mimeType.isEmpty()) {
            return false;
        }
        
        for (String preferredFormat : PREFERRED_FORMATS) {
            if (preferredFormat.equalsIgnoreCase(mimeType)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Validates a video file and extracts format information.
     * 
     * @param videoUri The URI of the video file to validate
     * @return VideoFormatInfo containing validation results and format details
     */
    public static VideoFormatInfo validateVideoFile(Uri videoUri) {
        VideoFormatInfo formatInfo = new VideoFormatInfo();
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        
        try {
            retriever.setDataSource(videoUri.toString());
            
            // Extract basic format information
            String mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
            String widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String bitrateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            String frameRateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
            
            formatInfo.mimeType = mimeType;
            formatInfo.width = widthStr != null ? Integer.parseInt(widthStr) : 0;
            formatInfo.height = heightStr != null ? Integer.parseInt(heightStr) : 0;
            formatInfo.duration = durationStr != null ? Long.parseLong(durationStr) : 0;
            formatInfo.bitrate = bitrateStr != null ? Integer.parseInt(bitrateStr) : 0;
            formatInfo.frameRate = frameRateStr != null ? Float.parseFloat(frameRateStr) : 30.0f;
            
            // Validate format support
            formatInfo.isSupported = isSupportedFormat(mimeType);
            formatInfo.isPreferred = isPreferredFormat(mimeType);
            formatInfo.hasHardwareDecoder = hasHardwareDecoder(mimeType);
            formatInfo.hasHardwareEncoder = hasHardwareEncoder(mimeType);
            
            // Check container format for lossless operations
            String containerFormat = extractContainerFormat(videoUri.toString());
            formatInfo.containerFormat = containerFormat;
            formatInfo.supportsLosslessOperations = isLosslessContainer(containerFormat);
            
            // Validate resolution and other constraints
            formatInfo.isValidResolution = validateResolution(formatInfo.width, formatInfo.height);
            formatInfo.isValidDuration = formatInfo.duration > 0;
            formatInfo.isValidBitrate = formatInfo.bitrate > 0;
            
            formatInfo.isValid = formatInfo.isSupported && formatInfo.isValidResolution && 
                               formatInfo.isValidDuration && formatInfo.width > 0 && formatInfo.height > 0;
            
            Log.d(TAG, "Video validation completed: " + formatInfo.toString());
            
        } catch (Exception e) {
            Log.e(TAG, "Error validating video file: " + e.getMessage());
            formatInfo.isValid = false;
            formatInfo.errorMessage = e.getMessage();
        } finally {
            try {
                retriever.release();
            } catch (Exception e) {
                Log.w(TAG, "Error releasing metadata retriever: " + e.getMessage());
            }
        }
        
        return formatInfo;
    }
    
    /**
     * Checks if hardware decoder is available for the specified MIME type.
     * 
     * @param mimeType The MIME type to check
     * @return true if hardware decoder is available, false otherwise
     */
    public static boolean hasHardwareDecoder(String mimeType) {
        if (mimeType == null || mimeType.isEmpty()) {
            return false;
        }
        
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        MediaCodecInfo[] codecInfos = codecList.getCodecInfos();
        
        for (MediaCodecInfo codecInfo : codecInfos) {
            if (!codecInfo.isEncoder()) {
                String[] supportedTypes = codecInfo.getSupportedTypes();
                for (String supportedType : supportedTypes) {
                    if (supportedType.equalsIgnoreCase(mimeType)) {
                        // Check if it's a hardware codec (not software)
                        return !codecInfo.getName().toLowerCase().contains("sw") &&
                               !codecInfo.getName().toLowerCase().contains("software");
                    }
                }
            }
        }
        
        return false;
    }
    
    /**
     * Checks if hardware encoder is available for the specified MIME type.
     * 
     * @param mimeType The MIME type to check
     * @return true if hardware encoder is available, false otherwise
     */
    public static boolean hasHardwareEncoder(String mimeType) {
        if (mimeType == null || mimeType.isEmpty()) {
            return false;
        }
        
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        MediaCodecInfo[] codecInfos = codecList.getCodecInfos();
        
        for (MediaCodecInfo codecInfo : codecInfos) {
            if (codecInfo.isEncoder()) {
                String[] supportedTypes = codecInfo.getSupportedTypes();
                for (String supportedType : supportedTypes) {
                    if (supportedType.equalsIgnoreCase(mimeType)) {
                        // Check if it's a hardware codec (not software)
                        return !codecInfo.getName().toLowerCase().contains("sw") &&
                               !codecInfo.getName().toLowerCase().contains("software");
                    }
                }
            }
        }
        
        return false;
    }
    
    /**
     * Gets a list of all available hardware decoders for video formats.
     * 
     * @return List of codec names that support hardware video decoding
     */
    public static List<String> getAvailableHardwareDecoders() {
        List<String> hardwareDecoders = new ArrayList<>();
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        MediaCodecInfo[] codecInfos = codecList.getCodecInfos();
        
        for (MediaCodecInfo codecInfo : codecInfos) {
            if (!codecInfo.isEncoder()) {
                String[] supportedTypes = codecInfo.getSupportedTypes();
                for (String supportedType : supportedTypes) {
                    if (supportedType.startsWith("video/") && 
                        !codecInfo.getName().toLowerCase().contains("sw") &&
                        !codecInfo.getName().toLowerCase().contains("software")) {
                        hardwareDecoders.add(codecInfo.getName() + " (" + supportedType + ")");
                    }
                }
            }
        }
        
        return hardwareDecoders;
    }
    
    /**
     * Validates video resolution constraints.
     * 
     * @param width Video width in pixels
     * @param height Video height in pixels
     * @return true if resolution is valid, false otherwise
     */
    private static boolean validateResolution(int width, int height) {
        // Check minimum resolution (at least 64x64)
        if (width < 64 || height < 64) {
            return false;
        }
        
        // Check maximum resolution (8K limit)
        if (width > 7680 || height > 4320) {
            return false;
        }
        
        // Check aspect ratio (should be reasonable)
        float aspectRatio = (float) width / height;
        return aspectRatio >= 0.1f && aspectRatio <= 10.0f;
    }
    
    /**
     * Extracts container format from file path or URI.
     * 
     * @param path The file path or URI string
     * @return The container format (e.g., "mp4", "mov", "avi")
     */
    private static String extractContainerFormat(String path) {
        if (path == null || path.isEmpty()) {
            return "unknown";
        }
        
        int lastDotIndex = path.lastIndexOf('.');
        if (lastDotIndex > 0 && lastDotIndex < path.length() - 1) {
            return path.substring(lastDotIndex + 1).toLowerCase();
        }
        
        return "unknown";
    }
    
    /**
     * Checks if a container format supports lossless operations.
     * 
     * @param containerFormat The container format to check
     * @return true if lossless operations are supported, false otherwise
     */
    private static boolean isLosslessContainer(String containerFormat) {
        if (containerFormat == null || containerFormat.isEmpty()) {
            return false;
        }
        
        for (String losslessContainer : LOSSLESS_CONTAINERS) {
            if (losslessContainer.equalsIgnoreCase(containerFormat)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Data class containing video format validation results.
     */
    public static class VideoFormatInfo {
        public String mimeType;
        public String containerFormat;
        public int width;
        public int height;
        public long duration;
        public int bitrate;
        public float frameRate;
        
        public boolean isValid;
        public boolean isSupported;
        public boolean isPreferred;
        public boolean hasHardwareDecoder;
        public boolean hasHardwareEncoder;
        public boolean supportsLosslessOperations;
        public boolean isValidResolution;
        public boolean isValidDuration;
        public boolean isValidBitrate;
        
        public String errorMessage;
        
        public String getResolutionString() {
            return width + "x" + height;
        }
        
        public String getDurationString() {
            long seconds = duration / 1000;
            long minutes = seconds / 60;
            seconds = seconds % 60;
            long hours = minutes / 60;
            minutes = minutes % 60;
            
            if (hours > 0) {
                return String.format("%d:%02d:%02d", hours, minutes, seconds);
            } else {
                return String.format("%d:%02d", minutes, seconds);
            }
        }
        
        public boolean isHighResolution() {
            return height >= 1080;
        }
        
        public boolean is4K() {
            return height >= 2160;
        }
        
        @Override
        public String toString() {
            return "VideoFormatInfo{" +
                    "mimeType='" + mimeType + '\'' +
                    ", container='" + containerFormat + '\'' +
                    ", resolution=" + getResolutionString() +
                    ", duration=" + getDurationString() +
                    ", bitrate=" + bitrate +
                    ", frameRate=" + frameRate +
                    ", isValid=" + isValid +
                    ", isSupported=" + isSupported +
                    ", hasHardwareDecoder=" + hasHardwareDecoder +
                    ", supportsLossless=" + supportsLosslessOperations +
                    (errorMessage != null ? ", error='" + errorMessage + '\'' : "") +
                    '}';
        }
    }
}