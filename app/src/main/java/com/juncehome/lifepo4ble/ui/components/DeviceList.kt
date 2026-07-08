package com.juncehome.lifepo4ble.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.juncehome.lifepo4ble.ble.ScannedDevice

@Composable
fun DeviceList(
    devices: List<ScannedDevice>,
    selectedDevice: ScannedDevice?,
    onConnect: (ScannedDevice) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Devices",
            style = MaterialTheme.typography.titleMedium,
        )
        if (devices.isEmpty()) {
            Text(text = "No devices yet")
        } else {
            devices.forEach { device ->
                DeviceRow(
                    device = device,
                    selected = device.address == selectedDevice?.address,
                    onConnect = onConnect,
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: ScannedDevice,
    selected: Boolean,
    onConnect: (ScannedDevice) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = device.name ?: "Unnamed BLE device",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(text = device.address)
            Text(text = "RSSI ${device.rssi} dBm")
            if (selected) {
                Text(text = "Selected")
            }
        }
        OutlinedButton(onClick = { onConnect(device) }) {
            Text(text = "Connect")
        }
    }
}
