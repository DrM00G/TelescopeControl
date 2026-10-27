package com.example.telescopecontrol.nexstar

import com.example.telescopecontrol.model.EquatorialCoordinates
import com.example.telescopecontrol.model.SlewAxis
import com.example.telescopecontrol.model.SlewDirection
import com.example.telescopecontrol.model.TrackingMode
import kotlin.math.abs
import kotlin.math.roundToLong

object NexStarProtocol {

    /**
     * Echo command ('K' + echo byte 'A').
     * Expected response: 'A' + '#' (0x41 0x23).
     */
    val ECHO_TEST: ByteArray = byteArrayOf(
        'K'.code.toByte(),
        'A'.code.toByte()
    )

    /**
     * Standard Get RA/DEC coordinates command ('E').
     * Expected response format: "34AB,12CE#"
     */
    val GET_RA_DEC_STANDARD: ByteArray = byteArrayOf(
        'E'.code.toByte()
    )

    /**
     * Precise Get RA/DEC coordinates command ('e').
     * Expected response format: "34AB0500,12CE0500#"
     */
    val GET_RA_DEC_PRECISE: ByteArray = byteArrayOf(
        'e'.code.toByte()
    )

    /**
     * Get alignment status command ('J').
     * Response: binary 0 (not aligned) or 1 (aligned) + '#'
     */
    val GET_ALIGNMENT_STATUS: ByteArray = byteArrayOf(
        'J'.code.toByte()
    )

    /**
     * Get hand controller version command ('V').
     * Response: major binary byte, minor binary byte + '#'
     */
    val GET_VERSION: ByteArray = byteArrayOf(
        'V'.code.toByte()
    )

    /**
     * Get tracking mode command ('t').
     * Response: one binary mode byte + '#'
     */
    val GET_TRACKING_MODE: ByteArray = byteArrayOf(
        't'.code.toByte()
    )

    /**
     * Query RA Motor Controller (Device 16 / 0x10) Firmware Version.
     * Pass-through packet: 'P', 1, 16, 254 (0xFE), 0, 0, 0, 2
     * Expected response: major byte, minor byte, '#' (3 bytes total).
     */
    val GET_RA_MC_VERSION: ByteArray = byteArrayOf(
        'P'.code.toByte(),
        1,
        16,
        254.toByte(),
        0,
        0,
        0,
        2
    )

    /**
     * Query DEC Motor Controller (Device 17 / 0x11) Firmware Version.
     * Pass-through packet: 'P', 1, 17, 254 (0xFE), 0, 0, 0, 2
     * Expected response: major byte, minor byte, '#' (3 bytes total).
     */
    val GET_DEC_MC_VERSION: ByteArray = byteArrayOf(
        'P'.code.toByte(),
        1,
        17,
        254.toByte(),
        0,
        0,
        0,
        2
    )

    /**
     * Query RA Motor Controller (Device 16 / 0x10) Raw Encoder Position.
     * Pass-through packet: 'P', 1, 16, 1 (MC_GET_POSITION), 0, 0, 0, 3
     * Expected response: 3 raw position bytes + '#' (4 bytes total).
     */
    val GET_RA_MC_POS: ByteArray = byteArrayOf(
        'P'.code.toByte(),
        1,
        16,
        1,
        0,
        0,
        0,
        3
    )

    /**
     * Query DEC Motor Controller (Device 17 / 0x11) Raw Encoder Position.
     * Pass-through packet: 'P', 1, 17, 1 (MC_GET_POSITION), 0, 0, 0, 3
     * Expected response: 3 raw position bytes + '#' (4 bytes total).
     */
    val GET_DEC_MC_POS: ByteArray = byteArrayOf(
        'P'.code.toByte(),
        1,
        17,
        1,
        0,
        0,
        0,
        3
    )

    /**
     * Set tracking mode command ('T' + binary mode byte).
     * Response: '#'
     */
    fun setTrackingModeCommand(mode: TrackingMode): ByteArray {
        return byteArrayOf(
            'T'.code.toByte(),
            mode.protocolValue.toByte()
        )
    }

    /**
     * Celestron fixed-rate pass-through slew packet (8 bytes).
     * 'P', 2, device, command, rate, 0, 0, 0
     * Rate 0 stops movement.
     */
    fun fixedSlew(
        axis: SlewAxis,
        direction: SlewDirection,
        rate: Int
    ): ByteArray {
        require(rate in 0..9) { "Slew rate must be between 0 and 9" }
        return byteArrayOf(
            'P'.code.toByte(),
            2,
            axis.deviceId.toByte(),
            direction.commandId.toByte(),
            rate.toByte(),
            0,
            0,
            0
        )
    }

    /** Guide-family stop uses the two-byte zero payload, independently of variableSlew. */
    fun guideZero(axis: SlewAxis, direction: SlewDirection): ByteArray = byteArrayOf(
        0x50, 0x03, axis.deviceId.toByte(),
        (if (direction == SlewDirection.POSITIVE) 0x06 else 0x07).toByte(),
        0, 0, 0, 0
    )

