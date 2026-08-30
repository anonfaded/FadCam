package com.fadcam.relay;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Direct transport for the built-in local HTTP stream.
 *
 * <p>The muxer/RemoteStreamManager already owns the local fragment buffer that
 * HardenedLiveM3U8Server serves. This adapter deliberately does not upload a
 * second copy of the bytes. Its job is to make that existing local delivery
 * path explicit to TransportController and to validate the lifecycle boundary.
 */
public final class LocalDirectMediaTransport implements MediaTransport {
    private final AtomicBoolean connected = new AtomicBoolean(false);

    @Override
    public void connect() {
        connected.set(true);
    }

    @Override
    public void disconnect() {
        connected.set(false);
    }

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public void sendInitializationSegment(byte[] payload) {
        requirePayload(payload);
        requireConnected();
    }

    @Override
    public void sendFragment(int sequenceNumber, byte[] payload, long durationMs) {
        requirePayload(payload);
        if (sequenceNumber < 0) throw new IllegalArgumentException("sequenceNumber must not be negative");
        if (durationMs <= 0) throw new IllegalArgumentException("durationMs must be positive");
        requireConnected();
    }

    private void requireConnected() {
        if (!connected.get()) throw new IllegalStateException("local direct transport is disconnected");
    }
}
