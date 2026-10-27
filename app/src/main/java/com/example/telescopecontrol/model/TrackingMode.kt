package com.example.telescopecontrol.model

enum class TrackingMode(val protocolValue: Int, val label: String) {
    OFF(0, "Off"),
    ALT_AZ(1, "Alt/Az"),
    EQ_NORTH(2, "EQ North"),
    EQ_SOUTH(3, "EQ South"),
    UNKNOWN(-1, "Unknown");

    companion object {
        fun fromProtocolValue(value: Int): TrackingMode {
            return entries.firstOrNull { it.protocolValue == value } ?: UNKNOWN
        }
    }
}
