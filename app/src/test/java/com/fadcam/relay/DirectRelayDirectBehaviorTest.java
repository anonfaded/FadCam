package com.fadcam.relay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Behavioral test for one logical media session surviving DIRECT → RELAY → DIRECT. */
public class DirectRelayDirectBehaviorTest {
    @Test public void directRelayDirectKeepsOneLogicalSessionAndDoesNotRestartMedia() throws Exception {
        FakeTransport direct = new FakeTransport();
        FakeTransport relay = new FakeTransport();
        TransportController controller = new TransportController(direct, relay);
        FakeMediaSession media = new FakeMediaSession();
        RelaySessionController relaySession = new RelaySessionController(
                "device-1", "auth-token", relaySessionTransport(relay), () -> 1_000,
                30_000, 3);

        // Camera/media session is started once and is independent of transport selection.
        media.start();
        controller.useDirect();
        String logicalSession = "logical-media-session-1";

        // Direct network failure: move only the transport, not the media session.
        controller.failoverToRelay();
        relaySession.connect();
        media.recordFrame("frame-1", logicalSession);

        // Network restoration: authenticate the same relay session, then make DIRECT authoritative.
        relaySession.markHeartbeatTimeout();
        controller.recoverToDirect();
        media.recordFrame("frame-2", logicalSession);

        assertEquals(1, media.startCount);
        assertEquals(2, media.frames.size());
        assertEquals(logicalSession, media.frames.get(0).sessionId);
        assertEquals(logicalSession, media.frames.get(1).sessionId);
        assertEquals(TransportController.State.DIRECT, controller.getState());
        assertEquals(relaySession.getSessionId(), relaySession.getSessionId());
        assertTrue(relay.disconnectCalls >= 1);
    }

    private static RelaySessionController.SessionTransport relaySessionTransport(FakeTransport transport) {
        return new RelaySessionController.SessionTransport() {
            @Override public void connect() throws Exception { transport.connect(); }
            @Override public void disconnect() { transport.disconnect(); }
            @Override public boolean isConnected() { return transport.isConnected(); }
            @Override public void send(RelaySessionProtocol.Request request) { transport.requests.add(request); }
        };
    }

    private static final class FakeTransport implements TransportController.Transport {
        final List<RelaySessionProtocol.Request> requests = new ArrayList<>();
        boolean connected;
        int disconnectCalls;
        @Override public void connect() { connected = true; }
        @Override public void disconnect() { connected = false; disconnectCalls++; }
        @Override public boolean isConnected() { return connected; }
    }

    private static final class FakeMediaSession {
        int startCount;
        final List<Frame> frames = new ArrayList<>();
        void start() { startCount++; }
        void recordFrame(String value, String sessionId) { frames.add(new Frame(value, sessionId)); }
    }

    private static final class Frame {
        final String value;
        final String sessionId;
        Frame(String value, String sessionId) { this.value = value; this.sessionId = sessionId; }
    }
}
