package com.example.telescopecontrol

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.telescopecontrol.coordinator.MotorCommandCoordinator
import com.example.telescopecontrol.coordinator.HeartbeatPolicy
import com.example.telescopecontrol.model.AlignmentTrust
import com.example.telescopecontrol.model.DesiredAxisState
import com.example.telescopecontrol.model.EquatorialCoordinates
import com.example.telescopecontrol.model.HandControllerStatus
import com.example.telescopecontrol.model.MountStatus
import com.example.telescopecontrol.model.MountTravelEnvelope
import com.example.telescopecontrol.model.SlewAxis
import com.example.telescopecontrol.model.SlewDirection
import com.example.telescopecontrol.model.StopDiagnosticStatus
import com.example.telescopecontrol.model.TrackingMode
import com.example.telescopecontrol.nexstar.NexStarProtocol
import com.example.telescopecontrol.nexstar.toHexString
import com.example.telescopecontrol.nexstar.toPrintableAscii
import com.example.telescopecontrol.usb.NexStarUsbConnection
import com.hoho.android.usbserial.driver.UsbSerialDriver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import android.os.SystemClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

data class TelescopeDeviceInfo(
    val deviceName: String,
    val vendorIdDecimal: Int,
    val vendorIdHex: String,
    val productIdDecimal: Int,
    val productIdHex: String,
    val deviceClass: Int,
    val deviceSubclass: Int,
    val manufacturerName: String?,
    val productName: String?,
    val interfaceCount: Int,
    val driverClassName: String?,
    val driver: UsbSerialDriver?,
    val hasPermission: Boolean,
    val rawDevice: UsbDevice
)

data class TelescopeUiState(
    val connected: Boolean = false,
    val status: String = "Disconnected",
    val usbDevices: List<TelescopeDeviceInfo> = emptyList(),
    val detectedDriver: String = "None",
    val selectedDevice: TelescopeDeviceInfo? = null,
    val responseHex: String = "",
    val responseAscii: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val isUsbHostSupported: Boolean = true,
    val logs: List<String> = emptyList(),
    // v0.2.1 fields
    val handControllerStatus: HandControllerStatus = HandControllerStatus.DISCONNECTED,
    val mountStatus: MountStatus = MountStatus.UNKNOWN,
    val alignmentTrust: AlignmentTrust = AlignmentTrust.UNALIGNED,
    val isAligned: Boolean? = null,
    val alignmentWarning: String? = null,
    val handControllerVersion: String = "Unknown",
    val raMcVersion: String = "Unknown",
    val decMcVersion: String = "Unknown",
    val trackingMode: TrackingMode = TrackingMode.UNKNOWN,
    val position: EquatorialCoordinates? = null,
    val slewRate: Int = 2,
    val activeSlewAxis: SlewAxis? = null,
    val activeSlewDirection: SlewDirection? = null,
    val activeRaDirection: SlewDirection? = null,
    val activeDecDirection: SlewDirection? = null,
    val desiredRa: DesiredAxisState = DesiredAxisState.Stop,
    val desiredDec: DesiredAxisState = DesiredAxisState.Stop,
    val isSlewing: Boolean = false,
    // v0.3 / v0.3.2 fields
    val isAvxReady: Boolean = false,
    val consecutiveHeartbeats: Int = 0,
    val manualTrackEnabled: Boolean = false,
    val backgroundRaArcsecPerSec: Double = 0.0,
    val backgroundDecArcsecPerSec: Double = 0.0,
    val trackStep: Double = 15.0,
    val savedCelestronTrackingMode: TrackingMode? = null,
    val rawRaMotorPositionHex: String = "------",
    val rawDecMotorPositionHex: String = "------",
    // v0.3.2 diagnostic & state fields
    val commandedRaArcsecPerSec: Double = 0.0,
    val commandedDecArcsecPerSec: Double = 0.0,
    val measuredRaArcsecPerSec: Double = 0.0,
    val measuredDecArcsecPerSec: Double = 0.0,
    val uncommandedMotionWarning: String? = null,
    val highSpeedBackgroundWarning: Boolean = false,
    val motorGeneration: Long = 0L,
    val stopDiagnosticStatus: StopDiagnosticStatus = StopDiagnosticStatus.NONE
)

class TelescopeViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context get() = getApplication()
    val usbConnection = NexStarUsbConnection(context)

    private val _uiState = MutableStateFlow(TelescopeUiState())
    val uiState: StateFlow<TelescopeUiState> = _uiState.asStateFlow()

    val motorCoordinator = MotorCommandCoordinator(
        usbConnection = usbConnection,
        logger = { msg, isErr -> log(msg, isErr) },
        onErrorWarning = { warn ->
            _uiState.update { it.copy(alignmentWarning = warn) }
        },
        onMovementFailure = { safetyScope.launch { stopAll() } }
    )

    private val travelEnvelope = MountTravelEnvelope()
    private val heartbeatPolicy = HeartbeatPolicy()

    // Outlives viewModelScope so lifecycle shutdown can finish before closing the port.
    private val safetyScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var safetyJob: Job? = null
    @Volatile private var controlEpoch = 0L
    @Volatile private var shuttingDown = false
    private var cleared = false
    private var stopPending = false
    private var trackingSetupPending = false
    private var pendingSlewAxis: SlewAxis? = null
    private var pollJob: Job? = null
    private var heartbeatJob: Job? = null
    private var preSlewTrackingMode: TrackingMode? = null

    // Manual directional overrides per axis
    private var raOverride: SlewDirection? = null
    private var decOverride: SlewDirection? = null

    // Raw encoder position velocity calculation state
    private var lastRaPosInt: Int? = null
    private var lastRaTimeMs: Long = 0L
    private var lastDecPosInt: Int? = null
    private var lastDecTimeMs: Long = 0L

    // Uncommanded motion verification counters
    private var uncommandedRaSamples = 0
    private var uncommandedDecSamples = 0
    private var hasRetriedStopForRa = false
    private var hasRetriedStopForDec = false

    init {
        val hasHostFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
        _uiState.update { it.copy(isUsbHostSupported = hasHostFeature) }
        log("USB Host support: $hasHostFeature")
        scanDevices()
        motorCoordinator.start(viewModelScope)
    }

    private fun log(message: String, isError: Boolean = false) {
        if (isError) {
            Log.e(TAG, message)
        } else {
            Log.d(TAG, message)
        }
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val formatted = "[$time] $message"
        _uiState.update { state ->
            val updatedLogs = (state.logs + formatted).takeLast(100)
            state.copy(logs = updatedLogs)
        }
    }

    fun scanDevices() {
        log("Scanning USB devices...")
        val rawDevices = usbConnection.getUsbDevices()
        val drivers = usbConnection.findDrivers()

        log("Found ${rawDevices.size} USB device(s) attached.")
        for (dev in rawDevices) {
            log("USB device attached: ${dev.deviceName} (VID: 0x${"%04X".format(dev.vendorId)}, PID: 0x${"%04X".format(dev.productId)})")
        }

        val deviceInfos = rawDevices.map { device ->
            val driver = drivers.firstOrNull { it.device.deviceId == device.deviceId }
                ?: usbConnection.probeDevice(device)
            val driverClassName = driver?.javaClass?.name
            val hasPerm = usbConnection.hasPermission(device)

            TelescopeDeviceInfo(
                deviceName = device.deviceName,
                vendorIdDecimal = device.vendorId,
                vendorIdHex = "0x%04X".format(device.vendorId),
                productIdDecimal = device.productId,
                productIdHex = "0x%04X".format(device.productId),
                deviceClass = device.deviceClass,
                deviceSubclass = device.deviceSubclass,
                manufacturerName = try { device.manufacturerName } catch (_: Exception) { null },
                productName = try { device.productName } catch (_: Exception) { null },
                interfaceCount = device.interfaceCount,
                driverClassName = driverClassName,
                driver = driver,
                hasPermission = hasPerm,
                rawDevice = device
            )
        }

        val primaryDriver = deviceInfos.firstOrNull { it.driver != null }?.driverClassName ?: "None"

        _uiState.update { state ->
            val currentSelected = state.selectedDevice
            val updatedSelected = deviceInfos.firstOrNull { it.rawDevice.deviceId == currentSelected?.rawDevice?.deviceId }
                ?: deviceInfos.firstOrNull { it.driver != null }
                ?: deviceInfos.firstOrNull()

            state.copy(
                usbDevices = deviceInfos,
                detectedDriver = primaryDriver,
                selectedDevice = updatedSelected,
                status = if (state.connected) state.status else "Scanned ${deviceInfos.size} device(s)"
            )
        }
    }

    fun selectDevice(info: TelescopeDeviceInfo) {
        _uiState.update { it.copy(selectedDevice = info) }
    }

    fun requestConnection(deviceInfo: TelescopeDeviceInfo, onRequestPermission: (UsbDevice) -> Unit) {
        log("Connection requested for device: ${deviceInfo.deviceName}")
        val driver = deviceInfo.driver
        if (driver == null) {
            val msg = "No supported USB serial driver found for VID:${deviceInfo.vendorIdHex} PID:${deviceInfo.productIdHex}."
            log(msg, isError = true)
            _uiState.update { it.copy(error = msg, status = "Driver missing") }
            return
        }

        if (!usbConnection.hasPermission(driver)) {
            log("Permission requested for device ${deviceInfo.deviceName}")
            onRequestPermission(deviceInfo.rawDevice)
            return
        }

        openDriverConnection(driver)
    }

    fun onPermissionGranted(device: UsbDevice) {
        log("Permission granted for device ${device.deviceName}")
        scanDevices()
        val driver = usbConnection.findDrivers().firstOrNull { it.device.deviceId == device.deviceId }
            ?: usbConnection.probeDevice(device)

        if (driver != null) {
            openDriverConnection(driver)
        } else {
            log("Permission granted but no serial driver found for ${device.deviceName}", isError = true)
            _uiState.update { it.copy(error = "No serial driver available for permitted device.") }
        }
    }

    fun onPermissionDenied(device: UsbDevice) {
        log("Permission denied for device ${device.deviceName}", isError = true)
        _uiState.update { it.copy(error = "USB Permission denied by user.") }
    }

    private fun openDriverConnection(driver: UsbSerialDriver) {
        if (shuttingDown || cleared || usbConnection.isConnected()) return
        log("Opening port for device ${driver.device.deviceName}...")
        try {
            usbConnection.open(driver)
            log("Port opened successfully.")
            _uiState.update {
                it.copy(
                    connected = true,
                    handControllerStatus = HandControllerStatus.CONNECTED,
                    status = "CONNECTED (${driver.javaClass.simpleName})",
                    detectedDriver = driver.javaClass.name,
                    error = null
                )
            }
            heartbeatPolicy.reset()
            initializeMountAndStartLoops()
        } catch (e: IOException) {
            log("Exception opening port: ${e.message}", isError = true)
            _uiState.update {
                it.copy(
                    connected = false,
                    handControllerStatus = HandControllerStatus.DISCONNECTED,
                    mountStatus = MountStatus.OFFLINE,
                    status = "Connection Failed",
                    error = "Failed to open port: ${e.message}"
                )
            }
        }
    }

    fun onUsbDeviceDetached() {
        log("USB device detached / disconnected physically.")
        controlEpoch++
        pollJob?.cancel()
        heartbeatJob?.cancel()
        shuttingDown = true
        val resetIntent = motorCoordinator.invalidateForDisconnect()
        val detachEpoch = controlEpoch
        val previousSafetyJob = safetyJob
        safetyJob = safetyScope.launch {
            previousSafetyJob?.join()
            motorCoordinator.closeConnection()
            motorCoordinator.resetAppliedIfCurrent(resetIntent.generation)
            if (!cleared && controlEpoch == detachEpoch) shuttingDown = false
        }
        raOverride = null
        decOverride = null
        _uiState.update {
            it.copy(
                connected = false,
                handControllerStatus = HandControllerStatus.DISCONNECTED,
                mountStatus = MountStatus.OFFLINE,
                status = "USB DISCONNECTED — Device Removed",
                isSlewing = false,
                activeSlewAxis = null,
                activeSlewDirection = null,
                activeRaDirection = null,
                activeDecDirection = null,
                desiredRa = DesiredAxisState.Stop,
                desiredDec = DesiredAxisState.Stop,
                isAvxReady = false,
                consecutiveHeartbeats = 0,
                manualTrackEnabled = false,
                backgroundRaArcsecPerSec = 0.0,
                backgroundDecArcsecPerSec = 0.0,
                rawRaMotorPositionHex = "------",
                rawDecMotorPositionHex = "------",
                commandedRaArcsecPerSec = 0.0,
                commandedDecArcsecPerSec = 0.0,
                measuredRaArcsecPerSec = 0.0,
                measuredDecArcsecPerSec = 0.0,
                uncommandedMotionWarning = null,
                highSpeedBackgroundWarning = false,
                motorGeneration = resetIntent.generation,
                stopDiagnosticStatus = StopDiagnosticStatus.NONE,
                error = null
            )
        }
    }

    private fun initializeMountAndStartLoops() {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, isAvxReady = false, consecutiveHeartbeats = 0) }
            log("Initializing mount parameters...")

            // 1. Echo Test
            try {
                val echoRx = usbConnection.transactSafe(NexStarProtocol.ECHO_TEST)
                val isEchoOk = echoRx.size == 2 && echoRx[0] == 0x41.toByte() && echoRx[1] == 0x23.toByte()
                if (!isEchoOk) {
                    log("Initialization Echo test failed!", isError = true)
                    _uiState.update { it.copy(error = "Echo test failed during initialization.") }
                } else {
                    log("Echo test OK.")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log("Echo test exception: ${e.message}", isError = true)
            }

            // 2. HC Version
            try {
                val verRx = usbConnection.transactSafe(NexStarProtocol.GET_VERSION)
                val versionStr = NexStarProtocol.parseVersion(verRx)
                log("Hand Controller Version: $versionStr")
                _uiState.update { it.copy(handControllerVersion = versionStr) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log("Get HC Version exception: ${e.message}", isError = true)
            }

            // 3. Initial Motor Controller Versions Heartbeat
            checkMotorControllerHeartbeatInternal()

            // 4. Alignment Status
            try {
                val alignRx = usbConnection.transactSafe(NexStarProtocol.GET_ALIGNMENT_STATUS)
                val aligned = NexStarProtocol.parseAlignmentStatus(alignRx)
                val trust = if (aligned) AlignmentTrust.TRUSTED else AlignmentTrust.UNALIGNED
                val warning = if (!aligned) "Manual Mode — celestial alignment not established" else null
                log("Alignment status: ${if (aligned) "ALIGNED" else "NOT ALIGNED"}")
                _uiState.update {
                    it.copy(
                        isAligned = aligned,
                        alignmentTrust = trust,
                        alignmentWarning = warning
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log("Get Alignment exception: ${e.message}", isError = true)
            }

            // 5. Tracking Mode
            try {
                val trackRx = usbConnection.transactSafe(NexStarProtocol.GET_TRACKING_MODE)
                val mode = NexStarProtocol.parseTrackingMode(trackRx)
                log("Tracking Mode: ${mode.label}")
                _uiState.update { it.copy(trackingMode = mode) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log("Get Tracking Mode exception: ${e.message}", isError = true)
            }

            // 6. Initial Precise RA/DEC & Raw Motor Positions
            queryPrecisePositionInternal()
            queryRawMotorPositionsInternal()

            _uiState.update { it.copy(busy = false) }

            startPositionPolling()
            startHeartbeatAndUsbMonitoring()
        }
    }

    private fun startPositionPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (usbConnection.isConnected() && isActive) {
                delay(1000)
                if (_uiState.value.isSlewing || _uiState.value.busy) {
                    continue
                }
                queryPrecisePositionInternal()
                queryRawMotorPositionsInternal()
            }
        }
    }

    private fun startHeartbeatAndUsbMonitoring() {
        heartbeatJob?.cancel()
        heartbeatJob = viewModelScope.launch {
            while (usbConnection.isConnected() && isActive) {
                delay(5000)

                val currentSelected = _uiState.value.selectedDevice
                if (currentSelected != null) {
                    val attachedDevices = usbConnection.getUsbDevices()
                    val stillExists = attachedDevices.any { it.deviceId == currentSelected.rawDevice.deviceId }
                    if (!stillExists) {
                        log("Passive scan: USB device ${currentSelected.deviceName} no longer attached.", isError = true)
                        onUsbDeviceDetached()
                        break
                    }
                }

                if (!_uiState.value.busy) {
                    checkMotorControllerHeartbeatInternal()
                }
            }
        }
    }

    private suspend fun checkMotorControllerHeartbeatInternal() {
        val epoch = controlEpoch
        var raVersionStr: String? = null
        var decVersionStr: String? = null

        try {
            val raRx = usbConnection.transactSafe(NexStarProtocol.GET_RA_MC_VERSION, timeoutMs = 2000)
            raVersionStr = NexStarProtocol.parseMotorControllerVersion(raRx)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }

        try {
            val decRx = usbConnection.transactSafe(NexStarProtocol.GET_DEC_MC_VERSION, timeoutMs = 2000)
            decVersionStr = NexStarProtocol.parseMotorControllerVersion(decRx)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }

        if (epoch != controlEpoch || shuttingDown || !usbConnection.isConnected()) return
        val raOk = raVersionStr != null
        val decOk = decVersionStr != null

        val previousState = _uiState.value
        val result = heartbeatPolicy.record(raOk, decOk, previousState.mountStatus)
        val newMountStatus = result.status
        val stopIntent = if (result.stop) {
            controlEpoch++
            raOverride = null
            decOverride = null
            motorCoordinator.invalidateForStop("HEARTBEAT_FAILURE")
        } else if (result.quiet) {
            controlEpoch++
            motorCoordinator.invalidateForDisconnect()
        } else null
        val prevMountStatus = previousState.mountStatus
        if (prevMountStatus == MountStatus.ONLINE && newMountStatus == MountStatus.OFFLINE) {
            log("AVX POWER LOST / MOTOR CONTROLLERS NOT RESPONDING", isError = true)
        } else if (prevMountStatus == MountStatus.OFFLINE && newMountStatus == MountStatus.ONLINE) {
            log("AVX POWER RESTORED")
        }
        _uiState.update { state ->
            val prevMountStatus = state.mountStatus
            var updatedTrust = state.alignmentTrust
            var warning = state.alignmentWarning

            val newHeartbeatCount = result.successes
            val newIsAvxReady = result.ready

            if (result.stop) {
                warning = "AVX COMMUNICATION INTERRUPTED — STOPPING MOTORS"
            } else if (newMountStatus == MountStatus.OFFLINE) {
                warning = "AVX OFFLINE — MOTOR CONTROLLERS NOT RESPONDING"
            } else if (prevMountStatus == MountStatus.OFFLINE && newMountStatus == MountStatus.ONLINE) {
                updatedTrust = AlignmentTrust.STALE_AFTER_POWER_LOSS
                warning = "AVX COMMUNICATION RESTORED — Alignment may be stale"
            } else if (raOk && decOk && warning == "AVX COMMUNICATION INTERRUPTED — STOPPING MOTORS") {
                warning = null
            }

            var isManualTrack = state.manualTrackEnabled
            var bgRa = state.backgroundRaArcsecPerSec
            var bgDec = state.backgroundDecArcsecPerSec

            if (!raOk || !decOk) {
                isManualTrack = false
                bgRa = 0.0
                bgDec = 0.0
            }

            state.copy(
                mountStatus = newMountStatus,
                raMcVersion = raVersionStr ?: "NO RESPONSE",
                decMcVersion = decVersionStr ?: "NO RESPONSE",
                alignmentTrust = updatedTrust,
                alignmentWarning = warning,
                consecutiveHeartbeats = newHeartbeatCount,
                isAvxReady = newIsAvxReady,
                manualTrackEnabled = isManualTrack,
                backgroundRaArcsecPerSec = bgRa,
                backgroundDecArcsecPerSec = bgDec,
                isSlewing = if (stopIntent != null) false else state.isSlewing,
                activeSlewAxis = if (stopIntent != null) null else state.activeSlewAxis,
                activeSlewDirection = if (stopIntent != null) null else state.activeSlewDirection,
                activeRaDirection = if (stopIntent != null) null else state.activeRaDirection,
                activeDecDirection = if (stopIntent != null) null else state.activeDecDirection,
                desiredRa = if (stopIntent != null) DesiredAxisState.Stop else state.desiredRa,
                desiredDec = if (stopIntent != null) DesiredAxisState.Stop else state.desiredDec,
                commandedRaArcsecPerSec = if (stopIntent != null) 0.0 else state.commandedRaArcsecPerSec,
                commandedDecArcsecPerSec = if (stopIntent != null) 0.0 else state.commandedDecArcsecPerSec,
                highSpeedBackgroundWarning = if (stopIntent != null) false else state.highSpeedBackgroundWarning,
                motorGeneration = stopIntent?.generation ?: state.motorGeneration,
                stopDiagnosticStatus = if (result.stop) StopDiagnosticStatus.STOP_REQUESTED else state.stopDiagnosticStatus
            )
        }
        if (stopIntent != null) {
            if (result.stop) {
                val status = motorCoordinator.stopIfCurrent(stopIntent, timeoutMs = 250)
                if (motorCoordinator.currentIntent.generation == stopIntent.generation && !shuttingDown) {
                    _uiState.update { it.copy(stopDiagnosticStatus = status) }
                }
            } else {
                motorCoordinator.resetAppliedIfCurrent(stopIntent.generation)
            }
        }
    }

    private suspend fun queryPrecisePositionInternal() {
        try {
            val rx = usbConnection.transactSafe(NexStarProtocol.GET_RA_DEC_PRECISE, timeoutMs = 4000)
            val ascii = rx.toPrintableAscii()
            val hex = rx.toHexString()
            try {
                val coords = NexStarProtocol.parsePreciseCoordinates(rx)
                _uiState.update {
                    it.copy(
                        position = coords,
                        responseAscii = "Raw: $ascii",
                        responseHex = hex
                    )
                }
            } catch (parseEx: Exception) {
                log("Position parse error: ${parseEx.message}. Raw: $ascii", isError = true)
                _uiState.update {
                    it.copy(
                        responseAscii = "Position parse error: $ascii",
                        responseHex = hex
                    )
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log("Position polling failed: ${e.message}")
        }
    }

    private suspend fun queryRawMotorPositionsInternal() {
        val nowMs = System.currentTimeMillis()
        try {
            val raRx = usbConnection.transactSafe(NexStarProtocol.GET_RA_MC_POS, timeoutMs = 2000)
            val raPosHex = NexStarProtocol.parseRawMotorPosition(raRx) ?: "------"
            val raPosInt = raPosHex.toIntOrNull(16)

            val decRx = usbConnection.transactSafe(NexStarProtocol.GET_DEC_MC_POS, timeoutMs = 2000)
            val decPosHex = NexStarProtocol.parseRawMotorPosition(decRx) ?: "------"
            val decPosInt = decPosHex.toIntOrNull(16)

            var measuredRa = _uiState.value.measuredRaArcsecPerSec
            var measuredDec = _uiState.value.measuredDecArcsecPerSec

            if (raPosInt != null && lastRaPosInt != null && lastRaTimeMs > 0) {
                val elapsedSec = (nowMs - lastRaTimeMs) / 1000.0
                if (elapsedSec > 0.05) {
                    var deltaCounts = raPosInt - lastRaPosInt!!
                    if (deltaCounts > 8388607) deltaCounts -= 16777216
                    if (deltaCounts < -8388608) deltaCounts += 16777216
                    val deltaDegrees = (deltaCounts.toDouble() / 16777216.0) * 360.0
                    measuredRa = (deltaDegrees * 3600.0) / elapsedSec
                }
            }
            if (raPosInt != null) {
                lastRaPosInt = raPosInt
                lastRaTimeMs = nowMs
            }

            if (decPosInt != null && lastDecPosInt != null && lastDecTimeMs > 0) {
                val elapsedSec = (nowMs - lastDecTimeMs) / 1000.0
                if (elapsedSec > 0.05) {
                    var deltaCounts = decPosInt - lastDecPosInt!!
                    if (deltaCounts > 8388607) deltaCounts -= 16777216
                    if (deltaCounts < -8388608) deltaCounts += 16777216
                    val deltaDegrees = (deltaCounts.toDouble() / 16777216.0) * 360.0
                    measuredDec = (deltaDegrees * 3600.0) / elapsedSec
                }
            }
            if (decPosInt != null) {
                lastDecPosInt = decPosInt
                lastDecTimeMs = nowMs
            }

            verifyUncommandedMotionAndStopVerification(measuredRa, measuredDec)

            _uiState.update {
                it.copy(
                    rawRaMotorPositionHex = raPosHex,
                    rawDecMotorPositionHex = decPosHex,
                    measuredRaArcsecPerSec = measuredRa,
                    measuredDecArcsecPerSec = measuredDec
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    /**
     * Requirement 3 & 5: Uncommanded-motion detection & STOP status verification.
     * SUPPRESSED whenever native Celestron tracking is active (TrackingMode != OFF).
     * ACTIVE ONLY when Celestron tracking is OFF and physical axes are expected to be stationary.
     */
    private fun verifyUncommandedMotionAndStopVerification(measuredRa: Double, measuredDec: Double) {
        val currentState = _uiState.value
        val isNativeTrackingActive = currentState.trackingMode != TrackingMode.OFF

        // Rule 3: Suppress completely when Celestron native tracking owns axis motion
        if (isNativeTrackingActive) {
            uncommandedRaSamples = 0
            uncommandedDecSamples = 0
            hasRetriedStopForRa = false
            hasRetriedStopForDec = false
            _uiState.update {
                it.copy(uncommandedMotionWarning = null)
            }
            return
        }

        // Native tracking is OFF. Check if physical axes are expected to be stationary (Stop)
        val raDesired = motorCoordinator.currentIntent.ra
        val decDesired = motorCoordinator.currentIntent.dec
        val isAppExpectingStop = raDesired is DesiredAxisState.Stop && decDesired is DesiredAxisState.Stop

        if (!isAppExpectingStop) {
            uncommandedRaSamples = 0
            uncommandedDecSamples = 0
            return
        }


        // Uncommanded motion check when expected stationary
        var raUncommanded = false
        if (abs(measuredRa) > 2.0) {
            uncommandedRaSamples++
            if (uncommandedRaSamples >= 3) raUncommanded = true
        } else {
            uncommandedRaSamples = 0
            hasRetriedStopForRa = false
        }

        var decUncommanded = false
        if (abs(measuredDec) > 2.0) {
            uncommandedDecSamples++
            if (uncommandedDecSamples >= 3) decUncommanded = true
        } else {
            uncommandedDecSamples = 0
            hasRetriedStopForDec = false
        }

        val warningMsg = when {
            raUncommanded && decUncommanded -> "STOP ACKNOWLEDGED BUT MOTION CONTINUES: RA & DEC"
            raUncommanded -> "STOP ACKNOWLEDGED BUT MOTION CONTINUES: RA"
            decUncommanded -> "STOP ACKNOWLEDGED BUT MOTION CONTINUES: DEC"
            else -> null
        }

        if (warningMsg != null) {
            _uiState.update {
                it.copy(
                    uncommandedMotionWarning = warningMsg,
                    stopDiagnosticStatus = StopDiagnosticStatus.STOP_ACKNOWLEDGED_BUT_MOTION_CONTINUES
                )
            }
            if ((raUncommanded && !hasRetriedStopForRa) || (decUncommanded && !hasRetriedStopForDec)) {
                log("SAFETY WARN: $warningMsg. Initiating single emergency STOP retry...", isError = true)
                if (raUncommanded) hasRetriedStopForRa = true
                if (decUncommanded) hasRetriedStopForDec = true
                stopAll()
            }
        } else {
            _uiState.update { it.copy(uncommandedMotionWarning = null) }
        }
    }

    fun refreshPosition() {
        if (!usbConnection.isConnected()) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            queryPrecisePositionInternal()
            queryRawMotorPositionsInternal()
            _uiState.update { it.copy(busy = false) }
        }
    }

    fun setSlewRate(rate: Int) {
        if (rate in 1..9 && !_uiState.value.isSlewing) {
            log("Manual slew rate changed to $rate")
            _uiState.update { it.copy(slewRate = rate) }
        }
    }

    // --- MANUAL VECTOR TRACKING & DESIRED MOTOR STATE COORDINATION ---

    private suspend fun setTrackingChecked(mode: TrackingMode, epoch: Long, generation: Long = motorCoordinator.currentIntent.generation) {
        val rx = usbConnection.transactSafe(NexStarProtocol.setTrackingModeCommand(mode), writeGate = { write ->
            motorCoordinator.admitWrite(generation, extraCheck = {
                epoch == controlEpoch && !shuttingDown && (mode == TrackingMode.OFF ||
                    (_uiState.value.trackingMode == TrackingMode.OFF &&
                        motorCoordinator.currentIntent.ra is DesiredAxisState.Stop &&
                        motorCoordinator.currentIntent.dec is DesiredAxisState.Stop))
            }, write = write)
        })
        if (rx.size != 1 || rx[0] != '#'.code.toByte()) throw IOException("Invalid tracking acknowledgement")
    }

    private suspend fun readFreshEncoderPair(): Pair<Int, Int>? {
        val ra = NexStarProtocol.parseRawMotorPosition(usbConnection.transactSafe(NexStarProtocol.GET_RA_MC_POS, 1000))?.toIntOrNull(16)
        val dec = NexStarProtocol.parseRawMotorPosition(usbConnection.transactSafe(NexStarProtocol.GET_DEC_MC_POS, 1000))?.toIntOrNull(16)
        return if (ra != null && dec != null) ra to dec else null
    }

    private suspend fun verifyFreshStop(generation: Long, epoch: Long): Boolean {
        fun stillSafe(): Boolean = usbConnection.isConnected() && !shuttingDown && epoch == controlEpoch &&
            _uiState.value.trackingMode == TrackingMode.OFF && motorCoordinator.currentIntent.generation == generation &&
            motorCoordinator.currentIntent.ra is DesiredAxisState.Stop && motorCoordinator.currentIntent.dec is DesiredAxisState.Stop
        return try {
            if (!stillSafe()) return false
            val first = readFreshEncoderPair() ?: return false
            val start = SystemClock.elapsedRealtime()
            delay(1000)
            if (!stillSafe()) return false
            val second = readFreshEncoderPair() ?: return false
            val elapsed = (SystemClock.elapsedRealtime() - start) / 1000.0
            fun velocity(a: Int, b: Int): Double {
                var delta = b - a
                if (delta > 8388607) delta -= 16777216
                if (delta < -8388608) delta += 16777216
                return abs(delta * 1296000.0 / 16777216.0 / elapsed)
            }
            elapsed > 0 && stillSafe() && velocity(first.first, second.first) < 1.0 && velocity(first.second, second.second) < 1.0
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        }
    }

    fun toggleManualTrack(enabled: Boolean) {
        if (!usbConnection.isConnected() || !_uiState.value.isAvxReady || shuttingDown || stopPending || trackingSetupPending) return
        trackingSetupPending = true
        val epoch = ++controlEpoch

        safetyJob = safetyScope.launch {
            try {
            if (enabled) {
                motorCoordinator.updateDesiredState(DesiredAxisState.Stop, DesiredAxisState.Stop, "MANUAL_TRACK_ENABLE_PENDING")
                val currentTracking = _uiState.value.trackingMode
                log("MANUAL TRACK ON requested. Saving current Celestron tracking mode: ${currentTracking.label}")

                try {
                    setTrackingChecked(TrackingMode.OFF, epoch)
                    if (epoch != controlEpoch || !usbConnection.isConnected()) {
                        _uiState.update { it.copy(trackingMode = TrackingMode.UNKNOWN) }
                        return@launch
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (epoch != controlEpoch) return@launch
                    log("Failed to turn off Celestron tracking: ${e.message}", isError = true)
                    _uiState.update { it.copy(manualTrackEnabled = false, trackingMode = TrackingMode.UNKNOWN, error = "Tracking OFF was not acknowledged") }
                    return@launch
                }

                _uiState.update {
                    it.copy(
                        manualTrackEnabled = true,
                        savedCelestronTrackingMode = currentTracking,
                        trackingMode = TrackingMode.OFF,
                        backgroundRaArcsecPerSec = 0.0,
                        backgroundDecArcsecPerSec = 0.0
                    )
                }
                recalculateAndApplyDesiredStates("MANUAL_TRACK_TOGGLE_ON")
            } else {
                log("MANUAL TRACK OFF requested.")
                val savedMode = _uiState.value.savedCelestronTrackingMode

                // Requirement 4: Manual Track OFF Sequence
                // A. Increment generation / set desired states to Stop
                // B. Clear overrides & background velocity
                _uiState.update {
                    it.copy(
                        manualTrackEnabled = false,
                        backgroundRaArcsecPerSec = 0.0,
                        backgroundDecArcsecPerSec = 0.0,
                        isSlewing = false,
                        activeSlewAxis = null,
                        activeSlewDirection = null,
                activeRaDirection = null,
                activeDecDirection = null,
                desiredRa = DesiredAxisState.Stop,
                desiredDec = DesiredAxisState.Stop,
                        stopDiagnosticStatus = StopDiagnosticStatus.STOP_REQUESTED
                    )
                }
                raOverride = null
                decOverride = null

                // C. Send authoritative Emergency STOP sequence
                var stopGeneration = -1L
                val stopAck = motorCoordinator.emergencyStop("MANUAL_TRACK_TOGGLE_OFF") { stopGeneration = it }
                _uiState.update { it.copy(stopDiagnosticStatus = stopAck) }
                val verified = stopAck == StopDiagnosticStatus.STOP_COMMANDS_ACKNOWLEDGED &&
                    epoch == controlEpoch && verifyFreshStop(stopGeneration, epoch)
                if (!verified) {
                    log("MANUAL TRACK OFF: STOP not verified; tracking will not be restored.", true)
                    return@launch
                }
                _uiState.update { it.copy(stopDiagnosticStatus = StopDiagnosticStatus.STOP_VERIFIED) }
                if (savedMode != null && savedMode != TrackingMode.OFF && savedMode != TrackingMode.UNKNOWN) {
                    try {
                        setTrackingChecked(savedMode, epoch, stopGeneration)
                        if (epoch == controlEpoch) _uiState.update { it.copy(trackingMode = savedMode) }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        _uiState.update { it.copy(trackingMode = TrackingMode.UNKNOWN, error = "Tracking restore failed") }
                        log("Error restoring tracking mode: ${e.message}", true)
                    }
                }
            }
            } finally {
                trackingSetupPending = false
            }
        }
    }

    fun adjustBackgroundRa(delta: Double) {
        if (trackingSetupPending || shuttingDown || stopPending || !_uiState.value.isAvxReady) return
        controlEpoch++
        _uiState.update {
            val newRa = (it.backgroundRaArcsecPerSec + delta).coerceIn(-14400.0, 14400.0)
            it.copy(backgroundRaArcsecPerSec = newRa)
        }
        recalculateAndApplyDesiredStates("BACKGROUND_CHANGE")
    }

    fun adjustBackgroundDec(delta: Double) {
        if (trackingSetupPending || shuttingDown || stopPending || !_uiState.value.isAvxReady) return
        controlEpoch++
        _uiState.update {
            val newDec = (it.backgroundDecArcsecPerSec + delta).coerceIn(-14400.0, 14400.0)
            it.copy(backgroundDecArcsecPerSec = newDec)
        }
        recalculateAndApplyDesiredStates("BACKGROUND_CHANGE")
    }

    fun zeroBackgroundRa() {
        if (trackingSetupPending || shuttingDown || stopPending || !_uiState.value.isAvxReady) return
        controlEpoch++
        _uiState.update { it.copy(backgroundRaArcsecPerSec = 0.0) }
        recalculateAndApplyDesiredStates("ZERO_BACKGROUND")
    }

    fun zeroBackgroundDec() {
        if (trackingSetupPending || shuttingDown || stopPending || !_uiState.value.isAvxReady) return
        controlEpoch++
        _uiState.update { it.copy(backgroundDecArcsecPerSec = 0.0) }
        recalculateAndApplyDesiredStates("ZERO_BACKGROUND")
    }

    fun zeroBackgroundBoth() {
        if (trackingSetupPending || shuttingDown || stopPending || !_uiState.value.isAvxReady) return
        controlEpoch++
        _uiState.update {
            it.copy(
                backgroundRaArcsecPerSec = 0.0,
                backgroundDecArcsecPerSec = 0.0
            )
        }
        recalculateAndApplyDesiredStates("ZERO_BACKGROUND")
    }

    fun setTrackStep(step: Double) {
        _uiState.update { it.copy(trackStep = step) }
    }

    private fun recalculateAndApplyDesiredStates(reason: String) {
        if (shuttingDown || stopPending || !_uiState.value.isAvxReady) return
        val state = _uiState.value
        val manualRate = state.slewRate
        val isManualTrack = state.manualTrackEnabled

        // Travel Envelope check hook
        if (raOverride != null && !travelEnvelope.isSafeToMove(SlewAxis.RA, raOverride!!)) {
            log("Travel Envelope blocked RA motion", isError = true)
            return
        }
        if (decOverride != null && !travelEnvelope.isSafeToMove(SlewAxis.DEC, decOverride!!)) {
            log("Travel Envelope blocked DEC motion", isError = true)
            return
        }

        // Calculate desired RA state
        val raDesired: DesiredAxisState = when {
            raOverride != null -> DesiredAxisState.Fixed(raOverride!!, manualRate)
            isManualTrack && abs(state.backgroundRaArcsecPerSec) >= 0.01 -> DesiredAxisState.Variable(state.backgroundRaArcsecPerSec)
            else -> DesiredAxisState.Stop
        }

        // Calculate desired DEC state
        val decDesired: DesiredAxisState = when {
            decOverride != null -> DesiredAxisState.Fixed(decOverride!!, manualRate)
            isManualTrack && abs(state.backgroundDecArcsecPerSec) >= 0.01 -> DesiredAxisState.Variable(state.backgroundDecArcsecPerSec)
            else -> DesiredAxisState.Stop
        }

        // Compute commanded velocities for diagnostic display
        val commandedRa = when (raDesired) {
            is DesiredAxisState.Stop -> 0.0
            is DesiredAxisState.Variable -> raDesired.arcsecPerSec
            is DesiredAxisState.Fixed -> 0.0
        }
        val commandedDec = when (decDesired) {
            is DesiredAxisState.Stop -> 0.0
            is DesiredAxisState.Variable -> decDesired.arcsecPerSec
            is DesiredAxisState.Fixed -> 0.0
        }

        val highSpeedWarning = abs(state.backgroundRaArcsecPerSec) > 3600.0 || abs(state.backgroundDecArcsecPerSec) > 3600.0

        val newIntent = motorCoordinator.updateDesiredState(raDesired, decDesired, reason)

        _uiState.update {
            it.copy(
                commandedRaArcsecPerSec = commandedRa,
                commandedDecArcsecPerSec = commandedDec,
                desiredRa = raDesired,
                desiredDec = decDesired,
                highSpeedBackgroundWarning = highSpeedWarning,
                motorGeneration = newIntent.generation
            )
        }
    }

    // --- DIRECTIONAL SLEW CONTROLS ---

    fun startManualSlew(axis: SlewAxis, direction: SlewDirection) {
        if (!usbConnection.isConnected() || !_uiState.value.isAvxReady || shuttingDown || stopPending || trackingSetupPending) return
        val epoch = ++controlEpoch
        val needsTrackingOff = !_uiState.value.manualTrackEnabled && _uiState.value.slewRate >= 3 && _uiState.value.trackingMode != TrackingMode.OFF
        if (needsTrackingOff) {
            trackingSetupPending = true
            pendingSlewAxis = axis
        }

        if (axis == SlewAxis.RA) {
            raOverride = direction
        } else {
            decOverride = direction
        }

        _uiState.update {
            it.copy(
                isSlewing = true,
                activeSlewAxis = axis,
                activeSlewDirection = direction,
                activeRaDirection = raOverride,
                activeDecDirection = decOverride
            )
        }

        viewModelScope.launch {
            try {
            if (!_uiState.value.manualTrackEnabled) {
                val rate = _uiState.value.slewRate
                val currentTracking = _uiState.value.trackingMode
                if (rate >= 3 && currentTracking != TrackingMode.OFF) {
                    preSlewTrackingMode = currentTracking
                    log("Rate $rate >= 3: Temporarily disabling tracking (${currentTracking.label})...")
                    try {
                        setTrackingChecked(TrackingMode.OFF, epoch)
                        if (epoch != controlEpoch) {
                            _uiState.update { it.copy(trackingMode = TrackingMode.UNKNOWN) }
                            return@launch
                        }
                        _uiState.update { it.copy(trackingMode = TrackingMode.OFF) }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        if (epoch != controlEpoch) return@launch
                        _uiState.update { it.copy(trackingMode = TrackingMode.UNKNOWN) }
                        log("Failed to disable tracking for slew: ${e.message}", isError = true)
                        stopAll()
                        return@launch
                    }
                }
            }
            if (epoch == controlEpoch) recalculateAndApplyDesiredStates("MANUAL_PRESS")
            } finally {
                if (needsTrackingOff) {
                    trackingSetupPending = false
                    pendingSlewAxis = null
                }
            }
        }
    }

    fun stopManualSlew(axis: SlewAxis) {
        if (!usbConnection.isConnected()) return
        if (trackingSetupPending && pendingSlewAxis != null && axis != pendingSlewAxis) return
        if ((axis == SlewAxis.RA && raOverride == null) ||
            (axis == SlewAxis.DEC && decOverride == null)) return
        controlEpoch++

        if (axis == SlewAxis.RA) {
            raOverride = null
        } else {
            decOverride = null
        }

        val remainingActive = when { raOverride != null -> SlewAxis.RA; decOverride != null -> SlewAxis.DEC; else -> null }

        _uiState.update {
            it.copy(
                isSlewing = remainingActive != null,
                activeSlewAxis = remainingActive,
                activeSlewDirection = if (remainingActive == SlewAxis.RA) raOverride else decOverride,
                activeRaDirection = raOverride,
                activeDecDirection = decOverride
            )
        }

        val reason = if (_uiState.value.manualTrackEnabled) "MANUAL_RELEASE_RETURN_TO_BACKGROUND" else "MANUAL_RELEASE"
        recalculateAndApplyDesiredStates(reason)
        // Ordinary slew release deliberately leaves native tracking off: restoring it
        // requires a fresh acknowledged four-packet stop and encoder verification.
        // The manual-track-off path performs that sequence; release does not.
    }

    fun stopAll() {
        if (stopPending || shuttingDown) return
        stopPending = true
        controlEpoch++
        motorCoordinator.updateDesiredState(DesiredAxisState.Stop, DesiredAxisState.Stop, "STOP_PENDING")
        val previousSafetyJob = safetyJob
        safetyJob = safetyScope.launch {
            log("TX STOP ALL (Global Emergency Stop)")

            raOverride = null
            decOverride = null

            _uiState.update {
                it.copy(
                    backgroundRaArcsecPerSec = 0.0,
                    backgroundDecArcsecPerSec = 0.0,
                    isSlewing = false,
                    activeSlewAxis = null,
                    activeSlewDirection = null,
                activeRaDirection = null,
                activeDecDirection = null,
                desiredRa = DesiredAxisState.Stop,
                desiredDec = DesiredAxisState.Stop,
                    commandedRaArcsecPerSec = 0.0,
                    commandedDecArcsecPerSec = 0.0,
                    highSpeedBackgroundWarning = false,
                    stopDiagnosticStatus = StopDiagnosticStatus.STOP_REQUESTED
                )
            }

            uncommandedRaSamples = 0
            uncommandedDecSamples = 0
            hasRetriedStopForRa = false
            hasRetriedStopForDec = false

            val ackStatus = motorCoordinator.emergencyStop("EMERGENCY_STOP")
            previousSafetyJob?.join()
            _uiState.update { it.copy(stopDiagnosticStatus = ackStatus) }
            stopPending = false

            // Emergency stop never restarts native tracking.
        }
    }

    fun disconnect() {
        if (shuttingDown) return
        log("Disconnecting...")
        pollJob?.cancel()
        heartbeatJob?.cancel()

        shuttingDown = true
        controlEpoch++
        motorCoordinator.updateDesiredState(DesiredAxisState.Stop, DesiredAxisState.Stop, "DISCONNECT_PENDING")
        val previousSafetyJob = safetyJob
        safetyJob = safetyScope.launch {
            previousSafetyJob?.join()
            if (usbConnection.isConnected()) {
                motorCoordinator.emergencyStop("USER_DISCONNECT")
            }
            motorCoordinator.closeConnection()
            val resetIntent = motorCoordinator.resetStatesQuietly()
            raOverride = null
            decOverride = null
            log("Disconnected.")
            if (!cleared) shuttingDown = false
            _uiState.update {
                it.copy(
                    connected = false,
                    handControllerStatus = HandControllerStatus.DISCONNECTED,
                    mountStatus = MountStatus.UNKNOWN,
                    status = "DISCONNECTED",
                    error = null,
                    isSlewing = false,
                    position = null,
                    isAvxReady = false,
                    consecutiveHeartbeats = 0,
                    manualTrackEnabled = false,
                    backgroundRaArcsecPerSec = 0.0,
                    backgroundDecArcsecPerSec = 0.0,
                    rawRaMotorPositionHex = "------",
                    rawDecMotorPositionHex = "------",
                    commandedRaArcsecPerSec = 0.0,
                    commandedDecArcsecPerSec = 0.0,
                    measuredRaArcsecPerSec = 0.0,
                    measuredDecArcsecPerSec = 0.0,
                    uncommandedMotionWarning = null,
                    highSpeedBackgroundWarning = false,
                    motorGeneration = resetIntent.generation,
                    stopDiagnosticStatus = StopDiagnosticStatus.NONE
                )
            }
        }
    }

    fun testEcho() {
        if (!usbConnection.isConnected()) {
            _uiState.update { it.copy(error = "Not connected to a serial port.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            val txBytes = NexStarProtocol.ECHO_TEST
            log("Command TX bytes: ${txBytes.toHexString()} ('K' 'A')")

            try {
                val rxBytes = usbConnection.transactSafe(txBytes, timeoutMs = 4000)
                val hexStr = rxBytes.toHexString()
                val asciiStr = rxBytes.toPrintableAscii()
                log("Response RX bytes: $hexStr | ASCII: $asciiStr")

                val isExactEcho = rxBytes.size == 2 && rxBytes[0] == 0x41.toByte() && rxBytes[1] == 0x23.toByte()
                val resultStatus = if (isExactEcho) {
                    "NexStar communication successful"
                } else {
                    "Unexpected echo response"
                }

                log("Test Echo result: $resultStatus")

                _uiState.update {
                    it.copy(
                        responseHex = hexStr,
                        responseAscii = asciiStr,
                        status = resultStatus,
                        busy = false
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log("Test Echo exception / timeout: ${e.message}", isError = true)
                _uiState.update {
                    it.copy(
                        status = "Echo Failed",
                        error = "Echo Error: ${e.message}",
                        busy = false
                    )
                }
            }
        }
    }

    fun getPositionStandard() {
        if (!usbConnection.isConnected()) {
            _uiState.update { it.copy(error = "Not connected to a serial port.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, error = null) }
            val txBytes = NexStarProtocol.GET_RA_DEC_STANDARD
            log("Command TX bytes: ${txBytes.toHexString()} ('E')")

            try {
                val rxBytes = usbConnection.transactSafe(txBytes, timeoutMs = 4000)
                val hexStr = rxBytes.toHexString()
                val asciiStr = rxBytes.toPrintableAscii()
                log("Response RX bytes: $hexStr | ASCII: $asciiStr")

                _uiState.update {
                    it.copy(
                        responseHex = hexStr,
                        responseAscii = "Raw: $asciiStr",
                        status = "Position received",
                        busy = false
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log("Get Position exception / timeout: ${e.message}", isError = true)
                _uiState.update {
                    it.copy(
                        status = "Get Position Failed",
                        error = "Position Error: ${e.message}",
                        busy = false
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
        heartbeatJob?.cancel()
        cleared = true
        shuttingDown = true
        controlEpoch++
        motorCoordinator.updateDesiredState(DesiredAxisState.Stop, DesiredAxisState.Stop, "CLEAR_PENDING")
        motorCoordinator.stopWorker()
        val previousSafetyJob = safetyJob
        safetyScope.launch {
            previousSafetyJob?.join()
            if (usbConnection.isConnected()) motorCoordinator.emergencyStop("VIEWMODEL_CLEARED")
            motorCoordinator.closeConnection()
            safetyScope.cancel()
        }
    }

    companion object {
        private const val TAG = "TelescopeViewModel"
    }
}
