package com.fadcam.ui.faditor.processors.opengl;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;
import android.view.Surface;

import com.fadcam.ui.faditor.models.VideoMetadata;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Handles MediaCodec integration for hardware-accelerated video encoding.
 * Manages surface-to-surface encoding for GPU-processed frames.
 */
public class MediaCodecIntegration {
    
    private static final String TAG = "MediaCodecIntegration";
    
    private static final String VIDEO_MIME_TYPE = "video/avc"; // H.264
    private static final int IFRAME_INTERVAL = 1; // seconds
    private static final int TIMEOUT_USEC = 10000; // 10ms
    
    private MediaCodec encoder;
    private MediaMuxer muxer;
    private Surface inputSurface;
    
    private int videoTrackIndex = -1;
    private boolean muxerStarted = false;
    private boolean encodingFinished = false;
    
    private VideoMetadata inputMetadata;
    private File outputFile;
    
    /**
     * Setup the encoder with the given video metadata and output file
     */
    public void setupEncoder(VideoMetadata metadata, File outputFile) throws IOException {
        this.inputMetadata = metadata;
        this.outputFile = outputFile;
        
        // Create MediaFormat for the encoder
        MediaFormat format = createEncoderFormat(metadata);
        
        // Create encoder
        encoder = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        
        // Get input surface for OpenGL rendering
        inputSurface = encoder.createInputSurface();
        
        // Create muxer
        muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        
        // Start encoder
        encoder.start();
        
        Log.d(TAG, "MediaCodec encoder setup complete");
    }
    
    private MediaFormat createEncoderFormat(VideoMetadata metadata) {
        MediaFormat format = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, metadata.getWidth(), metadata.getHeight());
        
        // Set color format for surface input
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        
        // Set bitrate - use original bitrate or calculate based on resolution
        int bitrate = metadata.getBitrate();
        if (bitrate <= 0) {
            // Calculate bitrate based on resolution (rough estimate)
            int pixels = metadata.getWidth() * metadata.getHeight();
            if (pixels >= 3840 * 2160) { // 4K
                bitrate = 20000000; // 20 Mbps
            } else if (pixels >= 1920 * 1080) { // 1080p
                bitrate = 8000000; // 8 Mbps
            } else if (pixels >= 1280 * 720) { // 720p
                bitrate = 5000000; // 5 Mbps
            } else {
                bitrate = 2000000; // 2 Mbps for lower resolutions
            }
        }
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        
        // Set frame rate
        float frameRate = metadata.getFrameRate();
        if (frameRate <= 0) {
            frameRate = 30.0f; // Default to 30 fps
        }
        format.setFloat(MediaFormat.KEY_FRAME_RATE, frameRate);
        
        // Set I-frame interval
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        
        // Set profile and level for better compatibility
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
        format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4);
        
        Log.d(TAG, "Created encoder format: " + format);
        return format;
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
     * Get optimal encoder selection based on device capabilities
     */
    public static String getOptimalEncoderName() {
        // For now, use the default H.264 encoder
        // In the future, this could be enhanced to select the best available encoder
        return VIDEO_MIME_TYPE;
    }
    
    /**
     * Check if hardware encoding is available
     */
    public static boolean isHardwareEncodingAvailable() {
        try {
            MediaCodec encoder = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE);
            MediaCodecInfo codecInfo = encoder.getCodecInfo();
            encoder.release();
            
            // Check if it's a hardware encoder
            return !codecInfo.getName().toLowerCase().contains("sw");
        } catch (IOException e) {
            Log.e(TAG, "Error checking hardware encoding availability", e);
            return false;
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