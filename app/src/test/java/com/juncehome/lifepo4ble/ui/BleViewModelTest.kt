package com.juncehome.lifepo4ble.ui

import com.juncehome.lifepo4ble.ble.BleEvent
import com.juncehome.lifepo4ble.ble.BleRepositoryContract
import com.juncehome.lifepo4ble.ble.GattProfile
import com.juncehome.lifepo4ble.ble.ScannedDevice
import com.juncehome.lifepo4ble.collection.CollectionConfiguration
import com.juncehome.lifepo4ble.collection.CollectionPhase
import com.juncehome.lifepo4ble.collection.CollectionServiceCommander
import com.juncehome.lifepo4ble.collection.CollectionState
import com.juncehome.lifepo4ble.collection.CollectionVisibility
import com.juncehome.lifepo4ble.collection.CompanionAssociationGateway
import com.juncehome.lifepo4ble.data.DeviceSnapshot
import com.juncehome.lifepo4ble.data.DeviceSnapshotStore
import com.juncehome.lifepo4ble.data.KmfBatterySampleEntity
import com.juncehome.lifepo4ble.data.KmfFrameEventEntity
import com.juncehome.lifepo4ble.data.KmfHistoryStore
import com.juncehome.lifepo4ble.data.KmfNotificationObservation
import com.juncehome.lifepo4ble.data.KmfWriteObservation
import com.juncehome.lifepo4ble.remote.RemoteAddress
import com.juncehome.lifepo4ble.remote.RemoteMeterLinkContract
import com.juncehome.lifepo4ble.remote.SharingStatus
import com.juncehome.lifepo4ble.remote.TimedBleEvent
import com.juncehome.lifepo4ble.ui.now.NowScreenReducer
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Capacity 180 Ah, remaining 152.34 Ah: 84.63 %.
private const val FULL_A = ":A=1331,5200,1,2380,152340,1800,\r\n"
private const val SECOND_A = ":A=1332,5300,1,2370,152200,1800,\r\n"
// No capacity field: 5.2 A for 1760 min is 152.53 Ah left to empty.
private const val SHORT_A = ":A=1329,5200,0,1760,\r\n"
private const val B_LINE = ":B=1226110,0,271158,\r\n"
private const val C_LINE = ":C=1820,486\r\n"

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
    fun firstRunSelectSavesMeterWithoutConnectingAndStopsScan() = runTest {
        val store = FakeDeviceStore()
        val repository = FakeBleRepository()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = BleViewModel(
            repository = repository,
            deviceStore = store,
            historyStore = FakeHistoryStore(),
            companionAssociationGateway = FakeCompanionAssociationGateway(),
            scope = viewModelScope,
        )

        try {
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.setupStateLoaded)
            assertNull(viewModel.uiState.value.savedMeterAddress)

            viewModel.selectMeterForSetup(ScannedDevice("KMF", "11:22:33:44:55:66", -50, emptyList()))
            advanceUntilIdle()

            assertEquals(DeviceSnapshot("11:22:33:44:55:66", "KMF", null, null, null), store.saved)
            assertEquals("11:22:33:44:55:66", viewModel.uiState.value.savedMeterAddress)
            assertEquals(1, repository.stopScanCalls)
            assertEquals(emptyList<Pair<ScannedDevice, DeviceSnapshot?>>(), repository.connectCalls)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun firstRunSelectKeepsSavedProfileForTheSameMeter() = runTest {
        val snapshot = configuredSnapshot()
        val store = FakeDeviceStore(initial = snapshot)
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = BleViewModel(
            repository = FakeBleRepository(),
            deviceStore = store,
            historyStore = FakeHistoryStore(),
            scope = viewModelScope,
        )

        try {
            viewModel.selectMeterForSetup(ScannedDevice(null, snapshot.address, -60, emptyList()))
            advanceUntilIdle()

            assertEquals(snapshot, store.saved)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun chooseAnotherMeterClearsUnconfiguredSelection() = runTest {
        val store = FakeDeviceStore(initial = configuredSnapshot())
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = BleViewModel(
            repository = FakeBleRepository(),
            deviceStore = store,
            historyStore = FakeHistoryStore(),
            companionAssociationGateway = FakeCompanionAssociationGateway(),
            scope = viewModelScope,
        )

        try {
            advanceUntilIdle()
            viewModel.onAssociationCanceled()
            viewModel.chooseAnotherMeter()
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.savedMeterAddress)
            assertNull(viewModel.uiState.value.autonomousCollectionDetail)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun companionAssociationLookupReachesUiState() = runTest {
        val snapshot = configuredSnapshot()
        val gateway = FakeCompanionAssociationGateway().apply { associatedAddresses += snapshot.address }
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = BleViewModel(
            repository = FakeBleRepository(),
            deviceStore = FakeDeviceStore(initial = snapshot),
            historyStore = FakeHistoryStore(),
            companionAssociationGateway = gateway,
            scope = viewModelScope,
        )

        try {
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.companionAssociated)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun companionSetupNeedsOnlyBluetoothPermissions() = runTest {
        val snapshot = configuredSnapshot()
        val gateway = FakeCompanionAssociationGateway()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = BleViewModel(
            repository = FakeBleRepository(),
            deviceStore = FakeDeviceStore(initial = snapshot),
            historyStore = FakeHistoryStore(),
            companionAssociationGateway = gateway,
            scope = viewModelScope,
        )

        try {
            viewModel.updateReadiness(
                requiredPermissions = setOf("scan", "connect", "notifications"),
                grantedPermissions = setOf("scan", "connect"),
                hasBleFeature = true,
                hasBluetoothAdapter = true,
                bluetoothEnabled = true,
                bluetoothPermissions = setOf("scan", "connect"),
                notificationPermission = "notifications",
            )
            viewModel.requestAutonomousCollection()
            advanceUntilIdle()

            assertEquals(listOf(snapshot.address), gateway.associationRequests)
            assertFalse(viewModel.uiState.value.notificationPermissionGranted)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun finishFirstRunPersistsCompletionAndStartsStoppedCollection() = runTest {
        val snapshot = configuredSnapshot()
        val store = FakeDeviceStore(initial = snapshot).apply { enableAutonomousCollection(snapshot, 7) }
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
            advanceUntilIdle()
            serviceCommander.commands.clear()
            viewModel.finishFirstRun()
            advanceUntilIdle()

            assertTrue(store.firstRunCompletedState.value)
            assertTrue(viewModel.uiState.value.firstRunCompleted)
            assertEquals("start", serviceCommander.commands.first())
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun finishFirstRunReEnablesCollectionForExistingAssociation() = runTest {
        val snapshot = configuredSnapshot()
        val store = FakeDeviceStore(initial = snapshot)
        val gateway = FakeCompanionAssociationGateway().apply { associatedAddresses += snapshot.address }
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
            advanceUntilIdle()
            viewModel.finishFirstRun()
            advanceUntilIdle()

            assertEquals(CollectionConfiguration(snapshot, null, true), store.collectionConfiguration.first())
            assertEquals(listOf(snapshot.address), gateway.observedAddresses)
            assertEquals("start", serviceCommander.commands.first())
        } finally {
            viewModelScope.cancel()
        }
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
    fun collectionStatusReachesUiStateAndRetrySendsReconnectNow() = runTest {
        val snapshot = configuredSnapshot()
        val store = FakeDeviceStore(initial = snapshot)
        store.enableAutonomousCollection(snapshot, associationId = 7)
        val serviceCommander = FakeCollectionServiceCommander()
        val status = MutableStateFlow<com.juncehome.lifepo4ble.collection.CollectionState?>(null)
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = BleViewModel(
            repository = FakeBleRepository(),
            deviceStore = store,
            historyStore = FakeHistoryStore(),
            collectionServiceCommander = serviceCommander,
            scope = viewModelScope,
            collectionStatus = status,
        )

        try {
            advanceUntilIdle()
            val reconnecting = com.juncehome.lifepo4ble.collection.CollectionState(
                phase = com.juncehome.lifepo4ble.collection.CollectionPhase.ERROR,
                visibility = com.juncehome.lifepo4ble.collection.CollectionVisibility.FOREGROUND,
                nextReconnectAtMs = 5_000L,
                reconnectAttempt = 3,
            )
            status.value = reconnecting
            advanceUntilIdle()
            assertEquals(reconnecting, viewModel.uiState.value.collectionState)

            viewModel.retryCollectionNow()
            assertEquals("reconnectNow", serviceCommander.commands.last())
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun retryIsIgnoredAndStartRunsSetupWhenCollectionIsNotConfigured() = runTest {
        val serviceCommander = FakeCollectionServiceCommander()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = BleViewModel(
            repository = FakeBleRepository(),
            deviceStore = FakeDeviceStore(initial = configuredSnapshot()),
            historyStore = FakeHistoryStore(),
            collectionServiceCommander = serviceCommander,
            scope = viewModelScope,
        )

        try {
            advanceUntilIdle()
            viewModel.retryCollectionNow()
            assertEquals(emptyList<String>(), serviceCommander.commands)

            viewModel.startCollectionFromNow()
            advanceUntilIdle()
            // Start falls through to companion setup; this fixture has no gateway.
            assertEquals(AutonomousCollectionStatus.ERROR, viewModel.uiState.value.autonomousCollectionStatus)
            assertEquals("Companion device setup is unavailable", viewModel.uiState.value.autonomousCollectionDetail)
            assertEquals(emptyList<String>(), serviceCommander.commands)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun directAssociationCreatedPersistsConfigurationOnlyOnceWhenActivityAlsoReturnsSuccess() = runTest {
        val snapshot = configuredSnapshot()
        val store = FakeDeviceStore(initial = snapshot).apply { gateFirstAutonomousEnable() }
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

            gateway.completeAssociationDirectly(37)
            runCurrent()
            viewModel.onAssociationCompleted(37)
            runCurrent()

            assertEquals(1, store.autonomousEnableCalls)
            assertEquals(listOf(snapshot.address), gateway.observedAddresses)
            assertEquals(emptyList<String>(), serviceCommander.commands)

            store.releaseFirstAutonomousEnable()
            advanceUntilIdle()

            assertEquals(CollectionConfiguration(snapshot, 37, true), store.collectionConfiguration.first())
            assertEquals(1, store.autonomousEnableCalls)
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
    fun manualConnectionPersistsNoHistoryButKeepsDiscoveredProfile() = runTest {
        val repo = FakeBleRepository()
        val store = FakeDeviceStore()
        val historyStore = FakeHistoryStore()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        BleViewModel(repo, store, historyStore, clock = { 1234L }, scope = viewModelScope)
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

            assertEquals(emptyList<String>(), historyStore.resetSessions)
            assertEquals(emptyList<KmfWriteObservation>(), historyStore.writes)
            assertEquals(emptyList<KmfNotificationObservation>(), historyStore.notifications)
            assertEquals(serviceUuid.toString(), store.saved?.serviceUuid)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun testConnectionRunsKmfStartupUntilAAndBThenEndsOnDisconnect() = runTest {
        val snapshot = configuredSnapshot()
        val repo = FakeBleRepository()
        val historyStore = FakeHistoryStore()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = BleViewModel(repo, FakeDeviceStore(initial = snapshot), historyStore, scope = viewModelScope)
        val device = ScannedDevice(snapshot.name, snapshot.address, 0, emptyList())
        val profile = GattProfile(
            UUID.fromString(snapshot.serviceUuid!!),
            UUID.fromString(snapshot.notifyUuid!!),
            UUID.fromString(snapshot.writeUuid!!),
            false,
        )

        try {
            advanceUntilIdle()
            viewModel.startTestConnection()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.testConnectionActive)
            assertEquals(snapshot.address, repo.connectCalls.single().first.address)

            repo.events.emit(BleEvent.Connecting(device))
            repo.events.emit(BleEvent.Connected(device))
            repo.events.emit(BleEvent.ServicesDiscovered(profile))
            runCurrent()
            assertEquals(emptyList<Int>(), repo.mtuRequests)

            // 1 s initial delay, MTU 100, 0.5 s, then :* every 2 s.
            advanceTimeBy(1_600L)
            assertEquals(listOf(100), repo.mtuRequests)
            assertEquals(listOf(":*"), repo.writes)
            advanceTimeBy(2_000L)
            assertEquals(listOf(":*", ":*"), repo.writes)

            // A alone keeps retrying; A and B stop the loop.
            repo.events.emit(BleEvent.NotificationReceived(profile.notifyUuid, ":A=1326,1080,0,5793,\n".encodeToByteArray()))
            advanceTimeBy(2_000L)
            assertEquals(3, repo.writes.size)
            repo.events.emit(BleEvent.NotificationReceived(profile.notifyUuid, ":B=1226110,0,271158,\n".encodeToByteArray()))
            advanceTimeBy(10_000L)
            assertEquals(3, repo.writes.size)
            advanceUntilIdle()
            assertEquals(2, viewModel.uiState.value.packetLog.size)
            assertEquals(emptyList<KmfNotificationObservation>(), historyStore.notifications)

            repo.events.emit(BleEvent.Disconnected("link lost"))
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.testConnectionActive)
            assertEquals("Disconnected \u00B7 link lost", viewModel.uiState.value.testConnectionResult)
            assertEquals(1, repo.connectCalls.size)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun testConnectionIsRefusedWhileCollectionIsConfigured() = runTest {
        val snapshot = configuredSnapshot()
        val store = FakeDeviceStore(initial = snapshot).apply { enableAutonomousCollection(snapshot, 42) }
        val repo = FakeBleRepository()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = BleViewModel(repo, store, FakeHistoryStore(), scope = viewModelScope)

        try {
            advanceUntilIdle()
            viewModel.startTestConnection()
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.testConnectionActive)
            assertEquals(emptyList<Pair<ScannedDevice, DeviceSnapshot?>>(), repo.connectCalls)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun stopTestConnectionDisconnects() = runTest {
        val repo = FakeBleRepository()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = BleViewModel(repo, FakeDeviceStore(initial = configuredSnapshot()), FakeHistoryStore(), scope = viewModelScope)

        try {
            advanceUntilIdle()
            viewModel.startTestConnection()
            advanceUntilIdle()
            viewModel.stopTestConnection()
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.testConnectionActive)
            assertEquals(1, repo.disconnectCalls)
            assertEquals("Disconnected", viewModel.uiState.value.testConnectionResult)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun deviceSwitchOnReusesExistingAssociationWithoutNewPairingSheet() = runTest {
        val snapshot = configuredSnapshot()
        val store = FakeDeviceStore(initial = snapshot)
        val gateway = FakeCompanionAssociationGateway().apply { associatedAddresses += snapshot.address }
        val commander = FakeCollectionServiceCommander()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = BleViewModel(
            repository = FakeBleRepository(),
            deviceStore = store,
            historyStore = FakeHistoryStore(),
            companionAssociationGateway = gateway,
            collectionServiceCommander = commander,
            scope = viewModelScope,
        )

        try {
            advanceUntilIdle()
            viewModel.enableBackgroundCollection()
            advanceUntilIdle()
            assertEquals(emptyList<String>(), gateway.associationRequests)
            assertEquals(listOf(snapshot.address), gateway.observedAddresses)
            assertEquals(listOf("start"), commander.commands)
            assertTrue(viewModel.uiState.value.autonomousCollectionConfigured)

            viewModel.disableBackgroundCollection()
            advanceUntilIdle()
            assertEquals(listOf("start", "stop"), commander.commands)
            assertFalse(viewModel.uiState.value.autonomousCollectionConfigured)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun deviceSwitchOnWithoutAssociationStartsPairing() = runTest {
        val snapshot = configuredSnapshot()
        val gateway = FakeCompanionAssociationGateway()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = BleViewModel(
            repository = FakeBleRepository(),
            deviceStore = FakeDeviceStore(initial = snapshot),
            historyStore = FakeHistoryStore(),
            companionAssociationGateway = gateway,
            collectionServiceCommander = FakeCollectionServiceCommander(),
            scope = viewModelScope,
        )

        try {
            advanceUntilIdle()
            viewModel.enableBackgroundCollection()
            advanceUntilIdle()
            assertEquals(listOf(snapshot.address), gateway.associationRequests)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun nowStartAfterStopReusesExistingAssociationWithoutNewPairingSheet() = runTest {
        val snapshot = configuredSnapshot()
        val store = FakeDeviceStore(initial = snapshot).apply { enableAutonomousCollection(snapshot, 42) }
        val gateway = FakeCompanionAssociationGateway().apply { associatedAddresses += snapshot.address }
        val commander = FakeCollectionServiceCommander()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = BleViewModel(
            repository = FakeBleRepository(),
            deviceStore = store,
            historyStore = FakeHistoryStore(),
            companionAssociationGateway = gateway,
            collectionServiceCommander = commander,
            scope = viewModelScope,
        )

        try {
            advanceUntilIdle()
            viewModel.disableBackgroundCollection()
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.autonomousCollectionConfigured)

            viewModel.startCollectionFromNow()
            advanceUntilIdle()
            assertEquals(emptyList<String>(), gateway.associationRequests)
            assertEquals(listOf(snapshot.address), gateway.observedAddresses)
            assertEquals("start", commander.commands.last())
            assertTrue(viewModel.uiState.value.autonomousCollectionConfigured)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun nowStartWithoutAssociationStartsPairing() = runTest {
        val snapshot = configuredSnapshot()
        val gateway = FakeCompanionAssociationGateway()
        val commander = FakeCollectionServiceCommander()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = BleViewModel(
            repository = FakeBleRepository(),
            deviceStore = FakeDeviceStore(initial = snapshot),
            historyStore = FakeHistoryStore(),
            companionAssociationGateway = gateway,
            collectionServiceCommander = commander,
            scope = viewModelScope,
        )

        try {
            advanceUntilIdle()
            viewModel.startCollectionFromNow()
            advanceUntilIdle()
            assertEquals(listOf(snapshot.address), gateway.associationRequests)
            assertEquals(emptyList<String>(), commander.commands)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun forgetMeterStopsCollectionRemovesAssociationAndClearsSetup() = runTest {
        val snapshot = configuredSnapshot()
        val store = FakeDeviceStore(initial = snapshot).apply {
            enableAutonomousCollection(snapshot, 42)
            setFirstRunCompleted(true)
        }
        val gateway = FakeCompanionAssociationGateway().apply { associatedAddresses += snapshot.address }
        val commander = FakeCollectionServiceCommander(store.operationLog)
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = BleViewModel(
            repository = FakeBleRepository(),
            deviceStore = store,
            historyStore = FakeHistoryStore(),
            companionAssociationGateway = gateway,
            collectionServiceCommander = commander,
            scope = viewModelScope,
        )

        try {
            advanceUntilIdle()
            viewModel.forgetMeter()
            advanceUntilIdle()
            assertEquals(listOf("disable", "stop"), store.operationLog)
            assertEquals(listOf(snapshot.address), gateway.disassociatedAddresses)
            assertNull(store.snapshot.first())
            val state = viewModel.uiState.value
            assertNull(state.savedMeterAddress)
            assertFalse(state.companionAssociated)
            assertFalse(state.autonomousCollectionConfigured)
            assertFalse(state.firstRunCompleted)
            assertFalse(com.juncehome.lifepo4ble.ui.firstrun.FirstRunResolver.setupComplete(state))
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
    fun manualTransportIsBlockedUntilTheInitialConfigurationIsExplicitlyUnconfigured() = runTest {
        val snapshot = configuredSnapshot()
        val delayedConfigurations = MutableSharedFlow<CollectionConfiguration?>(extraBufferCapacity = 1)
        val repository = FakeBleRepository()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = BleViewModel(
            repository = repository,
            deviceStore = FakeDeviceStore(initial = snapshot, configurationFlow = delayedConfigurations),
            historyStore = FakeHistoryStore(),
            scope = viewModelScope,
        )
        val device = ScannedDevice(snapshot.name, snapshot.address, -55, emptyList())

        try {
            runCurrent()
            viewModel.startScan()
            viewModel.stopScan()
            viewModel.connect(device)
            viewModel.disconnect()
            runCurrent()

            assertEquals(0, repository.startScanCalls)
            assertEquals(0, repository.stopScanCalls)
            assertEquals(emptyList<Pair<ScannedDevice, DeviceSnapshot?>>(), repository.connectCalls)
            assertEquals(0, repository.disconnectCalls)
            assertEquals(false, viewModel.uiState.value.manualTransportControlsEnabled)

            delayedConfigurations.emit(null)
            runCurrent()
            viewModel.startScan()
            viewModel.stopScan()
            viewModel.connect(device)
            viewModel.disconnect()
            runCurrent()

            assertEquals(1, repository.startScanCalls)
            assertEquals(1, repository.stopScanCalls)
            assertEquals(listOf(device to snapshot), repository.connectCalls)
            assertEquals(1, repository.disconnectCalls)
            assertEquals(true, viewModel.uiState.value.manualTransportControlsEnabled)
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

    // ---- Remote mode: the Now tab reads the meter through another phone ----

    @Test
    fun remoteFramesUseSameReducerAndHomeTime() = runTest {
        val link = FakeRemoteMeterLink()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = remoteViewModel(viewModelScope, FakeDeviceStore(initialRemoteAddress = "h:8765"), link)

        try {
            viewModel.onAppVisibilityChanged(CollectionVisibility.FOREGROUND)
            advanceUntilIdle()
            assertEquals(listOf("start h:8765"), link.calls)

            link.events.emit(TimedBleEvent(BleEvent.Connecting(remoteMeter()), 500L))
            link.events.emit(TimedBleEvent(BleEvent.Connected(remoteMeter()), 600L))
            link.events.emit(remoteLine(FULL_A, atMs = 1_000L))
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertTrue(state.remoteMode)
            assertEquals("h:8765", state.remoteAddress)
            assertEquals("remote:h:8765", state.selectedDevice?.address)
            assertEquals(180.0, state.latestReading.capacityAh, 0.001)
            assertEquals(84.63, state.latestReading.socPercent, 0.01)
            assertEquals(1_000L, state.lastReadingTimestampMs)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun remoteModeIgnoresLocalSavedSamples() = runTest {
        val snapshot = configuredSnapshot()
        val historyStore = FakeHistoryStore().apply {
            emitRecentBatterySamples(snapshot.address, listOf(batterySample(id = 1, timestampMs = 99L, socPercent = 12.0)))
        }
        val link = FakeRemoteMeterLink()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = remoteViewModel(
            viewModelScope,
            FakeDeviceStore(initial = snapshot, initialRemoteAddress = "h:8765"),
            link,
            historyStore = historyStore,
        )

        try {
            viewModel.onAppVisibilityChanged(CollectionVisibility.FOREGROUND)
            advanceUntilIdle()

            assertNotEquals(12.0, viewModel.uiState.value.latestReading.socPercent, 0.001)
            assertNull(viewModel.uiState.value.lastReadingTimestampMs)
            assertEquals(emptyList<String>(), historyStore.recentObservations)
            assertEquals(emptyList<KmfBatterySampleEntity>(), viewModel.recentBatterySamples.value)
            // The saved meter stays known (Device tab) but is not the displayed meter.
            assertEquals(snapshot.address, viewModel.uiState.value.savedMeterAddress)
            assertNull(viewModel.uiState.value.selectedDevice)

            link.events.emit(TimedBleEvent(BleEvent.Connecting(remoteMeter()), 500L))
            link.events.emit(remoteLine(FULL_A, atMs = 1_000L))
            advanceUntilIdle()

            assertEquals(listOf("remote:h:8765"), viewModel.recentBatterySamples.value.map { it.deviceAddress })
            assertEquals(emptyList<String>(), historyStore.recentObservations)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun enteringRemoteModeDropsAlreadyShownBluetoothSamples() = runTest {
        val snapshot = configuredSnapshot()
        val historyStore = FakeHistoryStore().apply {
            emitRecentBatterySamples(snapshot.address, listOf(batterySample(id = 1, timestampMs = 99L, socPercent = 12.0)))
        }
        val store = FakeDeviceStore(initial = snapshot)
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = remoteViewModel(viewModelScope, store, FakeRemoteMeterLink(), historyStore = historyStore)

        try {
            advanceUntilIdle()
            assertEquals(12.0, viewModel.uiState.value.latestReading.socPercent, 0.001)
            assertEquals(1, viewModel.recentBatterySamples.value.size)

            store.setRemoteAddress("h:8765")
            advanceUntilIdle()

            assertEquals(0.0, viewModel.uiState.value.latestReading.socPercent, 0.001)
            assertNull(viewModel.uiState.value.lastReadingTimestampMs)
            assertEquals(emptyList<KmfBatterySampleEntity>(), viewModel.recentBatterySamples.value)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun remoteChartPointsComeFromLiveAFrames() = runTest {
        val link = FakeRemoteMeterLink()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = remoteViewModel(viewModelScope, FakeDeviceStore(initialRemoteAddress = "h:8765"), link)

        try {
            viewModel.onAppVisibilityChanged(CollectionVisibility.FOREGROUND)
            link.events.emit(TimedBleEvent(BleEvent.Connecting(remoteMeter()), 500L))
            link.events.emit(remoteLine(FULL_A, atMs = 1_000L))
            link.events.emit(remoteLine(B_LINE, atMs = 1_100L))
            link.events.emit(remoteLine(C_LINE, atMs = 1_200L))
            assertEquals(listOf(1_000L), viewModel.recentBatterySamples.value.map { it.timestampMs })

            link.events.emit(remoteLine(SECOND_A, atMs = 2_000L))
            advanceUntilIdle()

            val points = viewModel.recentBatterySamples.value
            assertEquals(listOf(2_000L, 1_000L), points.map { it.timestampMs })
            val newest = points.first()
            assertEquals("remote:h:8765", newest.deviceAddress)
            assertEquals(0L, newest.id)
            assertEquals(13.32, newest.voltageV, 0.001)
            assertEquals(5.3, newest.currentA, 0.001)
            assertEquals(70.596, newest.powerW, 0.001)
            assertEquals(viewModel.uiState.value.latestReading.powerW, newest.powerW, 0.001)
            assertEquals(viewModel.uiState.value.latestReading.socPercent, newest.socPercent, 0.001)
            assertTrue(newest.hasAFrame)
            assertTrue(newest.hasBFrame)
            assertTrue(newest.hasCFrame)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun remoteChartKeepsOnlyTheChartWindowAndTheSampleLimit() = runTest {
        val link = FakeRemoteMeterLink()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = remoteViewModel(viewModelScope, FakeDeviceStore(initialRemoteAddress = "h:8765"), link)

        try {
            viewModel.onAppVisibilityChanged(CollectionVisibility.FOREGROUND)
            link.events.emit(TimedBleEvent(BleEvent.Connecting(remoteMeter()), 0L))
            link.events.emit(remoteLine(FULL_A, atMs = 1_000L))
            link.events.emit(remoteLine(FULL_A, atMs = 2_000L))
            assertEquals(2, viewModel.recentBatterySamples.value.size)

            // Exactly one window later the first point is the oldest one still inside it.
            link.events.emit(remoteLine(FULL_A, atMs = 1_000L + NowScreenReducer.CHART_WINDOW_MS))
            assertEquals(
                listOf(1_000L + NowScreenReducer.CHART_WINDOW_MS, 2_000L, 1_000L),
                viewModel.recentBatterySamples.value.map { it.timestampMs },
            )
            link.events.emit(remoteLine(FULL_A, atMs = 1_001L + NowScreenReducer.CHART_WINDOW_MS))
            assertEquals(
                listOf(1_001L + NowScreenReducer.CHART_WINDOW_MS, 1_000L + NowScreenReducer.CHART_WINDOW_MS, 2_000L),
                viewModel.recentBatterySamples.value.map { it.timestampMs },
            )

            // A burst of frames inside one window never grows past the live sample limit.
            val burstStartMs = 10_000_000L
            repeat(2_100) { index -> link.events.emit(remoteLine(FULL_A, atMs = burstStartMs + index)) }
            val samples = viewModel.recentBatterySamples.value
            assertEquals(2_048, samples.size)
            assertEquals(burstStartMs + 2_099, samples.first().timestampMs)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun connectRemoteStopsBluetoothCollectionAndSavesAddress() = runTest {
        val snapshot = configuredSnapshot()
        val store = FakeDeviceStore(initial = snapshot)
        store.enableAutonomousCollection(snapshot, associationId = 7)
        val commander = FakeCollectionServiceCommander()
        val link = FakeRemoteMeterLink()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = remoteViewModel(viewModelScope, store, link, commander = commander)

        try {
            viewModel.onAppVisibilityChanged(CollectionVisibility.FOREGROUND)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.autonomousCollectionConfigured)

            viewModel.connectRemote(" H:8765 ")
            advanceUntilIdle()

            assertTrue("stop" in commander.commands)
            assertEquals("h:8765", store.remoteAddressState.value)
            assertEquals(listOf("disable"), store.operationLog)
            val state = viewModel.uiState.value
            assertFalse(state.autonomousCollectionConfigured)
            assertTrue(state.remoteMode)
            assertEquals("h:8765", state.remoteAddress)
            assertNull(state.remoteAddressError)
            assertEquals(listOf("start h:8765"), link.calls)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun connectRemoteEndsAnActiveTestConnection() = runTest {
        val snapshot = configuredSnapshot()
        val store = FakeDeviceStore(initial = snapshot)
        val repository = FakeBleRepository()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = remoteViewModel(viewModelScope, store, FakeRemoteMeterLink(), repository = repository)

        try {
            advanceUntilIdle()
            viewModel.startTestConnection()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.testConnectionActive)

            viewModel.connectRemote("h")
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.testConnectionActive)
            assertEquals(1, repository.disconnectCalls)
            assertEquals("h:8765", store.remoteAddressState.value)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun connectRemoteRejectsInvalidAddress() = runTest {
        val snapshot = configuredSnapshot()
        val store = FakeDeviceStore(initial = snapshot)
        store.enableAutonomousCollection(snapshot, associationId = 7)
        val commander = FakeCollectionServiceCommander()
        val link = FakeRemoteMeterLink()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = remoteViewModel(viewModelScope, store, link, commander = commander)

        try {
            advanceUntilIdle()
            val before = viewModel.uiState.value

            viewModel.connectRemote("fe80::1")
            advanceUntilIdle()

            assertEquals(
                before.copy(remoteAddressError = "Enter a host name or IP, optionally with :port"),
                viewModel.uiState.value,
            )
            assertNull(store.remoteAddressState.value)
            assertEquals(emptyList<String>(), commander.commands)
            assertEquals(emptyList<String>(), store.operationLog)
            assertEquals(emptyList<String>(), link.calls)

            viewModel.connectRemote("home-phone")
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.remoteAddressError)
            assertEquals("home-phone:8765", store.remoteAddressState.value)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun disconnectRemoteReturnsToBluetoothWithCollectionOff() = runTest {
        val snapshot = configuredSnapshot()
        val historyStore = FakeHistoryStore().apply {
            emitRecentBatterySamples(snapshot.address, listOf(batterySample(id = 1, timestampMs = 99L)))
        }
        val store = FakeDeviceStore(initial = snapshot, initialRemoteAddress = "h:8765")
        val commander = FakeCollectionServiceCommander()
        val link = FakeRemoteMeterLink()
        val relay = MutableStateFlow<CollectionState?>(stoppedState())
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = remoteViewModel(
            viewModelScope, store, link, historyStore = historyStore, commander = commander, relay = relay,
        )

        try {
            viewModel.onAppVisibilityChanged(CollectionVisibility.FOREGROUND)
            link.state.value = collectionState(CollectionPhase.COLLECTING)
            link.events.emit(TimedBleEvent(BleEvent.Connecting(remoteMeter()), 500L))
            link.events.emit(remoteLine(FULL_A, atMs = 1_000L))
            advanceUntilIdle()
            assertEquals(CollectionPhase.COLLECTING, viewModel.uiState.value.collectionState?.phase)
            assertEquals(1, viewModel.recentBatterySamples.value.size)
            assertEquals(emptyList<String>(), historyStore.recentObservations)

            viewModel.disconnectRemote()
            advanceUntilIdle()

            assertEquals("stop", link.calls.last())
            assertNull(store.remoteAddressState.value)
            val state = viewModel.uiState.value
            assertFalse(state.remoteMode)
            assertNull(state.remoteAddress)
            assertEquals(snapshot.address, state.selectedDevice?.address)
            assertEquals(CollectionPhase.STOPPED, state.collectionState?.phase)
            assertFalse(state.autonomousCollectionConfigured)
            // Back on the saved meter's history, as after a normal launch with collection off.
            assertEquals(listOf(snapshot.address), historyStore.recentObservations)
            assertEquals(listOf(99L), viewModel.recentBatterySamples.value.map { it.timestampMs })
            assertEquals(99L, state.lastReadingTimestampMs)
            assertEquals(57.9, state.latestReading.socPercent, 0.001)
            // Collection stays off: nothing starts the service or the Bluetooth link.
            assertEquals(emptyList<String>(), commander.commands)
            assertEquals(emptyList<String>(), store.operationLog)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun meterActionsAreIgnoredInRemoteMode() = runTest {
        val snapshot = configuredSnapshot()
        val store = FakeDeviceStore(initial = snapshot, initialRemoteAddress = "h:8765").apply {
            setFirstRunCompleted(true)
        }
        val gateway = FakeCompanionAssociationGateway().apply { associatedAddresses += snapshot.address }
        val commander = FakeCollectionServiceCommander(store.operationLog)
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = BleViewModel(
            repository = FakeBleRepository(),
            deviceStore = store,
            historyStore = FakeHistoryStore(),
            companionAssociationGateway = gateway,
            collectionServiceCommander = commander,
            scope = viewModelScope,
            remoteLink = FakeRemoteMeterLink(),
        )

        try {
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.remoteMode)
            val before = viewModel.uiState.value

            viewModel.forgetMeter()
            viewModel.changeMeter()
            viewModel.pairWithAndroid()
            viewModel.requestAutonomousCollection()
            advanceUntilIdle()

            assertEquals(snapshot, store.snapshot.first())
            assertEquals(emptyList<String>(), store.operationLog)
            assertEquals(emptyList<String>(), commander.commands)
            assertEquals(emptyList<String>(), gateway.disassociatedAddresses)
            assertEquals(emptyList<String>(), gateway.associationRequests)
            assertEquals("h:8765", store.remoteAddressState.value)
            assertEquals(before, viewModel.uiState.value)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun retryInRemoteModeReconnectsLink() = runTest {
        val commander = FakeCollectionServiceCommander()
        val link = FakeRemoteMeterLink()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = remoteViewModel(
            viewModelScope, FakeDeviceStore(initialRemoteAddress = "h:8765"), link, commander = commander,
        )

        try {
            viewModel.onAppVisibilityChanged(CollectionVisibility.FOREGROUND)
            advanceUntilIdle()

            viewModel.retryCollectionNow()

            assertEquals(listOf("start h:8765", "reconnectNow"), link.calls)
            assertEquals(emptyList<String>(), commander.commands)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun remoteLinkFollowsAppVisibility() = runTest {
        val link = FakeRemoteMeterLink()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = remoteViewModel(viewModelScope, FakeDeviceStore(initialRemoteAddress = "h:8765"), link)

        try {
            advanceUntilIdle()
            // The address is known but the app is not visible yet: no link.
            assertEquals(emptyList<String>(), link.calls)

            viewModel.onAppVisibilityChanged(CollectionVisibility.FOREGROUND)
            assertEquals(listOf("start h:8765"), link.calls)

            link.events.emit(TimedBleEvent(BleEvent.Connecting(remoteMeter()), 500L))
            link.events.emit(remoteLine(FULL_A, atMs = 1_000L))
            viewModel.onAppVisibilityChanged(CollectionVisibility.BACKGROUND)
            assertEquals(listOf("start h:8765", "stop"), link.calls)
            // The last values stay on screen while the app is in the background.
            assertEquals(180.0, viewModel.uiState.value.latestReading.capacityAh, 0.001)
            assertEquals(1_000L, viewModel.uiState.value.lastReadingTimestampMs)

            viewModel.onAppVisibilityChanged(CollectionVisibility.FOREGROUND)
            assertEquals(listOf("start h:8765", "stop", "start h:8765"), link.calls)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun bluetoothModeNeverTouchesTheRemoteLink() = runTest {
        val link = FakeRemoteMeterLink()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = remoteViewModel(viewModelScope, FakeDeviceStore(initial = configuredSnapshot()), link)

        try {
            viewModel.onAppVisibilityChanged(CollectionVisibility.FOREGROUND)
            advanceUntilIdle()
            viewModel.onAppVisibilityChanged(CollectionVisibility.BACKGROUND)
            viewModel.retryCollectionNow()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.remoteMode)
            assertEquals(emptyList<String>(), link.calls)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun changingTheRemoteAddressRestartsTheLinkAndDropsTheOldMeterValues() = runTest {
        val store = FakeDeviceStore(initialRemoteAddress = "h:8765")
        val link = FakeRemoteMeterLink()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = remoteViewModel(viewModelScope, store, link)

        try {
            viewModel.onAppVisibilityChanged(CollectionVisibility.FOREGROUND)
            link.events.emit(TimedBleEvent(BleEvent.Connecting(remoteMeter()), 500L))
            link.events.emit(remoteLine(FULL_A, atMs = 1_000L))
            advanceUntilIdle()
            assertEquals(1, viewModel.recentBatterySamples.value.size)

            viewModel.connectRemote("other:9000")
            advanceUntilIdle()

            assertEquals(listOf("start h:8765", "start other:9000"), link.calls)
            assertEquals("other:9000", viewModel.uiState.value.remoteAddress)
            assertEquals(0.0, viewModel.uiState.value.latestReading.socPercent, 0.001)
            assertNull(viewModel.uiState.value.lastReadingTimestampMs)
            assertEquals(emptyList<KmfBatterySampleEntity>(), viewModel.recentBatterySamples.value)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun storedRemoteAddressThatDoesNotParseMeansBluetoothMode() = runTest {
        val snapshot = configuredSnapshot()
        val historyStore = FakeHistoryStore().apply {
            emitRecentBatterySamples(snapshot.address, listOf(batterySample(id = 1, timestampMs = 99L)))
        }
        val link = FakeRemoteMeterLink()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = remoteViewModel(
            viewModelScope,
            FakeDeviceStore(initial = snapshot, initialRemoteAddress = "fe80::1"),
            link,
            historyStore = historyStore,
        )

        try {
            viewModel.onAppVisibilityChanged(CollectionVisibility.FOREGROUND)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.remoteMode)
            assertNull(state.remoteAddress)
            assertEquals(emptyList<String>(), link.calls)
            assertEquals(snapshot.address, state.selectedDevice?.address)
            assertEquals(1, viewModel.recentBatterySamples.value.size)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun launchReportsConfigurationLoadedOnlyTogetherWithTheRemoteAddress() = runTest {
        val remoteAddresses = MutableSharedFlow<String?>(extraBufferCapacity = 1)
        val store = FakeDeviceStore(remoteAddressFlow = remoteAddresses)
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = remoteViewModel(viewModelScope, store, FakeRemoteMeterLink())
        val seen = mutableListOf<BleUiState>()
        val collector = viewModelScope.launch { viewModel.uiState.collect { seen += it } }

        try {
            runCurrent()
            // The configuration is known but the mode is not yet: the Now tab must not decide anything.
            assertFalse(viewModel.uiState.value.collectionConfigurationLoaded)

            remoteAddresses.emit("h:8765")
            advanceUntilIdle()

            val loaded = seen.filter { it.collectionConfigurationLoaded }
            assertTrue(loaded.isNotEmpty())
            assertTrue("configuration loaded without the remote address: $loaded", loaded.all { it.remoteMode })
        } finally {
            collector.cancel()
            viewModelScope.cancel()
        }
    }

    @Test
    fun collectionStateComesFromRemoteLinkInRemoteMode() = runTest {
        val link = FakeRemoteMeterLink()
        val relay = MutableStateFlow<CollectionState?>(stoppedState())
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = remoteViewModel(
            viewModelScope, FakeDeviceStore(initialRemoteAddress = "h:8765"), link, relay = relay,
        )

        try {
            viewModel.onAppVisibilityChanged(CollectionVisibility.FOREGROUND)
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.collectionState)

            link.state.value = collectionState(CollectionPhase.COLLECTING)
            advanceUntilIdle()
            assertEquals(CollectionPhase.COLLECTING, viewModel.uiState.value.collectionState?.phase)

            // A Bluetooth service state change must not leak into the remote phase.
            relay.value = collectionState(CollectionPhase.WAITING_FOR_DEVICE)
            advanceUntilIdle()
            assertEquals(CollectionPhase.COLLECTING, viewModel.uiState.value.collectionState?.phase)

            // A stopped link (app in the background) keeps the last remote state on screen.
            link.state.value = null
            advanceUntilIdle()
            assertEquals(CollectionPhase.COLLECTING, viewModel.uiState.value.collectionState?.phase)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun bluetoothEventsIgnoredInRemoteMode() = runTest {
        val repository = FakeBleRepository()
        val link = FakeRemoteMeterLink()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = remoteViewModel(
            viewModelScope, FakeDeviceStore(initialRemoteAddress = "h:8765"), link, repository = repository,
        )

        try {
            viewModel.onAppVisibilityChanged(CollectionVisibility.FOREGROUND)
            link.events.emit(TimedBleEvent(BleEvent.Connecting(remoteMeter()), 500L))
            link.events.emit(remoteLine(FULL_A, atMs = 1_000L))
            advanceUntilIdle()
            val before = viewModel.uiState.value

            val device = ScannedDevice("KMF", "AA:BB:CC:DD:EE:FF", -55, emptyList())
            repository.events.emit(BleEvent.Connecting(device))
            repository.events.emit(
                BleEvent.NotificationReceived(UUID.randomUUID(), ":A=1200,100,0,60,5000,10000,\n".encodeToByteArray()),
            )
            repository.events.emit(BleEvent.Disconnected("closing"))
            advanceUntilIdle()

            assertEquals(before, viewModel.uiState.value)
            assertEquals(180.0, viewModel.uiState.value.latestReading.capacityAh, 0.001)
            assertEquals("remote:h:8765", viewModel.uiState.value.selectedDevice?.address)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun remoteResumeKeepsLastReadingUntilReplay() = runTest {
        val link = FakeRemoteMeterLink()
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = remoteViewModel(viewModelScope, FakeDeviceStore(initialRemoteAddress = "h:8765"), link)

        try {
            viewModel.onAppVisibilityChanged(CollectionVisibility.FOREGROUND)
            link.events.emit(TimedBleEvent(BleEvent.Connecting(remoteMeter()), 500L))
            link.events.emit(TimedBleEvent(BleEvent.Connected(remoteMeter()), 600L))
            link.events.emit(remoteLine(FULL_A, atMs = 1_000L))
            link.events.emit(remoteLine(B_LINE, atMs = 1_100L))
            link.events.emit(remoteLine(C_LINE, atMs = 1_200L))
            advanceUntilIdle()
            val shown = viewModel.uiState.value.latestReading
            assertEquals(180.0, shown.capacityAh, 0.001)

            viewModel.onAppVisibilityChanged(CollectionVisibility.BACKGROUND)
            viewModel.onAppVisibilityChanged(CollectionVisibility.FOREGROUND)
            link.events.emit(TimedBleEvent(BleEvent.Connecting(remoteMeter()), 9_000L))
            advanceUntilIdle()

            val resyncing = viewModel.uiState.value
            assertEquals(shown, resyncing.latestReading)
            assertEquals(1_200L, resyncing.lastReadingTimestampMs)
            assertFalse(resyncing.hasAFrame)
            assertFalse(resyncing.hasBFrame)

            // A short A frame on the resumed stream merges onto the kept capacity, never onto zeros.
            link.events.emit(remoteLine(SHORT_A, atMs = 9_500L))
            advanceUntilIdle()
            assertEquals(180.0, viewModel.uiState.value.latestReading.capacityAh, 0.001)
            assertEquals(13.29, viewModel.uiState.value.latestReading.voltageV, 0.001)
            assertEquals(84.74, viewModel.uiState.value.latestReading.socPercent, 0.01)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun bluetoothActionsAreIgnoredInRemoteMode() = runTest {
        val snapshot = configuredSnapshot()
        val commander = FakeCollectionServiceCommander()
        val repository = FakeBleRepository()
        val gateway = FakeCompanionAssociationGateway().apply { associatedAddresses += snapshot.address }
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = BleViewModel(
            repository = repository,
            deviceStore = FakeDeviceStore(initial = snapshot, initialRemoteAddress = "h:8765"),
            historyStore = FakeHistoryStore(),
            companionAssociationGateway = gateway,
            collectionServiceCommander = commander,
            scope = viewModelScope,
            remoteLink = FakeRemoteMeterLink(),
        )

        try {
            advanceUntilIdle()

            viewModel.startCollectionFromNow()
            viewModel.enableBackgroundCollection()
            viewModel.startTestConnection()
            viewModel.startScan()
            viewModel.connect(ScannedDevice("KMF", snapshot.address, -55, emptyList()))
            advanceUntilIdle()

            assertEquals(emptyList<String>(), commander.commands)
            assertEquals(emptyList<String>(), gateway.observedAddresses)
            assertEquals(emptyList<String>(), gateway.associationRequests)
            assertEquals(emptyList<Pair<ScannedDevice, DeviceSnapshot?>>(), repository.connectCalls)
            assertEquals(0, repository.startScanCalls)
            assertFalse(viewModel.uiState.value.testConnectionActive)
            assertEquals(AutonomousCollectionStatus.NOT_CONFIGURED, viewModel.uiState.value.autonomousCollectionStatus)
        } finally {
            viewModelScope.cancel()
        }
    }

    @Test
    fun shareOnNetworkAndSharingStatusReachUiState() = runTest {
        val store = FakeDeviceStore()
        val sharing = MutableStateFlow<SharingStatus?>(null)
        val viewModelScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val viewModel = remoteViewModel(viewModelScope, store, FakeRemoteMeterLink(), sharing = sharing)

        try {
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.shareOnNetwork)
            assertNull(viewModel.uiState.value.sharingStatus)

            viewModel.setShareOnNetwork(true)
            advanceUntilIdle()
            assertTrue(store.shareOnNetworkState.value)
            assertTrue(viewModel.uiState.value.shareOnNetwork)

            val running = SharingStatus(running = true, port = 8765, addresses = listOf("192.168.1.20"), viewers = 2)
            sharing.value = running
            advanceUntilIdle()
            assertEquals(running, viewModel.uiState.value.sharingStatus)

            viewModel.setShareOnNetwork(false)
            advanceUntilIdle()
            assertFalse(store.shareOnNetworkState.value)
            assertFalse(viewModel.uiState.value.shareOnNetwork)
        } finally {
            viewModelScope.cancel()
        }
    }

    private fun remoteViewModel(
        scope: CoroutineScope,
        store: FakeDeviceStore,
        link: FakeRemoteMeterLink,
        repository: FakeBleRepository = FakeBleRepository(),
        historyStore: FakeHistoryStore = FakeHistoryStore(),
        commander: FakeCollectionServiceCommander? = null,
        relay: StateFlow<CollectionState?>? = null,
        sharing: StateFlow<SharingStatus?>? = null,
    ): BleViewModel = BleViewModel(
        repository = repository,
        deviceStore = store,
        historyStore = historyStore,
        collectionServiceCommander = commander,
        scope = scope,
        collectionStatus = relay,
        remoteLink = link,
        sharingStatus = sharing,
    )

    private fun remoteMeter(): ScannedDevice = ScannedDevice(
        name = "Home meter",
        address = checkNotNull(RemoteAddress.parse("h:8765")).id,
        rssi = 0,
        serviceUuids = emptyList(),
    )

    private fun remoteLine(line: String, atMs: Long): TimedBleEvent =
        TimedBleEvent(BleEvent.NotificationReceived(UUID(0L, 0L), line.encodeToByteArray()), atMs)

    private fun collectionState(phase: CollectionPhase): CollectionState = CollectionState(
        phase = phase,
        visibility = CollectionVisibility.FOREGROUND,
    )

    private fun stoppedState(): CollectionState = collectionState(CollectionPhase.STOPPED)

    private fun batterySample(
        id: Long,
        timestampMs: Long,
        socPercent: Double = 57.9,
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
            socPercent = socPercent,
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

        override fun connect(device: ScannedDevice, preferred: DeviceSnapshot?, autoConnect: Boolean) {
            connectCalls += device to preferred
        }

        val writes = mutableListOf<String>()

        override fun write(bytes: ByteArray): Boolean {
            writes += bytes.toString(Charsets.US_ASCII)
            return true
        }

        val mtuRequests = mutableListOf<Int>()

        override fun requestMtu(mtu: Int): Boolean {
            mtuRequests += mtu
            return true
        }

        override fun disconnect() {
            disconnectCalls += 1
        }
    }

    private class FakeDeviceStore(
        initial: DeviceSnapshot? = null,
        configurationFlow: Flow<CollectionConfiguration?>? = null,
        initialRemoteAddress: String? = null,
        remoteAddressFlow: Flow<String?>? = null,
    ) : DeviceSnapshotStore {
        private val snapshots = MutableStateFlow(initial)
        private val configurations = MutableStateFlow<CollectionConfiguration?>(null)
        private var firstAutonomousEnableGate: CompletableDeferred<Unit>? = null
        var saved: DeviceSnapshot? = null
        var autonomousEnableCalls = 0
            private set

        override val snapshot: Flow<DeviceSnapshot?> = snapshots
        override val collectionConfiguration: Flow<CollectionConfiguration?> = configurationFlow ?: configurations

        override suspend fun save(snapshot: DeviceSnapshot) {
            saved = snapshot
            snapshots.value = snapshot
        }

        val operationLog = mutableListOf<String>()

        override suspend fun enableAutonomousCollection(snapshot: DeviceSnapshot, associationId: Int?) {
            autonomousEnableCalls += 1
            firstAutonomousEnableGate?.await()
            save(snapshot)
            configurations.value = CollectionConfiguration(snapshot, associationId, true)
        }

        fun gateFirstAutonomousEnable() {
            firstAutonomousEnableGate = CompletableDeferred()
        }

        fun releaseFirstAutonomousEnable() {
            checkNotNull(firstAutonomousEnableGate).complete(Unit)
        }

        override suspend fun disableAutonomousCollection() {
            operationLog += "disable"
            configurations.value = null
        }

        override suspend fun clear() {
            saved = null
            snapshots.value = null
            configurations.value = null
            firstRunCompletedState.value = false
        }

        val firstRunCompletedState = MutableStateFlow(false)
        override val firstRunCompleted: Flow<Boolean> = firstRunCompletedState

        override suspend fun setFirstRunCompleted(completed: Boolean) {
            firstRunCompletedState.value = completed
        }

        val shareOnNetworkState = MutableStateFlow(false)
        override val shareOnNetwork: Flow<Boolean> = shareOnNetworkState

        override suspend fun setShareOnNetwork(enabled: Boolean) {
            shareOnNetworkState.value = enabled
        }

        val remoteAddressState = MutableStateFlow(initialRemoteAddress)
        override val remoteAddress: Flow<String?> = remoteAddressFlow ?: remoteAddressState

        override suspend fun setRemoteAddress(address: String?) {
            remoteAddressState.value = address
        }
    }

    private class FakeCompanionAssociationGateway : CompanionAssociationGateway {
        val observedAddresses = mutableListOf<String>()
        val stoppedObservedAddresses = mutableListOf<String>()
        val associationRequests = mutableListOf<String>()
        private var onAssociationCreated: ((Int) -> Unit)? = null

        override fun isSupported(): Boolean = true

        override fun requestAssociation(
            address: String,
            onPending: (android.content.IntentSender) -> Unit,
            onAssociationCreated: (Int) -> Unit,
            onFailure: (Throwable) -> Unit,
        ) {
            associationRequests += address
            this.onAssociationCreated = onAssociationCreated
        }

        fun completeAssociationDirectly(associationId: Int) {
            checkNotNull(onAssociationCreated).invoke(associationId)
        }

        override fun associationIdFromResult(successfulResultData: android.content.Intent): Int? = null

        override fun startPresenceObservation(address: String) {
            observedAddresses += address
        }

        override fun stopPresenceObservation(address: String) {
            stoppedObservedAddresses += address
        }

        val associatedAddresses = mutableSetOf<String>()

        override fun isAssociated(address: String): Boolean = address in associatedAddresses

        val disassociatedAddresses = mutableListOf<String>()

        override fun disassociate(address: String) {
            disassociatedAddresses += address
            associatedAddresses -= address
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

        override fun reconnectNow() {
            commands += "reconnectNow"
        }
    }

    private class FakeRemoteMeterLink : RemoteMeterLinkContract {
        override val events = MutableSharedFlow<TimedBleEvent>(extraBufferCapacity = 64)
        override val state = MutableStateFlow<CollectionState?>(null)

        /** "start host:port", "stop" and "reconnectNow" in call order. */
        val calls = mutableListOf<String>()

        override fun start(address: RemoteAddress) {
            calls += "start ${address.display}"
        }

        override fun stop() {
            calls += "stop"
        }

        override fun reconnectNow() {
            calls += "reconnectNow"
        }
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

        /** Every address the view model asked recent samples for, in order. */
        val recentObservations = mutableListOf<String>()

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
        ): Flow<List<KmfBatterySampleEntity>> {
            recentObservations += deviceAddress
            return recentBatterySamplesByDevice.getOrPut(deviceAddress) { MutableStateFlow(emptyList()) }
        }

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
