package com.juncehome.lifepo4ble

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.juncehome.lifepo4ble.ble.AndroidBleScanner
import com.juncehome.lifepo4ble.ble.AndroidBleSession
import com.juncehome.lifepo4ble.ble.BleRepository
import com.juncehome.lifepo4ble.data.DeviceStore
import com.juncehome.lifepo4ble.platform.BlePermissionPolicy
import com.juncehome.lifepo4ble.ui.BleViewModel

private val Context.deviceDataStore by preferencesDataStore(name = "device")

class AppGraph(
    private val application: Application,
) {
    private val bluetoothManager: BluetoothManager by lazy {
        requireNotNull(application.getSystemService(BluetoothManager::class.java))
    }

    private val deviceStore: DeviceStore by lazy {
        DeviceStore(application.deviceDataStore)
    }

    private val scanner by lazy {
        AndroidBleScanner(
            context = application,
            hasScanPermission = ::hasRequiredBlePermissions,
        )
    }

    private val session by lazy {
        AndroidBleSession(
            context = application,
            bluetoothManager = bluetoothManager,
        )
    }

    private val repository by lazy {
        BleRepository(scanner, session)
    }

    val bleViewModelFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(BleViewModel::class.java)) {
                return BleViewModel(repository, deviceStore) as T
            }
            error("Unknown ViewModel class ${modelClass.name}")
        }
    }

    private fun hasRequiredBlePermissions(): Boolean =
        BlePermissionPolicy.requiredRuntimePermissions(Build.VERSION.SDK_INT).all { permission ->
            application.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
}
