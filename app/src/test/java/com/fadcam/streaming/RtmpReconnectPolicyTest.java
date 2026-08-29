package com.fadcam.streaming;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RtmpReconnectPolicyTest {
    @Test
    public void usesExponentialBackoffAndStopsAtBudget() {
        RtmpReconnectPolicy policy = new RtmpReconnectPolicy(4, 1000L, 5000L);
        assertEquals(1000L, policy.nextDelayMs());
        assertEquals(2000L, policy.nextDelayMs());
        assertEquals(4000L, policy.nextDelayMs());
        assertEquals(-1L, policy.nextDelayMs());
    }

    @Test
    public void resetRestartsRetryBudget() {
        RtmpReconnectPolicy policy = new RtmpReconnectPolicy(2, 500L, 5000L);
        assertEquals(500L, policy.nextDelayMs());
        assertEquals(1000L, policy.nextDelayMs());
        assertEquals(-1L, policy.nextDelayMs());
        policy.reset();
        assertEquals(500L, policy.nextDelayMs());
    }

    @Test
    public void delayIsCappedAtMaximum() {
        RtmpReconnectPolicy policy = new RtmpReconnectPolicy(5, 1000L, 2500L);
        assertEquals(1000L, policy.nextDelayMs());
        assertEquals(2000L, policy.nextDelayMs());
        assertEquals(2500L, policy.nextDelayMs());
        assertEquals(2500L, policy.nextDelayMs());
        assertEquals(2500L, policy.nextDelayMs());
        assertEquals(-1L, policy.nextDelayMs());
    }
}
