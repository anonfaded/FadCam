package com.fadcam;

import android.media.MediaRecorder;

import androidx.annotation.NonNull;

import java.io.Serializable;

public enum VideoCodec implements Serializable {
    AVC("H.264","video/avc", "h264_mediacodec"),
    HEVC("H.265", "video/hevc", "hevc_mediacodec");

    private final String name;
    private final String mimeType;
    private final String ffmpeg;

    VideoCodec(String name, String mimeType, String ffmpeg) {
        this.name = name;
        this.mimeType = mimeType;
        this.ffmpeg = ffmpeg;
    }

    public String getName() {
        return name;
    }

    public String getMimeType() { return this.mimeType; }

    /**
     * Returns the MediaRecorder video encoder associated with this video codec.
     *
     * @return The MediaRecorder video encoder.
     */
    public int getEncoder() {
        switch (this) {
            case AVC:
                return MediaRecorder.VideoEncoder.H264;
            case HEVC:
                return MediaRecorder.VideoEncoder.HEVC;
            default:
                throw new IllegalStateException("Unexpected value: " + this);
        }
    }

    /**
     * Returns the ffmpeg codec associated with this video codec.
     *
     * @return The ffmpeg codec string.
     */
    public String getFfmpeg() { return this.ffmpeg; }

    @NonNull
    @Override
    public String toString() {
        return name();
    }
}
