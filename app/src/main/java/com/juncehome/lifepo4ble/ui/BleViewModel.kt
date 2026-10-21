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
import com.juncehome.lifepo4ble.remote.RemoteAddress
import com.juncehome.lifepo4ble.remote.RemoteMeterLinkContract
import com.juncehome.lifepo4ble.remote.SharingStatus
import com.juncehome.lifepo4ble.remote.TimedBleEvent
import com.juncehome.lifepo4ble.ui.now.NowScreenReducer
import com.juncehome.lifepo4ble.ui.firstrun.FirstRunNotice
import com.juncehome.lifepo4ble.ui.firstrun.FirstRunStep
import android.content.IntentSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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
    private val remoteLink: RemoteMeterLinkContract? = null,
    private val sharingStatus: StateFlow<SharingStatus?>? = null,
) : ViewModel() {
    private val parser = KmfLineParser()
    // Remote lines get their own parser so a half line left by the Bluetooth path can never prefix them.
    private val remoteParser = KmfLineParser()
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

    /** The home phone this phone reads the meter from; null while it uses Bluetooth. Main thread only. */
    private var remoteAddress: RemoteAddress? = null
    private var remoteSawC = false
    private var savedSnapshot: DeviceSnapshot? = null
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
                if (remoteAddress != null) {
                    AppLog.d("Remote: Bluetooth event ignored in remote mode event=${event.javaClass.simpleName}", TAG)
                    return@collect
                }
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
        remoteLink?.let { link ->
            // The link's events have no replay: subscribe before anything can start it.
            workScope.launch(start = CoroutineStart.UNDISPATCHED) {
                link.events.collect { timed -> onRemoteEvent(timed) }
            }
            workScope.launch {
                link.state.collect { linkState ->
                    if (remoteAddress == null) return@collect
                    if (linkState == null) {
                        // Stopped (background, disconnect or a dead loop): keep the last remote values on screen.
                        AppLog.d("Remote: link state null; keeping the last remote values", TAG)
                        return@collect
                    }
                    val previous = mutableUiState.value.collectionState
                    if (previous?.phase != linkState.phase || previous.reconnectAttempt != linkState.reconnectAttempt) {
                        AppLog.d(
                            "Remote: link phase=${linkState.phase} attempt=${linkState.reconnectAttempt} " +
                                "unreachable=${linkState.remoteUnreachable} nextReconnectAtMs=${linkState.nextReconnectAtMs} " +
                                "error=${linkState.error}",
                            TAG,
                        )
                    }
                    mutableUiState.update { it.copy(collectionState = linkState) }
                }
            }
        }
        // Collected together with the remote address so "configuration loaded" and the mode land in one
        // state update: a remote-mode phone never flashes NoMeter / CollectionOff at launch.
        workScope.launch {
            combine(deviceStore.collectionConfiguration, deviceStore.remoteAddress) { configuration, storedRemote ->
                configuration to storedRemote
            }.collect { (configuration, storedRemote) ->
                val remote = resolveStoredRemoteAddress(storedRemote)
                val modeSwitch = switchRemoteMode(remote)
                if (configuration?.autonomousEnabled == true) {
                    collectionConfigurationState = CollectionConfigurationState.CONFIGURED
                    autonomousCollectionConfigured = true
                    mutableUiState.update {
                        it.copy(
                            autonomousCollectionStatus = AutonomousCollectionStatus.CONFIGURED,
                            autonomousCollectionDetail = "Configured for ${configuration.snapshot.address}; the service collects when the meter is present.",
                            autonomousCollectionConfigured = true,
                            collectionConfigurationLoaded = true,
                        ).let(modeSwitch)
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
                        ).let(modeSwitch)
                    }
                }
                afterRemoteModeSwitch(remote)
            }
        }
        collectionStatus?.let { status ->
            workScope.launch {
                status.collect { collection ->
                    if (remoteAddress != null) {
                        // In remote mode the link owns the phase; the relay belongs to the Bluetooth service.
                        return@collect
                    }
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
                savedSnapshot = snapshot
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
                val remoteActive = remoteAddress != null
                mutableUiState.update { state ->
                    state.copy(
                        canConfigureAutonomousCollection = snapshot != null && state.permissionsGranted,
                        // In remote mode the shown meter is the remote one, whatever meter is saved.
                        selectedDevice = if (remoteActive) {
                            state.selectedDevice
                        } else {
                            snapshot?.toScannedDevice() ?: state.selectedDevice
                        },
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
        workScope.launch {
            deviceStore.shareOnNetwork.collect { enabled ->
                AppLog.d("Sharing: persisted shareOnNetwork=$enabled", TAG)
                mutableUiState.update { it.copy(shareOnNetwork = enabled) }
            }
        }
        sharingStatus?.let { status ->
            workScope.launch {
                status.collect { sharing ->
                    AppLog.d(
                        "Sharing: status running=${sharing?.running} port=${sharing?.port} " +
                            "viewers=${sharing?.viewers} addresses=${sharing?.addresses?.size} error=${sharing?.error}",
                        TAG,
                    )
                    mutableUiState.update { it.copy(sharingStatus = sharing) }
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
        workScope.launch { disableAutonomousCollectionNow() }
    }

    private suspend fun disableAutonomousCollectionNow() {
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

    /** Now tab "Retry now" / "Retry": skip the service's reconnect backoff. */
    fun retryCollectionNow() {
        val link = remoteLink
        if (remoteAddress != null && link != null) {
            AppLog.d("Remote: retry requested phase=${mutableUiState.value.collectionState?.phase}", TAG)
            link.reconnectNow()
            return
        }
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

    /**
     * Now tab "Start": same decision as the Device tab switch, so a meter Android already holds an
     * association for is re-enabled without showing the association sheet again.
     */
    fun startCollectionFromNow() {
        turnOnCollection(source = "Now")
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
        turnOnCollection(source = "Device")
    }

    private fun turnOnCollection(source: String) {
        if (remoteAddress != null) {
            AppLog.w("$source: turn on collection ignored in remote mode; Bluetooth collection is off", TAG)
            return
        }
        val state = mutableUiState.value
        val associated = lookUpCompanionAssociation(state.savedMeterAddress)
        AppLog.d(
            "$source: turn on collection configured=$autonomousCollectionConfigured " +
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
            associated -> workScope.launch { enableWithExistingAssociation(source = source) }
            else -> {
                AppLog.d("$source: no existing association; requesting companion setup", TAG)
                requestAutonomousCollection()
            }
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
        if (remoteAddress != null) {
            AppLog.w("Device: test connection ignored in remote mode", TAG)
            return
        }
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
        collectionConfigurationState == CollectionConfigurationState.UNCONFIGURED && remoteAddress == null

    fun onAppVisibilityChanged(visibility: CollectionVisibility) {
        currentAppVisibility = visibility
        if (autonomousCollectionConfigured) {
            collectionServiceCommander?.setVisibility(visibility)
        }
        val address = remoteAddress
        val link = remoteLink
        if (address != null && link != null) {
            // The remote link only runs while the app is visible; the last values stay on screen.
            if (visibility == CollectionVisibility.FOREGROUND) {
                startRemoteLink(address, link)
            } else {
                AppLog.d("Remote: app in background; stopping the link host=${address.display}", TAG)
                link.stop()
            }
        }
    }

    /** Device tab "Connect through another phone": shows the meter from a home phone's live feed. */
    fun connectRemote(addressText: String) {
        val address = RemoteAddress.parse(addressText)
        if (address == null) {
            AppLog.w("Remote: address rejected text='${addressText.trim().take(MAX_LOGGED_ADDRESS_CHARS)}'", TAG)
            mutableUiState.update { it.copy(remoteAddressError = REMOTE_ADDRESS_ERROR) }
            return
        }
        if (remoteLink == null) {
            AppLog.w("Remote: connect ignored because no remote link is available", TAG)
            return
        }
        AppLog.d(
            "Remote: connect requested host=${address.display} configured=$autonomousCollectionConfigured " +
                "testActive=${mutableUiState.value.testConnectionActive}",
            TAG,
        )
        mutableUiState.update { it.copy(remoteAddressError = null) }
        if (mutableUiState.value.testConnectionActive) {
            stopTestConnection(reason = "remote connect")
        }
        workScope.launch {
            try {
                if (autonomousCollectionConfigured) {
                    AppLog.d("Remote: stopping Bluetooth collection before switching to remote mode", TAG)
                    disableAutonomousCollectionNow()
                }
                deviceStore.setRemoteAddress(address.display)
            } catch (error: Throwable) {
                AppLog.e("Remote: connect failed to save host=${address.display}", error, TAG)
            }
        }
    }

    /** Device tab "Disconnect" in remote mode: back to Bluetooth, collection stays off until switched on. */
    fun disconnectRemote() {
        AppLog.d("Remote: disconnect requested host=${remoteAddress?.display}", TAG)
        remoteLink?.stop()
        workScope.launch {
            try {
                deviceStore.setRemoteAddress(null)
            } catch (error: Throwable) {
                AppLog.e("Remote: disconnect failed to clear the saved address", error, TAG)
            }
        }
    }

    /** Device tab "Share on network" switch (home phone side); the service follows the stored value. */
    fun setShareOnNetwork(enabled: Boolean) {
        AppLog.d("Sharing: share on network set to $enabled", TAG)
        workScope.launch {
            try {
                deviceStore.setShareOnNetwork(enabled)
            } catch (error: Throwable) {
                AppLog.e("Sharing: failed to persist shareOnNetwork=$enabled", error, TAG)
            }
        }
    }

    /** A stored address that does not parse is logged and treated as Bluetooth mode. */
    private fun resolveStoredRemoteAddress(stored: String?): RemoteAddress? {
        if (stored == null) return null
        val parsed = RemoteAddress.parse(stored)
        if (parsed == null) {
            AppLog.w("Remote: stored address '${stored.take(MAX_LOGGED_ADDRESS_CHARS)}' does not parse; using Bluetooth mode", TAG)
            return null
        }
        if (remoteLink == null) {
            AppLog.w("Remote: stored address host=${parsed.display} ignored because no remote link is available", TAG)
            return null
        }
        return parsed
    }

    /**
     * Leaves the previous mode and enters the one for [remote] (null = Bluetooth), returning the state change
     * that belongs to it so the caller applies it in the same update as the configuration. Nothing to do
     * (identity) while the mode did not change.
     */
    private fun switchRemoteMode(remote: RemoteAddress?): (BleUiState) -> BleUiState {
        if (remote == remoteAddress) return { it }
        val previous = remoteAddress
        remoteAddress = remote
        remoteSawC = false
        recentSamplesJob?.cancel()
        recentSamplesJob = null
        recentSamplesDeviceAddress = null
        mutableRecentBatterySamples.value = emptyList()
        if (remote == null) {
            AppLog.d("Remote: leaving remote mode host=${previous?.display}; using Bluetooth mode", TAG)
            remoteLink?.stop()
            val saved = savedSnapshot?.toScannedDevice()
            val relay = collectionStatus?.value
            return { state ->
                state.withEmptyMeterValues().copy(
                    remoteAddress = null,
                    selectedDevice = saved,
                    collectionState = relay,
                )
            }
        }
        AppLog.d("Remote: entering remote mode host=${remote.display} previous=${previous?.display}", TAG)
        val linkState = remoteLink?.state?.value
        return { state ->
            state.withEmptyMeterValues().copy(
                remoteAddress = remote.display,
                remoteAddressError = null,
                selectedDevice = null,
                collectionState = linkState,
            )
        }
    }

    /** Runs after the state update of a mode switch: starts the link or restores the saved meter's history. */
    private fun afterRemoteModeSwitch(remote: RemoteAddress?) {
        val link = remoteLink
        if (remote == null) {
            syncRecentBatterySamples(savedSnapshot?.address)
        } else if (link != null && currentAppVisibility == CollectionVisibility.FOREGROUND) {
            startRemoteLink(remote, link)
        }
    }

    private fun startRemoteLink(address: RemoteAddress, link: RemoteMeterLinkContract) {
        AppLog.d("Remote: starting link host=${address.display}", TAG)
        link.start(address)
    }

    private fun onRemoteEvent(timed: TimedBleEvent) {
        val address = remoteAddress ?: run {
            AppLog.d("Remote: event ignored outside remote mode event=${timed.event.javaClass.simpleName}", TAG)
            return
        }
        val nextState = BleStateReducer.reduce(mutableUiState.value, timed.event, remoteParser, timed.atMs)
        mutableUiState.value = nextState
        if (timed.event is BleEvent.NotificationReceived) {
            val frames = remoteParser.lastOfferFrames
            if (frames.any { it is KmfFrame.C }) remoteSawC = true
            if (frames.any { it is KmfFrame.A }) {
                addRemoteChartPoint(address, nextState, timed.atMs)
            }
        }
    }

    /** Newest first, like the history flow; points older than the Now chart window are dropped. */
    private fun addRemoteChartPoint(address: RemoteAddress, state: BleUiState, atMs: Long) {
        val reading = state.latestReading
        val point = KmfBatterySampleEntity(
            timestampMs = atMs,
            deviceAddress = address.id,
            deviceName = state.selectedDevice?.name,
            voltageV = reading.voltageV,
            currentA = reading.currentA,
            powerW = reading.powerW,
            charging = reading.charging,
            minutesRemaining = reading.minutesRemaining,
            remainingAh = reading.remainingAh,
            capacityAh = reading.capacityAh,
            socPercent = reading.socPercent,
            chargeKwh = reading.chargeKwh,
            dischargeKwh = reading.dischargeKwh,
            hasAFrame = state.hasAFrame,
            hasBFrame = state.hasBFrame,
            hasCFrame = remoteSawC,
            rawAFieldsCsv = null,
            rawBFieldsCsv = null,
            rawCFieldsCsv = null,
            connectionState = state.connectionState.name,
            serviceUuid = null,
            notifyUuid = null,
            writeUuid = null,
        )
        val cutoffMs = atMs - NowScreenReducer.CHART_WINDOW_MS
        mutableRecentBatterySamples.value = (listOf(point) + mutableRecentBatterySamples.value)
            .filter { it.timestampMs >= cutoffMs }
            .take(LIVE_SAMPLE_LIMIT)
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
        if (remoteAddress != null) {
            // Remote mode charts in-memory points of the live feed, never the saved Bluetooth meter's history.
            return
        }
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
        const val MAX_LOGGED_ADDRESS_CHARS = 100
        const val REMOTE_ADDRESS_ERROR = "Enter a host name or IP, optionally with :port"
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

/** What a meter switch (Bluetooth <-> remote) clears: the other mode's readings, flags, frame times and log. */
private fun BleUiState.withEmptyMeterValues(): BleUiState = copy(
    connectionState = ConnectionState.DISCONNECTED,
    hasAFrame = false,
    hasBFrame = false,
    latestReading = KmfReading(),
    lastReadingTimestampMs = null,
    inboundFrameTimesMs = emptyList(),
    packetLog = emptyList(),
    latestError = null,
)

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
