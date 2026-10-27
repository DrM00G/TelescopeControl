package com.example.telescopecontrol

import com.example.telescopecontrol.coordinator.HeartbeatPolicy
import com.example.telescopecontrol.model.MountStatus
import org.junit.Assert.*
import org.junit.Test

class HeartbeatPolicyTest {
    @Test fun completeFailureIsSuspectThenOfflineAndPartialResetsFailureRun() {
        val policy = HeartbeatPolicy()
        val first = policy.record(false, false, MountStatus.ONLINE)
        assertEquals(MountStatus.SUSPECT, first.status)
        assertTrue(first.stop)
        assertFalse(first.ready)
        val second = policy.record(false, false, first.status)
        assertEquals(MountStatus.OFFLINE, second.status)
        assertTrue(second.quiet)
        assertEquals(MountStatus.PARTIAL, policy.record(true, false, second.status).status)
        assertEquals(MountStatus.SUSPECT, policy.record(false, false, MountStatus.PARTIAL).status)
    }
}
