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

    /** Legacy connectivity-only contract retained for existing JVM callers. */
    public interface Transport {
        void connect() throws Exception;
        void disconnect();
        boolean isConnected();
    }

    private final MediaTransport directTransport;
    private final MediaTransport relayTransport;
    private final AtomicReference<State> state = new AtomicReference<>(State.OFFLINE);
    private volatile Thread relayPromotionThread;

    public TransportController(MediaTransport directTransport, MediaTransport relayTransport) {
        this.directTransport = Objects.requireNonNull(directTransport, "directTransport");
        this.relayTransport = Objects.requireNonNull(relayTransport, "relayTransport");
    }

    /** Compatibility constructor for connectivity-only transports. */
    public TransportController(Transport directTransport, Transport relayTransport) {
        this(asMediaTransport(directTransport), asMediaTransport(relayTransport));
    }

    public State getState() { return state.get(); }

    /**
     * Establish the immediately available direct path, then asynchronously
     * promote to the authenticated Server Room relay when it is configured and
     * reachable. This is the production-safe construction path because
     * RemoteStreamService invokes connect() from Android's service lifecycle;
     * network authentication must never block that thread.
     */
    @Override
    public void connect() throws Exception {
        useDirect();
        startRelayPromotion();
    }

    /** Direct becomes authoritative only after a complete successful connection. */
    public void useDirect() throws Exception {
        state.set(State.RECONNECTING);
        try {
            relayTransport.disconnect();
            if (!directTransport.isConnected()) directTransport.connect();
            if (!directTransport.isConnected()) {
                throw new IllegalStateException("Direct transport did not connect");
            }
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
            if (!relayTransport.isConnected()) {
                throw new IllegalStateException("Relay transport did not connect");
            }
            directTransport.disconnect();
            state.set(State.RELAYING);
        } catch (Exception failure) {
            if (relayTransport.isConnected()) relayTransport.disconnect();
            if (directTransport.isConnected()) state.set(State.DIRECT);
            else state.set(State.OFFLINE);
            throw failure;
        }
    }

    /** Invalidates relay immediately; no media path is authoritative afterwards. */
    public void markRelayFailure() {
        relayTransport.disconnect();
        state.set(State.RECONNECTING);
    }

    public void recoverToDirect() throws Exception { useDirect(); }

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

    public synchronized void stop() {
        Thread promotion = relayPromotionThread;
        relayPromotionThread = null;
        if (promotion != null) promotion.interrupt();
        directTransport.disconnect();
        relayTransport.disconnect();
        state.set(State.OFFLINE);
    }

    private synchronized void startRelayPromotion() {
        if (relayPromotionThread != null || state.get() != State.DIRECT) return;
        Thread promotion = new Thread(() -> {
            try {
                if (state.get() != State.DIRECT) return;
                relayTransport.connect();
                if (!relayTransport.isConnected()) return;
                if (state.compareAndSet(State.DIRECT, State.RELAYING)) {
                    directTransport.disconnect();
                } else {
                    relayTransport.disconnect();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                relayTransport.disconnect();
            } catch (Exception ignored) {
                relayTransport.disconnect();
            } finally {
                synchronized (this) {
                    relayPromotionThread = null;
                }
            }
        }, "FadCam-RelayPromotion");
        promotion.setDaemon(true);
        relayPromotionThread = promotion;
        promotion.start();
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

    private static MediaTransport asMediaTransport(final Transport transport) {
        Objects.requireNonNull(transport, "transport");
        if (transport instanceof MediaTransport) return (MediaTransport) transport;
        return new MediaTransport() {
            @Override public void connect() throws Exception { transport.connect(); }
            @Override public void disconnect() { transport.disconnect(); }
            @Override public boolean isConnected() { return transport.isConnected(); }
            @Override public void sendInitializationSegment(byte[] payload) throws Exception {
                requirePayload(payload);
                throw new UnsupportedOperationException("connectivity-only transport cannot send media");
            }
            @Override public void sendFragment(int sequenceNumber, byte[] payload, long durationMs) throws Exception {
                requirePayload(payload);
                throw new UnsupportedOperationException("connectivity-only transport cannot send media");
            }
        };
    }
}
