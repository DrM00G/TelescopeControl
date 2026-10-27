package com.example.telescopecontrol

import com.example.telescopecontrol.coordinator.HeartbeatRecovery
import com.example.telescopecontrol.coordinator.MotorCommandCoordinator
import com.example.telescopecontrol.model.DesiredAxisState
import com.example.telescopecontrol.model.MountStatus
import com.example.telescopecontrol.model.SlewAxis
import com.example.telescopecontrol.model.SlewDirection
import com.example.telescopecontrol.model.StopDiagnosticStatus
import com.example.telescopecontrol.nexstar.NexStarProtocol
import com.example.telescopecontrol.usb.MotorTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class HeartbeatRecoveryTest {
    private class Fake : MotorTransport {
        var connected = true
        var badPacket: Int? = null
        val packets = mutableListOf<List<Byte>>()
        val timeouts = mutableListOf<Long>()
        var pause: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()
        override fun isConnected() = connected
        override suspend fun closeSafely() { connected = false }
        override suspend fun transactSafe(command: ByteArray, timeoutMs: Long, writeGate: ((() -> Unit) -> Unit)?): ByteArray {
            pause?.let { entered.complete(Unit); it.await() }
            val write = { packets.add(command.toList()); timeouts.add(timeoutMs); Unit }
            if (writeGate == null) write() else writeGate(write)
            return if (badPacket == packets.size) byteArrayOf() else byteArrayOf('#'.code.toByte())
        }
    }
    private fun motor(fake: Fake) = MotorCommandCoordinator(fake, { _, _ -> }, {}, {})
    private fun stops() = listOf(
        listOf<Byte>(0x50, 3, 0x10, 6, 0, 0, 0, 0),
        listOf<Byte>(0x50, 3, 0x10, 7, 0, 0, 0, 0),
        listOf<Byte>(0x50, 2, 0x10, 0x24, 0, 0, 0, 0),
        listOf<Byte>(0x50, 2, 0x10, 0x25, 0, 0, 0, 0),
        listOf<Byte>(0x50, 3, 0x11, 6, 0, 0, 0, 0),
        listOf<Byte>(0x50, 3, 0x11, 7, 0, 0, 0, 0),
        listOf<Byte>(0x50, 2, 0x11, 0x24, 0, 0, 0, 0),
        listOf<Byte>(0x50, 2, 0x11, 0x25, 0, 0, 0, 0))

    @Test fun failedFirstStopAndRepeatedFailureStayUnknownUntilFreshRecovery() = runBlocking {
        val fake = Fake(); val motor = motor(fake); val recovery = HeartbeatRecovery(motor)
        fake.badPacket = 2
        assertEquals(StopDiagnosticStatus.STOP_REQUESTED, recovery.record(false, false, MountStatus.ONLINE).stopStatus)
        assertEquals(null to null, motor.appliedStates())
        assertTrue(recovery.record(false, false, MountStatus.ONLINE).heartbeat.quiet)
        assertEquals(null to null, motor.appliedStates())
        assertEquals(stops(), fake.packets)
        assertTrue(recovery.needsStopOnRecovery)
        fake.badPacket = null
        val returned = recovery.record(true, true, MountStatus.OFFLINE)
        assertFalse(returned.heartbeat.ready)
        assertEquals(StopDiagnosticStatus.STOP_COMMANDS_ACKNOWLEDGED, returned.stopStatus)
        assertEquals(stops() + stops(), fake.packets)
        assertEquals(List(16) { 250L }, fake.timeouts)
        assertTrue(recovery.needsStopOnRecovery)
        val verified = recovery.record(true, true, MountStatus.ONLINE, verifyStationary = { true })
        assertEquals(StopDiagnosticStatus.STOP_VERIFIED, verified.stopStatus)
        assertFalse(verified.heartbeat.ready)
        assertFalse(recovery.record(true, true, MountStatus.ONLINE).heartbeat.ready)
        assertTrue(recovery.record(true, true, MountStatus.ONLINE).heartbeat.ready)
        assertEquals(DesiredAxisState.Stop, motor.currentIntent.ra)
    }

    @Test fun failureIsPublishedBeforePendingStopCompletes() = runBlocking {
        val fake = Fake(); fake.pause = CompletableDeferred()
        val motor = motor(fake); val recovery = HeartbeatRecovery(motor)
        var status: MountStatus? = null
        val pending = async { recovery.record(false, false, MountStatus.ONLINE, onFailure = { status = it.status }) }
        fake.entered.await()
        assertEquals(MountStatus.SUSPECT, status)
        assertFalse(pending.isCompleted)
        fake.pause!!.complete(Unit); fake.pause = null
        pending.await()
        Unit
    }

    @Test fun startupReadinessRequiresExactlyTwoHeartbeatsWithoutRecoveryStop() = runBlocking {
        val fake = Fake(); val motor = motor(fake); val recovery = HeartbeatRecovery(motor)
        assertEquals(null to null, motor.appliedStates())
        recovery.reset()
        val first = recovery.record(true, true, MountStatus.UNKNOWN)
        assertEquals(1, first.heartbeat.successes); assertFalse(first.heartbeat.ready)
        val second = recovery.record(true, true, MountStatus.ONLINE)
        assertEquals(2, second.heartbeat.successes); assertTrue(second.heartbeat.ready)
        assertFalse(recovery.needsStopOnRecovery); assertTrue(fake.packets.isEmpty())
        val move = motor.updateDesiredState(DesiredAxisState.Fixed(SlewDirection.POSITIVE, 2), DesiredAxisState.Stop, "startup")
        assertEquals(DesiredAxisState.Fixed(SlewDirection.POSITIVE, 2), move.ra)
    }

    @Test fun unavailableVerificationKeepsInhibitAcrossRetriesAndReset() = runBlocking {
        val fake = Fake(); val motor = motor(fake); val recovery = HeartbeatRecovery(motor)
        recovery.record(false, false, MountStatus.ONLINE)
        repeat(3) {
            val result = recovery.record(true, true, MountStatus.ONLINE, verifyStationary = { null })
            assertEquals(StopDiagnosticStatus.STOP_COMMANDS_ACKNOWLEDGED, result.stopStatus)
            assertEquals(0, result.heartbeat.successes); assertFalse(result.heartbeat.ready)
            assertTrue(recovery.needsStopOnRecovery)
            assertEquals(DesiredAxisState.Stop, motor.updateDesiredState(
                DesiredAxisState.Fixed(SlewDirection.POSITIVE, 2), DesiredAxisState.Stop, "blocked").ra)
        }
        recovery.reset()
        assertTrue(recovery.needsStopOnRecovery)
        assertEquals(DesiredAxisState.Stop, motor.updateDesiredState(
            DesiredAxisState.Fixed(SlewDirection.POSITIVE, 2), DesiredAxisState.Stop, "still blocked").ra)
    }

    @Test fun quietFailureForgetsPreviouslyAcknowledgedStop() = runBlocking {
        val fake = Fake(); val motor = motor(fake); val recovery = HeartbeatRecovery(motor)
        recovery.record(false, false, MountStatus.ONLINE)
        assertEquals(DesiredAxisState.Stop to DesiredAxisState.Stop, motor.appliedStates())
        recovery.record(false, false, MountStatus.ONLINE)
        assertEquals(null to null, motor.appliedStates())
        assertEquals(stops(), fake.packets)
    }

    @Test fun partialFailureRequiresRecoveryStopAndFreshVerification() = runBlocking {
        val fake = Fake(); val motor = motor(fake); val recovery = HeartbeatRecovery(motor)
        assertEquals(MountStatus.PARTIAL, recovery.record(true, false, MountStatus.ONLINE).heartbeat.status)
        assertTrue(recovery.needsStopOnRecovery)
        val failedVerification = recovery.record(true, true, MountStatus.PARTIAL, verifyStationary = { false })
        assertEquals(StopDiagnosticStatus.STOP_COMMANDS_ACKNOWLEDGED, failedVerification.stopStatus)
        assertTrue(recovery.needsStopOnRecovery)
        val verified = recovery.record(true, true, MountStatus.ONLINE, verifyStationary = { true })
        assertEquals(StopDiagnosticStatus.STOP_VERIFIED, verified.stopStatus)
        assertFalse(verified.heartbeat.ready)
        assertFalse(recovery.record(true, true, MountStatus.ONLINE).heartbeat.ready)
        assertTrue(recovery.record(true, true, MountStatus.ONLINE).heartbeat.ready)
    }

    @Test fun failedRecoveryRetriesOnlyOnNextHeartbeatAndNeverResumes() = runBlocking {
        val fake = Fake(); val motor = motor(fake); val recovery = HeartbeatRecovery(motor)
        recovery.record(false, false, MountStatus.ONLINE)
        fake.badPacket = 10
        val failed = recovery.record(true, true, MountStatus.ONLINE)
        assertEquals(StopDiagnosticStatus.STOP_REQUESTED, failed.stopStatus)
        assertFalse(failed.heartbeat.ready); assertTrue(recovery.needsStopOnRecovery)
        assertEquals(null to null, motor.appliedStates())
        assertEquals(16, fake.packets.size)
        fake.badPacket = null
        recovery.record(true, true, MountStatus.ONLINE)
        assertEquals(24, fake.packets.size)
        assertEquals(DesiredAxisState.Stop, motor.currentIntent.dec)
    }

    @Test fun inputCannotOverwriteRecoveryStopAndDetachStaysQuiet() = runBlocking {
        val fake = Fake(); val motor = motor(fake); val recovery = HeartbeatRecovery(motor)
        recovery.record(false, false, MountStatus.ONLINE)
        fake.pause = CompletableDeferred()
        val attempt = async { recovery.record(true, true, MountStatus.ONLINE) }
        fake.entered.await()
        motor.updateDesiredState(DesiredAxisState.Fixed(SlewDirection.POSITIVE, 2), DesiredAxisState.Stop, "concurrent")
        fake.pause!!.complete(Unit); fake.pause = null
        attempt.await(); yield()
        assertEquals(DesiredAxisState.Stop, motor.currentIntent.ra)
        assertEquals(stops() + stops(), fake.packets)
        fake.packets.clear(); fake.connected = false
        val detach = motor.invalidateForDisconnect(); motor.resetAppliedIfCurrent(detach.generation)
        assertEquals(null to null, motor.appliedStates())
        assertTrue(fake.packets.isEmpty())
    }
}
