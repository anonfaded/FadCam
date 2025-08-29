package com.fadcam.ui.faditor.processors.opengl;

import android.util.Log;

import com.fadcam.ui.faditor.models.VideoMetadata;

import java.io.File;

/**
 * Example class demonstrating the enhanced MediaCodecIntegration features.
 * Shows how to use optimal encoder selection, quality preservation, and hardware acceleration.
 */
public class MediaCodecIntegrationExample {
    
    private static final String TAG = "MediaCodecExample";
    
    /**
     * Example of setting up MediaCodec with optimal encoder selection and quality preservation
     */
    public static void demonstrateOptimalEncoderSetup(VideoMetadata inputMetadata, File outputFile) {
        try {
            // Check if hardware encoding is available
            if (!MediaCodecIntegration.isHardwareEncodingAvailable()) {
                Log.w(TAG, "Hardware encoding not available on this device");
                return;
            }
            
            // Get optimal encoder for the video
            String optimalEncoder = MediaCodecIntegration.getOptimalEncoderName(inputMetadata);
            Log.d(TAG, "Optimal encoder selected: " + optimalEncoder);
            
            // Check if specific codecs are supported
            boolean h264Supported = MediaCodecIntegration.isCodecSupportedByHardware("video/avc");
            boolean h265Supported = MediaCodecIntegration.isCodecSupportedByHardware("video/hevc");
            
            Log.d(TAG, "H.264 hardware support: " + h264Supported);
            Log.d(TAG, "H.265 hardware support: " + h265Supported);
            
            // Create and setup MediaCodec integration
            MediaCodecIntegration integration = new MediaCodecIntegration();
            integration.setupEncoder(inputMetadata, outputFile);
            
            // Assess encoding quality
            MediaCodecIntegration.QualityAssessment quality = integration.assessEncodingQuality(inputMetadata);
            Log.d(TAG, "Quality assessment:");
            Log.d(TAG, "  Maintains quality: " + quality.maintainsQuality);
            Log.d(TAG, "  Quality score: " + quality.qualityScore);
            Log.d(TAG, "  Recommendation: " + quality.recommendation);
            
            // Get encoder performance information
            MediaCodecIntegration.EncoderPerformance performance = integration.getEncoderPerformance();
            if (performance != null) {
                Log.d(TAG, "Encoder performance:");
                Log.d(TAG, "  Encoder name: " + performance.encoderName);
                Log.d(TAG, "  Hardware accelerated: " + performance.isHardwareAccelerated);
                Log.d(TAG, "  Supports target resolution: " + performance.supportsTargetResolution);
                Log.d(TAG, "  Max resolution: " + performance.maxSupportedWidth + "x" + performance.maxSupportedHeight);
            }
            
            // Example of encoding workflow
            // Note: In real usage, you would render frames to the input surface
            // and call encodeFrame() for each frame
            
            // Simulate encoding a few frames
            for (int i = 0; i < 10; i++) {
                long presentationTimeUs = i * 33333; // 30fps = 33.333ms per frame
                integration.encodeFrame(presentationTimeUs);
            }
            
            // Finish encoding
            integration.finishEncoding();
            
            // Clean up resources
            integration.release();
            
            Log.d(TAG, "Encoding completed successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "Error in MediaCodec integration example", e);
        }
    }
    
    /**
     * Example of quality-aware encoder selection
     */
    public static void demonstrateQualityAwareSelection(VideoMetadata inputMetadata) {
        Log.d(TAG, "=== Quality-Aware Encoder Selection ===");
        
        // Analyze input video characteristics
        Log.d(TAG, "Input video: " + inputMetadata.getResolutionString() + 
                   " @ " + inputMetadata.getFrameRate() + "fps");
        Log.d(TAG, "Bitrate: " + inputMetadata.getBitrate() + " bps");
        Log.d(TAG, "Codec: " + inputMetadata.getCodec());
        Log.d(TAG, "Is 4K: " + inputMetadata.is4K());
        Log.d(TAG, "Is high resolution: " + inputMetadata.isHighResolution());
        
        // Check format compatibility
        Log.d(TAG, "Is common format: " + inputMetadata.isCommonFormat());
        Log.d(TAG, "Supports lossless trim: " + inputMetadata.isLosslessTrimCompatible());
        
        // Get optimal encoder recommendation
        String recommendedEncoder = MediaCodecIntegration.getOptimalEncoderName(inputMetadata);
        Log.d(TAG, "Recommended encoder: " + recommendedEncoder);
        
        // Provide encoding strategy recommendation
        if (inputMetadata.isLosslessTrimCompatible()) {
            Log.d(TAG, "Strategy: Use lossless stream copying when possible");
        } else {
            Log.d(TAG, "Strategy: Use hardware re-encoding with " + recommendedEncoder);
        }
        
        // Quality preservation recommendations
        if (inputMetadata.is4K()) {
            Log.d(TAG, "Quality tip: 4K video detected - ensure sufficient bitrate for quality preservation");
        } else if (inputMetadata.isHighResolution()) {
            Log.d(TAG, "Quality tip: High resolution video - hardware encoding recommended");
        }
    }
    
    /**
     * Example of device capability assessment
     */
    public static void demonstrateDeviceCapabilityAssessment() {
        Log.d(TAG, "=== Device Capability Assessment ===");
        
        // Check overall hardware encoding availability
        boolean hardwareAvailable = MediaCodecIntegration.isHardwareEncodingAvailable();
        Log.d(TAG, "Hardware encoding available: " + hardwareAvailable);
        
        if (!hardwareAvailable) {
            Log.w(TAG, "Device does not support hardware encoding - performance may be limited");
            return;
        }
        
        // Check specific codec support
        String[] codecs = {"video/avc", "video/hevc", "video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9"};
        for (String codec : codecs) {
            boolean supported = MediaCodecIntegration.isCodecSupportedByHardware(codec);
            Log.d(TAG, codec + " hardware support: " + supported);
        }
        
        // Create test metadata for capability testing
        VideoMetadata testMetadata = new VideoMetadata();
        testMetadata.setWidth(1920);
        testMetadata.setHeight(1080);
        testMetadata.setFrameRate(30.0f);
        testMetadata.setBitrate(8000000);
        testMetadata.setCodec("avc");
        testMetadata.setContainerFormat("mp4");
        
        // Test encoder performance for different resolutions
        int[][] resolutions = {{1280, 720}, {1920, 1080}, {3840, 2160}};
        for (int[] resolution : resolutions) {
            testMetadata.setWidth(resolution[0]);
            testMetadata.setHeight(resolution[1]);
            
            try {
                MediaCodecIntegration integration = new MediaCodecIntegration();
                // Note: We can't actually setup without a real output file
                // This is just for demonstration of the API
                
                String optimalEncoder = MediaCodecIntegration.getOptimalEncoderName(testMetadata);
                Log.d(TAG, resolution[0] + "x" + resolution[1] + " optimal encoder: " + optimalEncoder);
                
            } catch (Exception e) {
                Log.w(TAG, "Error testing " + resolution[0] + "x" + resolution[1] + ": " + e.getMessage());
            }
        }
    }
}