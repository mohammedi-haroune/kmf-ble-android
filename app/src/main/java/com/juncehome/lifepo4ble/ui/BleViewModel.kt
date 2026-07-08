package com.juncehome.lifepo4ble.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juncehome.lifepo4ble.ble.BleRepositoryContract
import com.juncehome.lifepo4ble.ble.GattProfile
import com.juncehome.lifepo4ble.ble.ScannedDevice
import com.juncehome.lifepo4ble.data.DeviceSnapshot
import com.juncehome.lifepo4ble.data.DeviceSnapshotStore
import com.juncehome.lifepo4ble.protocol.KmfLineParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BleViewModel(
    private val repository: BleRepositoryContract,
    private val deviceStore: DeviceSnapshotStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val scope: CoroutineScope? = null,
) : ViewModel() {
    private val parser = KmfLineParser()
    private val workScope: CoroutineScope
        get() = scope ?: viewModelScope

    private val mutableUiState = MutableStateFlow(BleUiState())
    val uiState: StateFlow<BleUiState> = mutableUiState

    init {
        workScope.launch {
            repository.events.collect { event ->
                val previousState = mutableUiState.value
                val nextState = BleStateReducer.reduce(previousState, event, parser, clock())
                mutableUiState.value = nextState

                if (event is com.juncehome.lifepo4ble.ble.BleEvent.ServicesDiscovered) {
                    saveSnapshot(nextState.selectedDevice, event.profile)
                }
            }
        }
    }

    fun updateReadiness(
        requiredPermissions: Set<String>,
        grantedPermissions: Set<String>,
        hasBleFeature: Boolean,
        hasBluetoothAdapter: Boolean,
        bluetoothEnabled: Boolean,
    ) {
        mutableUiState.update { state ->
            state.copy(
                requiredPermissions = requiredPermissions,
                grantedPermissions = grantedPermissions,
                hasBleFeature = hasBleFeature,
                hasBluetoothAdapter = hasBluetoothAdapter,
                bluetoothEnabled = bluetoothEnabled,
            )
        }
    }

    fun startScan() {
        repository.startScan()
    }

    fun stopScan() {
        repository.stopScan()
        mutableUiState.update { state ->
            state.copy(scanning = false)
        }
    }

    fun connect(device: ScannedDevice) {
        workScope.launch {
            repository.connect(device, preferred = deviceStore.snapshot.first())
        }
    }

    fun disconnect() {
        repository.disconnect()
    }

    fun clearLog() {
        mutableUiState.update { state ->
            state.copy(packetLog = emptyList())
        }
    }

    private suspend fun saveSnapshot(device: ScannedDevice?, profile: GattProfile) {
        if (device == null) {
            return
        }

        deviceStore.save(
            DeviceSnapshot(
                address = device.address,
                name = device.name,
                serviceUuid = profile.serviceUuid.toString(),
                notifyUuid = profile.notifyUuid.toString(),
                writeUuid = profile.writeUuid.toString(),
            )
        )
    }
}
