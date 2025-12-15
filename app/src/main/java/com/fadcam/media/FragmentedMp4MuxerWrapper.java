package com.fadcam.media;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.container.Mp4OrientationData;
import androidx.media3.common.util.Consumer;
import androidx.media3.muxer.FragmentedMp4Muxer;
import androidx.media3.muxer.MuxerException;
import androidx.media3.muxer.ProcessedSegment;

import com.fadcam.streaming.RemoteStreamManager;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Wrapper around Media3's FragmentedMp4Muxer that provides a MediaMuxer-compatible API.
 * Fragmented MP4 files are crash-safe because they write data incrementally with moov fragments,
 * unlike regular MP4 which writes the moov atom at the end.
 */
@OptIn(markerClass = UnstableApi.class)
public class FragmentedMp4MuxerWrapper {

    private static final String TAG = "FragmentedMp4MuxerWrap";

    private final FragmentedMp4Muxer muxer;
    private final FileOutputStream fileOutputStream;
    private final Object muxerLock = new Object();
    private boolean started = false;
    private boolean released = false;
    private int orientationHint = 0;
    private int sampleCountDebug = 0;  // For limiting debug log spam
    
    // Track indices
    private int videoTrackIndex = -1;
    private int audioTrackIndex = -1;
    
    // Track count for timestamp offset initialization
    private int trackCount = 0;
    
    // Fragment tracking for live streaming
    // CRITICAL VLC FIX: Start from 0, not 1 (VLC expects 0-based fragment sequence numbers)
    private int nextFragmentNumber = 0;
    private boolean initSegmentSent = false;
    
    // CRITICAL FIX: Timestamp normalization to prevent serving 45+ minute old video
    // MediaCodec timestamps accumulate across recording sessions (based on system uptime)
    // We must normalize them to start from 0 for each new recording
    private final android.util.SparseArray<Long> timestampOffsets = new android.util.SparseArray<>();
    private boolean timestampOffsetsInitialized = false;
    // Track how many samples logged per track to avoid log spam
    private final android.util.SparseArray<Integer> trackSampleLogs = new android.util.SparseArray<>();

    /**
     * Creates a FragmentedMp4MuxerWrapper with a file path.
     *
     * @param path The path to the output file.
     * @throws IOException If the file cannot be created.
     */
    public FragmentedMp4MuxerWrapper(@NonNull String path) throws IOException {
        this.fileOutputStream = new FileOutputStream(path);
        
        // Create callback consumer for live streaming integration
        Consumer<ProcessedSegment> segmentConsumer = segment -> {
            handleProcessedSegment(segment);
        };
        
        // CRITICAL FIX for fMP4 seeking (GitHub issue #6704):
        // 1. Use 1000ms (1 second) fragments for low-latency streaming
        // 2. Fragments will be automatically keyframe-aligned by Media3
        // 3. This ensures proper tfdt timestamps and fragment boundaries
        // 4. NEW: Callback-based architecture for real-time streaming
        this.muxer = new FragmentedMp4Muxer.Builder(segmentConsumer)
                .setFragmentDurationMs(1000) // 1 second per fragment for low-latency streaming
                .build();
        // Log.d(TAG, "Created FragmentedMp4Muxer with 1s fragments and live streaming callback for path: " + path);
    }

    /**
     * Creates a FragmentedMp4MuxerWrapper with a FileDescriptor.
     *
     * @param fd The FileDescriptor for the output file.
     * @throws IOException If the output stream cannot be created.
     */
    public FragmentedMp4MuxerWrapper(@NonNull FileDescriptor fd) throws IOException {
        this.fileOutputStream = new FileOutputStream(fd);
        
        // Create callback consumer for live streaming integration
        Consumer<ProcessedSegment> segmentConsumer = segment -> {
            handleProcessedSegment(segment);
        };
        
        // CRITICAL FIX for fMP4 seeking (GitHub issue #6704):
        // Use 1000ms (1 second) fragments for low-latency streaming
        // NEW: Callback-based architecture for real-time streaming
        this.muxer = new FragmentedMp4Muxer.Builder(segmentConsumer)
                .setFragmentDurationMs(1000) // 1 second per fragment for low-latency streaming
                .build();
        // Log.d(TAG, "Created FragmentedMp4Muxer with 1s fragments and live streaming callback for file descriptor");
    }

