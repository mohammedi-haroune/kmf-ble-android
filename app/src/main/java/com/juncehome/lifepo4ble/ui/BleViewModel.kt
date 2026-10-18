package com.juncehome.lifepo4ble.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juncehome.lifepo4ble.AppLog
import com.juncehome.lifepo4ble.ble.BleRepositoryContract
import com.juncehome.lifepo4ble.ble.GattProfile
import com.juncehome.lifepo4ble.ble.ScannedDevice
import com.juncehome.lifepo4ble.collection.CollectionPhase
import com.juncehome.lifepo4ble.collection.CollectionServiceCommander
import com.juncehome.lifepo4ble.collection.CollectionState
import com.juncehome.lifepo4ble.collection.CollectionVisibility
import com.juncehome.lifepo4ble.collection.KmfBootstrapSequence
import com.juncehome.lifepo4ble.collection.CompanionAssociationGateway
import com.juncehome.lifepo4ble.collection.AutonomousCollectionDisabler
import com.juncehome.lifepo4ble.collection.AutonomousCollectionStopper
import com.juncehome.lifepo4ble.data.DeviceSnapshot
import com.juncehome.lifepo4ble.data.DeviceSnapshotStore
import com.juncehome.lifepo4ble.data.KmfBatterySampleEntity
import com.juncehome.lifepo4ble.data.KmfHistoryStore
import com.juncehome.lifepo4ble.protocol.KmfFrame
import com.juncehome.lifepo4ble.protocol.KmfLineParser
import com.juncehome.lifepo4ble.protocol.KmfReading
import com.juncehome.lifepo4ble.ble.BleEvent
import com.juncehome.lifepo4ble.ui.firstrun.FirstRunNotice
import com.juncehome.lifepo4ble.ui.firstrun.FirstRunStep
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
    private val companionAssociationGateway: CompanionAssociationGateway? = null,
    private val collectionServiceCommander: CollectionServiceCommander? = null,
    private val autonomousCollectionDisabler: AutonomousCollectionStopper? = companionAssociationGateway?.let {
        AutonomousCollectionDisabler(deviceStore, it)
    },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val scope: CoroutineScope? = null,
    private val collectionStatus: StateFlow<CollectionState?>? = null,
) : ViewModel() {
    private val parser = KmfLineParser()
    private var recentSamplesJob: kotlinx.coroutines.Job? = null
    private var recentSamplesDeviceAddress: String? = null
    private var pendingAssociationSnapshot: DeviceSnapshot? = null
    private var associationCompletionStarted = false
    private var autonomousCollectionConfigured = false
    private var collectionConfigurationState = CollectionConfigurationState.UNKNOWN
    private var awaitingAutonomousPermissions = false
    private var currentAppVisibility = CollectionVisibility.BACKGROUND
    private var testBootstrapJob: kotlinx.coroutines.Job? = null
    private var testSeenA = false
    private var testSeenB = false
    private val workScope: CoroutineScope
        get() = scope ?: viewModelScope

    private val mutableUiState = MutableStateFlow(BleUiState())
    val uiState: StateFlow<BleUiState> = mutableUiState
    private val mutableRecentBatterySamples = MutableStateFlow<List<KmfBatterySampleEntity>>(emptyList())
    val recentBatterySamples: StateFlow<List<KmfBatterySampleEntity>> = mutableRecentBatterySamples
    private val mutableAssociationIntentSenders = MutableSharedFlow<IntentSender>(extraBufferCapacity = 1)
    val associationIntentSenders = mutableAssociationIntentSenders
    private val mutableAutonomousPermissionRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val autonomousPermissionRequests = mutableAutonomousPermissionRequests
    private val mutableFirstRunNotice = MutableStateFlow<FirstRunNotice?>(null)

    /** Short error line for the current first-run step after a denial or cancel. */
    val firstRunNotice: StateFlow<FirstRunNotice?> = mutableFirstRunNotice

    init {
        workScope.launch {
            repository.events.collect { event ->
                val previousState = mutableUiState.value
                val nowMs = clock()
                val nextState = BleStateReducer.reduce(previousState, event, parser, nowMs)
                mutableUiState.value = nextState
                syncRecentBatterySamples(nextState.selectedDevice?.address)
                if (collectionConfigurationState == CollectionConfigurationState.UNCONFIGURED) {
                    // Manual (Device tab test) connections never write battery history; the
                    // collection service is the only writer. Only the discovered GATT profile is
                    // kept on the saved meter so the service can reuse it.
                    if (event is BleEvent.ServicesDiscovered) {
                        saveSnapshot(nextState.selectedDevice, event.profile)
                    }
                }
                if (nextState.testConnectionActive) {
                    onTestConnectionEvent(event, nextState)
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
                            collectionConfigurationLoaded = true,
                        )
                    }
                    collectionServiceCommander?.setVisibility(currentAppVisibility)
                } else {
                    collectionConfigurationState = CollectionConfigurationState.UNCONFIGURED
                    autonomousCollectionConfigured = false
                    mutableUiState.update {
                        it.copy(
                            collectionConfigurationLoaded = true,
                            autonomousCollectionStatus = if (it.autonomousCollectionConfigured) {
                                AutonomousCollectionStatus.NOT_CONFIGURED
                            } else {
                                it.autonomousCollectionStatus
                            },
                            autonomousCollectionDetail = if (it.autonomousCollectionConfigured) {
                                null
                            } else {
                                it.autonomousCollectionDetail
                            },
                            autonomousCollectionConfigured = false,
                        )
                    }
                }
            }
        }
        collectionStatus?.let { status ->
            workScope.launch {
                status.collect { collection ->
                    val previous = mutableUiState.value.collectionState
                    if (previous?.phase != collection?.phase ||
                        previous?.reconnectAttempt != collection?.reconnectAttempt
                    ) {
                        AppLog.d(
                            "UI collection state phase=${collection?.phase} attempt=${collection?.reconnectAttempt} " +
                                "nextReconnectAtMs=${collection?.nextReconnectAtMs} error=${collection?.error}",
                            TAG,
                        )
                    }
                    mutableUiState.update { it.copy(collectionState = collection) }
                }
            }
        }
        workScope.launch {
            deviceStore.snapshot.collect { snapshot ->
                val associated = lookUpCompanionAssociation(snapshot?.address)
                val previous = mutableUiState.value
                if (previous.savedMeterAddress != snapshot?.address || previous.companionAssociated != associated ||
                    !previous.savedMeterLoaded
                ) {
                    AppLog.d(
                        "FirstRun: saved meter present=${snapshot != null} companionAssociated=$associated",
                        TAG,
                    )
                }
                mutableUiState.update { state ->
                    state.copy(
                        canConfigureAutonomousCollection = snapshot != null && state.permissionsGranted,
                        selectedDevice = snapshot?.toScannedDevice() ?: state.selectedDevice,
                        savedMeterAddress = snapshot?.address,
                        savedMeterName = snapshot?.name,
                        savedMeterLoaded = true,
                        companionAssociated = associated,
                    )
                }
                syncRecentBatterySamples(snapshot?.address)
            }
        }
        workScope.launch {
            deviceStore.firstRunCompleted.collect { completed ->
                AppLog.d("FirstRun: persisted firstRunCompleted=$completed", TAG)
                mutableUiState.update { it.copy(firstRunCompleted = completed, firstRunCompletedLoaded = true) }
            }
        }
    }

    fun updateReadiness(
        requiredPermissions: Set<String>,
        grantedPermissions: Set<String>,
        hasBleFeature: Boolean,
        hasBluetoothAdapter: Boolean,
        bluetoothEnabled: Boolean,
        bluetoothPermissions: Set<String> = requiredPermissions,
        notificationPermission: String? = null,
    ) {
        val previousState = mutableUiState.value
        val associated = lookUpCompanionAssociation(previousState.savedMeterAddress)
        val nextState = previousState.copy(
            requiredPermissions = requiredPermissions,
            grantedPermissions = grantedPermissions,
            hasBleFeature = hasBleFeature,
            hasBluetoothAdapter = hasBluetoothAdapter,
            bluetoothEnabled = bluetoothEnabled,
            bluetoothPermissions = bluetoothPermissions,
            notificationPermission = notificationPermission,
            companionAssociated = associated,
            canConfigureAutonomousCollection = previousState.selectedDevice != null &&
                grantedPermissions.containsAll(requiredPermissions),
        )
        mutableUiState.value = nextState
        if (previousState.bluetoothEnabled != nextState.bluetoothEnabled ||
            previousState.bluetoothPermissionsGranted != nextState.bluetoothPermissionsGranted ||
            previousState.notificationPermissionGranted != nextState.notificationPermissionGranted ||
            previousState.companionAssociated != associated
        ) {
            AppLog.d(
                "Readiness: bluetoothEnabled=${nextState.bluetoothEnabled} adapter=$hasBluetoothAdapter " +
                    "btPermissions=${nextState.bluetoothPermissionsGranted} " +
                    "notifications=${nextState.notificationPermissionGranted} companionAssociated=$associated",
                TAG,
            )
        }
        if (awaitingAutonomousPermissions) {
            awaitingAutonomousPermissions = false
            if (nextState.bluetoothPermissionsGranted) {
                requestAutonomousCollection()
            } else {
                updateAutonomousCollectionError("BLE and notification permissions are required before companion setup.")
            }
        }
    }

    fun startScan() {
        if (!manualTransportAllowed()) return
        repository.startScan()
    }

    fun stopScan() {
        if (!manualTransportAllowed()) return
        repository.stopScan()
        mutableUiState.update { state ->
            state.copy(scanning = false)
        }
    }

    fun connect(device: ScannedDevice) {
        if (!manualTransportAllowed()) return
        workScope.launch {
            repository.connect(device, preferred = deviceStore.snapshot.first())
        }
    }

    fun disconnect() {
        if (!manualTransportAllowed()) return
        repository.disconnect()
    }

    fun clearLog() {
        AppLog.d("Device: packet log cleared entries=${mutableUiState.value.packetLog.size}", TAG)
        mutableUiState.update { state ->
            state.copy(packetLog = emptyList())
        }
    }

    fun requestAutonomousCollection() {
        // Notifications are requested separately (first-run step 4); companion setup only needs
        // the Bluetooth permissions.
        if (!mutableUiState.value.bluetoothPermissionsGranted) {
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

    /** Handles either Android's direct association callback or a successful association-sheet result. */
    fun onAssociationCompleted(associationId: Int?) {
        if (associationCompletionStarted || autonomousCollectionConfigured) {
            AppLog.d("CDM setup: duplicate association success ignored", TAG)
            return
        }
        val snapshot = pendingAssociationSnapshot ?: run {
            AppLog.w("CDM setup: association success without pending setup ignored", TAG)
            return
        }
        associationCompletionStarted = true
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
                        companionAssociated = true,
                    )
                }
            } catch (error: Throwable) {
                associationCompletionStarted = false
                pendingAssociationSnapshot = null
                updateAutonomousCollectionError(error.message ?: "Could not start companion observation")
            }
        }
    }

    fun onAssociationCanceled(detail: String = "Companion association was canceled; background collection is not enabled.") {
        if (associationCompletionStarted || autonomousCollectionConfigured) {
            AppLog.d("CDM setup: duplicate association cancellation ignored", TAG)
            return
        }
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
        AppLog.d("Collection: explicit stop requested from the app", TAG)
        workScope.launch {
            autonomousCollectionDisabler?.disable() ?: deviceStore.disableAutonomousCollection()
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

    /** Now tab "Retry now" / "Retry": skip the service's reconnect backoff. */
    fun retryCollectionNow() {
        val commander = collectionServiceCommander
        AppLog.d(
            "Now: retry requested phase=${mutableUiState.value.collectionState?.phase} " +
                "configured=$autonomousCollectionConfigured commander=${commander != null}",
            TAG,
        )
        if (!autonomousCollectionConfigured || commander == null) {
            AppLog.w("Now: retry ignored because background collection is not configured", TAG)
            return
        }
        commander.reconnectNow()
    }

    /** Now tab "Start": restart the configured service, or run companion setup when not configured. */
    fun startCollectionFromNow() {
        AppLog.d("Now: start collection requested configured=$autonomousCollectionConfigured", TAG)
        if (autonomousCollectionConfigured) {
            collectionServiceCommander?.start()
            collectionServiceCommander?.setVisibility(currentAppVisibility)
        } else {
            requestAutonomousCollection()
        }
    }

    /** First run step 2 "Select": saves the meter (address and name) without connecting. */
    fun selectMeterForSetup(device: ScannedDevice) {
        AppLog.d(
            "FirstRun: meter selected address=${device.address} name=${device.name} rssi=${device.rssi}",
            TAG,
        )
        if (manualTransportAllowed()) {
            repository.stopScan()
        }
        mutableUiState.update { it.copy(scanning = false, selectedDevice = device) }
        workScope.launch {
            val existing = deviceStore.snapshot.first()
            val snapshot = if (existing?.address == device.address) {
                existing.copy(name = device.name ?: existing.name)
            } else {
                // GATT UUIDs stay unknown until the collection service connects; the profile
                // selector discovers them, so no app-owned test connection is needed here.
                DeviceSnapshot(
                    address = device.address,
                    name = device.name,
                    serviceUuid = null,
                    notifyUuid = null,
                    writeUuid = null,
                )
            }
            try {
                deviceStore.save(snapshot)
                AppLog.d("FirstRun: meter snapshot saved keptProfile=${existing?.address == device.address}", TAG)
            } catch (error: Throwable) {
                AppLog.e("FirstRun: failed to save selected meter", error, TAG)
                showFirstRunNotice(
                    FirstRunNotice(
                        step = FirstRunStep.FindMeter,
                        message = "Could not save this meter. Try again.",
                    ),
                )
            }
        }
    }

    /** First run step 3 "Choose another meter": forgets the unconfigured selection. */
    fun chooseAnotherMeter() {
        if (autonomousCollectionConfigured) {
            AppLog.w("FirstRun: choose another meter ignored because collection is configured", TAG)
            return
        }
        AppLog.d("FirstRun: choose another meter; clearing saved selection", TAG)
        pendingAssociationSnapshot = null
        mutableFirstRunNotice.value = null
        mutableUiState.update {
            it.copy(
                autonomousCollectionStatus = AutonomousCollectionStatus.NOT_CONFIGURED,
                autonomousCollectionDetail = null,
            )
        }
        workScope.launch {
            try {
                deviceStore.clear()
            } catch (error: Throwable) {
                AppLog.e("FirstRun: failed to clear saved meter", error, TAG)
            }
        }
    }

    /**
     * First run Done "Open dashboard": persists completion and makes sure collection is on. If
     * Android still holds the association but collection was stopped earlier, it is re-enabled.
     */
    fun finishFirstRun() {
        val phase = mutableUiState.value.collectionState?.phase
        AppLog.d("FirstRun: finish requested configured=$autonomousCollectionConfigured phase=$phase", TAG)
        mutableFirstRunNotice.value = null
        // Optimistic so the shell gate does not flash first run while DataStore writes.
        mutableUiState.update { it.copy(firstRunCompleted = true) }
        workScope.launch {
            try {
                deviceStore.setFirstRunCompleted(true)
            } catch (error: Throwable) {
                AppLog.e("FirstRun: failed to persist completion", error, TAG)
            }
            if (autonomousCollectionConfigured) {
                if (phase == null || phase == CollectionPhase.STOPPED) {
                    AppLog.d("FirstRun: starting collection service (phase=$phase)", TAG)
                    collectionServiceCommander?.start()
                    collectionServiceCommander?.setVisibility(currentAppVisibility)
                }
                return@launch
            }
            enableWithExistingAssociation(source = "FirstRun")
        }
    }

    /**
     * Turns collection back on for a meter Android already holds a companion association for (after
     * an explicit stop), without showing the association sheet again.
     */
    private suspend fun enableWithExistingAssociation(source: String) {
        val snapshot = deviceStore.snapshot.first()
        val gateway = companionAssociationGateway
        if (snapshot == null || gateway == null) {
            AppLog.w("$source: cannot enable collection snapshot=${snapshot != null} gateway=${gateway != null}", TAG)
            return
        }
        try {
            gateway.startPresenceObservation(snapshot.address)
            deviceStore.enableAutonomousCollection(snapshot, associationId = null)
            collectionConfigurationState = CollectionConfigurationState.CONFIGURED
            autonomousCollectionConfigured = true
            collectionServiceCommander?.start()
            collectionServiceCommander?.setVisibility(currentAppVisibility)
            AppLog.d("$source: re-enabled collection for existing companion association", TAG)
        } catch (error: Throwable) {
            AppLog.e("$source: re-enabling collection failed", error, TAG)
            mutableUiState.update {
                it.copy(companionAssociated = lookUpCompanionAssociation(snapshot.address))
            }
            updateAutonomousCollectionError(error.message ?: "Could not start companion observation")
        }
    }

    /** Device tab switch on: restart the service, re-use an existing association, or pair first. */
    fun enableBackgroundCollection() {
        val state = mutableUiState.value
        val associated = lookUpCompanionAssociation(state.savedMeterAddress)
        AppLog.d(
            "Device: background collection switch on configured=$autonomousCollectionConfigured " +
                "associated=$associated testActive=${state.testConnectionActive} phase=${state.collectionState?.phase}",
            TAG,
        )
        if (state.testConnectionActive) {
            stopTestConnection(reason = "background collection turned on")
        }
        when {
            autonomousCollectionConfigured -> {
                collectionServiceCommander?.start()
                collectionServiceCommander?.setVisibility(currentAppVisibility)
            }
            associated -> workScope.launch { enableWithExistingAssociation(source = "Device") }
            else -> requestAutonomousCollection()
        }
    }

    /** Device tab switch off: the same durable stop as the notification's Stop action. */
    fun disableBackgroundCollection() {
        AppLog.d(
            "Device: background collection switch off configured=$autonomousCollectionConfigured " +
                "phase=${mutableUiState.value.collectionState?.phase}",
            TAG,
        )
        stopAutonomousCollection()
    }

    /** Device tab "Pair with Android": runs the companion association for the saved meter. */
    fun pairWithAndroid() {
        AppLog.d("Device: pair with Android requested meterSaved=${mutableUiState.value.savedMeterAddress != null}", TAG)
        requestAutonomousCollection()
    }

    /**
     * Device tab "Connect once": a diagnostic connection to the saved meter while collection is
     * off. Runs the KMF startup sequence (MTU 100, `:*` every 2 s until A and B), shows raw
     * frames, saves no history and never reconnects.
     */
    fun startTestConnection() {
        val state = mutableUiState.value
        if (!manualTransportAllowed()) {
            AppLog.w("Device: test connection ignored because background collection owns the link", TAG)
            return
        }
        if (state.testConnectionActive) {
            AppLog.d("Device: test connection already active", TAG)
            return
        }
        workScope.launch {
            val snapshot = deviceStore.snapshot.first()
            if (snapshot == null) {
                AppLog.w("Device: test connection ignored because no meter is saved", TAG)
                mutableUiState.update { it.copy(testConnectionResult = "Choose a meter first.") }
                return@launch
            }
            AppLog.d(
                "Device: test connection starting address=${snapshot.address} knownProfile=${snapshot.serviceUuid != null} " +
                    "(no history, no reconnect)",
                TAG,
            )
            mutableUiState.update {
                it.copy(testConnectionActive = true, testConnectionResult = null, packetLog = emptyList())
            }
            repository.connect(snapshot.toScannedDevice(), preferred = snapshot)
        }
    }

    private fun cancelTestBootstrap() {
        testBootstrapJob?.cancel()
        testBootstrapJob = null
    }

    /** Device tab "Disconnect" for the test connection. */
    fun stopTestConnection() = stopTestConnection(reason = "user")

    private fun stopTestConnection(reason: String) {
        if (!mutableUiState.value.testConnectionActive) {
            AppLog.d("Device: test disconnect ignored because no test is active (reason=$reason)", TAG)
            return
        }
        AppLog.d("Device: test connection stopping reason=$reason", TAG)
        cancelTestBootstrap()
        mutableUiState.update { it.copy(testConnectionActive = false, testConnectionResult = "Disconnected") }
        if (manualTransportAllowed()) {
            repository.disconnect()
        }
    }

    private fun onTestConnectionEvent(event: BleEvent, state: BleUiState) {
        when (event) {
            is BleEvent.ServicesDiscovered -> {
                testBootstrapJob?.cancel()
                testSeenA = false
                testSeenB = false
                AppLog.d("Device: test connection ready; starting KMF bootstrap (MTU, :* until A and B)", TAG)
                testBootstrapJob = workScope.launch {
                    KmfBootstrapSequence.run(repository, source = "Device test") {
                        mutableUiState.value.testConnectionActive && !(testSeenA && testSeenB)
                    }
                }
            }
            is BleEvent.NotificationReceived -> {
                // Tracked from this connection's parsed frames only; the saved-sample flags in the
                // UI state can come from history and must not end the startup loop early.
                parser.lastOfferFrames.forEach { frame ->
                    when (frame) {
                        is KmfFrame.A -> testSeenA = true
                        is KmfFrame.B -> testSeenB = true
                        is KmfFrame.C -> Unit
                    }
                }
                if (testSeenA && testSeenB && testBootstrapJob?.isActive == true) {
                    AppLog.d("Device: test connection has A and B frames; stopping :* retries", TAG)
                    testBootstrapJob?.cancel()
                    testBootstrapJob = null
                }
            }
            is BleEvent.Disconnected -> {
                cancelTestBootstrap()
                val detail = event.reason?.takeIf { it.isNotBlank() }
                AppLog.d("Device: test connection ended by disconnect reason=$detail (no reconnect)", TAG)
                mutableUiState.update {
                    it.copy(
                        testConnectionActive = false,
                        testConnectionResult = detail?.let { reason -> "Disconnected \u00B7 $reason" } ?: "Disconnected",
                    )
                }
            }
            is BleEvent.Error -> {
                val linkUp = state.connectionState == ConnectionState.CONNECTED ||
                    state.connectionState == ConnectionState.READY
                AppLog.w("Device: test connection error '${event.message}' linkUp=$linkUp", TAG)
                if (!linkUp) {
                    cancelTestBootstrap()
                    mutableUiState.update {
                        it.copy(testConnectionActive = false, testConnectionResult = event.message)
                    }
                    repository.disconnect()
                }
            }
            else -> Unit
        }
    }

    /** Device tab "Forget meter" (after confirmation). The shell then shows first run. */
    fun forgetMeter() = resetSavedMeter(reason = "forget")

    /** Device tab "Change meter": same reset, then first run opens at Find meter. */
    fun changeMeter() = resetSavedMeter(reason = "change")

    /**
     * Stops collection durably, removes the Android companion association and clears the saved
     * meter (which also clears the first-run completion flag). Saved battery history is kept.
     */
    private fun resetSavedMeter(reason: String) {
        val state = mutableUiState.value
        AppLog.d(
            "Device: $reason meter requested configured=$autonomousCollectionConfigured " +
                "associated=${state.companionAssociated} testActive=${state.testConnectionActive}",
            TAG,
        )
        if (state.testConnectionActive) {
            stopTestConnection(reason = "$reason meter")
        }
        pendingAssociationSnapshot = null
        associationCompletionStarted = false
        mutableFirstRunNotice.value = null
        workScope.launch {
            val address = deviceStore.snapshot.first()?.address
            try {
                autonomousCollectionDisabler?.disable() ?: deviceStore.disableAutonomousCollection()
            } catch (error: Throwable) {
                AppLog.e("Device: disabling collection during $reason meter failed", error, TAG)
            }
            collectionConfigurationState = CollectionConfigurationState.UNCONFIGURED
            autonomousCollectionConfigured = false
            collectionServiceCommander?.stop()
            if (address != null) {
                try {
                    companionAssociationGateway?.disassociate(address)
                    AppLog.d("Device: companion association removed during $reason meter", TAG)
                } catch (error: Throwable) {
                    AppLog.e("Device: removing companion association failed during $reason meter", error, TAG)
                }
            }
            try {
                deviceStore.clear()
                AppLog.d("Device: saved meter cleared ($reason); first run will open", TAG)
            } catch (error: Throwable) {
                AppLog.e("Device: clearing saved meter failed during $reason meter", error, TAG)
            }
            mutableUiState.update {
                it.copy(
                    autonomousCollectionStatus = AutonomousCollectionStatus.NOT_CONFIGURED,
                    autonomousCollectionDetail = null,
                    autonomousCollectionConfigured = false,
                    companionAssociated = false,
                    selectedDevice = null,
                    savedMeterAddress = null,
                    savedMeterName = null,
                    hasAFrame = false,
                    hasBFrame = false,
                    latestReading = KmfReading(),
                    lastReadingTimestampMs = null,
                    packetLog = emptyList(),
                    firstRunCompleted = false,
                    testConnectionResult = null,
                )
            }
        }
    }

    fun showFirstRunNotice(notice: FirstRunNotice) {
        AppLog.d(
            "FirstRun: notice step=${notice.step} settings=${notice.offerAppSettings} message='${notice.message}'",
            TAG,
        )
        mutableFirstRunNotice.value = notice
    }

    fun clearFirstRunNotice() {
        mutableFirstRunNotice.value = null
    }

    private fun lookUpCompanionAssociation(address: String?): Boolean {
        if (address == null) return false
        return try {
            companionAssociationGateway?.isAssociated(address) == true
        } catch (error: Throwable) {
            AppLog.e("FirstRun: companion association lookup failed", error, TAG)
            false
        }
    }

    private fun manualTransportAllowed(): Boolean =
        collectionConfigurationState == CollectionConfigurationState.UNCONFIGURED

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
        associationCompletionStarted = false
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
            onAssociationCreated = ::onAssociationCompleted,
            onFailure = { error ->
                if (associationCompletionStarted || autonomousCollectionConfigured) {
                    AppLog.d("CDM setup: duplicate association failure ignored", TAG)
                    return@requestAssociation
                }
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
