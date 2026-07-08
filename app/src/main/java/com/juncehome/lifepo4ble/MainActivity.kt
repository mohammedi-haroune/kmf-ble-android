package com.juncehome.lifepo4ble

import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.juncehome.lifepo4ble.platform.BlePermissionPolicy

class MainActivity : ComponentActivity() {
    private var readiness by mutableStateOf(BleReadinessUiState())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        refreshReadiness()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshReadiness()
        setContent {
            KmfBleApp(
                readiness = readiness,
                onRequestPermissions = ::requestBlePermissions,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshReadiness()
    }

    private fun requestBlePermissions() {
        val permissions = BlePermissionPolicy.requiredRuntimePermissions(Build.VERSION.SDK_INT)
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun refreshReadiness() {
        val requiredPermissions = BlePermissionPolicy.requiredRuntimePermissions(Build.VERSION.SDK_INT)
        val grantedPermissions = requiredPermissions.filterTo(mutableSetOf()) { permission ->
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
        val permissionsGranted = grantedPermissions.containsAll(requiredPermissions)
        val hasBleFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter

        readiness = BleReadinessUiState(
            requiredPermissions = requiredPermissions,
            grantedPermissions = grantedPermissions,
            hasBleFeature = hasBleFeature,
            hasBluetoothAdapter = adapter != null,
            bluetoothEnabled = adapter.isEnabledWhenAllowed(permissionsGranted),
        )
    }

    private fun android.bluetooth.BluetoothAdapter?.isEnabledWhenAllowed(
        permissionsGranted: Boolean,
    ): Boolean {
        if (this == null || !permissionsGranted) {
            return false
        }
        return try {
            isEnabled
        } catch (_: SecurityException) {
            false
        }
    }
}
