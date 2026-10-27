package com.example.telescopecontrol.model

import java.util.Locale

sealed class DesiredAxisState {

    data object Stop : DesiredAxisState()

    data class Variable(
        val arcsecPerSec: Double
    ) : DesiredAxisState()

    data class Fixed(
        val direction: SlewDirection,
        val rate: Int
    ) : DesiredAxisState()

    fun toLogString(): String = when (this) {
        is Stop -> "STOP"
        is Variable -> "VARIABLE(%+.2f\"/s)".format(Locale.US, arcsecPerSec)
        is Fixed -> "FIXED($direction, rate=$rate)"
    }
}
