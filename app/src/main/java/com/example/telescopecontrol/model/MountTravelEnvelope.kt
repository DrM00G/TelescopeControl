package com.example.telescopecontrol.model

/**
 * TODO: Architecture hook for future configurable software travel and collision limits.
 *
 * Physical mount travel limits, meridian flip boundaries, cable wrap limits, and OTA
 * collision bounds will be checked against raw motor positions and equatorial coordinates
 * before issuing slews.
 */
data class MountTravelEnvelope(
    val maxRaDegrees: Double = 360.0,
    val minRaDegrees: Double = 0.0,
    val maxDecDegrees: Double = 90.0,
    val minDecDegrees: Double = -90.0,
    val enableSoftwareLimits: Boolean = false
) {
    /**
     * Safety check hook for future travel limit enforcement.
     */
    fun isSafeToMove(axis: SlewAxis, direction: SlewDirection): Boolean {
        // Unconstrained in v0.3.2 until OTA/pier geometry is configured by user.
        return true
    }
}
