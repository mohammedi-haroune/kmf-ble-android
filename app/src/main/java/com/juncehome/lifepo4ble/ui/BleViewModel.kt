package com.juncehome.lifepo4ble.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juncehome.lifepo4ble.AppLog
import com.juncehome.lifepo4ble.ble.BleRepositoryContract
import com.juncehome.lifepo4ble.ble.GattProfile
import com.juncehome.lifepo4ble.ble.ScannedDevice
import com.juncehome.lifepo4ble.collection.CollectionServiceCommander
import com.juncehome.lifepo4ble.collection.CollectionVisibility
import com.juncehome.lifepo4ble.collection.CompanionAssociationGateway
import com.juncehome.lifepo4ble.data.DeviceSnapshot
import com.juncehome.lifepo4ble.data.DeviceSnapshotStore
import com.juncehome.lifepo4ble.data.KmfBatterySampleEntity
import com.juncehome.lifepo4ble.data.KmfBatteryHistoryExportStore
import com.juncehome.lifepo4ble.data.KmfHistoryStore
import com.juncehome.lifepo4ble.data.KmfNotificationObservation
import com.juncehome.lifepo4ble.data.KmfWriteObservation
import com.juncehome.lifepo4ble.protocol.KmfLineParser
import com.juncehome.lifepo4ble.protocol.KmfReading
import com.juncehome.lifepo4ble.ble.BleEvent
import android.content.IntentSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BleViewModel(
    private val repository: BleRepositoryContract,
    private val deviceStore: DeviceSnapshotStore,
    private val historyStore: KmfHistoryStore,
    private val batteryHistoryExportStore: KmfBatteryHistoryExportStore? = null,
    private val companionAssociationGateway: CompanionAssociationGateway? = null,
    private val collectionServiceCommander: CollectionServiceCommander? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val scope: CoroutineScope? = null,
) : ViewModel() {
    private val parser = KmfLineParser()
    private var recentSamplesJob: kotlinx.coroutines.Job? = null
    private var recentSamplesDeviceAddress: String? = null
    private var pendingAssociationSnapshot: DeviceSnapshot? = null
    private var autonomousCollectionConfigured = false
    private var collectionConfigurationState = CollectionConfigurationState.UNKNOWN
    private var awaitingAutonomousPermissions = false
    private var currentAppVisibility = CollectionVisibility.BACKGROUND
    private val workScope: CoroutineScope
        get() = scope ?: viewModelScope

    private val mutableUiState = MutableStateFlow(BleUiState())
    val uiState: StateFlow<BleUiState> = mutableUiState
    private val mutableRecentBatterySamples = MutableStateFlow<List<KmfBatterySampleEntity>>(emptyList())
    val recentBatterySamples: StateFlow<List<KmfBatterySampleEntity>> = mutableRecentBatterySamples
    private val mutableBatteryHistoryStatus = MutableStateFlow<String?>(null)
    val batteryHistoryStatus: StateFlow<String?> = mutableBatteryHistoryStatus
    private val mutableAssociationIntentSenders = MutableSharedFlow<IntentSender>(extraBufferCapacity = 1)
    val associationIntentSenders = mutableAssociationIntentSenders
    private val mutableAutonomousPermissionRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val autonomousPermissionRequests = mutableAutonomousPermissionRequests

    init {
        workScope.launch {
            repository.events.collect { event ->
                val previousState = mutableUiState.value
                val nowMs = clock()
                val nextState = BleStateReducer.reduce(previousState, event, parser, nowMs)
                mutableUiState.value = nextState
                syncRecentBatterySamples(nextState.selectedDevice?.address)
                if (collectionConfigurationState == CollectionConfigurationState.UNCONFIGURED) {
                    persistHistory(event, nextState, nowMs)
                    if (event is BleEvent.ServicesDiscovered) {
                        saveSnapshot(nextState.selectedDevice, event.profile)
                    }
                }
            }
        }
        workScope.launch {
            deviceStore.collectionConfiguration.collect { configuration ->
                if (configuration?.autonomousEnabled == true) {
                    collectionConfigurationState = CollectionConfigurationState.CONFIGURED
                    autonomousCollectionConfigured = true
                    mutableUiState.update {
                        it.copy(
                            autonomousCollectionStatus = AutonomousCollectionStatus.CONFIGURED,
                            autonomousCollectionDetail = "Configured for ${configuration.snapshot.address}; the service collects when the meter is present.",
                            autonomousCollectionConfigured = true,
                        )
                    }
                    collectionServiceCommander?.setVisibility(currentAppVisibility)
                } else {
                    collectionConfigurationState = CollectionConfigurationState.UNCONFIGURED
                    if (autonomousCollectionConfigured) {
                        autonomousCollectionConfigured = false
                        mutableUiState.update {
                            it.copy(
                                autonomousCollectionStatus = AutonomousCollectionStatus.NOT_CONFIGURED,
                                autonomousCollectionDetail = null,
                                autonomousCollectionConfigured = false,
                            )
                        }
                    }
                }
            }
        }
        workScope.launch {
            deviceStore.snapshot.collect { snapshot ->
                mutableUiState.update { state ->
                    state.copy(
                        canConfigureAutonomousCollection = snapshot != null && state.permissionsGranted,
                        selectedDevice = snapshot?.toScannedDevice() ?: state.selectedDevice,
                    )
                }
                syncRecentBatterySamples(snapshot?.address)
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
            canConfigureAutonomousCollection = previousState.selectedDevice != null &&
                grantedPermissions.containsAll(requiredPermissions),
        )
        mutableUiState.value = nextState
        if (awaitingAutonomousPermissions) {
            awaitingAutonomousPermissions = false
            if (nextState.permissionsGranted) {
                requestAutonomousCollection()
            } else {
                updateAutonomousCollectionError("BLE and notification permissions are required before companion setup.")
            }
        }
    }

    fun startScan() {
        if (autonomousCollectionConfigured) return
        repository.startScan()
    }

    fun stopScan() {
        if (autonomousCollectionConfigured) return
        repository.stopScan()
        mutableUiState.update { state ->
            state.copy(scanning = false)
        }
    }

    fun connect(device: ScannedDevice) {
        if (autonomousCollectionConfigured) return
        workScope.launch {
            repository.connect(device, preferred = deviceStore.snapshot.first())
        }
    }

    fun disconnect() {
        if (autonomousCollectionConfigured) return
        repository.disconnect()
    }

    fun clearLog() {
        mutableUiState.update { state ->
            state.copy(packetLog = emptyList())
        }
    }

    fun exportBatteryHistory() {
        val exportStore = batteryHistoryExportStore ?: return
        workScope.launch {
            runCatching { exportStore.export() }
                .onSuccess { export ->
                    mutableBatteryHistoryStatus.value = "Exported ${export.rowCount} samples to files/exports/${export.file.name}"
                }
                .onFailure { error ->
                    AppLog.e("failed to export battery history", error, TAG)
                    mutableBatteryHistoryStatus.value = "Export failed: ${error.message.orEmpty()}"
                }
        }
    }

    fun clearBatteryHistory() {
        val exportStore = batteryHistoryExportStore ?: return
        workScope.launch {
            runCatching { exportStore.clear() }
                .onSuccess {
                    mutableRecentBatterySamples.value = emptyList()
                    mutableBatteryHistoryStatus.value = "Saved battery history cleared"
                }
                .onFailure { error ->
                    AppLog.e("failed to clear battery history", error, TAG)
                    mutableBatteryHistoryStatus.value = "Clear failed: ${error.message.orEmpty()}"
                }
        }
    }

    fun requestAutonomousCollection() {
        if (!mutableUiState.value.permissionsGranted) {
            awaitingAutonomousPermissions = true
            mutableUiState.update {
                it.copy(
                    autonomousCollectionStatus = AutonomousCollectionStatus.SETUP_IN_PROGRESS,
                    autonomousCollectionDetail = "Grant BLE and notification permissions before Android companion setup.",
                )
            }
            mutableAutonomousPermissionRequests.tryEmit(Unit)
            return
        }
        val gateway = companionAssociationGateway ?: run {
            updateAutonomousCollectionError("Companion device setup is unavailable")
            return
        }
        if (!gateway.isSupported()) {
            mutableUiState.update {
                it.copy(
                    autonomousCollectionStatus = AutonomousCollectionStatus.UNSUPPORTED,
                    autonomousCollectionDetail = "This phone cannot keep a companion meter available in the background.",
                )
            }
            return
        }
        workScope.launch {
            val snapshot = deviceStore.snapshot.first()
            if (snapshot == null) {
                updateAutonomousCollectionError("Connect to the selected meter once so its BLE profile can be saved.")
                return@launch
            }
            beginCompanionAssociation(snapshot, gateway)
        }
    }

    /** Call only after MainActivity receives RESULT_OK from Android's association sheet. */
    fun onAssociationCompleted(associationId: Int?) {
        val snapshot = pendingAssociationSnapshot ?: run {
            updateAutonomousCollectionError("Association result did not match a pending meter setup.")
            return
        }
        workScope.launch {
            try {
                requireNotNull(companionAssociationGateway).startPresenceObservation(snapshot.address)
                deviceStore.enableAutonomousCollection(snapshot, associationId)
                collectionConfigurationState = CollectionConfigurationState.CONFIGURED
                autonomousCollectionConfigured = true
                collectionServiceCommander?.start()
                collectionServiceCommander?.setVisibility(currentAppVisibility)
                pendingAssociationSnapshot = null
                mutableUiState.update {
                    it.copy(
                        autonomousCollectionStatus = AutonomousCollectionStatus.CONFIGURED,
                        autonomousCollectionDetail = "Configured for ${snapshot.address}; the service collects when the meter is present.",
                        autonomousCollectionConfigured = true,
                    )
                }
            } catch (error: Throwable) {
                pendingAssociationSnapshot = null
                updateAutonomousCollectionError(error.message ?: "Could not start companion observation")
            }
        }
    }

    fun onAssociationCanceled(detail: String = "Companion association was canceled; background collection is not enabled.") {
        AppLog.w("CDM association terminal result: $detail", TAG)
        pendingAssociationSnapshot = null
        mutableUiState.update {
            it.copy(
                autonomousCollectionStatus = AutonomousCollectionStatus.NOT_CONFIGURED,
                autonomousCollectionDetail = detail,
            )
        }
    }

    fun stopAutonomousCollection() {
        workScope.launch {
            val configuration = deviceStore.collectionConfiguration.first()
            try {
                configuration?.let { companionAssociationGateway?.stopPresenceObservation(it.snapshot.address) }
            } catch (error: Throwable) {
                AppLog.e("failed to stop companion presence observation", error, TAG)
            }
            deviceStore.disableAutonomousCollection()
            collectionConfigurationState = CollectionConfigurationState.UNCONFIGURED
            autonomousCollectionConfigured = false
            mutableUiState.update {
                it.copy(
                    autonomousCollectionStatus = AutonomousCollectionStatus.NOT_CONFIGURED,
                    autonomousCollectionDetail = null,
                    autonomousCollectionConfigured = false,
                )
            }
            collectionServiceCommander?.stop()
        }
    }

    fun onAppVisibilityChanged(visibility: CollectionVisibility) {
        currentAppVisibility = visibility
        if (autonomousCollectionConfigured) {
            collectionServiceCommander?.setVisibility(visibility)
        }
    }

    private fun updateAutonomousCollectionError(detail: String) {
        mutableUiState.update {
            it.copy(
                autonomousCollectionStatus = AutonomousCollectionStatus.ERROR,
                autonomousCollectionDetail = detail,
            )
        }
    }

    private fun syncRecentBatterySamples(deviceAddress: String?) {
        if (recentSamplesDeviceAddress == deviceAddress) {
            return
        }
        recentSamplesDeviceAddress = deviceAddress
        recentSamplesJob?.cancel()
        recentSamplesJob = null
        mutableRecentBatterySamples.value = emptyList()

        if (deviceAddress == null) {
            return
        }

        recentSamplesJob = workScope.launch {
            historyStore.observeRecentBatterySamples(deviceAddress, LIVE_SAMPLE_LIMIT).collectLatest { samples ->
                mutableRecentBatterySamples.value = samples
                samples.firstOrNull()?.let { sample ->
                    mutableUiState.update { state ->
                        state.copy(
                            hasAFrame = sample.hasAFrame,
                            hasBFrame = sample.hasBFrame,
                            latestReading = sample.toReading(),
                            lastReadingTimestampMs = sample.timestampMs,
                        )
                    }
                }
            }
        }
    }

    private fun beginCompanionAssociation(
        snapshot: DeviceSnapshot,
        gateway: CompanionAssociationGateway,
    ) {
        AppLog.d("CDM setup: requesting association for saved meter profile", TAG)
        pendingAssociationSnapshot = snapshot
        mutableUiState.update {
            it.copy(
                autonomousCollectionStatus = AutonomousCollectionStatus.SETUP_IN_PROGRESS,
                autonomousCollectionDetail = "Approve the Android companion association for ${snapshot.address}.",
            )
        }
        gateway.requestAssociation(
            address = snapshot.address,
            onPending = { sender ->
                val delivered = mutableAssociationIntentSenders.tryEmit(sender)
                AppLog.d("CDM setup: pending IntentSender emitted=$delivered", TAG)
                if (!delivered) {
                    pendingAssociationSnapshot = null
                    updateAutonomousCollectionError("Android companion setup could not be displayed because the activity was not ready.")
                }
            },
            onFailure = { error ->
                pendingAssociationSnapshot = null
                AppLog.e("CDM setup: gateway terminal failure", error, TAG)
                updateAutonomousCollectionError(error.message ?: "Companion association failed")
            },
        )
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
        const val LIVE_SAMPLE_LIMIT = 2_048
    }

    private enum class CollectionConfigurationState {
        UNKNOWN,
        CONFIGURED,
        UNCONFIGURED,
    }
}

object CompanionAssociationResultDiagnostics {
    fun describe(resultCode: Int, hasData: Boolean): String =
        "Android companion setup ended without confirmation (resultCode=$resultCode, resultData=$hasData); background collection is not enabled."
}

private fun DeviceSnapshot.toScannedDevice(): ScannedDevice = ScannedDevice(
    name = name,
    address = address,
    rssi = 0,
    serviceUuids = emptyList(),
)

private fun KmfBatterySampleEntity.toReading(): KmfReading = KmfReading(
    voltageV = voltageV,
    currentA = currentA,
    powerW = powerW,
    charging = charging,
    minutesRemaining = minutesRemaining,
    remainingAh = remainingAh,
    capacityAh = capacityAh,
    socPercent = socPercent,
    chargeKwh = chargeKwh,
    dischargeKwh = dischargeKwh,
)
