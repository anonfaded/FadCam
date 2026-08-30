package com.fadcam.relay;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Adapter boundary for remote Server Room media relay. */
public final class ServerRoomRelayAgent implements MediaTransport {
    public enum Mode { DIRECT, RELAY }

    public interface RelayTransport {
        void connect() throws Exception;
        void disconnect();
        boolean isConnected();
        default void sendMedia(int sequenceNumber, byte[] payload, long durationMs) throws Exception {
            throw new UnsupportedOperationException("relay media transport is not configured");
        }
        default void sendInitializationSegment(byte[] payload) throws Exception {
            throw new UnsupportedOperationException("relay media transport is not configured");
        }
    }

    public interface RelayMediaSink { void onRelayMedia(byte[] payload); }

    private final RelayTransport transport;
    private final RelayMediaSink mediaSink;
    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.DIRECT);

    public ServerRoomRelayAgent(RelayTransport transport, RelayMediaSink mediaSink) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.mediaSink = Objects.requireNonNull(mediaSink, "mediaSink");
    }

    public void start() { mode.set(Mode.DIRECT); }

    @Override public void connect() throws Exception { enterRelayMode(); }
    @Override public void disconnect() { leaveRelayMode(); }
    @Override public boolean isConnected() { return mode.get() == Mode.RELAY && transport.isConnected(); }

    @Override
    public void sendInitializationSegment(byte[] payload) throws Exception {
        requirePayload(payload);
        requireRelayConnected();
        transport.sendInitializationSegment(payload.clone());
    }

    @Override
    public void sendFragment(int sequenceNumber, byte[] payload, long durationMs) throws Exception {
        requirePayload(payload);
        requireRelayConnected();
        if (sequenceNumber < 1) throw new IllegalArgumentException("sequenceNumber must be positive");
        transport.sendMedia(sequenceNumber, payload.clone(), durationMs);
    }

    public void enterRelayMode() throws Exception {
        transport.connect();
        if (!transport.isConnected()) throw new IllegalStateException("Relay transport did not connect");
        mode.set(Mode.RELAY);
    }

    public void leaveRelayMode() {
        transport.disconnect();
        mode.set(Mode.DIRECT);
    }

    public void deliverRelayMedia(byte[] payload) {
        if (mode.get() != Mode.RELAY) throw new IllegalStateException("Relay media received while not in RELAY mode");
        Objects.requireNonNull(payload, "payload");
        mediaSink.onRelayMedia(payload.clone());
    }

    public Mode getMode() { return mode.get(); }
    public void stop() { disconnect(); }

    private void requireRelayConnected() {
        if (!isConnected()) throw new IllegalStateException("relay media transport is not connected");
    }
}
