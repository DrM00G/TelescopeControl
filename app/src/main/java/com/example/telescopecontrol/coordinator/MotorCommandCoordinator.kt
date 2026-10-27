package com.example.telescopecontrol.coordinator

import com.example.telescopecontrol.model.DesiredAxisState
import com.example.telescopecontrol.model.MotorIntent
import com.example.telescopecontrol.model.SlewAxis
import com.example.telescopecontrol.model.SlewDirection
import com.example.telescopecontrol.model.StopDiagnosticStatus
import com.example.telescopecontrol.nexstar.NexStarProtocol
import com.example.telescopecontrol.nexstar.toHexString
import com.example.telescopecontrol.usb.NexStarUsbConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val usbConnection: NexStarUsbConnection,
    private val logger: (message: String, isError: Boolean) -> Unit,
    private val onErrorWarning: (warning: String) -> Unit
) {
    private val _motorIntent = AtomicReference(MotorIntent())

    private var currentAppliedRaState: DesiredAxisState = DesiredAxisState.Stop
    private var currentAppliedDecState: DesiredAxisState = DesiredAxisState.Stop

    private val stateChannel = Channel<MotorIntent>(Channel.CONFLATED)
    private var workerJob: Job? = null

    // High-level coordinator mutex protecting complete movement application sequences
    private val movementMutex = Mutex()

    val currentIntent: MotorIntent
        get() = _motorIntent.get()

    fun start(scope: CoroutineScope) {
        stopWorker()
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
        val newIntent = _motorIntent.updateAndGet { current ->
            MotorIntent(
                generation = current.generation + 1,
                ra = raState,
                dec = decState,
                reason = reason
            )
        }
        stateChannel.trySend(newIntent)
        return newIntent
    }

    /**
     * Resets internal applied state tracking without transmitting over USB.
     * Useful on USB disconnect or mount power loss.
     */
    fun resetStatesQuietly(): MotorIntent {
        val resetIntent = _motorIntent.updateAndGet { current ->
            MotorIntent(
                generation = current.generation + 1,
                ra = DesiredAxisState.Stop,
                dec = DesiredAxisState.Stop,
                reason = "POWER_LOSS_OR_DISCONNECT"
            )
        }
        currentAppliedRaState = DesiredAxisState.Stop
        currentAppliedDecState = DesiredAxisState.Stop
        while (stateChannel.tryReceive().isSuccess) {}
        return resetIntent
    }

    /**
     * Emergency STOP sequence with absolute coordinator authority.
     * Atomically increments generation, sets intent to Stop, acquires movementMutex,
     * and sends 4 physical STOP packets (POS 0 and NEG 0 for both RA and DEC).
     */
    suspend fun emergencyStop(reason: String): StopDiagnosticStatus {
        val stopIntent = _motorIntent.updateAndGet { current ->
            MotorIntent(
                generation = current.generation + 1,
                ra = DesiredAxisState.Stop,
                dec = DesiredAxisState.Stop,
                reason = reason
            )
        }

        // Drain any pending worker signals
        while (stateChannel.tryReceive().isSuccess) {}

        logger("GEN ${stopIntent.generation} requested STOP ($reason)", false)

        return movementMutex.withLock {
            val currentGen = _motorIntent.get().generation
            if (stopIntent.generation != currentGen) {
                logger("GEN ${stopIntent.generation} DISCARDED — current generation $currentGen", false)
                return@withLock StopDiagnosticStatus.NONE
            }

            if (!usbConnection.isConnected()) {
                currentAppliedRaState = DesiredAxisState.Stop
                currentAppliedDecState = DesiredAxisState.Stop
                return@withLock StopDiagnosticStatus.STOP_COMMANDS_ACKNOWLEDGED
            }

            var stopFailed = false

            // 1. RA POS rate 0
            val raPosBytes = NexStarProtocol.fixedSlew(SlewAxis.RA, SlewDirection.POSITIVE, 0)
            try {
                val rx = usbConnection.transactSafe(raPosBytes)
                val rxStr = if (rx.isNotEmpty()) rx.toHexString() else "NO RESPONSE"
                logger("EMERGENCY STOP RA POS:\nTX ${raPosBytes.toHexString()}\nRX: $rxStr", false)
                if (rx.isEmpty() || rx.last() != '#'.code.toByte()) stopFailed = true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger("EMERGENCY STOP RA POS FAILED: ${e.message}", true)
                stopFailed = true
            }

            // 2. RA NEG rate 0
            val raNegBytes = NexStarProtocol.fixedSlew(SlewAxis.RA, SlewDirection.NEGATIVE, 0)
            try {
                val rx = usbConnection.transactSafe(raNegBytes)
                val rxStr = if (rx.isNotEmpty()) rx.toHexString() else "NO RESPONSE"
                logger("EMERGENCY STOP RA NEG:\nTX ${raNegBytes.toHexString()}\nRX: $rxStr", false)
                if (rx.isEmpty() || rx.last() != '#'.code.toByte()) stopFailed = true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger("EMERGENCY STOP RA NEG FAILED: ${e.message}", true)
                stopFailed = true
            }

            // 3. DEC POS rate 0
            val decPosBytes = NexStarProtocol.fixedSlew(SlewAxis.DEC, SlewDirection.POSITIVE, 0)
            try {
                val rx = usbConnection.transactSafe(decPosBytes)
                val rxStr = if (rx.isNotEmpty()) rx.toHexString() else "NO RESPONSE"
                logger("EMERGENCY STOP DEC POS:\nTX ${decPosBytes.toHexString()}\nRX: $rxStr", false)
                if (rx.isEmpty() || rx.last() != '#'.code.toByte()) stopFailed = true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger("EMERGENCY STOP DEC POS FAILED: ${e.message}", true)
                stopFailed = true
            }

            // 4. DEC NEG rate 0
            val decNegBytes = NexStarProtocol.fixedSlew(SlewAxis.DEC, SlewDirection.NEGATIVE, 0)
            try {
                val rx = usbConnection.transactSafe(decNegBytes)
                val rxStr = if (rx.isNotEmpty()) rx.toHexString() else "NO RESPONSE"
                logger("EMERGENCY STOP DEC NEG:\nTX ${decNegBytes.toHexString()}\nRX: $rxStr", false)
                if (rx.isEmpty() || rx.last() != '#'.code.toByte()) stopFailed = true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger("EMERGENCY STOP DEC NEG FAILED: ${e.message}", true)
                stopFailed = true
            }

            currentAppliedRaState = DesiredAxisState.Stop
            currentAppliedDecState = DesiredAxisState.Stop

            if (stopFailed) {
                val warnMsg = "SAFETY WARNING: Emergency STOP packet failed or missing '#' response!"
                logger(warnMsg, true)
                onErrorWarning(warnMsg)
                StopDiagnosticStatus.STOP_REQUESTED
            } else {
                logger("GEN ${stopIntent.generation} applied STOP", false)
                StopDiagnosticStatus.STOP_COMMANDS_ACKNOWLEDGED
            }
        }
    }

    private suspend fun runMotorWorker() {
        for (intent in stateChannel) {
            logger("GEN ${intent.generation} requested RA=${intent.ra.toLogString()} DEC=${intent.dec.toLogString()} (${intent.reason})", false)

            movementMutex.withLock {
                val currentGen = _motorIntent.get().generation
                if (intent.generation != currentGen) {
                    logger("GEN ${intent.generation} DISCARDED — current generation $currentGen", false)
                    return@withLock
                }

                if (!usbConnection.isConnected()) return@withLock

                // 1. Apply RA if changed
                if (intent.ra != currentAppliedRaState) {
                    val ok = applySingleAxisState(SlewAxis.RA, currentAppliedRaState, intent.ra, intent.generation, intent.reason)
                    if (ok) {
                        currentAppliedRaState = intent.ra
                    }
                }

                // Re-verify generation before DEC transmission!
                val decGenCheck = _motorIntent.get().generation
                if (intent.generation != decGenCheck) {
                    logger("GEN ${intent.generation} DISCARDED before DEC — current generation $decGenCheck", false)
                    return@withLock
                }

                // 2. Apply DEC if changed
                if (intent.dec != currentAppliedDecState) {
                    val ok = applySingleAxisState(SlewAxis.DEC, currentAppliedDecState, intent.dec, intent.generation, intent.reason)
                    if (ok) {
                        currentAppliedDecState = intent.dec
                    }
                }
            }
        }
    }

    private suspend fun applySingleAxisState(
        axis: SlewAxis,
        prevApplied: DesiredAxisState,
        newTarget: DesiredAxisState,
        generation: Long,
        reason: String
    ): Boolean {
        logger("GEN $generation axis=$axis state=${newTarget.toLogString()} reason=$reason", false)
        try {
            // If previously in Fixed slew and transitioning out, send explicit rate-0 STOP first
            if (prevApplied is DesiredAxisState.Fixed && newTarget !is DesiredAxisState.Fixed) {
                val stopBytes = NexStarProtocol.fixedSlew(axis, SlewDirection.POSITIVE, 0)
                usbConnection.transactSafe(stopBytes)
            }

            when (newTarget) {
                is DesiredAxisState.Stop -> {
                    val stopBytes = NexStarProtocol.fixedSlew(axis, SlewDirection.POSITIVE, 0)
                    usbConnection.transactSafe(stopBytes)
                }
                is DesiredAxisState.Variable -> {
                    val rate = newTarget.arcsecPerSec
                    if (abs(rate) < 0.01) {
                        val stopBytes = NexStarProtocol.fixedSlew(axis, SlewDirection.POSITIVE, 0)
                        usbConnection.transactSafe(stopBytes)
                    } else {
                        val varBytes = NexStarProtocol.variableSlew(axis, rate)
                        usbConnection.transactSafe(varBytes)
                    }
                }
                is DesiredAxisState.Fixed -> {
                    val fixedBytes = NexStarProtocol.fixedSlew(axis, newTarget.direction, newTarget.rate)
                    usbConnection.transactSafe(fixedBytes)
                }
            }
            logger("GEN $generation applied ${newTarget.toLogString()}", false)
            return true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger("GEN $generation axis=$axis state application error: ${e.message}", true)
            return false
        }
    }
}
