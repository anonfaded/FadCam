package com.fadcam.relay;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Adapter boundary for remote Server Room media relay.
 *
 * <p>The agent is the single relay-side transport adapter used by
 * {@link TransportController}. It does not own or replace the existing local
 * Server Room media implementation; media crosses the explicit
 * {@link RelayMediaSink} boundary.</p>
 */
public final class ServerRoomRelayAgent implements TransportController.Transport {

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
     * opened until TransportController selects relay mode.
     */
    public void start() {
        mode.set(Mode.DIRECT);
    }

    /** TransportController relay contract. */
    @Override
    public void connect() throws Exception {
        enterRelayMode();
    }

    /** TransportController relay contract. */
    @Override
    public void disconnect() {
        leaveRelayMode();
    }

    /** TransportController relay contract. */
    @Override
    public boolean isConnected() {
        return mode.get() == Mode.RELAY && transport.isConnected();
    }

    /**
     * Requests relay mode. The existing local media path remains owned by the
     * caller through RelayMediaSink.
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
        disconnect();
    }
}
