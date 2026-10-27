package com.example.telescopecontrol.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.example.telescopecontrol.nexstar.NexStarProtocol
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

class NexStarUsbConnection(
    private val context: Context
) : MotorTransport {

    private val usbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    @Volatile private var port: UsbSerialPort? = null
    private val transactionMutex = Mutex()

    /** Called at physical I/O boundaries, including partial reads and failed writes. */
    @Volatile var wireLogger: ((String, Long, ByteArray, String?) -> Unit)? = null
    /** Snapshot at transaction admission; guarded movement writes replace this at physical TX. */
    @Volatile var generationProvider: (() -> Long)? = null

    private fun trace(direction: String, generation: Long, bytes: ByteArray, detail: String? = null) {
        try { wireLogger?.invoke(direction, generation, bytes, detail) } catch (_: Exception) { /* Diagnostics must not affect motor safety. */ }
    }

    /**
     * Finds all drivers recognized by usb-serial-for-android.
     */
    fun findDrivers(): List<UsbSerialDriver> {
        return UsbSerialProber
            .getDefaultProber()
            .findAllDrivers(usbManager)
    }

    /**
     * Probes a specific USB device for a matching serial driver.
     */
    fun probeDevice(device: UsbDevice): UsbSerialDriver? {
        return UsbSerialProber.getDefaultProber().probeDevice(device)
    }

    /**
     * Returns all attached USB devices from Android UsbManager.
     */
    fun getUsbDevices(): List<UsbDevice> {
        return usbManager.deviceList.values.toList()
    }

    fun hasPermission(driver: UsbSerialDriver): Boolean {
        return usbManager.hasPermission(driver.device)
    }

    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    @Throws(IOException::class)
    fun open(driver: UsbSerialDriver) {
        val connection = usbManager.openDevice(driver.device)
            ?: throw IOException(
                "Could not open USB device. USB permission may not have been granted."
            )

        val selectedPort = driver.ports.firstOrNull()
            ?: throw IOException(
                "USB serial driver contains no serial ports."
            )

        selectedPort.open(connection)

        // Celestron NexStar serial settings: 9600 baud, 8 data bits, 1 stop bit, no parity
        selectedPort.setParameters(
            9600,
            8,
            UsbSerialPort.STOPBITS_1,
            UsbSerialPort.PARITY_NONE
        )

        port = selectedPort
    }

    override suspend fun closeSafely() = transactionMutex.withLock {
        withContext(Dispatchers.IO) { close() }
    }

    private fun close() {
        try {
            port?.close()
        } catch (_: Exception) {
        } finally {
            port = null
        }
    }

    override fun isConnected(): Boolean {
        return port != null
    }

    /**
     * Thread-safe transaction wrapper to guarantee single-transaction-at-a-time execution off UI thread.
     */
    override suspend fun transactSafe(
        command: ByteArray,
        timeoutMs: Long,
        writeGate: ((() -> Unit) -> Unit)?
    ): ByteArray = transactionMutex.withLock {
        withContext(Dispatchers.IO) {
            transact(command, timeoutMs, writeGate)
        }
    }

    @Throws(IOException::class)
    private fun transact(
        command: ByteArray,
        timeoutMs: Long = 4000,
        writeGate: ((() -> Unit) -> Unit)? = null
    ): ByteArray {
        val serialPort = port
            ?: throw IOException("USB serial port is not connected.")

        // Clear stale input before beginning a new transaction if supported.
        try {
            serialPort.purgeHwBuffers(false, true)
        } catch (_: Exception) {
            // Some drivers may not support this.
        }

        var transactionGeneration = generationProvider?.invoke() ?: 0L
        val write = {
            transactionGeneration = generationProvider?.invoke() ?: 0L
            try {
                serialPort.write(command, if (writeGate == null) 1000 else 50)
                trace("TX", transactionGeneration, command)
            } catch (e: Exception) {
                trace("TX_ERROR", transactionGeneration, command, e.message)
                throw e
            }
        }
        if (writeGate == null) write() else writeGate(write)

        // Binary replies may contain '#'. Their protocol-defined payload length, not
        // the first delimiter byte, determines the end of the response.
        val expectedLength = when {
            command.contentEquals(NexStarProtocol.GET_VERSION) -> 3
            command.contentEquals(NexStarProtocol.GET_ALIGNMENT_STATUS) -> 2
            command.contentEquals(NexStarProtocol.GET_TRACKING_MODE) -> 2
            command.size == 8 && command[0] == 'P'.code.toByte() -> (command[7].toInt() and 0xff) + 1
            command.contentEquals(NexStarProtocol.ECHO_TEST) -> 2
            else -> null
        }
        val response = mutableListOf<Byte>()
        val buffer = ByteArray(64)
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L

        while (System.nanoTime() < deadline) {
            val remaining = (deadline - System.nanoTime() + 999_999L) / 1_000_000L
            if (remaining <= 0) {
                break
            }

            val readTimeout = minOf(remaining, 500L).toInt()
            val count = try {
                serialPort.read(buffer, readTimeout)
            } catch (e: Exception) {
                trace("RX_ERROR", transactionGeneration, response.toByteArray(), e.message)
                throw e
            }

            if (count > 0) {
                trace("RX", transactionGeneration, buffer.copyOf(count))
                for (i in 0 until count) {
                    val value = buffer[i]
                    response.add(value)
                    if (expectedLength != null && response.size == expectedLength) {
                        if (i != count - 1) {
                            trace("RX_ERROR", transactionGeneration, buffer.copyOfRange(i + 1, count), "unexpected trailing bytes")
                            throw IOException("Unexpected trailing NexStar response bytes")
                        }
                        if (value != '#'.code.toByte()) {
                            trace("RX_ERROR", transactionGeneration, response.toByteArray(), "invalid response terminator")
                            throw IOException("Invalid NexStar response terminator")
                        }
                        return response.toByteArray()
                    }
                    if (expectedLength == null && value == '#'.code.toByte()) {
                        if (i != count - 1) {
                            trace("RX_ERROR", transactionGeneration, buffer.copyOfRange(i + 1, count), "unexpected trailing bytes")
                            throw IOException("Unexpected trailing NexStar response bytes")
                        }
                        return response.toByteArray()
                    }
                    if (response.size > 256) {
                        trace("RX_ERROR", transactionGeneration, response.toByteArray(), "response too long")
                        throw IOException("NexStar response too long")
                    }
                }
            }
        }

        trace("RX_ERROR", transactionGeneration, response.toByteArray(), "timeout")
        throw IOException(
            "Timed out waiting for NexStar response terminator '#'. " +
                    "Received ${response.size} bytes."
        )
    }
}
