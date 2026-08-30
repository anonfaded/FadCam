package com.fadcam.relay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class RelaySessionControllerTest {

    @Test
    public void connectAuthenticatesRegistersAndStartsHeartbeat() throws Exception {
        FakeClock clock = new FakeClock(1_000);
        FakeTransport transport = new FakeTransport();
        RelaySessionController controller = controller(transport, clock);

        controller.connect();

        assertEquals(RelaySessionController.State.CONNECTED, controller.getState());
        assertEquals(3, transport.requests.size());
        assertEquals(RelaySessionProtocol.Operation.AUTHENTICATE, transport.requests.get(0).getOperation());
        assertEquals(RelaySessionProtocol.Operation.REGISTER_SESSION, transport.requests.get(1).getOperation());
        assertEquals(1L, transport.requests.get(0).getSequence());
        assertEquals(2L, transport.requests.get(1).getSequence());
    }

    @Test
    public void heartbeatTimeoutProducesDegradedStateAndSuccessfulHeartbeatRecovers() throws Exception {
        FakeClock clock = new FakeClock(1_000);
        FakeTransport transport = new FakeTransport();
        RelaySessionController controller = controller(transport, clock);
        controller.connect();

        controller.markHeartbeatTimeout();
        assertEquals(RelaySessionController.State.DEGRADED, controller.getState());

        controller.heartbeat();
        assertEquals(RelaySessionController.State.CONNECTED, controller.getState());
        assertEquals(RelaySessionProtocol.Operation.HEARTBEAT,
                transport.requests.get(3).getOperation());
    }

    @Test
    public void reconnectReusesTheSameSessionAndDoesNotCreateDuplicateLogicalSession() throws Exception {
        FakeClock clock = new FakeClock(1_000);
        FakeTransport transport = new FakeTransport();
        RelaySessionController controller = controller(transport, clock);
        controller.connect();
        String session = controller.getSessionId();
        transport.disconnect();
        controller.markHeartbeatTimeout();

        assertTrue(controller.reconnect());

        assertEquals(RelaySessionController.State.CONNECTED, controller.getState());
        assertEquals(session, controller.getSessionId());
        assertEquals(1, transport.connectionCount);
        assertEquals(2, transport.connectCalls);
        assertEquals(RelaySessionProtocol.Operation.AUTHENTICATE, transport.requests.get(3).getOperation());
        assertEquals(RelaySessionProtocol.Operation.REGISTER_SESSION, transport.requests.get(4).getOperation());
        assertEquals(5L, transport.requests.get(4).getSequence());
    }

    @Test
    public void boundedReconnectEndsOffline() throws Exception {
        FakeClock clock = new FakeClock(1_000);
        FakeTransport transport = new FakeTransport();
        transport.connectResult = false;
        RelaySessionController controller = controller(transport, clock);

        assertFalse(controller.reconnect());
        assertEquals(RelaySessionController.State.OFFLINE, controller.getState());
        assertEquals(3, transport.connectCalls);
    }

    @Test
    public void closeExplicitlyTerminatesSession() throws Exception {
        FakeClock clock = new FakeClock(1_000);
        FakeTransport transport = new FakeTransport();
        RelaySessionController controller = controller(transport, clock);
        controller.connect();

        controller.close();

        assertEquals(RelaySessionController.State.CLOSED, controller.getState());
        assertEquals(null, controller.getSessionId());
        assertEquals(RelaySessionProtocol.Operation.CLOSE,
                transport.requests.get(3).getOperation());
    }

    @Test
    public void replayGuardRejectsDuplicateAndLateRequests() {
        RelaySessionProtocol.ReplayGuard guard = new RelaySessionProtocol.ReplayGuard(100);
        RelaySessionProtocol.Request first = request(1, 1_000);

        guard.accept(first, 1_050);
        assertThrows(SecurityException.class, () -> guard.accept(first, 1_050));
        assertThrows(SecurityException.class, () -> guard.accept(request(2, 1_000), 1_101));
    }

    @Test
    public void requestContainsIdentitySessionTimestampSequenceAndAuthentication() throws Exception {
        FakeClock clock = new FakeClock(5_000);
        FakeTransport transport = new FakeTransport();
        RelaySessionController controller = controller(transport, clock);

        controller.connect();

        RelaySessionProtocol.Request request = transport.requests.get(0);
        assertEquals("device-1", request.getDeviceId());
        assertEquals("pending", request.getSessionId());
        assertEquals(5_000L, request.getTimestampMillis());
        assertEquals(1L, request.getSequence());
        assertEquals("auth-token", request.getAuthentication());
    }

    private static RelaySessionController controller(FakeTransport transport, FakeClock clock) {
        return new RelaySessionController(
                "device-1", "auth-token", transport, clock,
                5_000, 30_000, 3);
    }

    private static RelaySessionProtocol.Request request(long sequence, long timestamp) {
        return new RelaySessionProtocol.Request(
                "device-1", "session-1", timestamp, sequence,
                "auth-token", RelaySessionProtocol.Operation.HEARTBEAT);
    }

    private static final class FakeClock implements RelaySessionController.Clock {
        private long now;
        FakeClock(long now) { this.now = now; }
        @Override public long nowMillis() { return now; }
    }

    private static final class FakeTransport implements RelaySessionController.SessionTransport {
        final List<RelaySessionProtocol.Request> requests = new ArrayList<>();
        boolean connected;
        boolean connectResult = true;
        int connectCalls;
        int connectionCount;

        @Override public void connect() throws Exception {
            connectCalls++;
            if (!connectResult) throw new Exception("network unavailable");
            connected = true;
            connectionCount++;
        }

        @Override public void disconnect() { connected = false; }

        @Override public boolean isConnected() { return connected; }

        @Override public void send(RelaySessionProtocol.Request request) {
            assertNotEquals(null, request.getAuthentication());
            requests.add(request);
        }
    }
}
