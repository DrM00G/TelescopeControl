package com.example.telescopecontrol

import com.example.telescopecontrol.coordinator.MotorCommandCoordinator
import com.example.telescopecontrol.model.DesiredAxisState
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

class MovementFamilyTest {
    private fun bytes(vararg values: Int) = values.map { it.toByte() }
    private val fixedPos = bytes(0x50, 2, 0x10, 0x24, 0, 0, 0, 0)
    private val fixedNeg = bytes(0x50, 2, 0x10, 0x25, 0, 0, 0, 0)
    private val guidePos = bytes(0x50, 3, 0x10, 6, 0, 0, 0, 0)
    private val guideNeg = bytes(0x50, 3, 0x10, 7, 0, 0, 0, 0)
    private val varPos = bytes(0x50, 4, 0x10, 6, 0, 4, 0, 0)
    private val varNeg = bytes(0x50, 4, 0x10, 7, 0, 4, 0, 0)
    private val fixedMove = bytes(0x50, 2, 0x10, 0x24, 4, 0, 0, 0)
    private val stop = DesiredAxisState.Stop
    private val positive = DesiredAxisState.Variable(1.0)
    private val negative = DesiredAxisState.Variable(-1.0)
    private val fixed = DesiredAxisState.Fixed(SlewDirection.POSITIVE, 4)

    private class Fake : MotorTransport {
        val packets = mutableListOf<List<Byte>>()
        var badIndex: Int? = null
        var afterWrite: (suspend (Int) -> Unit)? = null
        override fun isConnected() = true
        override suspend fun closeSafely() = Unit
        override suspend fun transactSafe(command: ByteArray, timeoutMs: Long, writeGate: ((() -> Unit) -> Unit)?): ByteArray {
            val write = { packets.add(command.toList()); Unit }
            if (writeGate == null) write() else writeGate(write)
            val index = packets.size
            afterWrite?.invoke(index)
            return if (index == badIndex) byteArrayOf() else byteArrayOf(0x23)
        }
    }
    private fun motor(fake: Fake, failure: () -> Unit = {}) = MotorCommandCoordinator(fake, { _, _ -> }, {}, failure)

    @Test fun literalProtocolPackets() {
        assertEquals(guidePos, NexStarProtocol.guideZero(SlewAxis.RA, SlewDirection.POSITIVE).toList())
        assertEquals(guideNeg, NexStarProtocol.guideZero(SlewAxis.RA, SlewDirection.NEGATIVE).toList())
        assertEquals(bytes(0x50, 3, 0x11, 6, 0, 0, 0, 0), NexStarProtocol.guideZero(SlewAxis.DEC, SlewDirection.POSITIVE).toList())
        assertEquals(bytes(0x50, 3, 0x11, 7, 0, 0, 0, 0), NexStarProtocol.guideZero(SlewAxis.DEC, SlewDirection.NEGATIVE).toList())
        assertEquals(varPos, NexStarProtocol.variableSlew(SlewAxis.RA, 1.0).toList())
        assertEquals(varNeg, NexStarProtocol.variableSlew(SlewAxis.RA, -1.0).toList())
    }

    @Test fun emergencySequenceIsLiteralAndEveryAckRequired() = runBlocking {
        val expected = listOf(guidePos, guideNeg, fixedPos, fixedNeg,
            bytes(0x50, 3, 0x11, 6, 0, 0, 0, 0), bytes(0x50, 3, 0x11, 7, 0, 0, 0, 0),
            bytes(0x50, 2, 0x11, 0x24, 0, 0, 0, 0), bytes(0x50, 2, 0x11, 0x25, 0, 0, 0, 0))
        for (bad in 1..8) {
            val fake = Fake(); fake.badIndex = bad; val motor = motor(fake)
            assertEquals(StopDiagnosticStatus.STOP_REQUESTED, motor.emergencyStop("test"))
            assertEquals(expected, fake.packets)
            assertEquals(null to null, motor.appliedStates())
        }
        val fake = Fake(); val motor = motor(fake)
        assertEquals(StopDiagnosticStatus.STOP_COMMANDS_ACKNOWLEDGED, motor.emergencyStop("test"))
        assertEquals(expected, fake.packets)
        assertEquals(stop to stop, motor.appliedStates())
    }

    @Test fun transitionsStopOldFamilyBeforeNewMovement() = runBlocking {
        val fake = Fake(); val motor = motor(fake)
        motor.emergencyStop("initialize"); fake.packets.clear(); motor.start(this)
        suspend fun apply(state: DesiredAxisState): List<List<Byte>> {
            fake.packets.clear(); motor.updateDesiredState(state, stop, "transition"); yield()
            return fake.packets.toList()
        }
        assertEquals(listOf(varPos), apply(positive))
        assertEquals(listOf(guidePos, guideNeg), apply(stop))
        assertEquals(listOf(varPos), apply(positive))
        assertEquals(listOf(guidePos, guideNeg, fixedMove), apply(fixed))
        assertEquals(listOf(fixedPos, fixedNeg, varPos), apply(positive))
        assertEquals(listOf(guidePos, guideNeg, varNeg), apply(negative))
        motor.stopWorker()
    }

    @Test fun failedTransitionForgetsAppliedState() = runBlocking {
        val fake = Fake(); var failures = 0; val motor = motor(fake) { failures++ }
        motor.emergencyStop("initialize"); fake.packets.clear(); motor.start(this)
        motor.updateDesiredState(positive, stop, "start"); yield()
        fake.badIndex = 2
        motor.updateDesiredState(stop, stop, "stop"); yield()
        assertEquals(1, failures)
        assertEquals(null, motor.appliedStates().first)
        motor.stopWorker()
    }

    @Test fun supersededTransitionCannotSendLaterPackets() = runBlocking {
        val fake = Fake(); val motor = motor(fake)
        motor.emergencyStop("initialize"); fake.packets.clear(); motor.start(this)
        motor.updateDesiredState(positive, stop, "start"); yield(); fake.packets.clear()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        fake.afterWrite = { if (it == 1) { entered.complete(Unit); release.await() } }
        motor.updateDesiredState(fixed, stop, "fixed")
        entered.await()
        motor.beginHeartbeatInhibit("inhibit")
        fake.afterWrite = null; release.complete(Unit); yield()
        assertEquals(listOf(guidePos), fake.packets)
        assertEquals(null, motor.appliedStates().first)
        motor.stopWorker()
    }

    @Test fun staleRecoveryStopAbortsAtNextPacket() = runBlocking {
        val fake = Fake(); val motor = motor(fake)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        fake.afterWrite = { if (it == 1) { entered.complete(Unit); release.await() } }
        val intent = motor.invalidateForStop("recovery")
        val job = async { motor.stopIfCurrent(intent) }
        entered.await(); motor.invalidateForDisconnect(); release.complete(Unit)
        assertEquals(StopDiagnosticStatus.STOP_REQUESTED, job.await())
        assertEquals(listOf(guidePos), fake.packets)
        assertEquals(null to null, motor.appliedStates())
    }
}
