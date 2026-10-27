package com.example.telescopecontrol.coordinator

import com.example.telescopecontrol.model.MountStatus
import com.example.telescopecontrol.model.StopDiagnosticStatus

/** Serial heartbeat recovery orchestration. Call once per completed heartbeat, on one coroutine. */
class HeartbeatRecovery(private val motor: MotorCommandCoordinator) {
    private val policy = HeartbeatPolicy()
    var needsStopOnRecovery: Boolean = false
        private set

    data class Outcome(val heartbeat: HeartbeatPolicy.Result, val stopStatus: StopDiagnosticStatus? = null)

    fun reset() {
        policy.reset()
        // An interrupted heartbeat remains a safety obligation across reconnects.
        if (!needsStopOnRecovery) motor.endHeartbeatInhibit()
    }

    suspend fun record(
        raOk: Boolean,
        decOk: Boolean,
        previous: MountStatus,
        onFailure: (HeartbeatPolicy.Result) -> Unit = {},
        verifyStationary: suspend (Long) -> Boolean? = { null }
    ): Outcome {
        val result = policy.record(raOk, decOk, previous)
        if (!raOk || !decOk) {
            needsStopOnRecovery = true
            val intent = motor.beginHeartbeatInhibit("HEARTBEAT_FAILURE")
            onFailure(result)
            if (result.quiet) {
                motor.markAppliedUnknownIfCurrent(intent.generation)
                return Outcome(result)
            }
            val status = motor.stopIfCurrent(intent, 250)
            return Outcome(result, status)
        }
        if (needsStopOnRecovery) {
            val intent = motor.beginHeartbeatInhibit("HEARTBEAT_RECOVERY_STOP")
            val status = motor.stopIfCurrent(intent, 250)
            var diagnostic = status
            if (status == StopDiagnosticStatus.STOP_COMMANDS_ACKNOWLEDGED) {
                // ACK alone cannot release the recovery inhibit, including when verification is unavailable.
                if (verifyStationary(intent.generation) == true && motor.currentIntent.generation == intent.generation) {
                    diagnostic = StopDiagnosticStatus.STOP_VERIFIED
                    needsStopOnRecovery = false
                }
                policy.reset()
            }
            return Outcome(result.copy(successes = 0, ready = false), diagnostic)
        }
        if (result.ready) motor.endHeartbeatInhibit()
        return Outcome(result)
    }
}
