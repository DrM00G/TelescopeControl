package com.example.telescopecontrol.model

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

data class EquatorialCoordinates(
    val raDegrees: Double,
    val decDegrees: Double,
    val raHours: Double,
    val rawResponse: String
) {
    /**
     * Formats RA as HHh MMm SS.s
     */
    val raFormatted: String
        get() {
            var h = raHours % 24.0
            if (h < 0) h += 24.0
            val hours = floor(h).toInt()
            val remMin = (h - hours) * 60.0
            val minutes = floor(remMin).toInt()
            val seconds = (remMin - minutes) * 60.0
            return "%02dh %02dm %04.1fs".format(hours, minutes, seconds)
        }

    /**
     * Formats DEC as +DD° MM' SS" or -DD° MM' SS"
     */
    val decFormatted: String
        get() {
            val sign = if (decDegrees >= 0) "+" else "-"
            val absDec = abs(decDegrees)
            val deg = floor(absDec).toInt()
            val remMin = (absDec - deg) * 60.0
            val min = floor(remMin).toInt()
            val sec = round((remMin - min) * 60.0).toInt()
            return "%s%02d° %02d' %02d\"".format(sign, deg, min, sec)
        }
}
