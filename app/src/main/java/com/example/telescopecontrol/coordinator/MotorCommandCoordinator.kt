package com.example.telescopecontrol.coordinator

import com.example.telescopecontrol.model.DesiredAxisState
import com.example.telescopecontrol.model.MotorIntent
import com.example.telescopecontrol.model.SlewAxis
import com.example.telescopecontrol.model.SlewDirection
import com.example.telescopecontrol.model.StopDiagnosticStatus
import com.example.telescopecontrol.nexstar.NexStarProtocol
import com.example.telescopecontrol.nexstar.toHexString
import com.example.telescopecontrol.usb.MotorTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Authoritative motor-control coordinator.
 *
 * Only this coordinator issues movement-related USB packets.
 * Uses atomic MotorIntent snapshots and a coordinator-level movement mutex
 * to guarantee latest-state-wins and prevent stale or interleaved commands.
 */
class MotorCommandCoordinator(
    private val usbConnection: MotorTransport,
    private val logger: (message: String, isError: Boolean) -> Unit,
    private val onErrorWarning: (warning: String) -> Unit,
    private val onMovementFailure: () -> Unit,
    private val onIntentPublished: (MotorIntent) -> Unit = {},
    private val onDiscarded: (Long, Long, String) -> Unit = { _, _, _ -> }
) {
    private val _motorIntent = AtomicReference(MotorIntent())
    // Generation publication and the physical write share this gate.
    private val generationLock = Any()
    private class StaleMovementException : IOException("Movement generation superseded")

    // Null means the physical outcome is unknown, not that a stop was applied.
    private var currentAppliedRaState: DesiredAxisState? = null
    private var currentAppliedDecState: DesiredAxisState? = null
    private var recoveryInhibit = false

    suspend fun appliedStates(): Pair<DesiredAxisState?, DesiredAxisState?> = movementMutex.withLock {
        currentAppliedRaState to currentAppliedDecState
    }

    private fun publish(ra: DesiredAxisState, dec: DesiredAxisState, reason: String): MotorIntent {
        val next = MotorIntent(_motorIntent.get().generation + 1, ra, dec, reason)
        _motorIntent.set(next)
        try { onIntentPublished(next) } catch (_: Exception) { /* Diagnostics cannot block safety. */ }
        return next
    }

    private fun discard(generation: Long, current: Long, reason: String) {
        try { onDiscarded(generation, current, reason) } catch (_: Exception) { }
    }

    fun beginHeartbeatInhibit(reason: String): MotorIntent = synchronized(generationLock) {
        recoveryInhibit = true
        publish(DesiredAxisState.Stop, DesiredAxisState.Stop, reason)
    }

    fun endHeartbeatInhibit() = synchronized(generationLock) { recoveryInhibit = false }

    private val stateChannel = Channel<MotorIntent>(Channel.CONFLATED)
    private var workerJob: Job? = null
    private var workerScope: CoroutineScope? = null

    // High-level coordinator mutex protecting complete movement application sequences
    private val movementMutex = Mutex()

    val currentIntent: MotorIntent
        get() = _motorIntent.get()

    fun start(scope: CoroutineScope) {
        stopWorker()
        workerScope = scope
        workerJob = scope.launch {
            runMotorWorker()
        }
    }

    fun stopWorker() {
        workerJob?.cancel()
        workerJob = null
    }

    /**
     * Atomically updates desired motor intent and signals the motor worker.
     * Increments generation counter atomically on every modification.
     */
    fun updateDesiredState(
        raState: DesiredAxisState,
        decState: DesiredAxisState,
        reason: String
    ): MotorIntent {
        val newIntent = synchronized(generationLock) {
            if (recoveryInhibit) return@synchronized _motorIntent.get()
            val next = publish(raState, decState, reason)
            stateChannel.trySend(next)
            next
        }
        return newIntent
    }

    /**
     * Resets internal applied state tracking without transmitting over USB.
     * Useful on USB disconnect or mount power loss.
     */
    suspend fun resetStatesQuietly(): MotorIntent {
        val resetIntent = invalidateForDisconnect()
        markAppliedUnknownIfCurrent(resetIntent.generation)
        return resetIntent
    }

    fun invalidateForDisconnect(): MotorIntent = synchronized(generationLock) {
        publish(DesiredAxisState.Stop, DesiredAxisState.Stop, "POWER_LOSS_OR_DISCONNECT")
    }

    /** Only for the quiet cleanup following a confirmed physical USB detach. */
    suspend fun resetAppliedIfCurrent(generation: Long) = markAppliedUnknownIfCurrent(generation)

    /** Forget untrustworthy acknowledgements without sending a motor command. */
    suspend fun markAppliedUnknownIfCurrent(generation: Long) = movementMutex.withLock {
        if (_motorIntent.get().generation == generation) {
            currentAppliedRaState = null
            currentAppliedDecState = null
        }
    }

    /** Serializes closing with every complete emergency stop and movement sequence. */
    suspend fun closeConnection() = withContext(NonCancellable) {
        movementMutex.withLock { usbConnection.closeSafely() }
    }

    /**
     * Emergency STOP sequence with absolute coordinator authority.
     * Atomically increments generation, sets intent to Stop, acquires movementMutex,
     * and sends both fixed and guide zero commands in both directions for each axis.
     */
    fun invalidateForStop(reason: String): MotorIntent = synchronized(generationLock) {
        publish(DesiredAxisState.Stop, DesiredAxisState.Stop, reason)
    }

    suspend fun emergencyStop(reason: String, onGeneration: (Long) -> Unit = {}): StopDiagnosticStatus {
        val stopIntent = synchronized(generationLock) {
            publish(DesiredAxisState.Stop, DesiredAxisState.Stop, reason)
        }

        onGeneration(stopIntent.generation)
        return sendStop(stopIntent, timeoutMs = 4000, onlyIfCurrent = false)
    }

    /** Does not publish a generation: an obsolete delayed safety job cannot override new intent. */
    suspend fun stopIfCurrent(stopIntent: MotorIntent, timeoutMs: Long = 4000): StopDiagnosticStatus =
        sendStop(stopIntent, timeoutMs, onlyIfCurrent = true)

    private suspend fun sendStop(stopIntent: MotorIntent, timeoutMs: Long, onlyIfCurrent: Boolean): StopDiagnosticStatus {
        val reason = stopIntent.reason
        // Stale queued signals are rejected by their generation; do not drain a newer intent.
        logger("GEN ${stopIntent.generation} requested STOP ($reason)", false)

        return withContext(NonCancellable) { movementMutex.withLock {
            if (onlyIfCurrent && _motorIntent.get().generation != stopIntent.generation) {
                discard(stopIntent.generation, _motorIntent.get().generation, "stop before application")
                return@withLock StopDiagnosticStatus.STOP_REQUESTED
            }
            if (!usbConnection.isConnected()) {
                currentAppliedRaState = null
                currentAppliedDecState = null
                val warning = "SAFETY WARNING: STOP unavailable — USB connection lost; physical motor state unknown"
                logger(warning, true)
                onErrorWarning(warning)
                return@withLock StopDiagnosticStatus.STOP_UNAVAILABLE_CONNECTION_LOST
            }

            var stopFailed = false
            // A partial or missing acknowledgement leaves both physical axes uncertain.
            currentAppliedRaState = null
            currentAppliedDecState = null

            // Each axis: guide POS/NEG followed by fixed POS/NEG; RA precedes DEC.
            val packets = listOf(SlewAxis.RA, SlewAxis.DEC).flatMap { axis ->
                SlewDirection.entries.map { direction -> NexStarProtocol.guideZero(axis, direction) } +
                    SlewDirection.entries.map { direction -> NexStarProtocol.fixedSlew(axis, direction, 0) }
            }
            for (packet in packets) {
                try {
                    val rx = usbConnection.transactSafe(packet, timeoutMs, writeGate = if (onlyIfCurrent) {
                        write -> admitWrite(stopIntent.generation, write = write)
                    } else null)
                    logger("STOP TX ${packet.toHexString()} RX ${if (rx.isEmpty()) "NO RESPONSE" else rx.toHexString()}", false)
                    if (rx.size != 1 || rx[0] != '#'.code.toByte()) stopFailed = true
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (e is StaleMovementException) return@withLock StopDiagnosticStatus.STOP_REQUESTED
                    logger("STOP TX ${packet.toHexString()} FAILED: ${e.message}", true)
                    stopFailed = true
                }
            }

            if (onlyIfCurrent && _motorIntent.get().generation != stopIntent.generation) {
                discard(stopIntent.generation, _motorIntent.get().generation, "stop after application")
                return@withLock StopDiagnosticStatus.STOP_REQUESTED
            }
            if (!stopFailed) {
                currentAppliedRaState = DesiredAxisState.Stop
                currentAppliedDecState = DesiredAxisState.Stop
            }

            if (stopFailed) {
                val warnMsg = "SAFETY WARNING: Emergency STOP packet failed or missing '#' response!"
                logger(warnMsg, true)
                onErrorWarning(warnMsg)
                StopDiagnosticStatus.STOP_REQUESTED
            } else {
                logger("GEN ${stopIntent.generation} applied STOP", false)
                StopDiagnosticStatus.STOP_COMMANDS_ACKNOWLEDGED
            }
        } }
    }

    /** The write completes before a newer generation can be published. */
    fun admitWrite(generation: Long, extraCheck: () -> Boolean = { true }, write: () -> Unit) {
        synchronized(generationLock) {
            val current = _motorIntent.get().generation
            if (current != generation || !extraCheck()) {
                discard(generation, current, "write gate")
                throw StaleMovementException()
            }
            write()
        }
    }

    private suspend fun runMotorWorker() {
        for (intent in stateChannel) {
            logger("GEN ${intent.generation} requested RA=${intent.ra.toLogString()} DEC=${intent.dec.toLogString()} (${intent.reason})", false)

            movementMutex.withLock {
                val currentGen = _motorIntent.get().generation
                if (intent.generation != currentGen) {
                    discard(intent.generation, currentGen, "worker admission")
                    return@withLock
                }

                if (!usbConnection.isConnected()) return@withLock

                // 1. Apply RA if changed
                if (intent.ra != currentAppliedRaState) {
                    val ok = applySingleAxisState(SlewAxis.RA, currentAppliedRaState, intent.ra, intent.generation, intent.reason)
                    if (ok) {
                        currentAppliedRaState = intent.ra
                    } else {
                        currentAppliedRaState = null
                        if (_motorIntent.get().generation != intent.generation) return@withLock
                        onErrorWarning("RA movement command failed; issuing emergency STOP")
                        launchEmergencyStop()
                        return@withLock
                    }
                }

                // Re-verify generation before DEC transmission!
                val decGenCheck = _motorIntent.get().generation
                if (intent.generation != decGenCheck) {
                    discard(intent.generation, decGenCheck, "before DEC")
                    return@withLock
                }

                // 2. Apply DEC if changed
                if (intent.dec != currentAppliedDecState) {
                    val ok = applySingleAxisState(SlewAxis.DEC, currentAppliedDecState, intent.dec, intent.generation, intent.reason)
                    if (ok) {
                        currentAppliedDecState = intent.dec
                    } else {
                        currentAppliedDecState = null
                        if (_motorIntent.get().generation != intent.generation) return@withLock
                        onErrorWarning("DEC movement command failed; issuing emergency STOP")
                        launchEmergencyStop()
                    }
                }
            }
        }
    }

    private fun launchEmergencyStop() {
        onMovementFailure()
    }

    private suspend fun movementTransaction(command: ByteArray, generation: Long) {
        val response = usbConnection.transactSafe(command, writeGate = { write ->
            admitWrite(generation, write = write)
        })
        if (response.size != 1 || response[0] != '#'.code.toByte()) throw IOException("Invalid movement acknowledgement")
    }

    private suspend fun applySingleAxisState(
        axis: SlewAxis,
        prevApplied: DesiredAxisState?,
        newTarget: DesiredAxisState,
        generation: Long,
        reason: String
    ): Boolean {
        logger("GEN $generation axis=$axis state=${newTarget.toLogString()} reason=$reason", false)
        try {
            suspend fun stopFixed() {
                for (direction in SlewDirection.entries) {
                    movementTransaction(NexStarProtocol.fixedSlew(axis, direction, 0), generation)
                }
            }
            suspend fun stopGuide() {
                for (direction in SlewDirection.entries) {
                    movementTransaction(NexStarProtocol.guideZero(axis, direction), generation)
                }
            }

            // An unknown axis may still be moving in either command family.
            when (prevApplied) {
                null -> { stopGuide(); stopFixed() }
                is DesiredAxisState.Fixed -> if (newTarget !is DesiredAxisState.Fixed ||
                    newTarget.direction != prevApplied.direction || newTarget.rate == 0) stopFixed()
                is DesiredAxisState.Variable -> if (newTarget !is DesiredAxisState.Variable ||
                    abs(newTarget.arcsecPerSec) < 0.01 ||
                    (prevApplied.arcsecPerSec < 0) != (newTarget.arcsecPerSec < 0)) stopGuide()
                DesiredAxisState.Stop -> Unit
            }

            when (newTarget) {
                DesiredAxisState.Stop -> Unit
                is DesiredAxisState.Variable -> if (abs(newTarget.arcsecPerSec) >= 0.01) {
                    movementTransaction(NexStarProtocol.variableSlew(axis, newTarget.arcsecPerSec), generation)
                }
                is DesiredAxisState.Fixed -> if (newTarget.rate != 0) {
                    movementTransaction(NexStarProtocol.fixedSlew(axis, newTarget.direction, newTarget.rate), generation)
                }
            }
            logger("GEN $generation applied ${newTarget.toLogString()}", false)
            return true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is StaleMovementException) return false
            logger("GEN $generation axis=$axis state application error: ${e.message}", true)
            return false
        }
    }
}
