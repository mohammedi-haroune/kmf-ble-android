package com.juncehome.lifepo4ble.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.juncehome.lifepo4ble.data.DeviceSnapshot
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

interface BleSession {
    fun connect(device: ScannedDevice, preferred: DeviceSnapshot?): Flow<BleEvent>

    fun write(bytes: ByteArray): Boolean

    fun disconnect()
}

class AndroidBleSession(
    private val context: Context,
    private val bluetoothManager: BluetoothManager,
    private val queue: GattOperationQueue = GattOperationQueue(),
) : BleSession {
    private var activeGatt: BluetoothGatt? = null
    private var activeScope: CoroutineScope? = null
    private var activeEvents: SendChannel<BleEvent>? = null
    private var activeWriteCharacteristic: BluetoothGattCharacteristic? = null

    @SuppressLint("MissingPermission")
    override fun connect(device: ScannedDevice, preferred: DeviceSnapshot?): Flow<BleEvent> = callbackFlow {
        val adapter = bluetoothManager.adapter
        if (adapter == null) {
            trySend(BleEvent.Error("Bluetooth adapter is not available"))
            close()
            return@callbackFlow
        }

        val bluetoothDevice = runCatching { adapter.getRemoteDevice(device.address) }.getOrElse { error ->
            trySend(BleEvent.Error("Invalid BLE device address", error))
            close(error)
            return@callbackFlow
        }

        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        activeScope = sessionScope
        activeEvents = this

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        trySend(BleEvent.Connected(device))
                        if (!gatt.discoverServices()) {
                            trySend(BleEvent.Error("Failed to start service discovery"))
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        clearActiveSession()
                        trySend(BleEvent.Disconnected("GATT disconnected with status $status"))
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    trySend(BleEvent.Error("Service discovery failed with status $status"))
                    return
                }
                val profile = GattProfileSelector.select(gatt.services.toServiceInfo(), preferred)
                if (profile == null) {
                    trySend(BleEvent.Error("No safe notify/write GATT profile found"))
                    return
                }
                val service = gatt.getService(profile.serviceUuid)
                val notifyCharacteristic = service?.getCharacteristic(profile.notifyUuid)
                val writeCharacteristic = service?.getCharacteristic(profile.writeUuid)
                val cccd = notifyCharacteristic?.getDescriptor(CLIENT_CONFIG_DESCRIPTOR_UUID)

                if (notifyCharacteristic == null || writeCharacteristic == null || cccd == null) {
                    trySend(BleEvent.Error("Selected GATT profile is no longer available"))
                    return
                }
                if (!gatt.setCharacteristicNotification(notifyCharacteristic, true)) {
                    trySend(BleEvent.Error("Failed to enable local notifications"))
                    return
                }

                sessionScope.launch {
                    val cccdValue = if (profile.usesIndications) {
                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    } else {
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    }
                    val subscribed = queue.enqueue("cccd") {
                        if (!gatt.writeDescriptorCompat(cccd, cccdValue)) {
                            queue.completeCurrent(success = false)
                        }
                    }
                    if (!subscribed) {
                        trySend(BleEvent.Error("Failed to write CCCD descriptor"))
                        return@launch
                    }

                    activeWriteCharacteristic = writeCharacteristic
                    trySend(BleEvent.ServicesDiscovered(profile))
                    startPolling(gatt, writeCharacteristic, this@callbackFlow, sessionScope)
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                queue.completeCurrent(status == BluetoothGatt.GATT_SUCCESS)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                queue.completeCurrent(status == BluetoothGatt.GATT_SUCCESS)
            }

            @Deprecated("Deprecated in Android 13 but still called on older devices")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: return
                trySend(BleEvent.NotificationReceived(characteristic.uuid, value.copyOf()))
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                trySend(BleEvent.NotificationReceived(characteristic.uuid, value.copyOf()))
            }
        }

        val gatt = try {
            bluetoothDevice.connectLeGatt(context, callback)
        } catch (securityException: SecurityException) {
            trySend(BleEvent.Error("Missing Bluetooth connect permission", securityException))
            close(securityException)
            return@callbackFlow
        }
        activeGatt = gatt

        awaitClose {
            sessionScope.cancel()
            disconnectGatt(gatt)
        }
    }

    override fun write(bytes: ByteArray): Boolean {
        val gatt = activeGatt ?: return false
        val writeCharacteristic = activeWriteCharacteristic ?: return false
        val events = activeEvents ?: return false
        val scope = activeScope ?: return false

        scope.launch {
            writeGattBytes(gatt, writeCharacteristic, bytes, events)
        }
        return true
    }

    override fun disconnect() {
        activeScope?.cancel()
        activeGatt?.let { disconnectGatt(it) }
        clearActiveSession()
    }

    private fun startPolling(
        gatt: BluetoothGatt,
        writeCharacteristic: BluetoothGattCharacteristic,
        events: SendChannel<BleEvent>,
        scope: CoroutineScope,
    ): Job = scope.launch {
        while (isActive) {
            delay(POLL_INTERVAL_MS)
            writeGattBytes(gatt, writeCharacteristic, KMF_TOTALS_POLL, events)
        }
    }

    private suspend fun writeGattBytes(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray,
        events: SendChannel<BleEvent>,
    ): Boolean {
        events.trySend(BleEvent.WriteQueued(bytes.copyOf()))
        val success = queue.enqueue("characteristic") {
            if (!gatt.writeCharacteristicCompat(characteristic, bytes)) {
                queue.completeCurrent(success = false)
            }
        }
        events.trySend(BleEvent.WriteCompleted(bytes.copyOf(), success))
        return success
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.connectLeGatt(
        context: Context,
        callback: BluetoothGattCallback,
    ): BluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    } else {
        connectGatt(context, false, callback)
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothGatt.writeDescriptorCompat(
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            writeDescriptor(descriptor)
        }
    } catch (securityException: SecurityException) {
        activeEvents?.trySend(BleEvent.Error("Missing permission for descriptor write", securityException))
        false
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothGatt.writeCharacteristicCompat(
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray,
    ): Boolean = try {
        val writeType = characteristic.preferredWriteType()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            writeCharacteristic(characteristic, bytes, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            @Suppress("DEPRECATION")
            writeCharacteristic(characteristic)
        }
    } catch (securityException: SecurityException) {
        activeEvents?.trySend(BleEvent.Error("Missing permission for characteristic write", securityException))
        false
    }

    private fun BluetoothGattCharacteristic.preferredWriteType(): Int {
        return if ((properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectGatt(gatt: BluetoothGatt) {
        queue.clear()
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }

    private fun clearActiveSession() {
        activeGatt = null
        activeScope = null
        activeEvents = null
        activeWriteCharacteristic = null
        queue.clear()
    }

    private fun List<BluetoothGattService>.toServiceInfo(): List<GattServiceInfo> =
        map { service ->
            GattServiceInfo(
                uuid = service.uuid,
                characteristics = service.characteristics.map { characteristic ->
                    characteristic.toCharacteristicInfo()
                },
            )
        }

    private fun BluetoothGattCharacteristic.toCharacteristicInfo(): GattCharacteristicInfo =
        GattCharacteristicInfo(
            uuid = uuid,
            canNotify = (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0,
            canIndicate = (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0,
            canRead = (properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0,
            canWrite = (properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0,
            canWriteNoResponse = (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0,
            hasClientConfigDescriptor = getDescriptor(CLIENT_CONFIG_DESCRIPTOR_UUID) != null,
        )

    private companion object {
        private val CLIENT_CONFIG_DESCRIPTOR_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val KMF_TOTALS_POLL = byteArrayOf(0x3A, 0x43, 0x0A)
        private const val POLL_INTERVAL_MS = 30_000L
    }
}
