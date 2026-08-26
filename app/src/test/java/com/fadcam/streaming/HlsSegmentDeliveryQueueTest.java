package com.fadcam.streaming;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

public class HlsSegmentDeliveryQueueTest {
    private FakeTransport transport;
    private HlsSegmentDeliveryQueue queue;

    @Before
    public void setUp() {
        transport = new FakeTransport();
        queue = new HlsSegmentDeliveryQueue(transport);
        queue.reset();
    }

    @Test
    public void segmentBeforeInitIsQueuedNotDropped() {
        queue.offerSegment(7, new byte[] {7});

        assertEquals(1, queue.pendingCount());
        assertEquals(Integer.valueOf(7), queue.nextPendingSequence());
        assertTrue(transport.segments.isEmpty());
    }

    @Test
    public void initAckDrainsQueuedSegmentsInOrder() {
        queue.offerSegment(2, new byte[] {2});
        queue.offerSegment(1, new byte[] {1});
        queue.offerSegment(3, new byte[] {3});
        queue.offerInit(new byte[] {9});

        assertEquals(1, transport.initUploads);
        assertTrue(transport.segments.isEmpty());

        transport.completeInitSuccess();

        assertEquals(Integer.valueOf(1), transport.segments.get(0));
        transport.completeSegmentSuccess();
        assertEquals(Integer.valueOf(2), transport.segments.get(1));
        transport.completeSegmentSuccess();
        assertEquals(Integer.valueOf(3), transport.segments.get(2));
        transport.completeSegmentSuccess();

        assertEquals(0, queue.pendingCount());
        assertTrue(queue.isInitAcknowledged());
    }

    @Test
    public void failedSegmentRemainsQueuedAndRetryResubmitsIt() {
        queue.offerInit(new byte[] {9});
        transport.completeInitSuccess();
        queue.offerSegment(4, new byte[] {4});

        transport.completeSegmentFailure("network");

        assertEquals(1, queue.pendingCount());
        assertEquals(Integer.valueOf(4), queue.nextPendingSequence());
        assertFalse(transport.segmentsDelivered);

        queue.retryPending();
        assertEquals(Integer.valueOf(4), transport.segments.get(1));
        transport.completeSegmentSuccess();
        assertEquals(0, queue.pendingCount());
    }

    @Test
    public void stopExplicitlyEndsSessionAndClearsOwnedQueue() {
        queue.offerSegment(1, new byte[] {1});
        queue.stop();

        assertEquals(0, queue.pendingCount());
        assertEquals(null, queue.nextPendingSequence());
    }

    private static final class FakeTransport implements HlsSegmentDeliveryQueue.Transport {
        int initUploads;
        final List<Integer> segments = new ArrayList<>();
        boolean segmentsDelivered;
        HlsSegmentDeliveryQueue.Completion initCompletion;
        HlsSegmentDeliveryQueue.Completion segmentCompletion;

        @Override
        public void uploadInit(byte[] initData, HlsSegmentDeliveryQueue.Completion completion) {
            initUploads++;
            initCompletion = completion;
        }

        @Override
        public void uploadSegment(int sequence, byte[] data, HlsSegmentDeliveryQueue.Completion completion) {
            segments.add(sequence);
            segmentCompletion = completion;
            segmentsDelivered = true;
        }

        void completeInitSuccess() {
            HlsSegmentDeliveryQueue.Completion callback = initCompletion;
            initCompletion = null;
            callback.success();
        }

        void completeSegmentSuccess() {
            HlsSegmentDeliveryQueue.Completion callback = segmentCompletion;
            segmentCompletion = null;
            callback.success();
        }

        void completeSegmentFailure(String error) {
            HlsSegmentDeliveryQueue.Completion callback = segmentCompletion;
            segmentCompletion = null;
            segmentsDelivered = false;
            callback.failure(error);
        }
    }
}
