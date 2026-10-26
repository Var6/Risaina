package com.risaina.app.core.sos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A test ring that falls back to the alarm clock (the service start was refused) is re-sent with
 * source ALARM_CLOCK. It must still ring as a 5-second test, never as a real 5-minute SOS.
 */
class RingRequestTest {
    @Test fun theAlarmClockRetryOfATestIsStillATest() {
        val test = RingRequest(partnerName = "your partner", source = RingSource.TEST, test = true)
        val retry = test.copy(source = RingSource.ALARM_CLOCK) // what SosAlarmController.scheduleAlarmClock sends
        assertTrue(retry.test)
    }

    @Test fun aRealSosIsNeverATest() {
        assertFalse(RingRequest(sosId = "s1", source = RingSource.PUSH).test)
        assertFalse(RingRequest(sosId = "s1", source = RingSource.PUSH).copy(source = RingSource.ALARM_CLOCK).test)
    }
}
