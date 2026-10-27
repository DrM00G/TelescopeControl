package com.example.telescopecontrol.model

enum class AlignmentTrust(val label: String) {
    UNALIGNED("NOT ALIGNED"),
    TRUSTED("ALIGNED"),
    STALE_AFTER_POWER_LOSS("STALE (POWER RECOVERED)")
}
