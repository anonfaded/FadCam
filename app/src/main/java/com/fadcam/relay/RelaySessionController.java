package com.fadcam.relay;

import java.util.Objects;
import java.util.UUID;

/** Deterministic relay session lifecycle with bounded reconnects. */
public final class RelaySessionController implements ServerRoomRelayAgent.RelayTransport {
    public enum State {
        DISCONNECTED, CONNECTING, AUTHENTICATING, REGISTERING,
        CONNECTED, DEGRADED, RECONNECTING, OFFLINE, CLOSED
    }

    public interface Clock { long nowMillis(); }

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
    private final int maxReconnectAttempts;
    private final RelaySessionProtocol.ReplayGuard replayGuard;

    private State state = State.DISCONNECTED;
    private String sessionId;
    private long sequence;
    private int reconnectAttempts;
    private long lastHeartbeatMillis;

    public RelaySessionController(String deviceId, String authentication,
                                  SessionTransport transport, Clock clock,
                                  long requestTimeoutMillis, long maxRequestAgeMillis,
                                  int maxReconnectAttempts) {
        this.deviceId = require(deviceId, "deviceId");
        this.authentication = require(authentication, "authentication");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (requestTimeoutMillis <= 0) throw new IllegalArgumentException("requestTimeoutMillis must be positive");
        if (maxReconnectAttempts < 1) throw new IllegalArgumentException("maxReconnectAttempts must be positive");
        this.requestTimeoutMillis = requestTimeoutMillis;
        this.maxReconnectAttempts = maxReconnectAttempts;
        this.replayGuard = new RelaySessionProtocol.ReplayGuard(maxRequestAgeMillis);
    }

    public synchronized State getState() { return state; }
    public synchronized String getSessionId() { return sessionId; }
    public synchronized long getSequence() { return sequence; }
    public synchronized long getLastHeartbeatMillis() { return lastHeartbeatMillis; }

    @Override
    public synchronized void connect() throws Exception {
        ensureNotClosed();
        if (state == State.CONNECTED) return;
        if (sessionId == null) sessionId = UUID.randomUUID().toString();
        state = State.CONNECTING;
        transport.connect();
        if (!transport.isConnected()) {
            state = State.OFFLINE;
            throw new IllegalStateException("relay transport did not connect");
        }
        state = State.AUTHENTICATING;
        send(RelaySessionProtocol.Operation.AUTHENTICATE);
        state = State.REGISTERING;
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

    public synchronized void poll() throws Exception {
        requireState(State.CONNECTED);
        send(RelaySessionProtocol.Operation.POLL);
    }

    public synchronized void acknowledge() throws Exception {
        requireState(State.CONNECTED);
        send(RelaySessionProtocol.Operation.ACKNOWLEDGE);
    }

    public synchronized void renew() throws Exception {
        requireState(State.CONNECTED);
        send(RelaySessionProtocol.Operation.RENEW);
    }

    public synchronized void sendInitializationSegment(byte[] payload) throws Exception {
        sendMediaFrame(0, payload, 1, "video/mp4");
    }

    @Override
    public synchronized void sendMedia(int sequenceNumber, byte[] payload, long durationMs) throws Exception {
        sendMediaFrame(sequenceNumber, payload, durationMs, "video/iso.segment");
    }

    private void sendMediaFrame(int mediaSequence, byte[] payload, long durationMs, String mediaType) throws Exception {
        requireState(State.CONNECTED);
        Objects.requireNonNull(payload, "payload");
        if (durationMs <= 0) throw new IllegalArgumentException("durationMs must be positive");
        long now = clock.nowMillis();
        RelaySessionProtocol.Request request = RelaySessionProtocol.Request.media(
                deviceId, sessionId, now, requestTimeoutMillis, ++sequence, authentication,
                now, durationMs, mediaType, payload);
        replayGuard.accept(request, now);
        transport.send(request);
    }

    public synchronized void markHeartbeatTimeout() {
        if (state == State.CONNECTED) state = State.DEGRADED;
    }

    /** Reconnects with a bounded retry count and the same logical session ID. */
    public synchronized boolean reconnect() {
        ensureNotClosed();
        if (state == State.CONNECTED) return true;
        state = State.RECONNECTING;
        while (reconnectAttempts < maxReconnectAttempts) {
            reconnectAttempts++;
            try {
                transport.connect();
                if (!transport.isConnected()) continue;
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

    /** Explicitly closes the logical session; only this operation clears its ID. */
    @Override
    public synchronized void disconnect() {
        try {
            close();
        } catch (Exception ignored) {
            transport.disconnect();
            sessionId = null;
            state = State.CLOSED;
        }
    }

    public synchronized void close() throws Exception {
        if (state == State.CLOSED) return;
        if (sessionId != null && transport.isConnected()) send(RelaySessionProtocol.Operation.CLOSE);
        transport.disconnect();
        sessionId = null;
        sequence = 0;
        reconnectAttempts = 0;
        state = State.CLOSED;
    }

    @Override
    public synchronized boolean isConnected() {
        return state == State.CONNECTED && transport.isConnected();
    }

    private void send(RelaySessionProtocol.Operation operation) throws Exception {
        long now = clock.nowMillis();
        RelaySessionProtocol.Request request = new RelaySessionProtocol.Request(
                deviceId, sessionId, now, requestTimeoutMillis, ++sequence,
                authentication, operation);
        replayGuard.accept(request, now);
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
