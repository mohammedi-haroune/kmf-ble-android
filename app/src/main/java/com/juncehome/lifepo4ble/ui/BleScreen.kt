package com.juncehome.lifepo4ble.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.juncehome.lifepo4ble.ble.ScannedDevice
import com.juncehome.lifepo4ble.ui.components.DeviceList
import com.juncehome.lifepo4ble.ui.components.PacketLogList
import com.juncehome.lifepo4ble.ui.components.ReadingPanel
import com.juncehome.lifepo4ble.ui.components.StatusPanel

@Composable
fun BleScreen(
    state: BleUiState,
    modifier: Modifier = Modifier,
    onRequestPermissions: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (ScannedDevice) -> Unit,
    onDisconnect: () -> Unit,
    onClearLog: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = "KMF BLE",
            style = MaterialTheme.typography.headlineMedium,
        )
        StatusPanel(state)
        if (!state.collectionConfigurationLoaded) {
            Text("Checking whether background collection owns the BLE connection...")
        } else if (state.autonomousCollectionConfigured) {
            Text("Autonomous collection owns the BLE connection. Stop it from Dashboard before changing transport controls.")
        }
        Controls(
            state = state,
            onRequestPermissions = onRequestPermissions,
            onStartScan = onStartScan,
            onStopScan = onStopScan,
            onDisconnect = onDisconnect,
            onClearLog = onClearLog,
            transportControlsEnabled = state.manualTransportControlsEnabled,
        )
        DeviceList(
            devices = state.devices,
            selectedDevice = state.selectedDevice,
            onConnect = onConnect,
            connectEnabled = state.manualTransportControlsEnabled,
        )
        ReadingPanel(reading = state.latestReading)
        PacketLogList(packetLog = state.packetLog)
    }
}

@Composable
private fun Controls(
    state: BleUiState,
    onRequestPermissions: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onDisconnect: () -> Unit,
    onClearLog: () -> Unit,
    transportControlsEnabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = !state.permissionsGranted,
            onClick = onRequestPermissions,
        ) {
            Text(text = "Grant BLE permissions")
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                enabled = transportControlsEnabled && state.readyToScan && !state.scanning,
                onClick = onStartScan,
            ) {
                Text(text = "Scan")
            }
            OutlinedButton(
                enabled = transportControlsEnabled && state.scanning,
                onClick = onStopScan,
            ) {
                Text(text = "Stop")
            }
            OutlinedButton(
                enabled = transportControlsEnabled && state.connectionState != ConnectionState.DISCONNECTED,
                onClick = onDisconnect,
            ) {
                Text(text = "Disconnect")
            }
        }
        OutlinedButton(
            enabled = state.packetLog.isNotEmpty(),
            onClick = onClearLog,
        ) {
            Text(text = "Clear log")
        }
    }
}
