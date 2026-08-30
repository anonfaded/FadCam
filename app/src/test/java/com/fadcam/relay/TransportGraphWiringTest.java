package com.fadcam.relay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class TransportGraphWiringTest {
    @Test
    public void directRelayAndTeardownFormOneExecutableGraph() throws Exception {
        FakeSessionTransport sessionTransport = new FakeSessionTransport();
        RelaySessionController session = new RelaySessionController(
                "device-1", "auth-1", sessionTransport, System::currentTimeMillis,
                5000, 10000, 2);
        ServerRoomRelayAgent relay = new ServerRoomRelayAgent(session, payload -> { });
        LocalDirectMediaTransport direct = new LocalDirectMediaTransport();
        TransportController controller = new TransportController(direct, relay);

        assertEquals(TransportController.State.OFFLINE, controller.getState());

        controller.connect();
        assertEquals(TransportController.State.DIRECT, controller.getState());
        assertTrue(direct.isConnected());
        controller.sendInitializationSegment(new byte[] {1, 2});
        controller.sendFragment(1, new byte[] {3, 4}, 2000);

        controller.failoverToRelay();
        assertEquals(TransportController.State.RELAYING, controller.getState());
        assertTrue(sessionTransport.connected);
        controller.sendInitializationSegment(new byte[] {5});
        controller.sendFragment(2, new byte[] {6, 7}, 2000);
        assertEquals(3, sessionTransport.mediaRequests);

        controller.markRelayFailure();
        assertEquals(TransportController.State.RECONNECTING, controller.getState());
        assertFalse(sessionTransport.connected);
        assertThrows(IllegalStateException.class,
                () -> controller.sendFragment(3, new byte[] {8}, 2000));

        controller.recoverToDirect();
        assertEquals(TransportController.State.DIRECT, controller.getState());
        assertTrue(direct.isConnected());

        controller.stop();
        assertEquals(TransportController.State.OFFLINE, controller.getState());
        assertFalse(direct.isConnected());
        assertFalse(sessionTransport.connected);
    }

    private static final class FakeSessionTransport implements RelaySessionController.SessionTransport {
        boolean connected;
        int mediaRequests;

        @Override public void connect() { connected = true; }
        @Override public void disconnect() { connected = false; }
        @Override public boolean isConnected() { return connected; }

        @Override
        public void send(RelaySessionProtocol.Request request) {
            assertTrue(connected);
            if (request.getOperation() == RelaySessionProtocol.Operation.MEDIA) mediaRequests++;
        }
    }
}
