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
import androidx.media3.muxer.FragmentedMp4Muxer;
import androidx.media3.muxer.MuxerException;

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
    private boolean started = false;
    private boolean released = false;
    private int orientationHint = 0;
    
    // Track last presentation times for EOS handling
    private final android.util.SparseArray<Long> lastPresentationTimeUs = new android.util.SparseArray<>();
    private int videoTrackId = -1;

    /**
     * Creates a FragmentedMp4MuxerWrapper with a file path.
     *
     * @param path The path to the output file.
     * @throws IOException If the file cannot be created.
     */
    public FragmentedMp4MuxerWrapper(@NonNull String path) throws IOException {
        this.fileOutputStream = new FileOutputStream(path);
        this.muxer = new FragmentedMp4Muxer.Builder(fileOutputStream).build();
        Log.d(TAG, "Created FragmentedMp4Muxer for path: " + path);
    }

    /**
     * Creates a FragmentedMp4MuxerWrapper with a FileDescriptor.
     *
     * @param fd The FileDescriptor for the output file.
     * @throws IOException If the output stream cannot be created.
     */
    public FragmentedMp4MuxerWrapper(@NonNull FileDescriptor fd) throws IOException {
        this.fileOutputStream = new FileOutputStream(fd);
        this.muxer = new FragmentedMp4Muxer.Builder(fileOutputStream).build();
        Log.d(TAG, "Created FragmentedMp4Muxer for file descriptor");
    }

    /**
     * Adds a track to the muxer.
     *
     * @param format The MediaFormat describing the track.
     * @return The track index.
     * @throws IllegalStateException If the muxer has already been started.
     */
    public int addTrack(@NonNull MediaFormat format) {
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
            Log.d(TAG, "Added track with id: " + trackId + ", format: " + mimeType);
            
            // Track video track ID for EOS handling
            if (isVideo) {
                videoTrackId = trackId;
            }
            
            // Initialize last presentation time for this track
            lastPresentationTimeUs.put(trackId, 0L);
            
            return trackId;
        } catch (Exception e) {
            Log.e(TAG, "Failed to add track", e);
            throw new RuntimeException("Failed to add track: " + e.getMessage(), e);
        }
    }

    /**
     * Starts the muxer. Must be called after all tracks have been added.
     */
    public void start() {
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
        Log.d(TAG, "Muxer started");
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
        if (!started) {
            throw new IllegalStateException("Muxer has not been started");
        }
        if (released) {
            throw new IllegalStateException("Muxer has been released");
        }

        try {
            // Track the last presentation time for this track (for EOS handling)
            if (bufferInfo.presentationTimeUs > 0) {
                Long current = lastPresentationTimeUs.get(trackIndex);
                if (current == null || bufferInfo.presentationTimeUs > current) {
                    lastPresentationTimeUs.put(trackIndex, bufferInfo.presentationTimeUs);
                }
            }
            
            // Convert MediaCodec.BufferInfo to Media3 BufferInfo
            int flags = convertFlags(bufferInfo.flags);
            androidx.media3.muxer.BufferInfo media3BufferInfo =
                new androidx.media3.muxer.BufferInfo(
                    bufferInfo.presentationTimeUs,
                    bufferInfo.size,
                    flags
                );

            // Position the buffer correctly
            ByteBuffer data = byteBuf.duplicate();
            data.position(bufferInfo.offset);
            data.limit(bufferInfo.offset + bufferInfo.size);

            muxer.writeSampleData(trackIndex, data, media3BufferInfo);
        } catch (MuxerException e) {
            Log.e(TAG, "Failed to write sample data", e);
            throw new RuntimeException("Failed to write sample data: " + e.getMessage(), e);
        }
    }

    /**
     * Stops the muxer and finalizes the output file.
     * Writes end-of-stream samples to ensure proper duration calculation.
     */
    public void stop() {
        if (!started) {
            Log.w(TAG, "Muxer was not started, nothing to stop");
            return;
        }
        if (released) {
            Log.w(TAG, "Muxer already released");
            return;
        }

        try {
            // Write end-of-stream samples for all tracks to finalize duration
            // This is critical for fragmented MP4 to have correct duration metadata
            writeEndOfStreamSamples();
            
            muxer.close();
            Log.d(TAG, "Muxer stopped successfully");
        } catch (MuxerException e) {
            Log.e(TAG, "Error stopping muxer", e);
            throw new RuntimeException("Failed to stop muxer: " + e.getMessage(), e);
        }
    }
    
    /**
     * Writes end-of-stream samples for all tracks to finalize duration.
     * This is required for FragmentedMp4Muxer to calculate proper duration.
     */
    private void writeEndOfStreamSamples() {
        ByteBuffer emptyBuffer = ByteBuffer.allocateDirect(0);
        
        for (int i = 0; i < lastPresentationTimeUs.size(); i++) {
            int trackId = lastPresentationTimeUs.keyAt(i);
            Long lastPts = lastPresentationTimeUs.valueAt(i);
            
            if (lastPts != null && lastPts > 0) {
                try {
                    // Write EOS sample with the final timestamp
                    androidx.media3.muxer.BufferInfo eosBufferInfo =
                        new androidx.media3.muxer.BufferInfo(
                            lastPts,
                            0, // size = 0 for EOS
                            C.BUFFER_FLAG_END_OF_STREAM
                        );
                    
                    muxer.writeSampleData(trackId, emptyBuffer.duplicate(), eosBufferInfo);
                    Log.d(TAG, "Wrote EOS for track " + trackId + " at pts=" + lastPts + "us (" + (lastPts / 1000000.0) + "s)");
                } catch (Exception e) {
                    Log.w(TAG, "Failed to write EOS for track " + trackId + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * Releases resources used by the muxer.
     */
    public void release() {
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
            fileOutputStream.close();
            Log.d(TAG, "Muxer released");
        } catch (IOException e) {
            Log.e(TAG, "Error releasing muxer", e);
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
            if (mediaFormat.containsKey(MediaFormat.KEY_COLOR_FORMAT)) {
                builder.setColorInfo(null); // Let Media3 infer from codec
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
        }

        // Handle audio format
        if (mimeType != null && mimeType.startsWith("audio/")) {
            Log.d(TAG, "Converting audio format: " + mimeType);
            
            // CRITICAL: Set codec string for AAC to enable proper sync sample flagging
            // Without this, Media3's FragmentedMp4Muxer marks AAC samples as non-sync
            // which causes ExoPlayer to not play audio. See: https://github.com/androidx/media/issues/2435
            if (MimeTypes.AUDIO_AAC.equals(mimeType)) {
                builder.setCodecs("mp4a.40.2"); // AAC-LC profile
                Log.d(TAG, "  Set codec string: mp4a.40.2 (AAC-LC)");
            }
            
            if (mediaFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                int channels = mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                builder.setChannelCount(channels);
                Log.d(TAG, "  channelCount=" + channels);
            } else {
                Log.w(TAG, "  WARNING: No channel count in audio format!");
            }
            if (mediaFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                int sampleRate = mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
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

            // Handle audio CSD - THIS IS CRITICAL for AAC playback!
            List<byte[]> initData = new ArrayList<>();
            if (mediaFormat.containsKey("csd-0")) {
                ByteBuffer csd0 = mediaFormat.getByteBuffer("csd-0");
                if (csd0 != null) {
                    byte[] csd0Bytes = new byte[csd0.remaining()];
                    csd0.get(csd0Bytes);
                    csd0.rewind();
                    initData.add(csd0Bytes);
                    Log.d(TAG, "  CSD-0 added: " + csd0Bytes.length + " bytes, data=" + bytesToHex(csd0Bytes));
                } else {
                    Log.w(TAG, "  WARNING: CSD-0 key exists but buffer is null!");
                }
            } else {
                Log.e(TAG, "  ERROR: No CSD-0 in audio format - audio playback WILL FAIL!");
            }
            if (!initData.isEmpty()) {
                builder.setInitializationData(initData);
                Log.d(TAG, "  Initialization data set with " + initData.size() + " entries");
            } else {
                Log.e(TAG, "  ERROR: No initialization data for audio - decoder won't know how to decode!");
            }
        }

        return builder.build();
    }

    /**
     * Converts MediaCodec buffer flags to Media3 buffer flags.
     */
    private int convertFlags(int mediaCodecFlags) {
        int flags = 0;

        if ((mediaCodecFlags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
            flags |= C.BUFFER_FLAG_KEY_FRAME;
        }
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
}
