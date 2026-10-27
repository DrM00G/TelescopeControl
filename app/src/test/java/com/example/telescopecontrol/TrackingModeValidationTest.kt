package com.example.telescopecontrol

import com.example.telescopecontrol.model.TrackingMode
import com.example.telescopecontrol.nexstar.NexStarProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingModeValidationTest {
    @Test fun onlyCompleteValidResponsesCanEstablishOff() {
        assertEquals(TrackingMode.UNKNOWN, NexStarProtocol.parseTrackingMode(byteArrayOf()))
        assertEquals(TrackingMode.UNKNOWN, NexStarProtocol.parseTrackingMode(byteArrayOf(0)))
        assertEquals(TrackingMode.UNKNOWN, NexStarProtocol.parseTrackingMode(byteArrayOf(0, 0)))
        assertEquals(TrackingMode.UNKNOWN, NexStarProtocol.parseTrackingMode(byteArrayOf(0, '#'.code.toByte(), 0)))
        assertEquals(TrackingMode.OFF, NexStarProtocol.parseTrackingMode(byteArrayOf(0, '#'.code.toByte())))
        assertEquals(TrackingMode.UNKNOWN, NexStarProtocol.parseTrackingMode(byteArrayOf(99, '#'.code.toByte())))
    }
}
