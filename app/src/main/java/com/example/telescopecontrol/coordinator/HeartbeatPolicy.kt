package com.example.telescopecontrol.coordinator

import com.example.telescopecontrol.model.MountStatus

/** Pure heartbeat hysteresis; a partial response interrupts a run of complete failures. */
class HeartbeatPolicy {
    private var completeFailures = 0
    private var successes = 0

    data class Result(val status: MountStatus, val successes: Int, val ready: Boolean, val stop: Boolean, val quiet: Boolean)

    fun reset() { completeFailures = 0; successes = 0 }

    fun record(raOk: Boolean, decOk: Boolean, previous: MountStatus): Result {
        if (raOk && decOk) {
            completeFailures = 0
            successes = (successes + 1).coerceAtMost(2)
            return Result(MountStatus.ONLINE, successes, successes >= 2, false, false)
        }
        successes = 0
        if (raOk || decOk) {
            completeFailures = 0
            return Result(MountStatus.PARTIAL, 0, false, true, false)
        }
        completeFailures++
        return if (completeFailures == 1) Result(MountStatus.SUSPECT, 0, false, true, false)
        else Result(MountStatus.OFFLINE, 0, false, false, true)
    }
}
