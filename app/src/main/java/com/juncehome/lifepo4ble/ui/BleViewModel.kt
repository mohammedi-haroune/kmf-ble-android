package com.juncehome.lifepo4ble.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juncehome.lifepo4ble.AppLog
import com.juncehome.lifepo4ble.ble.BleRepositoryContract
import com.juncehome.lifepo4ble.ble.GattProfile
import com.juncehome.lifepo4ble.ble.ScannedDevice
import com.juncehome.lifepo4ble.data.DeviceSnapshot
import com.juncehome.lifepo4ble.data.DeviceSnapshotStore
import com.juncehome.lifepo4ble.data.KmfHistoryStore
import com.juncehome.lifepo4ble.data.KmfNotificationObservation
import com.juncehome.lifepo4ble.data.KmfWriteObservation
import com.juncehome.lifepo4ble.protocol.KmfLineParser
import com.juncehome.lifepo4ble.ble.BleEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BleViewModel(
    private val repository: BleRepositoryContract,
    private val deviceStore: DeviceSnapshotStore,
    private val historyStore: KmfHistoryStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val scope: CoroutineScope? = null,
    private val reconnectDelayMs: Long = 1_000L,
) : ViewModel() {
    private val parser = KmfLineParser()
    private var bootstrapJob: Job? = null
    private var totalsPollJob: Job? = null
    private var reconnectJob: Job? = null
    private var manualDisconnectRequested = false
    private val workScope: CoroutineScope
        get() = scope ?: viewModelScope

    private val mutableUiState = MutableStateFlow(BleUiState())
    val uiState: StateFlow<BleUiState> = mutableUiState

    init {
        workScope.launch {
            repository.events.collect { event ->
                val previousState = mutableUiState.value
                val nowMs = clock()
                val nextState = BleStateReducer.reduce(previousState, event, parser, nowMs)
                mutableUiState.value = nextState
                persistHistory(event, nextState, nowMs)

                if (event is BleEvent.Connecting || event is BleEvent.Connected) {
                    cancelReconnect()
                }

                if (event is BleEvent.ServicesDiscovered) {
                    cancelReconnect()
                    saveSnapshot(nextState.selectedDevice, event.profile)
                    startOfficialBootstrapSequence()
                }

                if (nextState.kmfBootstrapReady) {
                    bootstrapJob?.cancel()
                    bootstrapJob = null
                    startTotalsPollingIfNeeded()
                }

                if (event is BleEvent.Disconnected ||
                    event is BleEvent.Error
                ) {
                    cancelBackgroundWrites()
                }

                if (event is BleEvent.Disconnected) {
                    if (manualDisconnectRequested) {
                        manualDisconnectRequested = false
                    } else if (shouldAutoReconnect(previousState)) {
                        scheduleRememberedConnection("disconnect")
                    }
                }

                if (event is BleEvent.Error && shouldAutoReconnect(previousState)) {
                    scheduleRememberedConnection("error", requireDisconnected = false)
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
        val previousState = mutableUiState.value
        val nextState = previousState.copy(
                requiredPermissions = requiredPermissions,
                grantedPermissions = grantedPermissions,
                hasBleFeature = hasBleFeature,
                hasBluetoothAdapter = hasBluetoothAdapter,
                bluetoothEnabled = bluetoothEnabled,
            )
        mutableUiState.value = nextState

        if (!previousState.readyToScan && nextState.readyToScan) {
            scheduleRememberedConnection("readiness", delayMs = 0L)
        } else if (previousState.readyToScan && !nextState.readyToScan) {
            cancelReconnect()
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
        manualDisconnectRequested = false
        cancelReconnect()
        workScope.launch {
            repository.connect(device, preferred = deviceStore.snapshot.first())
        }
    }

    fun disconnect() {
        manualDisconnectRequested = true
        cancelReconnect()
        cancelBackgroundWrites()
        repository.disconnect()
    }

    fun clearLog() {
        mutableUiState.update { state ->
            state.copy(packetLog = emptyList())
        }
    }

    private fun startOfficialBootstrapSequence() {
        cancelBackgroundWrites()
        bootstrapJob = workScope.launch {
            delay(INITIAL_BOOTSTRAP_DELAY_MS)
            repository.requestMtu(PREFERRED_MTU)
            delay(POST_MTU_DELAY_MS)
            repeat(MAX_BOOTSTRAP_ATTEMPTS) { attempt ->
                if (!isActive) {
                    return@launch
                }
                val state = mutableUiState.value
                if (state.kmfBootstrapReady || state.connectionState == ConnectionState.DISCONNECTED) {
                    return@launch
                }
                AppLog.d(
                    "bootstrap loop attempt=${attempt + 1}/$MAX_BOOTSTRAP_ATTEMPTS writing ${KMF_BOOTSTRAP_REQUEST.toHexString()}",
                    TAG,
                )
                repository.write(KMF_BOOTSTRAP_REQUEST)
                delay(BOOTSTRAP_RETRY_DELAY_MS)
            }
            AppLog.w("bootstrap loop ended without observing both A and B frames", TAG)
        }
    }

    private fun startTotalsPollingIfNeeded() {
        if (totalsPollJob?.isActive == true) {
            return
        }
        totalsPollJob = workScope.launch {
            while (isActive) {
                delay(TOTALS_POLL_INTERVAL_MS)
                if (mutableUiState.value.connectionState == ConnectionState.DISCONNECTED) {
                    break
                }
                AppLog.d("poll timer fired writing ${KMF_TOTALS_POLL.toHexString()}", TAG)
                repository.write(KMF_TOTALS_POLL)
            }
        }
    }

    private fun cancelBackgroundWrites() {
        bootstrapJob?.cancel()
        bootstrapJob = null
        totalsPollJob?.cancel()
        totalsPollJob = null
    }

    private fun scheduleRememberedConnection(
        reason: String,
        delayMs: Long = reconnectDelayMs,
        requireDisconnected: Boolean = true,
    ) {
        if (reconnectJob?.isActive == true) {
            return
        }
        reconnectJob = workScope.launch {
            if (delayMs > 0) {
                delay(delayMs)
            }
            if (manualDisconnectRequested) {
                return@launch
            }
            val state = mutableUiState.value
            if (!state.readyToScan) {
                return@launch
            }
            if (requireDisconnected && state.connectionState != ConnectionState.DISCONNECTED) {
                return@launch
            }
            val snapshot = deviceStore.snapshot.first() ?: return@launch
            AppLog.d("auto connect reason=$reason address=${snapshot.address}", TAG)
            repository.connect(snapshot.toScannedDevice(), preferred = snapshot)
        }.also { job ->
            job.invokeOnCompletion {
                if (reconnectJob === job) {
                    reconnectJob = null
                }
            }
        }
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun shouldAutoReconnect(state: BleUiState): Boolean =
        state.selectedDevice != null && state.connectionState != ConnectionState.DISCONNECTED

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

    private suspend fun persistHistory(
        event: BleEvent,
        state: BleUiState,
        nowMs: Long,
    ) {
        when (event) {
            is BleEvent.Connecting -> historyStore.resetSession(event.device.address)
            is BleEvent.NotificationReceived -> {
                val device = state.selectedDevice ?: return
                try {
                    historyStore.recordNotification(
                        KmfNotificationObservation(
                            timestampMs = nowMs,
                            deviceAddress = device.address,
                            deviceName = device.name,
                            serviceUuid = state.serviceUuid,
                            notifyUuid = state.notifyUuid,
                            writeUuid = state.writeUuid,
                            connectionState = state.connectionState.name,
                            bytes = event.bytes.copyOf(),
                            frames = parser.lastOfferFrames,
                            mergedReading = state.latestReading,
                        )
                    )
                } catch (error: Throwable) {
                    AppLog.e("failed to persist inbound notification", error, TAG)
                }
            }
            is BleEvent.WriteCompleted -> {
                val device = state.selectedDevice ?: return
                try {
                    historyStore.recordWrite(
                        KmfWriteObservation(
                            timestampMs = nowMs,
                            deviceAddress = device.address,
                            deviceName = device.name,
                            serviceUuid = state.serviceUuid,
                            notifyUuid = state.notifyUuid,
                            writeUuid = state.writeUuid,
                            bytes = event.bytes.copyOf(),
                            writeSuccess = event.success,
                            error = if (event.success) null else "BLE write failed",
                        )
                    )
                } catch (error: Throwable) {
                    AppLog.e("failed to persist outbound write", error, TAG)
                }
            }
            else -> Unit
        }
    }

    private companion object {
        const val TAG = "KMF-BLE"
        const val PREFERRED_MTU = 100
        const val INITIAL_BOOTSTRAP_DELAY_MS = 1_000L
        const val POST_MTU_DELAY_MS = 500L
        const val BOOTSTRAP_RETRY_DELAY_MS = 2_000L
        const val MAX_BOOTSTRAP_ATTEMPTS = 15
        const val TOTALS_POLL_INTERVAL_MS = 30_000L
        val KMF_BOOTSTRAP_REQUEST = byteArrayOf(0x3A, 0x2A)
        val KMF_TOTALS_POLL = byteArrayOf(0x3A, 0x43, 0x0A)
    }
}

private fun DeviceSnapshot.toScannedDevice(): ScannedDevice =
    ScannedDevice(
        name = name,
        address = address,
        rssi = 0,
        serviceUuids = emptyList(),
    )

private fun ByteArray.toHexString(): String =
    joinToString(separator = " ") { "%02X".format(it.toInt() and 0xFF) }
