package com.fadcam.relay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class TransportControllerTest {

    @Test
    public void startsOfflineUntilDirectTransportConnects() throws Exception {
        FakeTransport direct = new FakeTransport();
        FakeTransport relay = new FakeTransport();
        TransportController controller = new TransportController(direct, relay);

        assertEquals(TransportController.State.OFFLINE, controller.getState());
        assertThrows(IllegalStateException.class,
                () -> controller.sendFragment(1, new byte[] {1}, 2000));

        controller.connect();

        assertEquals(TransportController.State.DIRECT, controller.getState());
        assertEquals(1, direct.connectCalls);
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
    public void failedRelayBecomesOfflineAndNeverFallsThroughToDirect() {
        FakeTransport direct = new FakeTransport();
        FakeTransport relay = new FakeTransport();
        relay.connectResult = false;
        TransportController controller = new TransportController(direct, relay);

        assertThrows(Exception.class, controller::failoverToRelay);
        assertEquals(TransportController.State.OFFLINE, controller.getState());
        assertEquals(0, direct.connectCalls);
        assertThrows(IllegalStateException.class,
                () -> controller.sendFragment(1, new byte[] {1}, 2000));
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
        assertThrows(IllegalStateException.class,
                () -> controller.sendFragment(1, new byte[] {1}, 2000));
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
        assertThrows(IllegalStateException.class,
                () -> controller.sendFragment(1, new byte[] {1}, 2000));
    }

    private static final class FakeTransport implements TransportController.Transport {
        int connectCalls;
        int disconnectCalls;
        boolean connected;
        boolean connectResult = true;

        @Override
        public void connect() throws Exception {
            connectCalls++;
            if (!connectResult) throw new Exception("connection failed");
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
