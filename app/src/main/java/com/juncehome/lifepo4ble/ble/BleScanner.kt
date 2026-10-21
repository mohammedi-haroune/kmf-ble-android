package com.juncehome.lifepo4ble.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import com.juncehome.lifepo4ble.AppLog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

interface BleScanner {
    fun scan(): Flow<List<ScannedDevice>>
}

class AndroidBleScanner(
    private val context: Context,
    private val hasScanPermission: () -> Boolean,
) : BleScanner {
    @SuppressLint("MissingPermission")
    override fun scan(): Flow<List<ScannedDevice>> = callbackFlow {
        AppLog.d("scanner.scan()", "KMF-BLE")
        if (!hasScanPermission()) {
            AppLog.e("scan permission missing", tag = "KMF-BLE")
            close(SecurityException("Missing BLE scan permission"))
            return@callbackFlow
        }

        val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
        val scanner = bluetoothManager?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            AppLog.e("BLE scanner unavailable", tag = "KMF-BLE")
            close(IllegalStateException("Bluetooth LE scanner is not available"))
            return@callbackFlow
        }

        val discovered = linkedMapOf<String, ScannedDevice>()
        // Logged once per address and name so non-meter neighbours do not flood the log.
        val ignored = hashSetOf<Pair<String, String?>>()
        fun accept(device: ScannedDevice): Boolean {
            if (KmfMeterFilter.isKmfMeter(device)) return true
            if (ignored.add(device.address to device.name)) {
                AppLog.d("scan ignored non-KMF ${device.address} name=${device.name} rssi=${device.rssi}", "KMF-BLE")
            }
            return false
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                result.toScannedDevice()?.takeIf(::accept)?.let { device ->
                    AppLog.d("scan result ${device.address} name=${device.name} rssi=${device.rssi}", "KMF-BLE")
                    discovered[device.address] = device
                    trySend(discovered.values.sortedForDisplay())
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                AppLog.d("scan batch size=${results.size}", "KMF-BLE")
                results.forEach { result ->
                    result.toScannedDevice()?.takeIf(::accept)?.let { device ->
                        discovered[device.address] = device
                    }
                }
                trySend(discovered.values.sortedForDisplay())
            }

            override fun onScanFailed(errorCode: Int) {
                AppLog.e("scan failed code=$errorCode", tag = "KMF-BLE")
                close(IllegalStateException("BLE scan failed with code $errorCode"))
            }
        }

        try {
            AppLog.d("startScan()", "KMF-BLE")
            scanner.startScan(callback)
        } catch (securityException: SecurityException) {
            AppLog.e("startScan security exception", securityException, "KMF-BLE")
            close(securityException)
            return@callbackFlow
        }

        awaitClose {
            AppLog.d("stopScan()", "KMF-BLE")
            try {
                scanner.stopScan(callback)
            } catch (_: SecurityException) {
                // Permission may have been revoked while scanning; cancellation still owns cleanup.
            }
        }
    }

    private fun ScanResult.toScannedDevice(): ScannedDevice? {
        val device = device ?: return null
        val address = try {
            device.address
        } catch (_: SecurityException) {
            return null
        }
        val name = scanRecord?.deviceName ?: runCatching { device.name }.getOrNull()
        val serviceUuids = scanRecord
            ?.serviceUuids
            ?.map { parcelUuid -> parcelUuid.uuid }
            .orEmpty()

        return ScannedDevice(
            name = name,
            address = address,
            rssi = rssi,
            serviceUuids = serviceUuids,
        )
    }

    private fun Collection<ScannedDevice>.sortedForDisplay(): List<ScannedDevice> =
        sortedWith(
            compareByDescending<ScannedDevice> { it.rssi }
                .thenBy { it.name.orEmpty() }
                .thenBy { it.address }
        )
}
