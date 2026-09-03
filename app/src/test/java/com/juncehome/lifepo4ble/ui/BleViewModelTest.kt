package com.juncehome.lifepo4ble.ui

import com.juncehome.lifepo4ble.ble.BleEvent
import com.juncehome.lifepo4ble.ble.BleRepositoryContract
import com.juncehome.lifepo4ble.ble.GattProfile
import com.juncehome.lifepo4ble.ble.ScannedDevice
import com.juncehome.lifepo4ble.collection.CollectionConfiguration
import com.juncehome.lifepo4ble.collection.CollectionServiceCommander
import com.juncehome.lifepo4ble.collection.CompanionAssociationGateway
import com.juncehome.lifepo4ble.data.DeviceSnapshot
import com.juncehome.lifepo4ble.data.DeviceSnapshotStore
import com.juncehome.lifepo4ble.data.KmfBatterySampleEntity
import com.juncehome.lifepo4ble.data.KmfFrameEventEntity
import com.juncehome.lifepo4ble.data.KmfHistoryStore
import com.juncehome.lifepo4ble.data.KmfNotificationObservation
import com.juncehome.lifepo4ble.data.KmfWriteObservation
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BleViewModelTest {
    @Test
    fun companionResultDiagnosticPreservesAndroidResultFacts() {
        assertEquals(
            "Android companion setup ended without confirmation (resultCode=0, resultData=false); background collection is not enabled.",
            CompanionAssociationResultDiagnostics.describe(resultCode = 0, hasData = false),
        )
    }

    @Test
    fun completedLegacyAssociationPersistsConfigurationStartsPresenceAndStartsService() = runTest {
        val snapshot = configuredSnapshot()
        val store = FakeDeviceStore(initial = snapshot)
        val gateway = FakeCompanionAssociationGateway()
        val serviceCommander = FakeCollectionServiceCommander()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = BleViewModel(
            repository = FakeBleRepository(),
            deviceStore = store,
            historyStore = FakeHistoryStore(),
            companionAssociationGateway = gateway,
            collectionServiceCommander = serviceCommander,
            scope = viewModelScope,
        )

        try {
            viewModel.requestAutonomousCollection()
            advanceUntilIdle()
            viewModel.onAssociationCompleted(associationId = null)
            advanceUntilIdle()

            assertEquals(CollectionConfiguration(snapshot, null, true), store.collectionConfiguration.first())
            assertEquals(listOf(snapshot.address), gateway.observedAddresses)
            assertEquals(listOf("start"), serviceCommander.commands)
            assertEquals(AutonomousCollectionStatus.CONFIGURED, viewModel.uiState.value.autonomousCollectionStatus)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun canceledAssociationDoesNotPersistOrStartService() = runTest {
        val snapshot = configuredSnapshot()
        val store = FakeDeviceStore(initial = snapshot)
        val serviceCommander = FakeCollectionServiceCommander()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = BleViewModel(
            repository = FakeBleRepository(),
            deviceStore = store,
            historyStore = FakeHistoryStore(),
            companionAssociationGateway = FakeCompanionAssociationGateway(),
            collectionServiceCommander = serviceCommander,
            scope = viewModelScope,
        )

        try {
            viewModel.requestAutonomousCollection()
            advanceUntilIdle()
            viewModel.onAssociationCanceled()
            advanceUntilIdle()

            assertNull(store.collectionConfiguration.first())
            assertEquals(emptyList<String>(), serviceCommander.commands)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun stopDisablesConfigurationBeforeStoppingService() = runTest {
        val snapshot = configuredSnapshot()
        val store = FakeDeviceStore(initial = snapshot).apply {
            enableAutonomousCollection(snapshot, 42)
        }
        val gateway = FakeCompanionAssociationGateway()
        val serviceCommander = FakeCollectionServiceCommander(store.operationLog)
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = BleViewModel(
            repository = FakeBleRepository(),
            deviceStore = store,
            historyStore = FakeHistoryStore(),
            companionAssociationGateway = gateway,
            collectionServiceCommander = serviceCommander,
            scope = viewModelScope,
        )

        try {
            viewModel.stopAutonomousCollection()
            advanceUntilIdle()

            assertNull(store.collectionConfiguration.first())
            assertEquals(listOf("disable", "stop"), store.operationLog)
            assertEquals(listOf(snapshot.address), gateway.stoppedObservedAddresses)
            assertEquals(AutonomousCollectionStatus.NOT_CONFIGURED, viewModel.uiState.value.autonomousCollectionStatus)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun connectSavesSuccessfulProfileAndKeepsLogBounded() = runTest {
        val repo = FakeBleRepository()
        val store = FakeDeviceStore()
        val historyStore = FakeHistoryStore()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = BleViewModel(repo, store, historyStore, clock = { 10L }, scope = viewModelScope)
        val device = ScannedDevice(
            name = "KMF",
            address = "AA:BB:CC:DD:EE:FF",
            rssi = -55,
            serviceUuids = emptyList(),
        )
        val dataUuid = UUID.fromString("11111111-1111-1111-1111-111111111111")

        try {
            advanceUntilIdle()
            viewModel.connect(device)
            advanceUntilIdle()
            repo.events.emit(BleEvent.Connected(device))
            repo.events.emit(
                BleEvent.ServicesDiscovered(
                    GattProfile(UUID.randomUUID(), dataUuid, dataUuid, usesIndications = false)
                )
            )
            repeat(250) {
                repo.events.emit(BleEvent.NotificationReceived(dataUuid, ":C=12,34\n".encodeToByteArray()))
            }
            advanceUntilIdle()

            assertEquals(200, viewModel.uiState.value.packetLog.size)
            assertEquals(device.address, store.saved?.address)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun readinessDoesNotStartAUiOwnedCollectionLoop() = runTest {
        val repo = FakeBleRepository()
        val remembered = DeviceSnapshot(
            address = "AA:BB:CC:DD:EE:FF",
            name = "KMF",
            serviceUuid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            notifyUuid = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            writeUuid = "cccccccc-cccc-cccc-cccc-cccccccccccc",
        )
        val store = FakeDeviceStore(initial = remembered)
        val historyStore = FakeHistoryStore()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = BleViewModel(
            repo,
            store,
            historyStore,
            clock = { 1L },
            scope = viewModelScope,
        )

        try {
            advanceUntilIdle()
            viewModel.updateReadiness(
                requiredPermissions = setOf("scan"),
                grantedPermissions = setOf("scan"),
                hasBleFeature = true,
                hasBluetoothAdapter = true,
                bluetoothEnabled = true,
            )
            advanceUntilIdle()

            assertEquals(emptyList<Pair<ScannedDevice, DeviceSnapshot?>>(), repo.connectCalls)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun disconnectDoesNotStartAUiOwnedReconnectLoop() = runTest {
        val repo = FakeBleRepository()
        val remembered = DeviceSnapshot(
            address = "AA:BB:CC:DD:EE:FF",
            name = "KMF",
            serviceUuid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            notifyUuid = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            writeUuid = "cccccccc-cccc-cccc-cccc-cccccccccccc",
        )
        val store = FakeDeviceStore(initial = remembered)
        val historyStore = FakeHistoryStore()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = BleViewModel(
            repo,
            store,
            historyStore,
            clock = { 2L },
            scope = viewModelScope,
        )

        try {
            advanceUntilIdle()
            viewModel.updateReadiness(
                requiredPermissions = setOf("scan"),
                grantedPermissions = setOf("scan"),
                hasBleFeature = true,
                hasBluetoothAdapter = true,
                bluetoothEnabled = true,
            )
            advanceUntilIdle()
            val device = ScannedDevice("KMF", remembered.address, -55, emptyList())
            viewModel.connect(device)
            advanceUntilIdle()
            repo.events.emit(BleEvent.Connecting(device))
            repo.events.emit(BleEvent.Disconnected("signal lost"))
            advanceUntilIdle()

            assertEquals(1, repo.connectCalls.size)

            viewModel.disconnect()
            repo.events.emit(BleEvent.Disconnected("manual"))
            advanceUntilIdle()

            assertEquals(1, repo.connectCalls.size)
            assertEquals(1, repo.disconnectCalls)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun connectionErrorDoesNotStartAUiOwnedReconnectLoop() = runTest {
        val repo = FakeBleRepository()
        val remembered = DeviceSnapshot(
            address = "AA:BB:CC:DD:EE:FF",
            name = "KMF",
            serviceUuid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            notifyUuid = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            writeUuid = "cccccccc-cccc-cccc-cccc-cccccccccccc",
        )
        val store = FakeDeviceStore(initial = remembered)
        val historyStore = FakeHistoryStore()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = BleViewModel(
            repo,
            store,
            historyStore,
            clock = { 3L },
            scope = viewModelScope,
        )

        try {
            advanceUntilIdle()
            viewModel.updateReadiness(
                requiredPermissions = setOf("scan"),
                grantedPermissions = setOf("scan"),
                hasBleFeature = true,
                hasBluetoothAdapter = true,
                bluetoothEnabled = true,
            )
            advanceUntilIdle()
            val device = ScannedDevice("KMF", remembered.address, -55, emptyList())
            viewModel.connect(device)
            advanceUntilIdle()
            repo.events.emit(BleEvent.Connecting(device))
            repo.events.emit(BleEvent.Error("connect failed"))
            advanceUntilIdle()

            assertEquals(1, repo.connectCalls.size)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun persistsWritesAndInboundNotificationsUsingCurrentSessionContext() = runTest {
        val repo = FakeBleRepository()
        val store = FakeDeviceStore()
        val historyStore = FakeHistoryStore()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = BleViewModel(repo, store, historyStore, clock = { 1234L }, scope = viewModelScope)
        val device = ScannedDevice(
            name = "KMF",
            address = "AA:BB:CC:DD:EE:FF",
            rssi = -55,
            serviceUuids = emptyList(),
        )
        val serviceUuid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val notifyUuid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        val writeUuid = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")

        try {
            advanceUntilIdle()
            repo.events.emit(BleEvent.Connecting(device))
            repo.events.emit(BleEvent.Connected(device))
            repo.events.emit(BleEvent.ServicesDiscovered(GattProfile(serviceUuid, notifyUuid, writeUuid, false)))
            repo.events.emit(BleEvent.WriteCompleted(":*\n".encodeToByteArray(), success = true))
            repo.events.emit(BleEvent.NotificationReceived(notifyUuid, ":A=1326,1080,0,5793,\n".encodeToByteArray()))
            advanceUntilIdle()

            assertEquals(listOf(device.address), historyStore.resetSessions)
            assertEquals(1, historyStore.writes.size)
            assertEquals(":*\n", historyStore.writes.single().bytes.toString(Charsets.US_ASCII))
            assertEquals(true, historyStore.writes.single().writeSuccess)

            val notification = historyStore.notifications.single()
            assertEquals(device.address, notification.deviceAddress)
            assertEquals(device.name, notification.deviceName)
            assertEquals(serviceUuid.toString(), notification.serviceUuid)
            assertEquals(notifyUuid.toString(), notification.notifyUuid)
            assertEquals(writeUuid.toString(), notification.writeUuid)
            assertEquals("READY", notification.connectionState)
            assertEquals(1, notification.frames.size)
            assertEquals(13.26, notification.mergedReading.voltageV, 0.001)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun recentBatterySamplesFollowSelectedDeviceHistory() = runTest {
        val repo = FakeBleRepository()
        val store = FakeDeviceStore()
        val historyStore = FakeHistoryStore()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = BleViewModel(repo, store, historyStore, clock = { 1234L }, scope = viewModelScope)
        val device = ScannedDevice(
            name = "KMF",
            address = "AA:BB:CC:DD:EE:FF",
            rssi = -55,
            serviceUuids = emptyList(),
        )

        try {
            advanceUntilIdle()
            repo.events.emit(BleEvent.Connecting(device))
            historyStore.emitRecentBatterySamples(
                device.address,
                listOf(
                    batterySample(id = 2, timestampMs = 20L),
                    batterySample(id = 1, timestampMs = 10L),
                )
            )
            advanceUntilIdle()

            assertEquals(2, viewModel.recentBatterySamples.value.size)
            assertEquals(20L, viewModel.recentBatterySamples.value.first().timestampMs)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun autonomousConfigurationMakesDiagnosticsPassiveAndLeavesHistoryToTheService() = runTest {
        val snapshot = configuredSnapshot()
        val repository = FakeBleRepository()
        val store = FakeDeviceStore(initial = snapshot).apply {
            enableAutonomousCollection(snapshot, 42)
        }
        val historyStore = FakeHistoryStore()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = BleViewModel(repository, store, historyStore, scope = viewModelScope)

        try {
            advanceUntilIdle()
            val device = ScannedDevice(snapshot.name, snapshot.address, -55, emptyList())
            val notifyUuid = UUID.fromString(snapshot.notifyUuid!!)
            repository.events.emit(BleEvent.Connecting(device))
            repository.events.emit(BleEvent.NotificationReceived(notifyUuid, ":A=1326,1080,0,5793,\n".encodeToByteArray()))
            advanceUntilIdle()

            viewModel.startScan()
            viewModel.stopScan()
            viewModel.connect(device)
            viewModel.disconnect()
            advanceUntilIdle()

            assertEquals(emptyList<String>(), historyStore.resetSessions)
            assertEquals(emptyList<KmfNotificationObservation>(), historyStore.notifications)
            assertEquals(0, repository.startScanCalls)
            assertEquals(0, repository.stopScanCalls)
            assertEquals(emptyList<Pair<ScannedDevice, DeviceSnapshot?>>(), repository.connectCalls)
            assertEquals(0, repository.disconnectCalls)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun configuredServiceEventsDoNotPersistBeforeConfigurationInitialLoadCompletes() = runTest {
        val snapshot = configuredSnapshot()
        val delayedConfigurations = MutableSharedFlow<CollectionConfiguration?>(extraBufferCapacity = 1)
        val repository = FakeBleRepository()
        val historyStore = FakeHistoryStore()
        val store = FakeDeviceStore(
            initial = snapshot,
            configurationFlow = delayedConfigurations,
        )
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = BleViewModel(repository, store, historyStore, scope = viewModelScope)
        val device = ScannedDevice(snapshot.name, snapshot.address, -55, emptyList())
        val notifyUuid = UUID.fromString(snapshot.notifyUuid!!)

        try {
            repository.events.emit(BleEvent.Connecting(device))
            repository.events.emit(BleEvent.NotificationReceived(notifyUuid, ":A=1326,1080,0,5793,\n".encodeToByteArray()))
            advanceUntilIdle()

            delayedConfigurations.emit(CollectionConfiguration(snapshot, 42, true))
            advanceUntilIdle()

            assertEquals(emptyList<String>(), historyStore.resetSessions)
            assertEquals(emptyList<KmfNotificationObservation>(), historyStore.notifications)
            assertEquals(AutonomousCollectionStatus.CONFIGURED, viewModel.uiState.value.autonomousCollectionStatus)
            assertEquals(snapshot.address, viewModel.uiState.value.selectedDevice?.address)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun missingRuntimePermissionsRequestGrantBeforeCompanionAssociation() = runTest {
        val snapshot = configuredSnapshot()
        val store = FakeDeviceStore(initial = snapshot)
        val gateway = FakeCompanionAssociationGateway()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = BleViewModel(
            repository = FakeBleRepository(),
            deviceStore = store,
            historyStore = FakeHistoryStore(),
            companionAssociationGateway = gateway,
            scope = viewModelScope,
        )

        try {
            viewModel.updateReadiness(
                requiredPermissions = setOf("scan", "connect", "notifications"),
                grantedPermissions = setOf("scan"),
                hasBleFeature = true,
                hasBluetoothAdapter = true,
                bluetoothEnabled = false,
            )
            val permissionRequest = async { viewModel.autonomousPermissionRequests.first() }
            runCurrent()

            viewModel.requestAutonomousCollection()
            assertEquals(Unit, permissionRequest.await())
            assertEquals(emptyList<String>(), gateway.associationRequests)

            viewModel.updateReadiness(
                requiredPermissions = setOf("scan", "connect", "notifications"),
                grantedPermissions = setOf("scan", "connect", "notifications"),
                hasBleFeature = true,
                hasBluetoothAdapter = true,
                bluetoothEnabled = true,
            )
            advanceUntilIdle()

            assertEquals(listOf(snapshot.address), gateway.associationRequests)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun deniedRuntimePermissionsDoNotLaunchCompanionAssociation() = runTest {
        val snapshot = configuredSnapshot()
        val store = FakeDeviceStore(initial = snapshot)
        val gateway = FakeCompanionAssociationGateway()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = BleViewModel(
            repository = FakeBleRepository(),
            deviceStore = store,
            historyStore = FakeHistoryStore(),
            companionAssociationGateway = gateway,
            scope = viewModelScope,
        )

        try {
            viewModel.updateReadiness(
                requiredPermissions = setOf("scan", "connect"),
                grantedPermissions = setOf("scan"),
                hasBleFeature = true,
                hasBluetoothAdapter = true,
                bluetoothEnabled = false,
            )
            val permissionRequest = async { viewModel.autonomousPermissionRequests.first() }
            runCurrent()

            viewModel.requestAutonomousCollection()
            permissionRequest.await()
            viewModel.updateReadiness(
                requiredPermissions = setOf("scan", "connect"),
                grantedPermissions = setOf("scan"),
                hasBleFeature = true,
                hasBluetoothAdapter = true,
                bluetoothEnabled = false,
            )
            advanceUntilIdle()

            assertEquals(emptyList<String>(), gateway.associationRequests)
            assertEquals(AutonomousCollectionStatus.ERROR, viewModel.uiState.value.autonomousCollectionStatus)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun reopeningHydratesConfiguredMeterAndItsSavedReadingWithoutTransportEvents() = runTest {
        val snapshot = configuredSnapshot()
        val store = FakeDeviceStore(initial = snapshot).apply {
            enableAutonomousCollection(snapshot, 42)
        }
        val historyStore = FakeHistoryStore().apply {
            emitRecentBatterySamples(snapshot.address, listOf(batterySample(id = 1, timestampMs = 99L)))
        }
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = BleViewModel(FakeBleRepository(), store, historyStore, scope = viewModelScope)

        try {
            advanceUntilIdle()

            assertEquals(snapshot.address, viewModel.uiState.value.selectedDevice?.address)
            assertEquals(AutonomousCollectionStatus.CONFIGURED, viewModel.uiState.value.autonomousCollectionStatus)
            assertEquals(13.26, viewModel.uiState.value.latestReading.voltageV, 0.001)
            assertEquals(99L, viewModel.uiState.value.lastReadingTimestampMs)
            assertEquals(1, viewModel.recentBatterySamples.value.size)
        } finally {
            viewModelScope.cancel()
        }
    }

    private fun batterySample(
        id: Long,
        timestampMs: Long,
    ): KmfBatterySampleEntity =
        KmfBatterySampleEntity(
            id = id,
            timestampMs = timestampMs,
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            deviceName = "KMF",
            voltageV = 13.26,
            currentA = 1.08,
            powerW = 14.32,
            charging = true,
            minutesRemaining = 95,
            remainingAh = 57.93,
            capacityAh = 100.0,
            socPercent = 57.9,
            chargeKwh = 12.34,
            dischargeKwh = 7.89,
            hasAFrame = true,
            hasBFrame = true,
            hasCFrame = true,
            rawAFieldsCsv = null,
            rawBFieldsCsv = null,
            rawCFieldsCsv = null,
            connectionState = "READY",
            serviceUuid = null,
            notifyUuid = null,
            writeUuid = null,
        )

    private class FakeBleRepository : BleRepositoryContract {
        override val events: MutableSharedFlow<BleEvent> = MutableSharedFlow(extraBufferCapacity = 300)
        val connectCalls = mutableListOf<Pair<ScannedDevice, DeviceSnapshot?>>()
        var startScanCalls = 0
        var stopScanCalls = 0
        var disconnectCalls = 0

        override fun startScan() {
            startScanCalls += 1
        }

        override fun stopScan() {
            stopScanCalls += 1
        }

        override fun connect(device: ScannedDevice, preferred: DeviceSnapshot?) {
            connectCalls += device to preferred
        }

        override fun write(bytes: ByteArray): Boolean = true

        override fun requestMtu(mtu: Int): Boolean = true

        override fun disconnect() {
            disconnectCalls += 1
        }
    }

    private class FakeDeviceStore(
        initial: DeviceSnapshot? = null,
        configurationFlow: Flow<CollectionConfiguration?>? = null,
    ) : DeviceSnapshotStore {
        private val snapshots = MutableStateFlow(initial)
        private val configurations = MutableStateFlow<CollectionConfiguration?>(null)
        var saved: DeviceSnapshot? = null

        override val snapshot: Flow<DeviceSnapshot?> = snapshots
        override val collectionConfiguration: Flow<CollectionConfiguration?> = configurationFlow ?: configurations

        override suspend fun save(snapshot: DeviceSnapshot) {
            saved = snapshot
            snapshots.value = snapshot
        }

        val operationLog = mutableListOf<String>()

        override suspend fun enableAutonomousCollection(snapshot: DeviceSnapshot, associationId: Int?) {
            save(snapshot)
            configurations.value = CollectionConfiguration(snapshot, associationId, true)
        }

        override suspend fun disableAutonomousCollection() {
            operationLog += "disable"
            configurations.value = null
        }

        override suspend fun clear() {
            saved = null
            snapshots.value = null
            configurations.value = null
        }
    }

    private class FakeCompanionAssociationGateway : CompanionAssociationGateway {
        val observedAddresses = mutableListOf<String>()
        val stoppedObservedAddresses = mutableListOf<String>()
        val associationRequests = mutableListOf<String>()

        override fun isSupported(): Boolean = true

        override fun requestAssociation(
            address: String,
            onPending: (android.content.IntentSender) -> Unit,
            onFailure: (Throwable) -> Unit,
        ) {
            associationRequests += address
        }

        override fun associationIdFromResult(successfulResultData: android.content.Intent): Int? = null

        override fun startPresenceObservation(address: String) {
            observedAddresses += address
        }

        override fun stopPresenceObservation(address: String) {
            stoppedObservedAddresses += address
        }
    }

    private class FakeCollectionServiceCommander(
        private val operationLog: MutableList<String>? = null,
    ) : CollectionServiceCommander {
        val commands = mutableListOf<String>()

        override fun start() {
            commands += "start"
        }

        override fun stop() {
            operationLog?.add("stop")
            commands += "stop"
        }

        override fun setVisibility(visibility: com.juncehome.lifepo4ble.collection.CollectionVisibility) = Unit
    }

    private fun configuredSnapshot(): DeviceSnapshot = DeviceSnapshot(
        address = "AA:BB:CC:DD:EE:FF",
        name = "KMF",
        serviceUuid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        notifyUuid = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
        writeUuid = "cccccccc-cccc-cccc-cccc-cccccccccccc",
    )

    private class FakeHistoryStore : KmfHistoryStore {
        val resetSessions = mutableListOf<String>()
        val notifications = mutableListOf<KmfNotificationObservation>()
        val writes = mutableListOf<KmfWriteObservation>()
        private val recentBatterySamplesByDevice =
            mutableMapOf<String, MutableStateFlow<List<KmfBatterySampleEntity>>>()

        override suspend fun resetSession(deviceAddress: String) {
            resetSessions += deviceAddress
        }

        override suspend fun recordNotification(observation: KmfNotificationObservation) {
            notifications += observation
        }

        override suspend fun recordWrite(observation: KmfWriteObservation) {
            writes += observation
        }

        override fun observeLatestBatterySample(deviceAddress: String): Flow<KmfBatterySampleEntity?> =
            emptyFlow()

        override fun observeRecentBatterySamples(
            deviceAddress: String,
            limit: Int,
        ): Flow<List<KmfBatterySampleEntity>> =
            recentBatterySamplesByDevice.getOrPut(deviceAddress) { MutableStateFlow(emptyList()) }

        override fun observeRecentFrameEvents(
            deviceAddress: String,
            limit: Int,
        ): Flow<List<KmfFrameEventEntity>> = emptyFlow()

        fun emitRecentBatterySamples(
            deviceAddress: String,
            samples: List<KmfBatterySampleEntity>,
        ) {
            recentBatterySamplesByDevice.getOrPut(deviceAddress) { MutableStateFlow(emptyList()) }.value = samples
        }
    }
}
