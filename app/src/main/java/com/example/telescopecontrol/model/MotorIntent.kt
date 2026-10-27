package com.example.telescopecontrol.model

/**
 * Immutable authoritative snapshot of user motor intent.
 * Updated atomically using atomic reference / state flow.
 */
data class MotorIntent(
    val generation: Long = 0L,
    val ra: DesiredAxisState = DesiredAxisState.Stop,
    val dec: DesiredAxisState = DesiredAxisState.Stop,
    val reason: String = "INITIALIZATION"
)
