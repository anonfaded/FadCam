package com.fadcam.relay;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single owner of the DIRECT/RELAY transport state machine and media routing.
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

    private final MediaTransport directTransport;
    private final MediaTransport relayTransport;
    private final AtomicReference<State> state = new AtomicReference<>(State.DIRECT);

    public TransportController(MediaTransport directTransport, MediaTransport relayTransport) {
        this.directTransport = Objects.requireNonNull(directTransport, "directTransport");
        this.relayTransport = Objects.requireNonNull(relayTransport, "relayTransport");
    }

    /** Compatibility constructor for callers that only need connectivity state. */
    public TransportController(Transport directTransport, Transport relayTransport) {
        this(asMediaTransport(directTransport), asMediaTransport(relayTransport));
    }

    public State getState() {
        return state.get();
    }

    /** Direct becomes authoritative only after it is already connected. */
    public void useDirect() throws Exception {
        state.set(State.RECONNECTING);
        if (!directTransport.isConnected()) {
            directTransport.connect();
        }
        if (!directTransport.isConnected()) {
            throw new IllegalStateException("Direct transport did not connect");
        }
        relayTransport.disconnect();
        state.set(State.DIRECT);
    }

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

    public void markRelayFailure() {
        relayTransport.disconnect();
        state.set(State.RECONNECTING);
    }

    public void recoverToDirect() throws Exception {
        useDirect();
    }

    /** Routes the initialization segment through the authoritative transport. */
    public void sendInitializationSegment(byte[] payload) throws Exception {
        requireMediaReady();
        authoritative().sendInitializationSegment(payload);
    }

    /** Routes a media fragment through the authoritative transport. */
    public void sendFragment(int sequenceNumber, byte[] payload, long durationMs) throws Exception {
        requireMediaReady();
        authoritative().sendFragment(sequenceNumber, payload, durationMs);
    }

    public void stop() {
        directTransport.disconnect();
        relayTransport.disconnect();
        state.set(State.DIRECT);
    }

    private MediaTransport authoritative() {
        return state.get() == State.RELAYING ? relayTransport : directTransport;
    }

    private void requireMediaReady() {
        if (!authoritative().isConnected()) {
            throw new IllegalStateException("authoritative media transport is not connected");
        }
    }

    private static MediaTransport asMediaTransport(final Transport transport) {
        Objects.requireNonNull(transport, "transport");
        if (transport instanceof MediaTransport) {
            return (MediaTransport) transport;
        }
        return new MediaTransport() {
            @Override public void connect() throws Exception { transport.connect(); }
            @Override public void disconnect() { transport.disconnect(); }
            @Override public boolean isConnected() { return transport.isConnected(); }
            @Override public void sendInitializationSegment(byte[] payload) { requirePayload(payload); }
            @Override public void sendFragment(int sequenceNumber, byte[] payload, long durationMs) { requirePayload(payload); }
        };
    }
}
