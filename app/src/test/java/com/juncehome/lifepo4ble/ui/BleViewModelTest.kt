package com.juncehome.lifepo4ble.ui

import com.juncehome.lifepo4ble.ble.BleEvent
import com.juncehome.lifepo4ble.ble.BleRepositoryContract
import com.juncehome.lifepo4ble.ble.GattProfile
import com.juncehome.lifepo4ble.ble.ScannedDevice
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BleViewModelTest {
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

    private class FakeBleRepository : BleRepositoryContract {
        override val events: MutableSharedFlow<BleEvent> = MutableSharedFlow(extraBufferCapacity = 300)
        var connectedDevice: ScannedDevice? = null

        override fun startScan() = Unit

        override fun stopScan() = Unit

        override fun connect(device: ScannedDevice, preferred: DeviceSnapshot?) {
            connectedDevice = device
        }

        override fun write(bytes: ByteArray): Boolean = true

        override fun requestMtu(mtu: Int): Boolean = true

        override fun disconnect() = Unit
    }

    private class FakeDeviceStore : DeviceSnapshotStore {
        private val snapshots = MutableStateFlow<DeviceSnapshot?>(null)
        var saved: DeviceSnapshot? = null

        override val snapshot: Flow<DeviceSnapshot?> = snapshots

        override suspend fun save(snapshot: DeviceSnapshot) {
            saved = snapshot
            snapshots.value = snapshot
        }

        override suspend fun clear() {
            saved = null
            snapshots.value = null
        }
    }

    private class FakeHistoryStore : KmfHistoryStore {
        val resetSessions = mutableListOf<String>()
        val notifications = mutableListOf<KmfNotificationObservation>()
        val writes = mutableListOf<KmfWriteObservation>()

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
        ): Flow<List<KmfBatterySampleEntity>> = emptyFlow()

        override fun observeRecentFrameEvents(
            deviceAddress: String,
            limit: Int,
        ): Flow<List<KmfFrameEventEntity>> = emptyFlow()
    }
}
