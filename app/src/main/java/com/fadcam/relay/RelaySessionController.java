package com.fadcam.relay;

import java.util.Objects;
import java.util.UUID;

/**
 * Deterministic relay session lifecycle. A session has exactly one logical
 * identity and sequence space; reconnects either resume that session or
 * explicitly close it before a new session is created.
 */
public final class RelaySessionController {

    public enum State {
        DISCONNECTED,
        CONNECTING,
        AUTHENTICATING,
        REGISTERING,
        CONNECTED,
        DEGRADED,
        RECONNECTING,
        OFFLINE,
        CLOSED
    }

    public interface Clock {
        long nowMillis();
    }

    public interface SessionTransport {
        void connect() throws Exception;
        void disconnect();
        boolean isConnected();
        void send(RelaySessionProtocol.Request request) throws Exception;
    }

    private final String deviceId;
    private final String authentication;
    private final SessionTransport transport;
    private final Clock clock;
    private final long requestTimeoutMillis;
    private final long maxRequestAgeMillis;
    private final int maxReconnectAttempts;
    private final RelaySessionProtocol.ReplayGuard replayGuard;

    private State state = State.DISCONNECTED;
    private String sessionId;
    private long sequence;
    private int reconnectAttempts;
    private long lastHeartbeatMillis;

    public RelaySessionController(
            String deviceId,
            String authentication,
            SessionTransport transport,
            Clock clock,
            long requestTimeoutMillis,
            long maxRequestAgeMillis,
            int maxReconnectAttempts) {
        this.deviceId = require(deviceId, "deviceId");
        this.authentication = require(authentication, "authentication");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (requestTimeoutMillis <= 0) throw new IllegalArgumentException("requestTimeoutMillis must be positive");
        if (maxReconnectAttempts < 1) throw new IllegalArgumentException("maxReconnectAttempts must be positive");
        this.requestTimeoutMillis = requestTimeoutMillis;
        this.maxRequestAgeMillis = maxRequestAgeMillis;
        this.maxReconnectAttempts = maxReconnectAttempts;
        this.replayGuard = new RelaySessionProtocol.ReplayGuard(maxRequestAgeMillis);
    }

    public synchronized State getState() { return state; }
    public synchronized String getSessionId() { return sessionId; }
    public synchronized long getSequence() { return sequence; }

    public synchronized void connect() throws Exception {
        ensureNotClosed();
        if (state == State.CONNECTED) return;
        state = State.CONNECTING;
        transport.connect();
        if (!transport.isConnected()) {
            state = State.OFFLINE;
            throw new IllegalStateException("relay transport did not connect");
        }

        state = State.AUTHENTICATING;
        send(RelaySessionProtocol.Operation.AUTHENTICATE);

        state = State.REGISTERING;
        // Reuse an existing session when possible; otherwise create exactly one.
        if (sessionId == null) sessionId = UUID.randomUUID().toString();
        send(RelaySessionProtocol.Operation.REGISTER_SESSION);

        reconnectAttempts = 0;
        lastHeartbeatMillis = clock.nowMillis();
        state = State.CONNECTED;
    }

    public synchronized void heartbeat() throws Exception {
        requireState(State.CONNECTED, State.DEGRADED);
        send(RelaySessionProtocol.Operation.HEARTBEAT);
        lastHeartbeatMillis = clock.nowMillis();
        state = State.CONNECTED;
    }

    public synchronized void markHeartbeatTimeout() {
        if (state == State.CONNECTED) state = State.DEGRADED;
    }

    /**
     * Performs bounded reconnect attempts. It never creates a second session:
     * the current session ID is reused until explicitly closed.
     */
    public synchronized boolean reconnect() {
        ensureNotClosed();
        if (state == State.CONNECTED) return true;
        state = State.RECONNECTING;
        while (reconnectAttempts < maxReconnectAttempts) {
            reconnectAttempts++;
            try {
                transport.connect();
                if (!transport.isConnected()) continue;
                if (sessionId == null) sessionId = UUID.randomUUID().toString();
                send(RelaySessionProtocol.Operation.AUTHENTICATE);
                send(RelaySessionProtocol.Operation.REGISTER_SESSION);
                lastHeartbeatMillis = clock.nowMillis();
                state = State.CONNECTED;
                reconnectAttempts = 0;
                return true;
            } catch (Exception ignored) {
                transport.disconnect();
            }
        }
        state = State.OFFLINE;
        return false;
    }

    /** Explicitly terminates the current logical session. */
    public synchronized void close() throws Exception {
        if (sessionId != null && transport.isConnected()) {
            send(RelaySessionProtocol.Operation.CLOSE);
        }
        transport.disconnect();
        sessionId = null;
        sequence = 0;
        reconnectAttempts = 0;
        state = State.CLOSED;
    }

    private void send(RelaySessionProtocol.Operation operation) throws Exception {
        long now = clock.nowMillis();
        long nextSequence = ++sequence;
        RelaySessionProtocol.Request request = new RelaySessionProtocol.Request(
                deviceId, sessionId == null ? "pending" : sessionId,
                now, nextSequence, authentication, operation);
        replayGuard.accept(request, now);
        // Transport adapters enforce the actual timeout; the value is retained
        // here as the protocol contract for adapters and tests.
        if (requestTimeoutMillis <= 0) throw new IllegalStateException("invalid request timeout");
        transport.send(request);
    }

    private void requireState(State... allowed) {
        for (State candidate : allowed) if (state == candidate) return;
        throw new IllegalStateException("invalid relay state: " + state);
    }

    private void ensureNotClosed() {
        if (state == State.CLOSED) throw new IllegalStateException("relay session is closed");
    }

    private static String require(String value, String name) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException(name + " must not be empty");
        return value;
    }
}
