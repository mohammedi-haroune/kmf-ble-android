package com.juncehome.lifepo4ble

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.juncehome.lifepo4ble.ui.theme.KmfBleTheme

data class BleReadinessUiState(
    val requiredPermissions: Set<String> = emptySet(),
    val grantedPermissions: Set<String> = emptySet(),
    val hasBleFeature: Boolean = false,
    val hasBluetoothAdapter: Boolean = false,
    val bluetoothEnabled: Boolean = false,
) {
    val permissionsGranted: Boolean
        get() = grantedPermissions.containsAll(requiredPermissions)
}

@Composable
fun KmfBleApp(
    readiness: BleReadinessUiState,
    onRequestPermissions: () -> Unit,
) {
    KmfBleTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "KMF BLE",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(text = "Permission status: ${readiness.permissionStatusText()}")
                Text(text = "Bluetooth enabled: ${readiness.bluetoothEnabled.yesNo()}")
                Text(text = "BLE feature: ${readiness.hasBleFeature.yesNo()}")
                Text(text = "Bluetooth adapter: ${readiness.hasBluetoothAdapter.yesNo()}")
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    enabled = !readiness.permissionsGranted,
                    onClick = onRequestPermissions,
                ) {
                    Text(text = "Grant BLE permissions")
                }
            }
        }
    }
}

private fun BleReadinessUiState.permissionStatusText(): String =
    if (permissionsGranted) {
        "granted"
    } else {
        "missing ${missingPermissionLabels().joinToString()}"
    }

private fun BleReadinessUiState.missingPermissionLabels(): List<String> =
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
