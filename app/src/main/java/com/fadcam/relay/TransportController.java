package com.fadcam.relay;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single owner of the DIRECT/RELAY transport state machine and media routing.
 *
 * <p>RECONNECTING and OFFLINE are intentionally non-authoritative states:
 * media must never fall through to the direct transport while relay recovery
 * is in progress or when no transport is available.</p>
 */
public final class TransportController implements MediaTransport {
    public enum State { DIRECT, RELAYING, RECONNECTING, OFFLINE }

    private final MediaTransport directTransport;
    private final MediaTransport relayTransport;
    private final AtomicReference<State> state = new AtomicReference<>(State.OFFLINE);

    public TransportController(MediaTransport directTransport, MediaTransport relayTransport) {
        this.directTransport = Objects.requireNonNull(directTransport, "directTransport");
        this.relayTransport = Objects.requireNonNull(relayTransport, "relayTransport");
    }

    public State getState() { return state.get(); }

    /** Direct becomes authoritative only after a complete successful connection. */
    public void useDirect() throws Exception {
        state.set(State.RECONNECTING);
        try {
            if (!directTransport.isConnected()) directTransport.connect();
            if (!directTransport.isConnected()) throw new IllegalStateException("Direct transport did not connect");
            relayTransport.disconnect();
            state.set(State.DIRECT);
        } catch (Exception failure) {
            state.set(State.RECONNECTING);
            throw failure;
        }
    }

    /** Relay becomes authoritative only after a complete successful connection. */
    public void failoverToRelay() throws Exception {
        state.set(State.RECONNECTING);
        try {
            relayTransport.connect();
            if (!relayTransport.isConnected()) throw new IllegalStateException("Relay transport did not connect");
            state.set(State.RELAYING);
        } catch (Exception failure) {
            relayTransport.disconnect();
            state.set(State.OFFLINE);
            throw failure;
        }
    }

    /** Invalidates relay immediately; no media path is authoritative afterwards. */
    public void markRelayFailure() {
        relayTransport.disconnect();
        state.set(State.RECONNECTING);
    }

    public void recoverToDirect() throws Exception { useDirect(); }

    @Override public void connect() throws Exception { useDirect(); }

    @Override public void disconnect() { stop(); }

    @Override
    public boolean isConnected() {
        State current = state.get();
        if (current == State.DIRECT) return directTransport.isConnected();
        if (current == State.RELAYING) return relayTransport.isConnected();
        return false;
    }

    @Override
    public void sendInitializationSegment(byte[] payload) throws Exception {
        requireMediaReady();
        authoritative().sendInitializationSegment(payload);
    }

    @Override
    public void sendFragment(int sequenceNumber, byte[] payload, long durationMs) throws Exception {
        requireMediaReady();
        authoritative().sendFragment(sequenceNumber, payload, durationMs);
    }

    public void stop() {
        directTransport.disconnect();
        relayTransport.disconnect();
        state.set(State.OFFLINE);
    }

    private MediaTransport authoritative() {
        State current = state.get();
        if (current == State.DIRECT) return directTransport;
        if (current == State.RELAYING) return relayTransport;
        throw new IllegalStateException("no authoritative media transport in state " + current);
    }

    private void requireMediaReady() {
        if (!isConnected()) {
            throw new IllegalStateException("authoritative media transport is not connected in state " + state.get());
        }
    }
}