    /**
     * Celestron 24-bit variable-rate pass-through slew packet (8 bytes).
     * 'P', 4, device, command, HIGH, MID, LOW, 0
     *
     * Length byte is 0x04 (1 command byte + 3 rate bytes).
     * Command 6 = Positive velocity, Command 7 = Negative velocity.
     * Resolution: 1/1024 arcsec/sec -> scaledRate = round(abs(rateArcsecPerSec) * 1024).
     */
    fun variableSlew(
        axis: SlewAxis,
        rateArcsecPerSec: Double
    ): ByteArray {
        val absRate = abs(rateArcsecPerSec)
        val scaledRate = (absRate * 1024.0).roundToLong().coerceIn(0L, 16777215L) // 24-bit max 0xFFFFFF
        val highByte = ((scaledRate shr 16) and 0xFF).toInt()
        val midByte = ((scaledRate shr 8) and 0xFF).toInt()
        val lowByte = (scaledRate and 0xFF).toInt()
        val commandId = if (rateArcsecPerSec >= 0) 6 else 7

        return byteArrayOf(
            0x50,
            4, // Length byte must be 4 to forward 3 rate payload bytes to the MC
            axis.deviceId.toByte(),
            commandId.toByte(),
            highByte.toByte(),
            midByte.toByte(),
            lowByte.toByte(),
            0
        )
    }

    /**
     * Parses the response for the Alignment command ('J').
     * Binary 1 = Aligned, Binary 0 = Not Aligned.
     */
    fun parseAlignmentStatus(response: ByteArray): Boolean {
        if (response.isEmpty()) return false
        return (response[0].toInt() and 0xFF) == 1
    }

    /**
     * Parses the response for the HC Version command ('V').
     * Returns "major.minor".
     */
    fun parseVersion(response: ByteArray): String {
        if (response.size < 2) return "Unknown"
        val major = response[0].toInt() and 0xFF
        val minor = response[1].toInt() and 0xFF
        return "$major.$minor"
    }

    /**
     * Parses the response for Motor Controller Version pass-through query.
     * Must be exactly 3 bytes ending in '#'. Returns "major.minor" if valid, or null if failed.
     */
    fun parseMotorControllerVersion(response: ByteArray): String? {
        if (response.size != 3 || response.last() != '#'.code.toByte()) {
            return null
        }
        val major = response[0].toInt() and 0xFF
        val minor = response[1].toInt() and 0xFF
        return "$major.$minor"
    }

    /**
     * Parses response for Motor Controller Raw Encoder Position pass-through query.
     * Must be exactly 4 bytes ending in '#'. Returns 6-digit hex string (e.g. "812345").
     */
    fun parseRawMotorPosition(response: ByteArray): String? {
        if (response.size != 4 || response.last() != '#'.code.toByte()) {
            return null
        }
        val rawInt = ((response[0].toInt() and 0xFF) shl 16) or
                ((response[1].toInt() and 0xFF) shl 8) or
                (response[2].toInt() and 0xFF)
        return "%06X".format(rawInt)
    }

    /**
     * Parses the response for Get Tracking Mode command ('t').
     */
    fun parseTrackingMode(response: ByteArray): TrackingMode {
        if (response.size != 2 || response[1] != '#'.code.toByte()) return TrackingMode.UNKNOWN
        val modeVal = response[0].toInt() and 0xFF
        return TrackingMode.fromProtocolValue(modeVal)
    }

    /**
     * Parses response for Precise Get RA/DEC ('e').
     * Expected format: XXXXXXXX,YYYYYYYY#
     */
    fun parsePreciseCoordinates(response: ByteArray): EquatorialCoordinates {
        val str = String(response, Charsets.US_ASCII).trimEnd('#')
        val parts = str.split(',')
        if (parts.size != 2 || parts[0].length < 4 || parts[1].length < 4) {
            throw IllegalArgumentException("Invalid precise RA/DEC format: $str")
        }

        val rawRa = parts[0].toLong(16)
        val rawDec = parts[1].toLong(16)

        var raDegrees = (rawRa.toDouble() / 4294967296.0) * 360.0
        raDegrees = ((raDegrees % 360.0) + 360.0) % 360.0

        var decDegrees = (rawDec.toDouble() / 4294967296.0) * 360.0
        if (decDegrees >= 180.0) {
            decDegrees -= 360.0
        }

        val raHours = raDegrees / 15.0

        return EquatorialCoordinates(
            raDegrees = raDegrees,
            decDegrees = decDegrees,
            raHours = raHours,
            rawResponse = String(response, Charsets.US_ASCII)
        )
    }
}

/**
 * Converts a byte array into a space-separated hexadecimal string.
 * Example: byteArrayOf(0x41, 0x23) -> "41 23"
 */
fun ByteArray.toHexString(): String {
    return joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}

/**
 * Converts a byte array into a string where printable ASCII characters are kept,
 * and non-printable bytes are represented in <HEX> format.
 */
fun ByteArray.toPrintableAscii(): String {
    return buildString {
        for (byte in this@toPrintableAscii) {
            val value = byte.toInt() and 0xFF
            when {
                value in 32..126 -> append(value.toChar())
                else -> append("<%02X>".format(value))
            }
        }
    }
}
