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

    public static final class TunnelRequest {
        private final String id;
        private final String method;
        private final String path;
        private final String query;
        private final java.util.Map<String, String> headers;

        public TunnelRequest(String id, String method, String path, String query,
                             java.util.Map<String, String> headers) {
            this.id = require(id, "id");
            this.method = require(method, "method");
            this.path = require(path, "path");
            this.query = query == null ? "" : query;
            this.headers = headers == null
                    ? java.util.Collections.emptyMap()
                    : java.util.Collections.unmodifiableMap(new java.util.HashMap<>(headers));
        }

        public String getId() { return id; }
        public String getMethod() { return method; }
        public String getPath() { return path; }
        public String getQuery() { return query; }
        public java.util.Map<String, String> getHeaders() { return headers; }
    }

    public static final class TunnelResponse {
        private final String id;
        private final int status;
        private final java.util.Map<String, String> headers;
        private final byte[] body;

        public TunnelResponse(String id, int status, java.util.Map<String, String> headers, byte[] body) {
            this.id = require(id, "id");
            if (status < 100 || status > 599) throw new IllegalArgumentException("invalid status");
            this.status = status;
            this.headers = headers == null
                    ? java.util.Collections.emptyMap()
                    : java.util.Collections.unmodifiableMap(new java.util.HashMap<>(headers));
            this.body = Objects.requireNonNull(body, "body").clone();
        }

        public String getId() { return id; }
        public int getStatus() { return status; }
        public java.util.Map<String, String> getHeaders() { return headers; }
        public byte[] getBody() { return body.clone(); }
    }

    public interface SessionTransport {
        void connect() throws Exception;
        void disconnect();
        boolean isConnected();
        void send(RelaySessionProtocol.Request request) throws Exception;
        default TunnelRequest poll() throws Exception {
            throw new UnsupportedOperationException("relay tunnel polling is not configured");
        }
        default void respond(TunnelResponse response) throws Exception {
            throw new UnsupportedOperationException("relay tunnel responses are not configured");
        }
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

    @Override
    public synchronized TunnelRequest poll() throws Exception {
        requireState(State.CONNECTED);
        return transport.poll();
    }

    @Override
    public synchronized void respond(TunnelResponse response) throws Exception {
        requireState(State.CONNECTED);
        transport.respond(Objects.requireNonNull(response, "response"));
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

    /** Disconnects the physical transport without terminally closing the logical session. */
    @Override
    public synchronized void disconnect() {
        transport.disconnect();
        if (state != State.CLOSED) state = State.DISCONNECTED;
    }

    /** Explicitly terminates the logical session and releases its identity. */
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
