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

/** Tests run on one runBlocking event loop; barriers identify exact transport boundaries. */
class MandatoryMotorScenariosTest {
    private class Fake : MotorTransport {
        val packets = mutableListOf<List<Byte>>()
        var beforeWrite: (suspend (ByteArray) -> Unit)? = null
        var afterWrite: (suspend (ByteArray) -> Unit)? = null
        override fun isConnected() = true
        override suspend fun closeSafely() = Unit
        override suspend fun transactSafe(command: ByteArray, timeoutMs: Long, writeGate: ((() -> Unit) -> Unit)?): ByteArray {
            beforeWrite?.invoke(command)
            val write = { packets.add(command.toList()); Unit }
            if (writeGate == null) write() else writeGate(write)
            afterWrite?.invoke(command)
            return byteArrayOf('#'.code.toByte())
        }
    }

    private val stop = DesiredAxisState.Stop
    private fun fixed(direction: SlewDirection) = DesiredAxisState.Fixed(direction, 4)
    private fun variable(rate: Double) = DesiredAxisState.Variable(rate)
    private fun fixedPacket(axis: SlewAxis, direction: SlewDirection, rate: Int) =
        NexStarProtocol.fixedSlew(axis, direction, rate).toList()
    private fun variablePacket(axis: SlewAxis, rate: Double) = NexStarProtocol.variableSlew(axis, rate).toList()
    private fun stops() = listOf(
        fixedPacket(SlewAxis.RA, SlewDirection.POSITIVE, 0),
        fixedPacket(SlewAxis.RA, SlewDirection.NEGATIVE, 0),
        fixedPacket(SlewAxis.DEC, SlewDirection.POSITIVE, 0),
        fixedPacket(SlewAxis.DEC, SlewDirection.NEGATIVE, 0)
    )
    private fun motor(fake: Fake) = MotorCommandCoordinator(fake, { _, _ -> }, {}, {})

    @Test fun staleDec420BlockedBeforePhysicalWrite() = runBlocking {
        val fake = Fake(); val motor = motor(fake)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        fake.beforeWrite = { entered.complete(Unit); release.await() }
        motor.start(this)
        motor.updateDesiredState(stop, variable(420.0), "dec 420")
        entered.await()
        motor.updateDesiredState(stop, stop, "dec stop")
        release.complete(Unit)
        yield()
        assertEquals(emptyList<List<Byte>>(), fake.packets)
        assertEquals(stop, motor.currentIntent.dec)
        motor.stopWorker()
    }

    @Test fun fixedDecToVariableSupersededAfterFixedStop() = runBlocking {
        val fake = Fake(); val motor = motor(fake)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        motor.start(this)
        motor.updateDesiredState(stop, fixed(SlewDirection.POSITIVE), "fixed")
        yield()
        fake.afterWrite = { command ->
            if (command.toList() == fixedPacket(SlewAxis.DEC, SlewDirection.POSITIVE, 0)) {
                entered.complete(Unit); release.await()
            }
        }
        motor.updateDesiredState(stop, variable(240.0), "background")
        entered.await()
        motor.updateDesiredState(stop, stop, "superseded")
        fake.afterWrite = null
        release.complete(Unit)
        yield()
        assertEquals(listOf(
            fixedPacket(SlewAxis.DEC, SlewDirection.POSITIVE, 4),
            fixedPacket(SlewAxis.DEC, SlewDirection.POSITIVE, 0),
            fixedPacket(SlewAxis.DEC, SlewDirection.POSITIVE, 0),
            fixedPacket(SlewAxis.DEC, SlewDirection.POSITIVE, 0)
        ), fake.packets)
        assertFalse(fake.packets.contains(variablePacket(SlewAxis.DEC, 240.0)))
        motor.stopWorker()
    }

    @Test fun emergencyStopSurvivesNewGenerationAndRemainsSerialized() = runBlocking {
        val fake = Fake(); val motor = motor(fake)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        fake.beforeWrite = { command ->
            if (command.toList() == variablePacket(SlewAxis.DEC, 420.0)) {
                entered.complete(Unit); release.await()
            }
        }
        motor.start(this)
        motor.updateDesiredState(stop, variable(420.0), "in flight")
        entered.await()
        val emergency = async(start = CoroutineStart.UNDISPATCHED) { motor.emergencyStop("emergency") }
        val newer = motor.updateDesiredState(variable(60.0), stop, "newer")
        fake.beforeWrite = null
        release.complete(Unit)
        assertEquals(StopDiagnosticStatus.STOP_COMMANDS_ACKNOWLEDGED, emergency.await())
        yield()
        assertEquals(stops() + listOf(variablePacket(SlewAxis.RA, 60.0)), fake.packets)
        assertEquals(newer, motor.currentIntent)
        motor.stopWorker()
    }

