package com.fadcam.relay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class TransportControllerTest {

    @Test
    public void startsInDirectState() {
        FakeTransport direct = new FakeTransport();
        FakeTransport relay = new FakeTransport();
        TransportController controller = new TransportController(direct, relay);

        assertEquals(TransportController.State.DIRECT, controller.getState());
        assertEquals(0, direct.connectCalls);
        assertEquals(0, relay.connectCalls);
    }

    @Test
    public void failoverUsesRelayAndOnlyThenBecomesRelaying() throws Exception {
        FakeTransport direct = new FakeTransport();
        FakeTransport relay = new FakeTransport();
        TransportController controller = new TransportController(direct, relay);

        controller.failoverToRelay();

        assertEquals(TransportController.State.RELAYING, controller.getState());
        assertEquals(1, relay.connectCalls);
        assertEquals(0, direct.connectCalls);
    }

    @Test
    public void failedRelayDoesNotPretendToBeRelaying() {
        FakeTransport direct = new FakeTransport();
        FakeTransport relay = new FakeTransport();
        relay.connectResult = false;
        TransportController controller = new TransportController(direct, relay);

        assertThrows(Exception.class, controller::failoverToRelay);
        assertEquals(TransportController.State.DIRECT, controller.getState());
    }

    @Test
    public void heartbeatFailureMovesToReconnectingAndDisconnectsRelay() throws Exception {
        FakeTransport direct = new FakeTransport();
        FakeTransport relay = new FakeTransport();
        TransportController controller = new TransportController(direct, relay);
        controller.failoverToRelay();

        controller.markRelayFailure();

        assertEquals(TransportController.State.RECONNECTING, controller.getState());
        assertEquals(1, relay.disconnectCalls);
    }

    @Test
    public void successfulRecoveryMakesDirectAuthoritative() throws Exception {
        FakeTransport direct = new FakeTransport();
        FakeTransport relay = new FakeTransport();
        TransportController controller = new TransportController(direct, relay);
        controller.failoverToRelay();

        controller.recoverToDirect();

        assertEquals(TransportController.State.DIRECT, controller.getState());
        assertEquals(1, direct.connectCalls);
        assertEquals(1, relay.disconnectCalls);
    }

    @Test
    public void failedDirectRecoveryRemainsReconnecting() throws Exception {
        FakeTransport direct = new FakeTransport();
        FakeTransport relay = new FakeTransport();
        TransportController controller = new TransportController(direct, relay);
        controller.failoverToRelay();
        controller.markRelayFailure();
        direct.connectResult = false;

        assertThrows(Exception.class, controller::recoverToDirect);
        assertEquals(TransportController.State.RECONNECTING, controller.getState());
    }

    private static final class FakeTransport implements TransportController.Transport {
        int connectCalls;
        int disconnectCalls;
        boolean connected;
        boolean connectResult = true;

        @Override
        public void connect() throws Exception {
            connectCalls++;
            if (!connectResult) {
                throw new Exception("connection failed");
            }
            connected = true;
        }

        @Override
        public void disconnect() {
            disconnectCalls++;
            connected = false;
        }

        @Override
        public boolean isConnected() {
            return connected;
        }
    }
}
