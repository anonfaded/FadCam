package com.fadcam.relay;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single owner of the DIRECT/RELAY transport state machine.
 *
 * <p>The controller deliberately contains no camera or Server Room media
 * implementation. Callers provide the existing direct and relay transports;
 * this class only decides which transport is authoritative.</p>
 */
public final class TransportController {

    public enum State {
        DIRECT,
        RELAYING,
        RECONNECTING
    }

    public interface Transport {
        void connect() throws Exception;
        void disconnect();
        boolean isConnected();
    }

    private final Transport directTransport;
    private final Transport relayTransport;
    private final AtomicReference<State> state = new AtomicReference<>(State.DIRECT);

    public TransportController(Transport directTransport, Transport relayTransport) {
        this.directTransport = Objects.requireNonNull(directTransport, "directTransport");
        this.relayTransport = Objects.requireNonNull(relayTransport, "relayTransport");
    }

    public State getState() {
        return state.get();
    }

    /** Records that direct connectivity is currently authoritative. */
    public void useDirect() {
        relayTransport.disconnect();
        state.set(State.DIRECT);
    }

    /**
     * Atomically enters the reconnecting phase before attempting relay. A
     * failed relay connection leaves the controller in DIRECT rather than
     * falsely claiming that relay is active.
     */
    public void failoverToRelay() throws Exception {
        state.set(State.RECONNECTING);
        try {
            relayTransport.connect();
            if (!relayTransport.isConnected()) {
                throw new IllegalStateException("Relay transport did not connect");
            }
            state.set(State.RELAYING);
        } catch (Exception failure) {
            state.set(State.DIRECT);
            throw failure;
        }
    }

    /**
     * Called after heartbeat/session failure. No media implementation is
     * touched here; the caller can retry failover through the same controller.
     */
    public void markRelayFailure() {
        relayTransport.disconnect();
        state.set(State.RECONNECTING);
    }

    /**
     * Completes recovery to direct connectivity. Direct becomes authoritative
     * only after its transport reports a successful connection.
     */
    public void recoverToDirect() throws Exception {
        state.set(State.RECONNECTING);
        try {
            directTransport.connect();
            if (!directTransport.isConnected()) {
                throw new IllegalStateException("Direct transport did not connect");
            }
            relayTransport.disconnect();
            state.set(State.DIRECT);
        } catch (Exception failure) {
            state.set(State.RECONNECTING);
            throw failure;
        }
    }

    public void stop() {
        directTransport.disconnect();
        relayTransport.disconnect();
        state.set(State.DIRECT);
    }
}
