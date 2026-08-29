package com.fadcam.streaming;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.pedro.common.ConnectChecker;
import com.pedro.library.rtmp.RtmpStream;

/**
 * Generic Android RTMP/RTMPS publisher used by FadCam.
 *
 * <p>RootEncoder owns camera/audio capture and MediaCodec encoding. This class
 * deliberately knows nothing about a social platform: destinations only
 * provide an ingest server and a stream key.</p>
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
public final class RtmpPublisher implements ConnectChecker {

    public interface Listener {
        void onConnecting(@NonNull String endpoint);
        void onConnected();
        void onBitrateChanged(long bitrate);
        void onFailed(@NonNull String reason);
        void onDisconnected();
        void onAuthError();
        void onAuthSuccess();
    }

    private static final int DEFAULT_WIDTH = 1280;
    private static final int DEFAULT_HEIGHT = 720;
    private static final int DEFAULT_VIDEO_BITRATE = 4_000_000;
    private static final int DEFAULT_FPS = 30;
    private static final int DEFAULT_AUDIO_SAMPLE_RATE = 44_100;
    private static final int DEFAULT_AUDIO_BITRATE = 128_000;

    private final RtmpStream stream;
    @Nullable private final Listener listener;
    private boolean prepared;

    public RtmpPublisher(@NonNull Context context, @Nullable Listener listener) {
        this.listener = listener;
        stream = new RtmpStream(context.getApplicationContext(), this);
    }

    /** Prepare H.264/AAC using settings suitable for mainstream live platforms. */
    public synchronized boolean prepare() {
        return prepare(DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_VIDEO_BITRATE, DEFAULT_FPS,
                DEFAULT_AUDIO_SAMPLE_RATE, true, DEFAULT_AUDIO_BITRATE);
    }

    public synchronized boolean prepare(int width, int height, int videoBitrate, int fps,
                                        int audioSampleRate, boolean stereo, int audioBitrate) {
        if (stream.isStreaming()) return true;
        try {
            prepared = stream.prepareVideo(width, height, videoBitrate, fps, 0)
                    && stream.prepareAudio(audioSampleRate, stereo, audioBitrate);
            return prepared;
        } catch (IllegalArgumentException e) {
            prepared = false;
            if (listener != null) listener.onFailed(e.getMessage() == null ? "Invalid encoder configuration" : e.getMessage());
            return false;
        }
    }

    public synchronized void start(@NonNull RtmpDestination destination,
                                   @NonNull String serverUrl,
                                   @NonNull String streamKey) {
        String endpoint = destination.buildEndpoint(serverUrl, streamKey);
        start(endpoint);
    }

    /** Start publishing to an already constructed RTMP/RTMPS endpoint. */
    public synchronized void start(@NonNull String endpoint) {
        if (!prepared && !prepare()) {
            throw new IllegalStateException("RTMP encoder is not prepared");
        }
        if (stream.isStreaming()) return;
        if (listener != null) listener.onConnecting(endpoint);
        stream.startStream(endpoint);
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
    }

    @Override public void onConnectionStarted(@NonNull String url) {
        if (listener != null) listener.onConnecting(url);
    }

    @Override public void onConnectionSuccess() {
        if (listener != null) listener.onConnected();
    }

    @Override public void onNewBitrate(long bitrate) {
        if (listener != null) listener.onBitrateChanged(bitrate);
    }

    @Override public void onConnectionFailed(@NonNull String reason) {
        if (listener != null) listener.onFailed(reason);
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