    @Test fun releaseRaReturnsToBackgroundWithoutChangingDec() = runBlocking {
        val fake = Fake(); val motor = motor(fake)
        motor.start(this)
        motor.updateDesiredState(fixed(SlewDirection.POSITIVE), fixed(SlewDirection.NEGATIVE), "both pressed")
        yield()
        motor.updateDesiredState(variable(60.0), fixed(SlewDirection.NEGATIVE), "release ra")
        yield()
        assertEquals(listOf(
            fixedPacket(SlewAxis.RA, SlewDirection.POSITIVE, 4),
            fixedPacket(SlewAxis.DEC, SlewDirection.NEGATIVE, 4),
            fixedPacket(SlewAxis.RA, SlewDirection.POSITIVE, 0),
            variablePacket(SlewAxis.RA, 60.0)
        ), fake.packets)
        assertEquals(fixed(SlewDirection.NEGATIVE), motor.currentIntent.dec)
        motor.stopWorker()
    }

    @Test fun rapidRatesConflateAndCannotAppearAfterFinalStop() = runBlocking {
        val fake = Fake(); val motor = motor(fake)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        fake.beforeWrite = { entered.complete(Unit); release.await() }
        motor.start(this)
        motor.updateDesiredState(variable(60.0), stop, "60")
        entered.await()
        motor.updateDesiredState(variable(120.0), stop, "120")
        motor.updateDesiredState(variable(180.0), stop, "180")
        motor.updateDesiredState(stop, stop, "zero")
        release.complete(Unit)
        yield()
        assertEquals(emptyList<List<Byte>>(), fake.packets)
        assertEquals(stop, motor.currentIntent.ra)
        motor.stopWorker()
    }

    @Test fun firstHeartbeatFailurePublishesStopThenPhysicallyStops() = runBlocking {
        val fake = Fake(); val motor = motor(fake); val policy = HeartbeatPolicy()
        motor.start(this)
        motor.updateDesiredState(variable(60.0), variable(420.0), "moving")
        yield()
        assertFalse(policy.record(true, true, MountStatus.ONLINE).ready)
        assertTrue(policy.record(true, true, MountStatus.ONLINE).ready)
        val result = policy.record(false, false, MountStatus.ONLINE)
        assertTrue(result.stop); assertFalse(result.quiet); assertFalse(result.ready)
        assertEquals(MountStatus.ONLINE, result.status)
        val intent = motor.invalidateForStop("HEARTBEAT_FAILURE")
        assertEquals(stop, motor.currentIntent.ra); assertEquals(stop, motor.currentIntent.dec)
        assertEquals(StopDiagnosticStatus.STOP_COMMANDS_ACKNOWLEDGED, motor.stopIfCurrent(intent, 250))
        assertEquals(listOf(variablePacket(SlewAxis.RA, 60.0), variablePacket(SlewAxis.DEC, 420.0)) + stops(), fake.packets)
        assertEquals(intent, motor.currentIntent)
        motor.stopWorker()
    }

    @Test fun secondHeartbeatFailureInvalidatesCoordinatorWithoutFurtherPackets() = runBlocking {
        val fake = Fake(); val motor = motor(fake); val policy = HeartbeatPolicy()
        motor.start(this)
        motor.updateDesiredState(stop, variable(420.0), "moving")
        yield()
        val first = policy.record(false, false, MountStatus.ONLINE)
        assertTrue(first.stop)
        val stopIntent = motor.invalidateForStop("HEARTBEAT_FAILURE")
        motor.stopIfCurrent(stopIntent, 250)
        val second = policy.record(false, false, first.status)
        assertTrue(second.quiet); assertFalse(second.ready); assertEquals(MountStatus.OFFLINE, second.status)
        val invalidated = motor.invalidateForDisconnect()
        motor.resetAppliedIfCurrent(invalidated.generation)
        assertTrue(invalidated.generation > stopIntent.generation)
        assertEquals(stop, invalidated.ra); assertEquals(stop, invalidated.dec)
        assertEquals(listOf(variablePacket(SlewAxis.DEC, 420.0)) + stops(), fake.packets)
        motor.stopWorker()
    }
}
