package com.fadcam.relay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class TransportControllerTest {

    @Test
    public void startsDirectThenPromotesToRelayWithoutBlocking() throws Exception {
        FakeTransport direct = new FakeTransport();
        FakeTransport relay = new FakeTransport();
        TransportController controller = new TransportController(direct, relay);

        try {
            assertEquals(TransportController.State.OFFLINE, controller.getState());
            assertThrows(IllegalStateException.class,
                    () -> controller.sendFragment(1, new byte[] {1}, 2000));

            controller.connect();

            assertEquals(TransportController.State.DIRECT, controller.getState());
            assertTrue(direct.connected);
            awaitState(controller, TransportController.State.RELAYING);
            assertTrue(relay.connected);
            assertFalse(direct.connected);
        } finally {
            controller.stop();
        }
    }

    @Test
    public void productionConnectStaysDirectWhenRelayUnavailable() throws Exception {
        FakeTransport direct = new FakeTransport();
        FakeTransport relay = new FakeTransport();
        relay.connectResult = false;
        TransportController controller = new TransportController(direct, relay);

        try {
            controller.connect();

            awaitRelayAttempt(relay);
            assertEquals(TransportController.State.DIRECT, controller.getState());
            assertEquals(1, relay.connectCalls);
            assertEquals(1, direct.connectCalls);
            assertTrue(direct.connected);
        } finally {
            controller.stop();
        }
    }

    @Test
    public void failoverUsesRelayAndDisconnectsDirectBeforeBecomingRelaying() throws Exception {
        FakeTransport direct = new FakeTransport();
        FakeTransport relay = new FakeTransport();
        TransportController controller = new TransportController(direct, relay);
        try {
            controller.useDirect();

            controller.failoverToRelay();

            assertEquals(TransportController.State.RELAYING, controller.getState());
            assertEquals(1, relay.connectCalls);
            assertEquals(1, direct.disconnectCalls);
            assertFalse(direct.connected);
        } finally {
            controller.stop();
        }
    }

    @Test
    public void failedRelayWithoutDirectBecomesOfflineAndNeverFallsThroughToDirect() {
        FakeTransport direct = new FakeTransport();
        FakeTransport relay = new FakeTransport();
        relay.connectResult = false;
        TransportController controller = new TransportController(direct, relay);

        try {
            assertThrows(Exception.class, controller::failoverToRelay);
            assertEquals(TransportController.State.OFFLINE, controller.getState());
            assertEquals(0, direct.connectCalls);
            assertThrows(IllegalStateException.class,
                    () -> controller.sendFragment(1, new byte[] {1}, 2000));
        } finally {
            controller.stop();
        }
    }

    @Test
    public void failedRelayPreservesExistingDirectAuthority() throws Exception {
        FakeTransport direct = new FakeTransport();
        FakeTransport relay = new FakeTransport();
        relay.connectResult = false;
        TransportController controller = new TransportController(direct, relay);
        try {
            controller.useDirect();

            assertThrows(Exception.class, controller::failoverToRelay);

            assertEquals(TransportController.State.DIRECT, controller.getState());
            assertEquals(1, direct.connectCalls);
            assertEquals(1, relay.disconnectCalls);
            assertEquals(0, direct.disconnectCalls);
        } finally {
            controller.stop();
        }
    }

    @Test
    public void heartbeatFailureMovesToReconnectingAndDisconnectsRelay() throws Exception {
        FakeTransport direct = new FakeTransport();
        FakeTransport relay = new FakeTransport();
        TransportController controller = new TransportController(direct, relay);
        try {
            controller.failoverToRelay();

            controller.markRelayFailure();

            assertEquals(TransportController.State.RECONNECTING, controller.getState());
            assertEquals(1, relay.disconnectCalls);
            assertThrows(IllegalStateException.class,
                    () -> controller.sendFragment(1, new byte[] {1}, 2000));
        } finally {
            controller.stop();
        }
    }

    @Test
    public void successfulRecoveryMakesDirectAuthoritative() throws Exception {
        FakeTransport direct = new FakeTransport();
        FakeTransport relay = new FakeTransport();
        TransportController controller = new TransportController(direct, relay);
        try {
            controller.failoverToRelay();

            controller.recoverToDirect();

            assertEquals(TransportController.State.DIRECT, controller.getState());
            assertEquals(1, direct.connectCalls);
            assertEquals(1, relay.disconnectCalls);
        } finally {
            controller.stop();
        }
    }

    @Test
    public void failedDirectRecoveryRemainsReconnecting() throws Exception {
        FakeTransport direct = new FakeTransport();
        FakeTransport relay = new FakeTransport();
        TransportController controller = new TransportController(direct, relay);
        try {
            controller.failoverToRelay();
            controller.markRelayFailure();
            direct.connectResult = false;

            assertThrows(Exception.class, controller::recoverToDirect);
            assertEquals(TransportController.State.RECONNECTING, controller.getState());
            assertThrows(IllegalStateException.class,
                    () -> controller.sendFragment(1, new byte[] {1}, 2000));
        } finally {
            controller.stop();
        }
    }

    private static void awaitState(TransportController controller, TransportController.State expected)
            throws Exception {
        long deadline = System.currentTimeMillis() + 2000L;
        while (System.currentTimeMillis() < deadline) {
            if (controller.getState() == expected) return;
            Thread.sleep(10L);
        }
        assertEquals(expected, controller.getState());
    }

    private static void awaitRelayAttempt(FakeTransport relay) throws Exception {
        long deadline = System.currentTimeMillis() + 2000L;
        while (System.currentTimeMillis() < deadline) {
            if (relay.connectCalls > 0) return;
            Thread.sleep(10L);
        }
        assertEquals(1, relay.connectCalls);
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
