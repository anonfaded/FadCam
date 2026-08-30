package com.fadcam.relay;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class ServerRoomRelayAgentTest {

    @Test
    public void startsInDirectModeWithoutConnecting() {
        FakeTransport transport = new FakeTransport();
        ServerRoomRelayAgent agent = new ServerRoomRelayAgent(transport, payload -> { });

        agent.start();

        assertEquals(ServerRoomRelayAgent.Mode.DIRECT, agent.getMode());
        assertEquals(0, transport.connectCalls);
    }

    @Test
    public void entersRelayModeOnlyAfterSuccessfulTransportConnection() throws Exception {
        FakeTransport transport = new FakeTransport();
        ServerRoomRelayAgent agent = new ServerRoomRelayAgent(transport, payload -> { });

        agent.enterRelayMode();

        assertEquals(ServerRoomRelayAgent.Mode.RELAY, agent.getMode());
        assertEquals(1, transport.connectCalls);
    }

    @Test
    public void rejectsRelayMediaOutsideRelayMode() {
        ServerRoomRelayAgent agent = new ServerRoomRelayAgent(new FakeTransport(), payload -> { });

        assertThrows(IllegalStateException.class, () -> agent.deliverRelayMedia(new byte[] {1}));
    }

    @Test
    public void deliversA defensiveCopyAcrossMediaBoundary() throws Exception {
        FakeTransport transport = new FakeTransport();
        byte[][] received = new byte[1][];
        ServerRoomRelayAgent agent = new ServerRoomRelayAgent(transport, payload -> received[0] = payload);
        agent.enterRelayMode();

        byte[] source = new byte[] {1, 2, 3};
        agent.deliverRelayMedia(source);
        source[0] = 9;

        assertArrayEquals(new byte[] {1, 2, 3}, received[0]);
    }

    @Test
    public void leavingRelayModeDisconnectsAndReturnsDirect() throws Exception {
        FakeTransport transport = new FakeTransport();
        ServerRoomRelayAgent agent = new ServerRoomRelayAgent(transport, payload -> { });
        agent.enterRelayMode();

        agent.leaveRelayMode();

        assertEquals(ServerRoomRelayAgent.Mode.DIRECT, agent.getMode());
        assertEquals(1, transport.disconnectCalls);
    }

    private static final class FakeTransport implements ServerRoomRelayAgent.RelayTransport {
        int connectCalls;
        int disconnectCalls;
        boolean connected;

        @Override
        public void connect() {
            connectCalls++;
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
