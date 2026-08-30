package com.fadcam.relay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class ServerRoomRelayAgentRecoveryTest {
    @Test
    public void transientPollFailureReconnectsWithoutLeavingRelayMode() throws Exception {
        RecoveringTransport transport = new RecoveringTransport();
        CountDownLatch handled = new CountDownLatch(1);
        ServerRoomRelayAgent agent = new ServerRoomRelayAgent(
                transport,
                payload -> { },
                request -> {
                    handled.countDown();
                    return new RelaySessionController.TunnelResponse(
                            request.getId(), 200, Collections.emptyMap(), new byte[0]);
                },
                () -> { throw new AssertionError("transient failure should have recovered"); });

        agent.enterRelayMode();

        assertTrue(handled.await(5, TimeUnit.SECONDS));
        assertEquals(ServerRoomRelayAgent.Mode.RELAY, agent.getMode());
        assertTrue(transport.connectCount.get() >= 2);
        assertTrue(transport.connected);

        agent.stop();
        assertTrue(!transport.connected);
    }

    private static final class RecoveringTransport implements ServerRoomRelayAgent.RelayTransport {
        final AtomicInteger connectCount = new AtomicInteger();
        boolean connected;
        int pollCount;

        @Override public void connect() {
            connectCount.incrementAndGet();
            connected = true;
        }

        @Override public void disconnect() { connected = false; }

        @Override public boolean isConnected() { return connected; }

        @Override public RelaySessionController.TunnelRequest poll() throws Exception {
            if (!connected) throw new IllegalStateException("disconnected");
            if (pollCount++ == 0) throw new IllegalStateException("simulated transient poll failure");
            Thread.sleep(10);
            return new RelaySessionController.TunnelRequest(
                    "request-1", "GET", "/stream.m3u8", "", Collections.emptyMap());
        }

        @Override public void respond(RelaySessionController.TunnelResponse response) {
            // no-op
        }
    }
}
