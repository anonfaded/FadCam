package com.fadcam.relay;

import java.util.Objects;

/**
 * Wire-level contract for a relay session request. HTTP/WebSocket/etc.
 * adapters serialize this same contract without changing session semantics.
 */
public final class RelaySessionProtocol {
    private RelaySessionProtocol() { }

    public enum Operation {
        CONNECT, AUTHENTICATE, REGISTER_SESSION, HEARTBEAT, POLL, MEDIA,
        ACKNOWLEDGE, RENEW, CLOSE
    }

    public static final class Request {
        private final String deviceId;
        private final String sessionId;
        private final long timestampMillis;
        private final long timeoutMillis;
        private final long sequence;
        private final String authentication;
        private final Operation operation;

        public Request(String deviceId, String sessionId, long timestampMillis,
                       long timeoutMillis, long sequence, String authentication,
                       Operation operation) {
            this.deviceId = require(deviceId, "deviceId");
            this.sessionId = require(sessionId, "sessionId");
            if (timestampMillis <= 0) throw new IllegalArgumentException("timestampMillis must be positive");
            if (timeoutMillis <= 0) throw new IllegalArgumentException("timeoutMillis must be positive");
            if (sequence < 0) throw new IllegalArgumentException("sequence must not be negative");
            this.timestampMillis = timestampMillis;
            this.timeoutMillis = timeoutMillis;
            this.sequence = sequence;
            this.authentication = require(authentication, "authentication");
            this.operation = Objects.requireNonNull(operation, "operation");
        }

        public String getDeviceId() { return deviceId; }
        public String getSessionId() { return sessionId; }
        public long getTimestampMillis() { return timestampMillis; }
        public long getTimeoutMillis() { return timeoutMillis; }
        public long getSequence() { return sequence; }
        public String getAuthentication() { return authentication; }
        public Operation getOperation() { return operation; }

        public boolean isExpired(long nowMillis) {
            return nowMillis - timestampMillis > timeoutMillis;
        }

        private static String require(String value, String name) {
            if (value == null || value.isEmpty()) throw new IllegalArgumentException(name + " must not be empty");
            return value;
        }
    }

    /** Rejects stale, duplicate, or out-of-order requests for one session. */
    public static final class ReplayGuard {
        private final long maxAgeMillis;
        private long highestSequence = -1;

        public ReplayGuard(long maxAgeMillis) {
            if (maxAgeMillis <= 0) throw new IllegalArgumentException("maxAgeMillis must be positive");
            this.maxAgeMillis = maxAgeMillis;
        }

        public synchronized void accept(Request request, long nowMillis) {
            Objects.requireNonNull(request, "request");
            long age = nowMillis - request.getTimestampMillis();
            if (age < 0 || age > maxAgeMillis || request.isExpired(nowMillis)) {
                throw new SecurityException("stale relay request");
            }
            if (request.getSequence() <= highestSequence) {
                throw new SecurityException("replayed or out-of-order relay request");
            }
            highestSequence = request.getSequence();
        }
    }
}
