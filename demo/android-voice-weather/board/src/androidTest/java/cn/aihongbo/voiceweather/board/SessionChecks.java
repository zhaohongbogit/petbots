package cn.aihongbo.voiceweather.board;

import static org.junit.Assert.*;

/** Keep conservation, drain and loss assertions separate: loss is not an undrained queue. */
final class SessionChecks {
    static void drainedAndAccounted(MicSession session){
        assertTrue("Session not finished",session.finished);
        assertEquals("Session error", "",session.error);
        assertEquals("Capture read errors",0,session.readErrors);
        assertEquals("Queue still contains frames",0,session.pendingFrames());
        assertEquals("Sample accounting: captured != decoded + overflow + expired",session.captured,session.decoded+session.totalDroppedSamples());
    }
    static void lossless(MicSession session){
        assertEquals("Overflow dropped samples",0,session.dropped);
        assertEquals("Expired dropped samples",0,session.staleDroppedSamples());
    }
}
