package com.fadcam.ui.faditor.processors.opengl;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import com.fadcam.ui.faditor.models.VideoMetadata;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles MediaCodec integration for hardware-accelerated video encoding.
 * Manages surface-to-surface encoding for GPU-processed frames with optimal
 * encoder selection and quality preservation.
 */
public class MediaCodecIntegration {
    
    private static final String TAG = "MediaCodecIntegration";
    
    // Supported video codecs in order of preference
    private static final String[] PREFERRED_CODECS = {
        "video/hevc",  // H.265 - better compression
        "video/avc"    // H.264 - wider compatibility
    };
    
    private static final int IFRAME_INTERVAL = 1; // seconds
    private static final int TIMEOUT_USEC = 10000; // 10ms
    
    // Quality preservation constants
    private static final float QUALITY_PRESERVATION_FACTOR = 1.1f; // 10% bitrate boost for quality
    private static final int MIN_BITRATE = 1000000; // 1 Mbps minimum
    private static final int MAX_BITRATE_4K = 50000000; // 50 Mbps for 4K
    private static final int MAX_BITRATE_1080P = 20000000; // 20 Mbps for 1080p
    private static final int MAX_BITRATE_720P = 10000000; // 10 Mbps for 720p
    
    private MediaCodec encoder;
    private MediaMuxer muxer;
    private Surface inputSurface;
    
    private int videoTrackIndex = -1;
    private boolean muxerStarted = false;
    private boolean encodingFinished = false;
    
    private VideoMetadata inputMetadata;
    private File outputFile;
    private String selectedCodec;
    private EncoderCapabilities encoderCapabilities;
    
    /**
     * Setup the encoder with the given video metadata and output file.
     * Automatically selects optimal encoder and preserves quality.
     */
    public void setupEncoder(VideoMetadata metadata, File outputFile) throws IOException {
        this.inputMetadata = metadata;
        this.outputFile = outputFile;
        
        // Select optimal encoder based on device capabilities
        selectedCodec = selectOptimalEncoder(metadata);
        encoderCapabilities = getEncoderCapabilities(selectedCodec);
        
        Log.d(TAG, "Selected encoder: " + selectedCodec + " with capabilities: " + encoderCapabilities);
        
        // Create MediaFormat for the encoder with quality preservation
        MediaFormat format = createEncoderFormat(metadata, selectedCodec);
        
        // Create encoder
        encoder = MediaCodec.createEncoderByType(selectedCodec);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        
        // Get input surface for OpenGL rendering
        inputSurface = encoder.createInputSurface();
        
        // Create muxer
        muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        
        // Start encoder
        encoder.start();
        
        Log.d(TAG, "MediaCodec encoder setup complete with " + selectedCodec);
    }
    
