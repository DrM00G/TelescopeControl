package com.example.telescopecontrol

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.telescopecontrol.model.HandControllerStatus
import com.example.telescopecontrol.model.MountStatus
import com.example.telescopecontrol.model.SlewAxis
import com.example.telescopecontrol.model.SlewDirection
import com.example.telescopecontrol.model.StopDiagnosticStatus
import com.example.telescopecontrol.ui.theme.TelescopeControlTheme
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: TelescopeViewModel by viewModels()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }

                        if (device != null) {
                            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                            if (granted) {
                                viewModel.onPermissionGranted(device)
                            } else {
                                viewModel.onPermissionDenied(device)
                            }
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    viewModel.onUsbDeviceDetached()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    viewModel.scanDevices()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        ContextCompat.registerReceiver(
            this,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setContent {
            TelescopeControlTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TelescopeControlScreen(
                        viewModel = viewModel,
                        onRequestPermission = { device -> requestUsbPermission(device) },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(usbReceiver)
        } catch (_: Exception) {
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            intent,
            PendingIntent.FLAG_MUTABLE
        )
        usbManager.requestPermission(device, pendingIntent)
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.example.telescopecontrol.USB_PERMISSION"
    }
}

@Composable
fun TelescopeControlScreen(
    viewModel: TelescopeViewModel,
    onRequestPermission: (UsbDevice) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // App Title
        Text(
            text = "AVX CONTROLLER v0.3.2",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        // Readiness & Status Panel
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Readiness Indicator Banner
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val readyText = if (state.isAvxReady) "AVX READY" else "AVX INITIALIZING..."
                    val readyColor = if (state.isAvxReady) Color(0xFF2E7D32) else Color(0xFFF57C00)

                    Text(
                        text = readyText,
                        color = readyColor,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp
                    )

                    Text(
                        text = "HC FW: ${state.handControllerVersion}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // USB Hand Controller Status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "USB Hand Controller: ", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = if (state.handControllerStatus == HandControllerStatus.CONNECTED) "CONNECTED" else "DISCONNECTED",
                            color = if (state.handControllerStatus == HandControllerStatus.CONNECTED) Color(0xFF2E7D32) else Color(0xFFD32F2F),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    val mountColor = when (state.mountStatus) {
                        MountStatus.ONLINE -> Color(0xFF2E7D32)
                        MountStatus.PARTIAL -> Color(0xFFF57C00)
                        MountStatus.OFFLINE -> Color(0xFFD32F2F)
                        MountStatus.UNKNOWN -> Color.Gray
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "AVX Power: ", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = state.mountStatus.name,
                            color = mountColor,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Motor Controllers FW Status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "RA MC: ${state.raMcVersion}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "DEC MC: ${state.decMcVersion}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Alignment & Tracking Mode Status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Alignment: ${state.alignmentTrust.label}",
                        color = if (state.isAligned == true) Color(0xFF2E7D32) else Color(0xFFE65100),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodySmall
                    )

                    Text(
                        text = "Celestron Track: ${state.trackingMode.label}",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // STOP Diagnostic Status Banner if active
                if (state.stopDiagnosticStatus != StopDiagnosticStatus.NONE) {
                    Spacer(modifier = Modifier.height(4.dp))
                    val stopColor = when (state.stopDiagnosticStatus) {
                        StopDiagnosticStatus.STOP_VERIFIED -> Color(0xFF2E7D32)
                        StopDiagnosticStatus.STOP_COMMANDS_ACKNOWLEDGED -> Color(0xFF1976D2)
                        StopDiagnosticStatus.STOP_REQUESTED -> Color(0xFFF57C00)
                        StopDiagnosticStatus.STOP_ACKNOWLEDGED_BUT_MOTION_CONTINUES -> Color(0xFFD32F2F)
                        else -> Color.Gray
                    }
                    Text(
                        text = "STOP DIAGNOSTIC: ${state.stopDiagnosticStatus.label}",
                        color = stopColor,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 12.sp
                    )
                }

                // Alignment Warning Info Banner
                state.alignmentWarning?.let { warningMsg ->
                    Spacer(modifier = Modifier.height(6.dp))
                    val isPowerLost = state.mountStatus == MountStatus.OFFLINE && state.handControllerStatus == HandControllerStatus.CONNECTED
                    val bannerBg = if (isPowerLost) Color(0xFFFFEBEE) else Color(0xFFFFF3E0)
                    val bannerText = if (isPowerLost) Color(0xFFC62828) else Color(0xFFE65100)

                    Surface(
                        color = bannerBg,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = warningMsg,
                            color = bannerText,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(6.dp)
                        )
                    }
                }

                // Safety Warning: Uncommanded Axis Motion
                state.uncommandedMotionWarning?.let { uncommandedMsg ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        color = Color(0xFFFFEBEE),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "⚠️ $uncommandedMsg",
                            color = Color(0xFFC62828),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(6.dp)
                        )
                    }
                }

                // High-Speed Continuous Motion Warning Indicator
                if (state.highSpeedBackgroundWarning) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        color = Color(0xFFFFF3E0),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "⚡ HIGH-SPEED CONTINUOUS MOTION (>3600\"/s)",
                            color = Color(0xFFE65100),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(6.dp)
                        )
                    }
                }
            }
        }

        // Connection Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val selected = state.selectedDevice
            Button(
                onClick = {
                    if (selected != null) {
                        viewModel.requestConnection(selected, onRequestPermission)
                    }
                },
                enabled = !state.connected && selected != null && !state.busy,
                modifier = Modifier.weight(1f)
            ) {
                Text("Connect")
            }

            Button(
                onClick = { viewModel.disconnect() },
                enabled = state.connected && !state.busy,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.weight(1f)
            ) {
                Text("Disconnect")
            }
        }

        // --- MANUAL VECTOR TRACKING PANEL (v0.3.2) ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Manual Vector Tracking",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Direct velocity control (No alignment needed)",
                            fontSize = 11.sp,
                            color = Color.DarkGray
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (state.manualTrackEnabled) "ON " else "OFF ",
                            fontWeight = FontWeight.Bold,
                            color = if (state.manualTrackEnabled) Color(0xFF2E7D32) else Color.Gray
                        )
                        Switch(
                            checked = state.manualTrackEnabled,
                            onCheckedChange = { viewModel.toggleManualTrack(it) },
                            enabled = state.connected && state.isAvxReady
                        )
                    }
                }

                if (state.manualTrackEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    // Motion Diagnostic Box
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Motion Diagnostic", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                Text("GEN: ${state.motorGeneration}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color.Gray)
                            }
                            Spacer(modifier = Modifier.height(4.dp))

                            Text("Commanded / Desired", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            Text(
                                "RA:  %+.1f\"/s".format(Locale.US, state.commandedRaArcsecPerSec),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                "DEC: %+.1f\"/s".format(Locale.US, state.commandedDecArcsecPerSec),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )

                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Measured (Encoder)", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            Text(
                                "RA:  %+.1f\"/s".format(Locale.US, state.measuredRaArcsecPerSec),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF2E7D32)
                            )
                            Text(
                                "DEC: %+.1f\"/s".format(Locale.US, state.measuredDecArcsecPerSec),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF2E7D32)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Background Speed Step Selector
                    Text("Background Speed Step: ${state.trackStep}\"/s", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("Amount added/subtracted from background speed each tap.", fontSize = 10.sp, color = Color.Gray)
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(0.25, 1.0, 5.0, 15.0, 60.0, 240.0, 960.0).forEach { step ->
                            val isSelected = step == state.trackStep
                            Button(
                                onClick = { viewModel.setTrackStep(step) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
                                ),
                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(32.dp)
                            ) {
                                val label = if (step >= 1.0) "${step.toInt()}\"" else "$step\""
                                Text(label, fontSize = 9.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Background DEC Velocity Adjustment Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("DEC:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Button(
                            onClick = { viewModel.adjustBackgroundDec(-state.trackStep) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                        ) {
                            Text(" - ")
                        }
                        Text(
                            text = "%+.2f\"/s".format(Locale.US, state.backgroundDecArcsecPerSec),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Button(
                            onClick = { viewModel.adjustBackgroundDec(state.trackStep) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                        ) {
                            Text(" + ")
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Background RA Velocity Adjustment Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("RA:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Button(
                            onClick = { viewModel.adjustBackgroundRa(-state.trackStep) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                        ) {
                            Text(" - ")
                        }
                        Text(
                            text = "%+.2f\"/s".format(Locale.US, state.backgroundRaArcsecPerSec),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Button(
                            onClick = { viewModel.adjustBackgroundRa(state.trackStep) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                        ) {
                            Text(" + ")
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Zero Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = { viewModel.zeroBackgroundRa() },
                            contentPadding = PaddingValues(2.dp),
                            modifier = Modifier.weight(1f).height(32.dp)
                        ) {
                            Text("ZERO RA", fontSize = 11.sp)
                        }
                        Button(
                            onClick = { viewModel.zeroBackgroundDec() },
                            contentPadding = PaddingValues(2.dp),
                            modifier = Modifier.weight(1f).height(32.dp)
                        ) {
                            Text("ZERO DEC", fontSize = 11.sp)
                        }
                        Button(
                            onClick = { viewModel.zeroBackgroundBoth() },
                            contentPadding = PaddingValues(2.dp),
                            modifier = Modifier.weight(1f).height(32.dp)
                        ) {
                            Text("ZERO BOTH", fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // Current Position Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (state.isAligned == true) "Current Position (Live)" else "Position (Unaligned Estimate)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (state.isAligned != true) {
                            Text(
                                text = "Not referenced to the actual sky",
                                fontSize = 11.sp,
                                color = Color.DarkGray
                            )
                        }
                    }

                    Button(
                        onClick = { viewModel.refreshPosition() },
                        enabled = state.connected && !state.busy,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Refresh", fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                val pos = state.position
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "RA", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                        Text(
                            text = pos?.raFormatted ?: "--h --m --.-s",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = pos?.let { "%.3f°".format(it.raDegrees) } ?: "---.---°",
                            fontSize = 12.sp,
                            color = Color.DarkGray
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "DEC", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                        Text(
                            text = pos?.decFormatted ?: "--° --' --\"",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = pos?.let { "%+.3f°".format(it.decDegrees) } ?: "---.---°",
                            fontSize = 12.sp,
                            color = Color.DarkGray
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Raw Motor Encoder Position: RA ${state.rawRaMotorPositionHex} | DEC ${state.rawDecMotorPositionHex}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        HorizontalDivider()

        // Manual Slew Rate Selector (1-9) — Single control for all manual directional arrows
        val isManualControlsEnabled = state.connected && state.isAvxReady

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Manual Slew Rate: ${state.slewRate}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (state.slewRate == 9) {
                    Text(
                        text = "MAX SLEW (9)",
                        color = Color(0xFFD32F2F),
                        fontWeight = FontWeight.ExtraBold,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                (1..9).forEach { rate ->
                    val isSelected = rate == state.slewRate
                    val isFast = rate == 9
                    val btnColor = when {
                        isSelected && isFast -> Color(0xFFD32F2F)
                        isSelected -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                    val txtColor = when {
                        isSelected -> Color.White
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(btnColor)
                            .clickable(enabled = isManualControlsEnabled && !state.isSlewing) {
                                viewModel.setSlewRate(rate)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = rate.toString(),
                            color = txtColor,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Manual Slew Directional Controls (D-Pad)
        Text(
            text = if (state.manualTrackEnabled) "Manual Directional Controls (Overrides Background)" else "Manual Directional Controls",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // DEC+ Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                SlewDirectionButton(
                    text = "DEC+ ▲",
                    axis = SlewAxis.DEC,
                    direction = SlewDirection.POSITIVE,
                    enabled = isManualControlsEnabled,
                    isCurrentlyActive = state.isSlewing && state.activeSlewAxis == SlewAxis.DEC && state.activeSlewDirection == SlewDirection.POSITIVE,
                    onSlewStart = { a, d -> viewModel.startManualSlew(a, d) },
                    onSlewStop = { a -> viewModel.stopManualSlew(a) },
                    modifier = Modifier.width(140.dp)
                )
            }

            // Middle Row: RA-, STOP, RA+
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SlewDirectionButton(
                    text = "◀ RA-",
                    axis = SlewAxis.RA,
                    direction = SlewDirection.NEGATIVE,
                    enabled = isManualControlsEnabled,
                    isCurrentlyActive = state.isSlewing && state.activeSlewAxis == SlewAxis.RA && state.activeSlewDirection == SlewDirection.NEGATIVE,
                    onSlewStart = { a, d -> viewModel.startManualSlew(a, d) },
                    onSlewStop = { a -> viewModel.stopManualSlew(a) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 4.dp)
                )

                // Red STOP Button (Global Emergency Stop with Absolute Authority)
                Button(
                    onClick = { viewModel.stopAll() },
                    enabled = state.connected,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD32F2F),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(vertical = 16.dp, horizontal = 12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp)
                ) {
                    Text(
                        text = "STOP",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 20.sp
                    )
                }

                SlewDirectionButton(
                    text = "RA+ ▶",
                    axis = SlewAxis.RA,
                    direction = SlewDirection.POSITIVE,
                    enabled = isManualControlsEnabled,
                    isCurrentlyActive = state.isSlewing && state.activeSlewAxis == SlewAxis.RA && state.activeSlewDirection == SlewDirection.POSITIVE,
                    onSlewStart = { a, d -> viewModel.startManualSlew(a, d) },
                    onSlewStop = { a -> viewModel.stopManualSlew(a) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp)
                )
            }

            // DEC- Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                SlewDirectionButton(
                    text = "DEC- ▼",
                    axis = SlewAxis.DEC,
                    direction = SlewDirection.NEGATIVE,
                    enabled = isManualControlsEnabled,
                    isCurrentlyActive = state.isSlewing && state.activeSlewAxis == SlewAxis.DEC && state.activeSlewDirection == SlewDirection.NEGATIVE,
                    onSlewStart = { a, d -> viewModel.startManualSlew(a, d) },
                    onSlewStop = { a -> viewModel.stopManualSlew(a) },
                    modifier = Modifier.width(140.dp)
                )
            }
        }

        HorizontalDivider()

        // Error Banner if present
        state.error?.let { err ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = err, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.clearError() }) {
                        Text("Dismiss")
                    }
                }
            }
        }

        // Expandable Diagnostics Section
        var diagnosticsExpanded by remember { mutableStateOf(false) }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { diagnosticsExpanded = !diagnosticsExpanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Diagnostics & USB Details ${if (diagnosticsExpanded) "▲" else "▼"}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (diagnosticsExpanded) "Hide" else "Expand",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }

                if (diagnosticsExpanded) {
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = { viewModel.scanDevices() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Scan USB", fontSize = 11.sp)
                        }
                        Button(
                            onClick = { viewModel.testEcho() },
                            enabled = state.connected && !state.busy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Test Echo", fontSize = 11.sp)
                        }
                        Button(
                            onClick = { viewModel.getPositionStandard() },
                            enabled = state.connected && !state.busy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Pos (Std E)", fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // USB Devices list
                    if (state.usbDevices.isNotEmpty()) {
                        Text("USB Devices Found (${state.usbDevices.size}):", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 140.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(state.usbDevices) { dev ->
                                val isSelected = dev.rawDevice.deviceId == state.selectedDevice?.rawDevice?.deviceId
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(
                                            width = if (isSelected) 2.dp else 0.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                            shape = RoundedCornerShape(6.dp)
                                        )
                                        .clickable { viewModel.selectDevice(dev) },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(6.dp)) {
                                        Text(dev.deviceName, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Text("VID: ${dev.vendorIdDecimal} (${dev.vendorIdHex}) PID: ${dev.productIdDecimal} (${dev.productIdHex})", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                        Text("Manufacturer: ${dev.manufacturerName ?: "N/A"} Product: ${dev.productName ?: "N/A"}", fontSize = 10.sp)
                                        Text("Driver: ${dev.driverClassName ?: "None"}", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Last Response ASCII: ${state.responseAscii}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Text("Last Response HEX: ${state.responseHex}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)

                    Spacer(modifier = Modifier.height(6.dp))

                    // Log output box
                    Text("Logs:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                            .background(Color.Black, shape = RoundedCornerShape(4.dp))
                            .padding(4.dp)
                    ) {
                        val logListState = rememberLazyListState()
                        LaunchedEffect(state.logs.size) {
                            if (state.logs.isNotEmpty()) {
                                logListState.animateScrollToItem(state.logs.size - 1)
                            }
                        }
                        LazyColumn(state = logListState) {
                            items(state.logs) { logLine ->
                                Text(
                                    text = logLine,
                                    color = Color.Green,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SlewDirectionButton(
    text: String,
    axis: SlewAxis,
    direction: SlewDirection,
    enabled: Boolean,
    isCurrentlyActive: Boolean,
    onSlewStart: (SlewAxis, SlewDirection) -> Unit,
    onSlewStop: (SlewAxis) -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (isCurrentlyActive) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = if (isCurrentlyActive) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) containerColor else Color.LightGray.copy(alpha = 0.5f))
            .pointerInput(enabled, axis, direction) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        onSlewStart(axis, direction)
                        try {
                            tryAwaitRelease()
                        } finally {
                            onSlewStop(axis)
                        }
                    }
                )
            }
            .padding(vertical = 16.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) contentColor else Color.Gray,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
    }
}
