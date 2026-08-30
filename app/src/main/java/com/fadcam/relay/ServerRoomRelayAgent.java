package com.fadcam.relay;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Adapter boundary for remote Server Room media relay.
 *
 * <p>This class deliberately does not own, replace, or modify the local
 * Server Room media implementation. It only defines the lifecycle and the
 * transport-to-media handoff boundary that later relay polling can use.</p>
 */
public final class ServerRoomRelayAgent {

    public enum Mode {
        DIRECT,
        RELAY
    }

    public interface RelayTransport {
        void connect() throws Exception;

        void disconnect();

        boolean isConnected();
    }

    public interface RelayMediaSink {
        void onRelayMedia(byte[] payload);
    }

    private final RelayTransport transport;
    private final RelayMediaSink mediaSink;
    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.DIRECT);

    public ServerRoomRelayAgent(RelayTransport transport, RelayMediaSink mediaSink) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.mediaSink = Objects.requireNonNull(mediaSink, "mediaSink");
    }

    /**
     * Starts the relay boundary in DIRECT mode. No network connection is
     * opened until the caller explicitly requests relay mode.
     */
    public void start() {
        mode.set(Mode.DIRECT);
    }

    /**
     * Requests relay mode. The existing local media path remains owned by the
     * caller through {@link RelayMediaSink}; this class does not reimplement it.
     */
    public void enterRelayMode() throws Exception {
        transport.connect();
        if (!transport.isConnected()) {
            throw new IllegalStateException("Relay transport did not connect");
        }
        mode.set(Mode.RELAY);
    }

    /**
     * Returns to direct mode without changing the local Server Room media
     * implementation.
     */
    public void leaveRelayMode() {
        transport.disconnect();
        mode.set(Mode.DIRECT);
    }

    /**
     * Delivers a relay payload across the existing media boundary.
     * Polling and authentication are intentionally separate stages.
     */
    public void deliverRelayMedia(byte[] payload) {
        if (mode.get() != Mode.RELAY) {
            throw new IllegalStateException("Relay media received while not in RELAY mode");
        }
        Objects.requireNonNull(payload, "payload");
        mediaSink.onRelayMedia(payload.clone());
    }

    public Mode getMode() {
        return mode.get();
    }

    public void stop() {
        transport.disconnect();
        mode.set(Mode.DIRECT);
    }
}
