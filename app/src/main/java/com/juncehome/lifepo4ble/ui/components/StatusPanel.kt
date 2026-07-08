package com.juncehome.lifepo4ble.ui.components

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.juncehome.lifepo4ble.ui.BleUiState

@Composable
fun StatusPanel(state: BleUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Status",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(text = "Permissions: ${state.permissionStatusText()}")
        Text(text = "Bluetooth: ${state.bluetoothEnabled.yesNo()}")
        Text(text = "BLE feature: ${state.hasBleFeature.yesNo()}")
        Text(text = "Adapter: ${state.hasBluetoothAdapter.yesNo()}")
        Text(text = "Connection: ${state.connectionState.name.lowercase()}")
        Text(text = "Service UUID: ${state.serviceUuid.orEmptyLabel()}")
        Text(text = "Notify UUID: ${state.notifyUuid.orEmptyLabel()}")
        Text(text = "Write UUID: ${state.writeUuid.orEmptyLabel()}")
        state.latestError?.let { error ->
            Text(
                text = "Error: $error",
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun BleUiState.permissionStatusText(): String =
    if (permissionsGranted) {
        "granted"
    } else {
        "missing ${missingPermissionLabels().joinToString()}"
    }

private fun BleUiState.missingPermissionLabels(): List<String> =
    (requiredPermissions - grantedPermissions).map { permission ->
        when (permission) {
            Manifest.permission.ACCESS_FINE_LOCATION -> "fine location"
            Manifest.permission.BLUETOOTH_SCAN -> if (Build.VERSION.SDK_INT >= 31) {
                "Bluetooth scan"
            } else {
                "scan"
            }
            Manifest.permission.BLUETOOTH_CONNECT -> if (Build.VERSION.SDK_INT >= 31) {
                "Bluetooth connect"
            } else {
                "connect"
            }
            else -> permission
        }
    }

private fun Boolean.yesNo(): String = if (this) "yes" else "no"

private fun String?.orEmptyLabel(): String = this ?: "-"