    /**
     * Adds a track to the muxer.
     *
     * @param format The MediaFormat describing the track.
     * @return The track index.
     * @throws IllegalStateException If the muxer has already been started.
     */
    public int addTrack(@NonNull MediaFormat format) {
        synchronized (muxerLock) {
            if (started) {
                throw new IllegalStateException("Cannot add track after muxer has started");
            }
            if (released) {
                throw new IllegalStateException("Muxer has been released");
            }

            // If this is a video track and we have an orientation hint, add it to the format
            String mimeType = format.getString(MediaFormat.KEY_MIME);
            boolean isVideo = mimeType != null && mimeType.startsWith("video/");
            if (isVideo && orientationHint != 0) {
                format.setInteger(MediaFormat.KEY_ROTATION, orientationHint);
            }

            Format media3Format = convertToMedia3Format(format);

            try {
                int trackId = muxer.addTrack(media3Format);
                
                // Track which index is audio/video for proper MP4 offset handling
                if (isVideo) {
                    videoTrackIndex = trackId;
                    Log.i(TAG, "✅ [MEDIA3-FIX] VIDEO track registered at index " + trackId + 
                               " - tfhd.DEFAULT_BASE_IS_MOOF enabled for proper moof-relative offsets");
                } else if (mimeType != null && mimeType.startsWith("audio/")) {
                    audioTrackIndex = trackId;
                    Log.i(TAG, "✅ [MEDIA3-FIX] AUDIO track registered at index " + trackId + 
                               " - tfhd.DEFAULT_BASE_IS_MOOF enabled for VLC compatibility");
                }

                trackCount++;

                return trackId;
            } catch (Exception e) {
                Log.e(TAG, "Failed to add track", e);
                throw new RuntimeException("Failed to add track: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Starts the muxer. Must be called after all tracks have been added.
     */
    public void start() {
        synchronized (muxerLock) {
            if (started) {
                Log.w(TAG, "Muxer already started");
                return;
            }
            if (released) {
                throw new IllegalStateException("Muxer has been released");
            }

            // Add orientation metadata if set
            if (orientationHint != 0) {
                try {
                    muxer.addMetadataEntry(new Mp4OrientationData(orientationHint));
                    Log.d(TAG, "Added orientation metadata: " + orientationHint);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to add orientation metadata", e);
                }
            }

            // Add timestamp metadata for creation/modification times
            try {
                long currentTimeSeconds = System.currentTimeMillis() / 1000;
                muxer.addMetadataEntry(new androidx.media3.container.Mp4TimestampData(
                    currentTimeSeconds, currentTimeSeconds));
                Log.d(TAG, "Added timestamp metadata: " + currentTimeSeconds);
            } catch (Exception e) {
                Log.w(TAG, "Failed to add timestamp metadata", e);
            }

            started = true;

            // CRITICAL: Force flush to disk so moov atom is written immediately
            // This makes the file streamable right away
            try {
                fileOutputStream.flush();
                Log.d(TAG, "Muxer started and flushed - file is now streamable");
                Log.i(TAG, "✅ [MEDIA3-FIX] Muxer initialized with " + trackCount + 
                           " tracks. ALL fragments will use tfhd.DEFAULT_BASE_IS_MOOF for proper offset calculation");
            } catch (IOException e) {
                Log.w(TAG, "Failed to flush after start", e);
            }
        }
    }

    /**
     * Writes sample data for the specified track.
     *
     * @param trackIndex The track index returned by addTrack.
     * @param byteBuf    The encoded sample data.
     * @param bufferInfo The buffer info containing presentation time, size, and flags.
     */
    public void writeSampleData(int trackIndex, @NonNull ByteBuffer byteBuf,
                                 @NonNull MediaCodec.BufferInfo bufferInfo) {
        synchronized (muxerLock) {
            if (!started) {
                throw new IllegalStateException("Muxer has not been started");
            }
            if (released) {
                throw new IllegalStateException("Muxer has been released");
            }

            try {
            // CRITICAL FIX: Normalize timestamps to start from 0 for each recording
            // MediaCodec provides timestamps based on system uptime which accumulates across sessions
            // This causes HLS players to show 45+ minute old timestamps
            if (!timestampOffsetsInitialized) {
                // First sample for this track - use its timestamp as the offset
                Long offset = timestampOffsets.get(trackIndex);
                if (offset == null) {
                    timestampOffsets.put(trackIndex, bufferInfo.presentationTimeUs);
                    Log.w(TAG, "⏱️ Track " + trackIndex + " timestamp offset initialized: " + bufferInfo.presentationTimeUs + "us (" + (bufferInfo.presentationTimeUs / 1000000.0) + "s) - will be normalized to 0");
                }
                
                // Check if all tracks have offsets
                if (timestampOffsets.size() >= trackCount) {
                    timestampOffsetsInitialized = true;
                    Log.i(TAG, "✅ All track timestamp offsets initialized - timestamps will now start from 0");
                }
            }
            
            // Normalize timestamp by subtracting the offset for this track
            Long offset = timestampOffsets.get(trackIndex);
            long normalizedPresentationTimeUs = offset != null ? 
                (bufferInfo.presentationTimeUs - offset) : bufferInfo.presentationTimeUs;

            // DEBUG: Log keyframes and first few samples with normalized PTS for diagnostics
            boolean isKeyFrame = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
            int logCount = trackSampleLogs.get(trackIndex, 0);
            // Verbose logging reduced per user request - only log first 3 samples to verify initialization
            if (logCount < 3) {
                Log.d(TAG, "[MUXER] PTS normalize t=" + normalizedPresentationTimeUs + "us (raw=" + bufferInfo.presentationTimeUs + "us, offset=" + offset + ") track=" + trackIndex + (isKeyFrame ? " [KEY]" : ""));
                trackSampleLogs.put(trackIndex, logCount + 1);
            }
            
            // VLC debugging logs - reduced verbosity (was spamming hundreds of lines)
            // Uncomment for VLC-specific debugging:
            // if (trackIndex == audioTrackIndex && audioTrackIndex != -1 && logCount < 5) {
            //     Log.d(TAG, String.format("[VLC-AUDIO] Sample #%d: pts=%dus, size=%d, origFlags=0x%X, hasKeyFlag=%b",
            //         logCount, normalizedPresentationTimeUs, bufferInfo.size, bufferInfo.flags, isKeyFrame));
            // }
            // if (trackIndex == videoTrackIndex && videoTrackIndex != -1 && isKeyFrame && logCount < 10) {
            //     Log.w(TAG, String.format("[VLC-VIDEO-KEY] Sample #%d: pts=%dus, size=%d, origFlags=0x%X - VIDEO KEYFRAME",
            //         logCount, normalizedPresentationTimeUs, bufferInfo.size, bufferInfo.flags));
            // }
            
            // Convert MediaCodec.BufferInfo to Media3 BufferInfo with normalized timestamp
            int flags = convertFlags(bufferInfo.flags, trackIndex);
            androidx.media3.muxer.BufferInfo media3BufferInfo =
                new androidx.media3.muxer.BufferInfo(
                    normalizedPresentationTimeUs,  // Use normalized timestamp
                    bufferInfo.size,
                    flags
                );

            // Position the buffer correctly for the muxer.
            // IMPORTANT: The caller (GLRecordingPipeline) pre-positions the buffer:
            //   - Sets position = bufferInfo.offset
            //   - Sets limit = bufferInfo.offset + bufferInfo.size
            // This means the buffer's remaining() exactly equals bufferInfo.size.
            // We must NOT re-apply the offset/size, just use the buffer as-is.
            ByteBuffer data = byteBuf.duplicate();
            
            // The buffer should already be properly positioned by the caller.
            // Just ensure the slice is correct and pass to muxer.
            if (data.remaining() != bufferInfo.size) {
                // Unexpected: caller didn't pre-position correctly
                // Try to fix it by slicing
                Log.w(TAG, String.format("Buffer not properly positioned: remaining=%d, size=%d. Attempting correction.",
                    data.remaining(), bufferInfo.size));
                int start = bufferInfo.offset;
                int end = bufferInfo.offset + bufferInfo.size;
                if (start < 0 || end < 0 || end > data.capacity()) {
                    throw new IllegalArgumentException(
                        "Invalid buffer range: offset=" + bufferInfo.offset +
                            ", size=" + bufferInfo.size +
                            ", capacity=" + data.capacity());
                }
                data.position(start);
                data.limit(end);
            }
            // Else: buffer is already correctly positioned by caller, use as-is

            muxer.writeSampleData(trackIndex, data, media3BufferInfo);
            } catch (MuxerException e) {
                Log.e(TAG, "Failed to write sample data", e);
                throw new RuntimeException("Failed to write sample data: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Stops the muxer and finalizes the output file.
     * Writes end-of-stream samples to ensure proper duration calculation.
     */
    public void stop() {
        synchronized (muxerLock) {
            if (!started) {
                Log.w(TAG, "Muxer was not started, nothing to stop");
                return;
            }
            if (released) {
                Log.w(TAG, "Muxer already released");
                return;
            }

            try {
                // Media3's FragmentedMp4Muxer.close() automatically creates the final fragment
                // and finalizes all track durations. No need to manually write EOS samples.
                muxer.close();
                Log.d(TAG, "Muxer stopped successfully");
            } catch (MuxerException e) {
                Log.e(TAG, "Error stopping muxer", e);
                throw new RuntimeException("Failed to stop muxer: " + e.getMessage(), e);
            }
        }
    }
    
    /**
     * Writes end-of-stream samples for all tracks to finalize duration.
     * This is required for FragmentedMp4Muxer to calculate proper duration.
     */
    /**
     * Releases resources used by the muxer.
     */
    public void release() {
        synchronized (muxerLock) {
            if (released) {
                return;
            }
            released = true;

            try {
                if (started) {
                    try {
                        muxer.close();
                    } catch (MuxerException e) {
                        Log.e(TAG, "Error closing muxer", e);
                    }
                }
                // CRITICAL: Flush BEFORE closing to ensure all data (including moov box) is written to disk
                try {
                    fileOutputStream.flush();
                    Log.d(TAG, "FileOutputStream flushed successfully");
                } catch (IOException e) {
                    Log.w(TAG, "Failed to flush FileOutputStream", e);
                }
                fileOutputStream.close();
                Log.d(TAG, "Muxer released and file closed");
            } catch (IOException e) {
                Log.e(TAG, "Error releasing muxer", e);
            }
        }
    }

    /**
     * Sets the orientation hint for video playback.
     *
     * @param degrees The orientation in degrees (0, 90, 180, or 270).
     */
    public void setOrientationHint(int degrees) {
        if (started) {
            Log.w(TAG, "Cannot set orientation after muxer started");
            return;
        }
        this.orientationHint = degrees;
        Log.d(TAG, "Orientation hint set to: " + degrees);
    }

    /**
     * Sets the geographical location for the recorded media.
     * Note: Location metadata support depends on Media3 version.
     *
     * @param latitude  Latitude in degrees (-90 to 90).
     * @param longitude Longitude in degrees (-180 to 180).
     */
    public void setLocation(float latitude, float longitude) {
        if (started) {
            Log.w(TAG, "Cannot set location after muxer started");
            return;
        }
        try {
            muxer.addMetadataEntry(new androidx.media3.container.Mp4LocationData(latitude, longitude));
            Log.d(TAG, "Location metadata set: " + latitude + ", " + longitude);
        } catch (Exception e) {
            Log.w(TAG, "Failed to set location metadata (may not be supported)", e);
        }
    }

    /**
     * Converts Android MediaFormat to Media3 Format.
     */
    private Format convertToMedia3Format(@NonNull MediaFormat mediaFormat) {
        String mimeType = mediaFormat.getString(MediaFormat.KEY_MIME);
        Format.Builder builder = new Format.Builder()
            .setSampleMimeType(mimeType);

        // Handle video format
        if (mimeType != null && mimeType.startsWith("video/")) {
            if (mediaFormat.containsKey(MediaFormat.KEY_WIDTH)) {
                builder.setWidth(mediaFormat.getInteger(MediaFormat.KEY_WIDTH));
            }
            if (mediaFormat.containsKey(MediaFormat.KEY_HEIGHT)) {
                builder.setHeight(mediaFormat.getInteger(MediaFormat.KEY_HEIGHT));
            }
            if (mediaFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                try {
                    builder.setFrameRate(mediaFormat.getFloat(MediaFormat.KEY_FRAME_RATE));
                } catch (ClassCastException e) {
                    builder.setFrameRate(mediaFormat.getInteger(MediaFormat.KEY_FRAME_RATE));
                }
            }
            if (mediaFormat.containsKey(MediaFormat.KEY_BIT_RATE)) {
                builder.setAverageBitrate(mediaFormat.getInteger(MediaFormat.KEY_BIT_RATE));
            }
            
            // CRITICAL FIX for VLC playback: Extract and set color metadata from MediaFormat
            // VLC needs explicit color information to properly decode and display HEVC video
            // Without this, VLC shows black video even though the frames are encoded correctly
            if (mediaFormat.containsKey(MediaFormat.KEY_COLOR_STANDARD) ||
                mediaFormat.containsKey(MediaFormat.KEY_COLOR_RANGE) ||
                mediaFormat.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                
                // Build ColorInfo from MediaFormat
                androidx.media3.common.ColorInfo.Builder colorBuilder = new androidx.media3.common.ColorInfo.Builder();
                
                // Extract color space from MediaFormat
                if (mediaFormat.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
                    int standard = mediaFormat.getInteger(MediaFormat.KEY_COLOR_STANDARD);
                    // MediaFormat.COLOR_STANDARD_BT709 = 1
                    if (standard == 1) {
                        colorBuilder.setColorSpace(androidx.media3.common.C.COLOR_SPACE_BT709);
                    } else if (standard == 2) { // BT601
                        colorBuilder.setColorSpace(androidx.media3.common.C.COLOR_SPACE_BT601);
                    } else if (standard == 6) { // BT2020
                        colorBuilder.setColorSpace(androidx.media3.common.C.COLOR_SPACE_BT2020);
                    }
                }
                
                // Extract color range from MediaFormat
                if (mediaFormat.containsKey(MediaFormat.KEY_COLOR_RANGE)) {
                    int range = mediaFormat.getInteger(MediaFormat.KEY_COLOR_RANGE);
                    // MediaFormat.COLOR_RANGE_LIMITED = 2, COLOR_RANGE_FULL = 1
                    if (range == 1) {
                        colorBuilder.setColorRange(androidx.media3.common.C.COLOR_RANGE_FULL);
                    } else {
                        colorBuilder.setColorRange(androidx.media3.common.C.COLOR_RANGE_LIMITED);
                    }
                }
                
                // Extract color transfer from MediaFormat
                if (mediaFormat.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                    int transfer = mediaFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER);
                    // MediaFormat.COLOR_TRANSFER_SDR_VIDEO = 3 (SMPTE 170M)
                    if (transfer == 3) {
                        colorBuilder.setColorTransfer(androidx.media3.common.C.COLOR_TRANSFER_SDR);
                    } else if (transfer == 6) { // ST2084 (HDR10)
                        colorBuilder.setColorTransfer(androidx.media3.common.C.COLOR_TRANSFER_ST2084);
                    } else if (transfer == 7) { // HLG
                        colorBuilder.setColorTransfer(androidx.media3.common.C.COLOR_TRANSFER_HLG);
                    }
                }
                
                androidx.media3.common.ColorInfo colorInfo = colorBuilder.build();
                builder.setColorInfo(colorInfo);
                Log.d(TAG, "  Set color info from MediaFormat");
            }
            
            if (mediaFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                builder.setRotationDegrees(mediaFormat.getInteger(MediaFormat.KEY_ROTATION));
            }

            // Handle CSD (Codec Specific Data)
            List<byte[]> initData = new ArrayList<>();
            if (mediaFormat.containsKey("csd-0")) {
                ByteBuffer csd0 = mediaFormat.getByteBuffer("csd-0");
                if (csd0 != null) {
                    byte[] csd0Bytes = new byte[csd0.remaining()];
                    csd0.get(csd0Bytes);
                    csd0.rewind();
                    initData.add(csd0Bytes);
                }
            }
            if (mediaFormat.containsKey("csd-1")) {
                ByteBuffer csd1 = mediaFormat.getByteBuffer("csd-1");
                if (csd1 != null) {
                    byte[] csd1Bytes = new byte[csd1.remaining()];
                    csd1.get(csd1Bytes);
                    csd1.rewind();
                    initData.add(csd1Bytes);
                }
            }
            if (!initData.isEmpty()) {
                builder.setInitializationData(initData);
            }
            
            // Set codec string for video - REQUIRED by Media3 for proper MP4 codec box
            // For fragmented MP4, Media3 uses this string in the codec descriptor
            if (mimeType != null) {
                if (mimeType.equals("video/hevc")) {
                    // HEVC: Infer profile from CSD data
                    // Most Android devices use Main profile (1), Level 5 (150 = level 5.0)
                    builder.setCodecs("hev1.1.6.L150.B0"); // HEVC Main profile, Level 5.0
                } else if (mimeType.equals("video/avc")) {
                    // AVC: Common profile used by Android encoders
                    builder.setCodecs("avc1.42001E"); // AVC Baseline profile, Level 30
                }
            }
        }

        // Handle audio format
        if (mimeType != null && mimeType.startsWith("audio/")) {
            Log.d(TAG, "Converting audio format: " + mimeType);
            
            // Extract sample rate and channel count for AAC config generation
            int sampleRate = 48000; // default
            int channels = 2; // default
            
            if (mediaFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                channels = mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                builder.setChannelCount(channels);
                Log.d(TAG, "  channelCount=" + channels);
            } else {
                Log.w(TAG, "  WARNING: No channel count in audio format!");
            }
            if (mediaFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                sampleRate = mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                builder.setSampleRate(sampleRate);
                Log.d(TAG, "  sampleRate=" + sampleRate);
            } else {
                Log.w(TAG, "  WARNING: No sample rate in audio format!");
            }
            if (mediaFormat.containsKey(MediaFormat.KEY_BIT_RATE)) {
                int bitrate = mediaFormat.getInteger(MediaFormat.KEY_BIT_RATE);
                builder.setAverageBitrate(bitrate);
                Log.d(TAG, "  bitrate=" + bitrate);
            }

            // CRITICAL VLC FIX: Generate proper AAC AudioSpecificConfig for ESDS box
            // The encoder's CSD-0 is often malformed. We generate a clean config based on
            // the actual sample rate and channel count for VLC compatibility.
            // AAC-LC (profile=2), with proper sampling frequency index
            if (mimeType != null && (mimeType.equals(MimeTypes.AUDIO_AAC) || mimeType.contains("mp4a"))) {
                byte[] aacConfig = generateAacAudioSpecificConfig(sampleRate, channels);
                List<byte[]> audioInitData = new ArrayList<>();
                audioInitData.add(aacConfig);
                builder.setInitializationData(audioInitData);
                
                // Set explicit codec string for MP4 descriptor box - Media3 REQUIRES this for proper ESDS
                // The codec string tells the MP4 parser (VLC) what audio codec profile/level to expect
                builder.setCodecs("mp4a.40.2"); // AAC-LC (object type 2), profile level 0
                
                Log.d(TAG, "  Generated clean AAC config for VLC: " + bytesToHex(aacConfig) + 
                           " (sampleRate=" + sampleRate + ", channels=" + channels + ")");
            }
        }

        return builder.build();
    }

    /**
     * Converts MediaCodec buffer flags to Media3 buffer flags.
     */
    private int convertFlags(int mediaCodecFlags, int trackIndex) {
        int flags = 0;

        if ((mediaCodecFlags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
            flags |= C.BUFFER_FLAG_KEY_FRAME;
            if (trackIndex == videoTrackIndex && videoTrackIndex != -1 && sampleCountDebug < 5) {
                Log.w(TAG, "[VLC-VIDEO-CONVERT] Video keyframe flag converted to Media3 C.BUFFER_FLAG_KEY_FRAME");
            }
        }
        
        // DO NOT force audio keyframes
        // AAC samples should NOT be marked as keyframes in fragmented MP4
        // Forcing keyframes corrupts the trun sample flags, causing VLC to read from wrong byte offsets
        // Audio-specific sync sample marking is handled by MP4 spec via stss atom separately
        
        if ((mediaCodecFlags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            flags |= C.BUFFER_FLAG_END_OF_STREAM;
        }

        return flags;
    }
    
    /**
     * Helper to convert bytes to hex string for logging.
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(bytes.length, 16); i++) {
            sb.append(String.format("%02X ", bytes[i]));
        }
        if (bytes.length > 16) {
            sb.append("...");
        }
        return sb.toString().trim();
    }
    
    /**
     * Handles processed segments from the patched Media3 muxer.
     * This callback receives segments in real-time as they're muxed, enabling live streaming.
     * 
     * @param segment ProcessedSegment containing either init segment or media fragment
     */
    private void handleProcessedSegment(ProcessedSegment segment) {
        synchronized (muxerLock) {
            if (released) {
                return;
            }
            try {
            // Extract bytes from Media3's payload buffer without mutating it.
            // Some Media3 versions leave the buffer in write-mode (position at end, limit=capacity),
            // while others provide it already ready for reading (position=0, limit=dataSize).
            ByteBuffer payload = segment.payload.duplicate();
            if (payload.limit() == payload.capacity() && payload.position() != 0) {
                payload.flip();
            }

            byte[] data = new byte[payload.remaining()];
            payload.get(data);
            
            // Check streaming mode to determine if we should save to disk
            RemoteStreamManager.StreamingMode streamingMode = RemoteStreamManager.getInstance().getStreamingMode();
            boolean shouldSaveToDisk = (streamingMode == RemoteStreamManager.StreamingMode.STREAM_AND_SAVE);
            
            if (segment.isInitSegment) {
                // Initialization segment (ftyp + moov)
                Log.i(TAG, "📦 [SEGMENT] Received INIT segment: " + data.length + " bytes");
                
                // Send to RemoteStreamManager for HLS streaming
                RemoteStreamManager.getInstance().onInitializationSegment(data);
                initSegmentSent = true;
                
                // Write to file only if STREAM_AND_SAVE mode
                if (shouldSaveToDisk && fileOutputStream != null) {
                    fileOutputStream.write(data);
                    fileOutputStream.flush();
                    // Log.d(TAG, "✅ Init segment written to file (STREAM_AND_SAVE mode)");
                } else {
                    // Log.d(TAG, "⏭️ Init segment NOT written to file (STREAM_ONLY mode)");
                }
            } else {
                // Media fragment (moof + mdat)
                Log.i(TAG, "🎬 [FRAGMENT] #" + segment.segmentNr + 
                    ": " + (data.length / 1024) + " KB, duration: " + segment.durationMs + " ms");
                
                // Send to RemoteStreamManager for HLS streaming
                if (initSegmentSent) {
                    RemoteStreamManager.getInstance().onFragmentComplete(segment.segmentNr, data);
                } else {
                    Log.w(TAG, "⚠️ Fragment #" + segment.segmentNr + 
                        " received before init segment - skipping stream upload");
                }
                
                // Write to file only if STREAM_AND_SAVE mode
                if (shouldSaveToDisk && fileOutputStream != null) {
                    fileOutputStream.write(data);
                    fileOutputStream.flush();
                    Log.d(TAG, "✅ Fragment #" + segment.segmentNr + " written to file (STREAM_AND_SAVE mode)");
                } else {
                    // Log.d(TAG, "⏭️ Fragment #" + segment.segmentNr + " NOT written to file (STREAM_ONLY mode)");
                }
                
                nextFragmentNumber++;
            }
            } catch (Exception e) {
                Log.e(TAG, "❌ Error handling processed segment", e);
            }
        }
    }

    /**
     * Generates a proper AAC AudioSpecificConfig for the ESDS box.
     * This ensures VLC compatibility by creating clean configuration data.
     * 
     * Format: 5 bits audioObjectType (2=AAC-LC) + 4 bits samplingFrequencyIndex + 4 bits channelConfiguration
     * 
     * @param sampleRate Sample rate in Hz (e.g., 48000)
     * @param channels Number of audio channels (1 or 2)
     * @return 2-byte AAC AudioSpecificConfig
     */
    private byte[] generateAacAudioSpecificConfig(int sampleRate, int channels) {
        // AAC-LC profile = 2
        int audioObjectType = 2;
        
        // Sampling frequency index for AAC
        int samplingFrequencyIndex;
        switch (sampleRate) {
            case 96000: samplingFrequencyIndex = 0; break;
            case 88200: samplingFrequencyIndex = 1; break;
            case 64000: samplingFrequencyIndex = 2; break;
            case 48000: samplingFrequencyIndex = 3; break;
            case 44100: samplingFrequencyIndex = 4; break;
            case 32000: samplingFrequencyIndex = 5; break;
            case 24000: samplingFrequencyIndex = 6; break;
            case 22050: samplingFrequencyIndex = 7; break;
            case 16000: samplingFrequencyIndex = 8; break;
            case 12000: samplingFrequencyIndex = 9; break;
            case 11025: samplingFrequencyIndex = 10; break;
            case 8000: samplingFrequencyIndex = 11; break;
            case 7350: samplingFrequencyIndex = 12; break;
            default:
                Log.w(TAG, "Unsupported sample rate: " + sampleRate + ", using 48000 Hz");
                samplingFrequencyIndex = 3; // 48000 Hz
                break;
        }
        
        // Channel configuration (1=mono, 2=stereo)
        int channelConfig = Math.min(channels, 2);
        
        // Build AudioSpecificConfig: 5 bits audioObjectType + 4 bits samplingFrequencyIndex + 4 bits channelConfiguration
        // Byte 1: xxxxx xxx (5 bits audioObjectType + 3 upper bits of samplingFrequencyIndex)
        // Byte 2: x xxxx xxx (1 lower bit of samplingFrequencyIndex + 4 bits channelConfiguration + 3 bits padding)
        
        int byte1 = (audioObjectType << 3) | (samplingFrequencyIndex >> 1);
        int byte2 = ((samplingFrequencyIndex & 0x1) << 7) | (channelConfig << 3);
        
        return new byte[] { (byte) byte1, (byte) byte2 };
    }
}
