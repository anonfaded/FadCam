package com.fadcam.streaming;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.pedro.common.ConnectChecker;
import com.pedro.library.rtmp.RtmpStream;

/** Generic Android RTMP/RTMPS publisher used by FadCam. */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
public final class RtmpPublisher implements ConnectChecker {
    public interface Listener {
        void onConnected();
        void onBitrateChanged(long bitrate);
        void onFailed(@NonNull String reason);
        void onDisconnected();
        void onAuthError();
        void onAuthSuccess();
    }

    public static final class VideoProfile {
        public final int width;
        public final int height;
        public final int fps;
        public final int bitrate;

        VideoProfile(int width, int height, int fps, int bitrate) {
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.bitrate = bitrate;
        }

        @NonNull
        @Override public String toString() {
            return width + "x" + height + "@" + fps + " " + bitrate + "bps";
        }
    }

    private static final VideoProfile[] VIDEO_LADDER = new VideoProfile[] {
            new VideoProfile(3840, 2160, 30, 16_000_000),
            new VideoProfile(1920, 1080, 30, 8_000_000),
            new VideoProfile(1280, 720, 30, 4_000_000)
    };
    private static final int AUDIO_SAMPLE_RATE = 48_000;
    private static final int AUDIO_BITRATE = 128_000;

    private final RtmpStream stream;
    @Nullable private final Listener listener;
    @Nullable private VideoProfile activeProfile;
    private boolean prepared;

    public RtmpPublisher(@NonNull Context context, @Nullable Listener listener) {
        this.listener = listener;
        stream = new RtmpStream(context.getApplicationContext(), this);
        stream.getStreamClient().setReTries(3);
    }

    public synchronized boolean prepare() {
        if (prepared && activeProfile != null) return true;
        prepared = false;
        activeProfile = null;
        for (VideoProfile profile : VIDEO_LADDER) {
            if (tryPrepareVideo(profile)) {
                activeProfile = profile;
                prepared = true;
                break;
            }
        }
        if (!prepared) {
            notifyFailure("No supported H.264 camera profile (4K/1080p/720p)");
            return false;
        }
        try {
            boolean audioPrepared = stream.prepareAudio(AUDIO_SAMPLE_RATE, true, AUDIO_BITRATE, true, true);
            if (!audioPrepared) {
                prepared = false;
                activeProfile = null;
                notifyFailure("AAC microphone encoder preparation failed");
                return false;
            }
            return true;
        } catch (IllegalArgumentException error) {
            prepared = false;
            activeProfile = null;
            notifyFailure("Invalid AAC configuration");
            return false;
        }
    }

    private boolean tryPrepareVideo(@NonNull VideoProfile profile) {
        try {
            return stream.prepareVideo(profile.width, profile.height, profile.bitrate, profile.fps, 0);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    public synchronized boolean prepare(int width, int height, int videoBitrate, int fps,
                                        int audioSampleRate, boolean stereo, int audioBitrate) {
        if (stream.isStreaming()) return true;
        try {
            prepared = stream.prepareVideo(width, height, videoBitrate, fps, 0)
                    && stream.prepareAudio(audioSampleRate, stereo, audioBitrate);
            if (prepared) activeProfile = new VideoProfile(width, height, fps, videoBitrate);
            else activeProfile = null;
            return prepared;
        } catch (IllegalArgumentException error) {
            prepared = false;
            activeProfile = null;
            notifyFailure("Invalid encoder configuration");
            return false;
        }
    }

    @NonNull
    public synchronized VideoProfile getActiveProfile() {
        if (activeProfile == null) throw new IllegalStateException("Publisher is not prepared");
        return activeProfile;
    }

    /** Constructs the credential-bearing endpoint only at the RTMP client boundary. */
    public synchronized void start(@NonNull RtmpDestination destination,
                                    @NonNull String serverUrl,
                                    @NonNull String streamKey) {
        if (!prepared && !prepare()) throw new IllegalStateException("RTMP encoder is not prepared");
        if (stream.isStreaming()) return;
        stream.startStream(destination.buildEndpoint(serverUrl, streamKey));
    }

    public synchronized void stop() {
        if (stream.isStreaming()) stream.stopStream();
    }

    public synchronized boolean isStreaming() {
        return stream.isStreaming();
    }

    public synchronized void release() {
        stream.release();
        prepared = false;
        activeProfile = null;
    }

    private void notifyFailure(@NonNull String ignoredReason) {
        if (listener != null) listener.onFailed("RTMP publication failed");
    }

    @Override public void onConnectionStarted(@NonNull String url) {
        // Deliberately do not forward the endpoint or a connection-start callback.
    }

    @Override public void onConnectionSuccess() {
        if (listener != null) listener.onConnected();
    }

    @Override public void onNewBitrate(long bitrate) {
        if (listener != null) listener.onBitrateChanged(bitrate);
    }

    @Override public void onConnectionFailed(@NonNull String reason) {
        // Library failure strings can contain URLs or transport details. Never forward them.
        notifyFailure("RTMP connection failed");
    }

    @Override public void onDisconnect() {
        if (listener != null) listener.onDisconnected();
    }

    @Override public void onAuthError() {
        if (listener != null) listener.onAuthError();
    }

    @Override public void onAuthSuccess() {
        if (listener != null) listener.onAuthSuccess();
    }
}
