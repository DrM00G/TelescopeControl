package com.example.telescopecontrol

import com.example.telescopecontrol.coordinator.HeartbeatPolicy
import com.example.telescopecontrol.coordinator.MotorCommandCoordinator
import com.example.telescopecontrol.model.DesiredAxisState
import com.example.telescopecontrol.model.MountStatus
import com.example.telescopecontrol.model.SlewAxis
import com.example.telescopecontrol.model.SlewDirection
import com.example.telescopecontrol.model.StopDiagnosticStatus
import com.example.telescopecontrol.nexstar.NexStarProtocol
import com.example.telescopecontrol.usb.MotorTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class MotorSafetyTest {
    private class Fake : MotorTransport {
        var connected = true
        val packets = mutableListOf<List<Byte>>()
        val timeouts = mutableListOf<Long>()
        var fail = false
        var pause: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()
        override fun isConnected() = connected
        override suspend fun closeSafely() { connected = false }
        override suspend fun transactSafe(command: ByteArray, timeoutMs: Long, writeGate: ((() -> Unit) -> Unit)?): ByteArray {
            pause?.let { entered.complete(Unit); it.await() }
            val write = { packets.add(command.toList()); timeouts.add(timeoutMs); Unit }
            if (writeGate == null) write() else writeGate(write)
            if (fail) throw IOException("no acknowledgement")
            return byteArrayOf('#'.code.toByte())
        }
    }
    private fun coordinator(fake: Fake) = MotorCommandCoordinator(fake, { _, _ -> }, {}, {})
    private fun stops() = listOf(
        NexStarProtocol.fixedSlew(SlewAxis.RA, SlewDirection.POSITIVE, 0).toList(),
        NexStarProtocol.fixedSlew(SlewAxis.RA, SlewDirection.NEGATIVE, 0).toList(),
        NexStarProtocol.fixedSlew(SlewAxis.DEC, SlewDirection.POSITIVE, 0).toList(),
        NexStarProtocol.fixedSlew(SlewAxis.DEC, SlewDirection.NEGATIVE, 0).toList()
    )

    @Test fun firstCompleteFailureStopsPhysically() = runBlocking {
        val policy = HeartbeatPolicy(); val fake = Fake(); val motor = coordinator(fake)
        val result = policy.record(false, false, MountStatus.ONLINE)
        assertFalse(result.ready); assertTrue(result.stop); assertEquals(MountStatus.ONLINE, result.status)
        val intent = motor.invalidateForStop("heartbeat")
        assertEquals(StopDiagnosticStatus.STOP_COMMANDS_ACKNOWLEDGED, motor.stopIfCurrent(intent, 250))
        assertEquals(stops(), fake.packets); assertEquals(listOf(250L, 250L, 250L, 250L), fake.timeouts)
        assertEquals(DesiredAxisState.Stop, motor.currentIntent.ra)
    }

    @Test fun secondCompleteFailureIsQuietAndOffline() = runBlocking {
        val policy = HeartbeatPolicy(); val fake = Fake(); val motor = coordinator(fake)
        policy.record(false, false, MountStatus.ONLINE)
        val result = policy.record(false, false, MountStatus.ONLINE)
        assertTrue(result.quiet); assertEquals(MountStatus.OFFLINE, result.status)
        val intent = motor.beginHeartbeatInhibit("HEARTBEAT_FAILURE"); motor.markAppliedUnknownIfCurrent(intent.generation)
        assertTrue(fake.packets.isEmpty())
        assertTrue(policy.record(false, false, MountStatus.OFFLINE).quiet)
    }

    @Test fun partialImmediatelyStopsAndResetsFailureRun() = runBlocking {
        val policy = HeartbeatPolicy(); val fake = Fake(); val motor = coordinator(fake)
        policy.record(false, false, MountStatus.ONLINE)
        val partial = policy.record(true, false, MountStatus.ONLINE)
        assertEquals(MountStatus.PARTIAL, partial.status); assertFalse(partial.ready); assertTrue(partial.stop)
        motor.stopIfCurrent(motor.invalidateForStop("partial"), 250)
        assertEquals(stops(), fake.packets)
        assertTrue(policy.record(false, false, MountStatus.PARTIAL).stop)
    }

    @Test fun recoveryRequiresTwoSuccessesAndDoesNotResume() = runBlocking {
        val policy = HeartbeatPolicy(); val fake = Fake(); val motor = coordinator(fake)
        val stop = motor.invalidateForStop("failure"); motor.stopIfCurrent(stop, 250)
        policy.record(false, false, MountStatus.ONLINE)
        assertFalse(policy.record(true, true, MountStatus.ONLINE).ready)
        assertTrue(policy.record(true, true, MountStatus.ONLINE).ready)
        assertEquals(stop, motor.currentIntent); assertEquals(stops(), fake.packets)
    }

    @Test fun supersededDelayedStopCannotOverrideNewIntent() = runBlocking {
        val fake = Fake(); val motor = coordinator(fake)
        val stop = motor.invalidateForStop("failure")
        val newer = motor.updateDesiredState(DesiredAxisState.Fixed(SlewDirection.POSITIVE, 2), DesiredAxisState.Stop, "new")
        assertEquals(StopDiagnosticStatus.STOP_REQUESTED, motor.stopIfCurrent(stop, 250))
        assertEquals(newer, motor.currentIntent); assertTrue(fake.packets.isEmpty())
    }

    @Test fun staleInFlightMovementIsBlockedAtWriteGate() = runBlocking {
        val fake = Fake(); fake.pause = CompletableDeferred(); val motor = coordinator(fake)
        motor.start(this)
        motor.updateDesiredState(DesiredAxisState.Fixed(SlewDirection.POSITIVE, 2), DesiredAxisState.Stop, "slew")
        fake.entered.await()
        val stop = motor.invalidateForStop("failure")
        fake.pause!!.complete(Unit); fake.pause = null
        val stopped = async(start = CoroutineStart.UNDISPATCHED) { motor.stopIfCurrent(stop, 250) }
        assertEquals(StopDiagnosticStatus.STOP_COMMANDS_ACKNOWLEDGED, stopped.await())
        assertEquals(stops(), fake.packets)
        motor.stopWorker()
    }

    @Test fun failedStopAttemptsAllPacketsAndDetachIsQuiet() = runBlocking {
        val fake = Fake(); fake.fail = true; val motor = coordinator(fake)
        assertEquals(StopDiagnosticStatus.STOP_REQUESTED, motor.stopIfCurrent(motor.invalidateForStop("failure"), 250))
        assertEquals(stops(), fake.packets)
        fake.packets.clear(); fake.connected = false
        val detach = motor.invalidateForDisconnect(); motor.resetAppliedIfCurrent(detach.generation)
        assertTrue(fake.packets.isEmpty())
        assertEquals(StopDiagnosticStatus.STOP_UNAVAILABLE_CONNECTION_LOST, motor.stopIfCurrent(motor.invalidateForStop("detached"), 250))
        assertTrue(fake.packets.isEmpty())
    }
}
