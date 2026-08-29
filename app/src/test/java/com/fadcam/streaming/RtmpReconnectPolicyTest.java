package com.fadcam.streaming;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class RtmpReconnectPolicyTest {
    @Test
    public void usesExponentialBackoffAndStopsAtBudget() {
        RtmpReconnectPolicy policy = new RtmpReconnectPolicy(4, 1000L, 5000L);
        assertEquals(1000L, policy.nextDelayMs());
        assertEquals(2000L, policy.nextDelayMs());
        assertEquals(4000L, policy.nextDelayMs());
        assertEquals(5000L, policy.nextDelayMs());
        assertEquals(-1L, policy.nextDelayMs());
        assertEquals(4, policy.getAttempts());
    }

    @Test
    public void resetRestartsRetryBudget() {
        RtmpReconnectPolicy policy = new RtmpReconnectPolicy(2, 500L, 5000L);
        assertEquals(500L, policy.nextDelayMs());
        assertEquals(1000L, policy.nextDelayMs());
        assertEquals(-1L, policy.nextDelayMs());
        policy.reset();
        assertEquals(0, policy.getAttempts());
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

    @Test
    public void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new RtmpReconnectPolicy(0, 1000L, 5000L));
        assertThrows(IllegalArgumentException.class,
                () -> new RtmpReconnectPolicy(3, 0L, 5000L));
        assertThrows(IllegalArgumentException.class,
                () -> new RtmpReconnectPolicy(3, 5000L, 1000L));
    }

    @Test
    public void handlesVeryLargeDelaysWithoutOverflow() {
        long initial = Long.MAX_VALUE / 4L + 1L;
        RtmpReconnectPolicy policy = new RtmpReconnectPolicy(3, initial, Long.MAX_VALUE);
        assertEquals(initial, policy.nextDelayMs());
        assertEquals(initial * 2L, policy.nextDelayMs());
        assertEquals(Long.MAX_VALUE, policy.nextDelayMs());
        assertEquals(-1L, policy.nextDelayMs());
    }
}
