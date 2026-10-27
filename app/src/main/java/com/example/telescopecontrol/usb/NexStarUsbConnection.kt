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

        if (writeGate == null) serialPort.write(command, 1000)
        else writeGate { serialPort.write(command, 50) }

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
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                break
            }

            val readTimeout = minOf(remaining, 500L).toInt()
            val count = serialPort.read(buffer, readTimeout)

            if (count > 0) {
                for (i in 0 until count) {
                    val value = buffer[i]
                    response.add(value)
                    if (expectedLength != null && response.size == expectedLength) {
                        if (value != '#'.code.toByte()) throw IOException("Invalid NexStar response terminator")
                        return response.toByteArray()
                    }
                    if (expectedLength == null && value == '#'.code.toByte()) {
                        return response.toByteArray()
                    }
                }
            }
        }

        throw IOException(
            "Timed out waiting for NexStar response terminator '#'. " +
                    "Received ${response.size} bytes."
        )
    }
}
