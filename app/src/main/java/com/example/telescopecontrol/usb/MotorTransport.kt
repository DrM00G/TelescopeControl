package com.example.telescopecontrol.usb

/** Serialized transport used by the motor coordinator. The write gate must surround the physical write. */
interface MotorTransport {
    fun isConnected(): Boolean
    suspend fun closeSafely()
    suspend fun transactSafe(command: ByteArray, timeoutMs: Long = 4000, writeGate: ((() -> Unit) -> Unit)? = null): ByteArray
}
