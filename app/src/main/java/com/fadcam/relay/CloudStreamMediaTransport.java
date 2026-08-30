package com.fadcam.relay;

import android.content.Context;

import com.fadcam.streaming.CloudStreamUploader;

import java.util.Objects;

/**
 * Direct media transport backed by the existing authenticated cloud uploader.
 * It deliberately does not own lifecycle or authentication; the service owns it.
 */
public final class CloudStreamMediaTransport implements MediaTransport {
    private final CloudStreamUploader uploader;
    private volatile boolean connected;

    public CloudStreamMediaTransport(Context context) {
        Objects.requireNonNull(context, "context");
        this.uploader = CloudStreamUploader.getInstance(context.getApplicationContext());
    }

    @Override
    public void connect() {
        connected = uploader.isEnabled() && uploader.isReady();
        if (!connected) {
            throw new IllegalStateException("cloud media transport is not ready");
        }
    }

    @Override
    public void disconnect() {
        connected = false;
    }

    @Override
    public boolean isConnected() {
        return connected && uploader.isEnabled() && uploader.isReady();
    }

    @Override
    public void sendInitializationSegment(byte[] payload) {
        requirePayload(payload);
        requireConnected();
        uploader.uploadInitSegment(payload.clone(), null);
    }

    @Override
    public void sendFragment(int sequenceNumber, byte[] payload, long durationMs) {
        requirePayload(payload);
        requireConnected();
        if (sequenceNumber < 1) throw new IllegalArgumentException("sequenceNumber must be positive");
        if (durationMs <= 0) throw new IllegalArgumentException("durationMs must be positive");
        uploader.uploadSegment(sequenceNumber, payload.clone(), null);
    }

    private void requireConnected() {
        if (!isConnected()) throw new IllegalStateException("cloud media transport is not connected");
    }
}