    private MediaFormat createEncoderFormat(VideoMetadata metadata, String codecMimeType) {
        MediaFormat format = MediaFormat.createVideoFormat(codecMimeType, metadata.getWidth(), metadata.getHeight());
        
        // Set color format for surface input
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        
        // Calculate optimal bitrate with quality preservation
        int bitrate = calculateOptimalBitrate(metadata);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        
        // Set frame rate with validation
        float frameRate = metadata.getFrameRate();
        if (frameRate <= 0 || frameRate > 120) {
            frameRate = 30.0f; // Default to 30 fps
        }
        format.setFloat(MediaFormat.KEY_FRAME_RATE, frameRate);
        
        // Set I-frame interval
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        
        // Set codec-specific profile and level
        setCodecProfileAndLevel(format, codecMimeType, metadata);
        
        // Enable hardware acceleration features if available
        if (encoderCapabilities != null) {
            if (encoderCapabilities.supportsLowLatency) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            }
            if (encoderCapabilities.supportsBFrames && codecMimeType.equals("video/hevc")) {
                // Enable B-frames for HEVC for better compression
                format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 2);
            }
        }
        
        Log.d(TAG, "Created encoder format: " + format + " with bitrate: " + bitrate);
        return format;
    }
    
    /**
     * Calculate optimal bitrate with quality preservation
     */
    private int calculateOptimalBitrate(VideoMetadata metadata) {
        int originalBitrate = metadata.getBitrate();
        int pixels = metadata.getWidth() * metadata.getHeight();
        
        // Calculate base bitrate based on resolution if original is not available
        int baseBitrate;
        if (originalBitrate > 0) {
            // Use original bitrate with quality preservation factor
            baseBitrate = (int) (originalBitrate * QUALITY_PRESERVATION_FACTOR);
        } else {
            // Calculate based on resolution and frame rate
            float frameRate = metadata.getFrameRate() > 0 ? metadata.getFrameRate() : 30.0f;
            float frameRateFactor = frameRate / 30.0f; // Normalize to 30fps
            
            if (pixels >= 3840 * 2160) { // 4K
                baseBitrate = (int) (25000000 * frameRateFactor);
            } else if (pixels >= 1920 * 1080) { // 1080p
                baseBitrate = (int) (10000000 * frameRateFactor);
            } else if (pixels >= 1280 * 720) { // 720p
                baseBitrate = (int) (6000000 * frameRateFactor);
            } else {
                baseBitrate = (int) (3000000 * frameRateFactor);
            }
        }
        
        // Apply resolution-based limits
        int maxBitrate;
        if (pixels >= 3840 * 2160) {
            maxBitrate = MAX_BITRATE_4K;
        } else if (pixels >= 1920 * 1080) {
            maxBitrate = MAX_BITRATE_1080P;
        } else {
            maxBitrate = MAX_BITRATE_720P;
        }
        
        // Ensure bitrate is within reasonable bounds
        return Math.max(MIN_BITRATE, Math.min(baseBitrate, maxBitrate));
    }
    
    /**
     * Set codec-specific profile and level for optimal quality
     */
    private void setCodecProfileAndLevel(MediaFormat format, String codecMimeType, VideoMetadata metadata) {
        if (codecMimeType.equals("video/avc")) {
            // H.264 profiles and levels
            if (metadata.is4K()) {
                format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
                format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel51);
            } else if (metadata.isHighResolution()) {
                format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
                format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4);
            } else {
                format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain);
                format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31);
            }
        } else if (codecMimeType.equals("video/hevc")) {
            // H.265 profiles and levels
            if (metadata.is4K()) {
                format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain);
                format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel51);
            } else {
                format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain);
                format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4);
            }
        }
    }
    
    /**
     * Get the input surface for OpenGL rendering
     */
    public Surface getInputSurface() {
        return inputSurface;
    }
    
    /**
     * Encode a frame with the given presentation time
     */
    public void encodeFrame(long presentationTimeUs) {
        if (encoder == null || encodingFinished) {
            return;
        }
        
        // Drain encoder output
        drainEncoder(false);
    }
    
    /**
     * Signal end of input and finish encoding
     */
    public void finishEncoding() {
        if (encoder == null || encodingFinished) {
            return;
        }
        
        Log.d(TAG, "Finishing encoding");
        
        // Signal end of input stream
        encoder.signalEndOfInputStream();
        
        // Drain remaining output
        drainEncoder(true);
        
        encodingFinished = true;
        
        // Stop muxer if it was started
        if (muxerStarted) {
            try {
                muxer.stop();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping muxer", e);
            }
        }
        
        Log.d(TAG, "Encoding finished");
    }
    
    private void drainEncoder(boolean endOfStream) {
        if (encoder == null) {
            return;
        }
        
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        
        while (true) {
            int outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);
            
            if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // No output available yet
                if (!endOfStream) {
                    break; // Out of while loop
                } else {
                    Log.d(TAG, "No output available, spinning to await EOS");
                }
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // Should happen before receiving buffers, and should only happen once
                if (muxerStarted) {
                    throw new RuntimeException("Format changed twice");
                }
                
                MediaFormat newFormat = encoder.getOutputFormat();
                Log.d(TAG, "Encoder output format changed: " + newFormat);
                
                // Add track to muxer
                videoTrackIndex = muxer.addTrack(newFormat);
                muxer.start();
                muxerStarted = true;
            } else if (outputBufferIndex < 0) {
                Log.w(TAG, "Unexpected result from encoder.dequeueOutputBuffer: " + outputBufferIndex);
            } else {
                ByteBuffer encodedData = encoder.getOutputBuffer(outputBufferIndex);
                if (encodedData == null) {
                    throw new RuntimeException("Encoder output buffer " + outputBufferIndex + " was null");
                }
                
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // The codec config data was pulled out and fed to the muxer when we got
                    // the INFO_OUTPUT_FORMAT_CHANGED status. Ignore it.
                    Log.d(TAG, "Ignoring BUFFER_FLAG_CODEC_CONFIG");
                    bufferInfo.size = 0;
                }
                
                if (bufferInfo.size != 0) {
                    if (!muxerStarted) {
                        throw new RuntimeException("Muxer hasn't started");
                    }
                    
                    // Adjust the ByteBuffer values to match BufferInfo
                    encodedData.position(bufferInfo.offset);
                    encodedData.limit(bufferInfo.offset + bufferInfo.size);
                    
                    // Write to muxer
                    muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo);
                    Log.v(TAG, "Sent " + bufferInfo.size + " bytes to muxer, ts=" + bufferInfo.presentationTimeUs);
                }
                
                encoder.releaseOutputBuffer(outputBufferIndex, false);
                
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (!endOfStream) {
                        Log.w(TAG, "Reached end of stream unexpectedly");
                    } else {
                        Log.d(TAG, "End of stream reached");
                    }
                    break; // Out of while loop
                }
            }
        }
    }
    
    /**
     * Select optimal encoder based on device capabilities and video metadata
     */
    private String selectOptimalEncoder(VideoMetadata metadata) {
        List<EncoderInfo> availableEncoders = getAvailableHardwareEncoders();
        
        // Filter encoders based on video requirements
        for (String preferredCodec : PREFERRED_CODECS) {
            for (EncoderInfo encoderInfo : availableEncoders) {
                if (encoderInfo.mimeType.equals(preferredCodec) && 
                    encoderInfo.supportsResolution(metadata.getWidth(), metadata.getHeight())) {
                    
                    Log.d(TAG, "Selected optimal encoder: " + encoderInfo.name + " for " + preferredCodec);
                    return preferredCodec;
                }
            }
        }
        
        // Fallback to H.264 if available
        for (EncoderInfo encoderInfo : availableEncoders) {
            if (encoderInfo.mimeType.equals("video/avc")) {
                Log.d(TAG, "Fallback to H.264 encoder: " + encoderInfo.name);
                return "video/avc";
            }
        }
        
        throw new RuntimeException("No suitable hardware encoder found");
    }
    
    /**
     * Get available hardware encoders on the device
     */
    private List<EncoderInfo> getAvailableHardwareEncoders() {
        List<EncoderInfo> encoders = new ArrayList<>();
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        
        for (MediaCodecInfo codecInfo : codecList.getCodecInfos()) {
            if (!codecInfo.isEncoder()) {
                continue;
            }
            
            // Skip software encoders
            if (codecInfo.getName().toLowerCase().contains("sw") ||
                codecInfo.getName().toLowerCase().contains("google")) {
                continue;
            }
            
            for (String type : codecInfo.getSupportedTypes()) {
                if (type.startsWith("video/")) {
                    try {
                        MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(type);
                        encoders.add(new EncoderInfo(codecInfo.getName(), type, capabilities));
                    } catch (Exception e) {
                        Log.w(TAG, "Error getting capabilities for " + codecInfo.getName() + " " + type, e);
                    }
                }
            }
        }
        
        Log.d(TAG, "Found " + encoders.size() + " hardware encoders");
        return encoders;
    }
    
    /**
     * Get encoder capabilities for the selected codec
     */
    private EncoderCapabilities getEncoderCapabilities(String codecMimeType) {
        try {
            MediaCodec encoder = MediaCodec.createEncoderByType(codecMimeType);
            MediaCodecInfo codecInfo = encoder.getCodecInfo();
            MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(codecMimeType);
            encoder.release();
            
            return new EncoderCapabilities(codecInfo, capabilities);
        } catch (IOException e) {
            Log.e(TAG, "Error getting encoder capabilities", e);
            return null;
        }
    }
    
    /**
     * Get optimal encoder name for external use
     */
    public static String getOptimalEncoderName(VideoMetadata metadata) {
        try {
            MediaCodecIntegration integration = new MediaCodecIntegration();
            return integration.selectOptimalEncoder(metadata);
        } catch (Exception e) {
            Log.e(TAG, "Error selecting optimal encoder, falling back to H.264", e);
            return "video/avc";
        }
    }
    
    /**
     * Check if hardware encoding is available for any supported codec
     */
    public static boolean isHardwareEncodingAvailable() {
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        
        for (MediaCodecInfo codecInfo : codecList.getCodecInfos()) {
            if (!codecInfo.isEncoder()) {
                continue;
            }
            
            // Skip software encoders
            if (codecInfo.getName().toLowerCase().contains("sw") ||
                codecInfo.getName().toLowerCase().contains("google")) {
                continue;
            }
            
            for (String type : codecInfo.getSupportedTypes()) {
                for (String supportedCodec : PREFERRED_CODECS) {
                    if (type.equals(supportedCodec)) {
                        Log.d(TAG, "Hardware encoding available: " + codecInfo.getName() + " supports " + type);
                        return true;
                    }
                }
            }
        }
        
        Log.w(TAG, "No hardware encoding available");
        return false;
    }
    
    /**
     * Check if a specific codec is supported by hardware
     */
    public static boolean isCodecSupportedByHardware(String codecMimeType) {
        try {
            MediaCodec encoder = MediaCodec.createEncoderByType(codecMimeType);
            MediaCodecInfo codecInfo = encoder.getCodecInfo();
            encoder.release();
            
            return !codecInfo.getName().toLowerCase().contains("sw") &&
                   !codecInfo.getName().toLowerCase().contains("google");
        } catch (IOException e) {
            return false;
        }
    }
    
    /**
     * Get encoding quality assessment based on original and target metadata
     */
    public static class QualityAssessment {
        public final boolean maintainsQuality;
        public final float qualityScore; // 0.0 to 1.0
        public final String recommendation;
        
        QualityAssessment(boolean maintainsQuality, float qualityScore, String recommendation) {
            this.maintainsQuality = maintainsQuality;
            this.qualityScore = qualityScore;
            this.recommendation = recommendation;
        }
    }
    
    /**
     * Assess encoding quality preservation
     */
    public QualityAssessment assessEncodingQuality(VideoMetadata originalMetadata) {
        if (selectedCodec == null || inputMetadata == null) {
            return new QualityAssessment(false, 0.0f, "Encoder not initialized");
        }
        
        float qualityScore = 1.0f;
        StringBuilder recommendation = new StringBuilder();
        
        // Check codec efficiency
        if (selectedCodec.equals("video/hevc") && originalMetadata.getCodec().contains("avc")) {
            qualityScore += 0.1f; // HEVC is more efficient
            recommendation.append("Upgraded to HEVC for better compression. ");
        } else if (selectedCodec.equals("video/avc") && originalMetadata.getCodec().contains("hevc")) {
            qualityScore -= 0.1f; // Downgrade from HEVC
            recommendation.append("Downgraded from HEVC due to compatibility. ");
        }
        
        // Check bitrate preservation
        int originalBitrate = originalMetadata.getBitrate();
        int targetBitrate = calculateOptimalBitrate(originalMetadata);
        
        if (originalBitrate > 0) {
            float bitrateRatio = (float) targetBitrate / originalBitrate;
            if (bitrateRatio >= QUALITY_PRESERVATION_FACTOR) {
                recommendation.append("Bitrate increased for quality preservation. ");
            } else if (bitrateRatio < 0.8f) {
                qualityScore -= 0.2f;
                recommendation.append("Bitrate reduced - some quality loss expected. ");
            }
        }
        
        // Check hardware acceleration
        if (encoderCapabilities != null && encoderCapabilities.isHardware) {
            recommendation.append("Using hardware acceleration for optimal performance. ");
        } else {
            qualityScore -= 0.3f;
            recommendation.append("Software encoding may impact quality and performance. ");
        }
        
        qualityScore = Math.max(0.0f, Math.min(1.0f, qualityScore));
        boolean maintainsQuality = qualityScore >= 0.8f;
        
        return new QualityAssessment(maintainsQuality, qualityScore, recommendation.toString().trim());
    }
    
    /**
     * Get encoder performance metrics
     */
    public static class EncoderPerformance {
        public final String encoderName;
        public final boolean isHardwareAccelerated;
        public final boolean supportsTargetResolution;
        public final int maxSupportedWidth;
        public final int maxSupportedHeight;
        public final String[] supportedProfiles;
        
        EncoderPerformance(String encoderName, boolean isHardwareAccelerated, 
                          boolean supportsTargetResolution, int maxWidth, int maxHeight,
                          String[] supportedProfiles) {
            this.encoderName = encoderName;
            this.isHardwareAccelerated = isHardwareAccelerated;
            this.supportsTargetResolution = supportsTargetResolution;
            this.maxSupportedWidth = maxWidth;
            this.maxSupportedHeight = maxHeight;
            this.supportedProfiles = supportedProfiles;
        }
    }
    
    /**
     * Get performance information for the current encoder
     */
    public EncoderPerformance getEncoderPerformance() {
        if (encoderCapabilities == null || selectedCodec == null) {
            return null;
        }
        
        try {
            MediaCodec encoder = MediaCodec.createEncoderByType(selectedCodec);
            MediaCodecInfo codecInfo = encoder.getCodecInfo();
            MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(selectedCodec);
            
            boolean supportsTarget = true;
            int maxWidth = 0;
            int maxHeight = 0;
            
            if (capabilities.getVideoCapabilities() != null) {
                MediaCodecInfo.VideoCapabilities videoCaps = capabilities.getVideoCapabilities();
                supportsTarget = videoCaps.isSizeSupported(inputMetadata.getWidth(), inputMetadata.getHeight());
                maxWidth = videoCaps.getSupportedWidths().getUpper();
                maxHeight = videoCaps.getSupportedHeights().getUpper();
            }
            
            // Get supported profiles (simplified)
            String[] profiles = {"Main", "High"}; // Default profiles
            
            encoder.release();
            
            return new EncoderPerformance(
                codecInfo.getName(),
                encoderCapabilities.isHardware,
                supportsTarget,
                maxWidth,
                maxHeight,
                profiles
            );
        } catch (IOException e) {
            Log.e(TAG, "Error getting encoder performance", e);
            return null;
        }
    }
    
    /**
     * Helper class to store encoder information
     */
    private static class EncoderInfo {
        final String name;
        final String mimeType;
        final MediaCodecInfo.CodecCapabilities capabilities;
        
        EncoderInfo(String name, String mimeType, MediaCodecInfo.CodecCapabilities capabilities) {
            this.name = name;
            this.mimeType = mimeType;
            this.capabilities = capabilities;
        }
        
        boolean supportsResolution(int width, int height) {
            if (capabilities.getVideoCapabilities() == null) {
                return false;
            }
            
            try {
                return capabilities.getVideoCapabilities().isSizeSupported(width, height);
            } catch (Exception e) {
                Log.w(TAG, "Error checking resolution support for " + name, e);
                return false;
            }
        }
    }
    
    /**
     * Helper class to store encoder capabilities
     */
    private static class EncoderCapabilities {
        final boolean isHardware;
        final boolean supportsLowLatency;
        final boolean supportsBFrames;
        final String encoderName;
        
        EncoderCapabilities(MediaCodecInfo codecInfo, MediaCodecInfo.CodecCapabilities capabilities) {
            this.encoderName = codecInfo.getName();
            this.isHardware = !encoderName.toLowerCase().contains("sw") && 
                             !encoderName.toLowerCase().contains("google");
            
            // Check for low latency support (API 23+)
            boolean lowLatencySupport = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    lowLatencySupport = capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency);
                } catch (Exception e) {
                    // Feature not supported or error checking
                }
            }
            this.supportsLowLatency = lowLatencySupport;
            
            // Check for B-frame support (heuristic based on encoder name)
            this.supportsBFrames = encoderName.toLowerCase().contains("qcom") || 
                                  encoderName.toLowerCase().contains("exynos") ||
                                  encoderName.toLowerCase().contains("mtk");
        }
        
        @Override
        public String toString() {
            return "EncoderCapabilities{" +
                    "name='" + encoderName + '\'' +
                    ", isHardware=" + isHardware +
                    ", supportsLowLatency=" + supportsLowLatency +
                    ", supportsBFrames=" + supportsBFrames +
                    '}';
        }
    }
    
    /**
     * Release all resources
     */
    public void release() {
        Log.d(TAG, "Releasing MediaCodecIntegration resources");
        
        if (encoder != null) {
            try {
                if (!encodingFinished) {
                    encoder.signalEndOfInputStream();
                    drainEncoder(true);
                }
                encoder.stop();
                encoder.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing encoder", e);
            }
            encoder = null;
        }
        
        if (muxer != null) {
            try {
                if (muxerStarted) {
                    muxer.stop();
                }
                muxer.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing muxer", e);
            }
            muxer = null;
        }
        
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
        
        muxerStarted = false;
        encodingFinished = false;
        videoTrackIndex = -1;
    }
}