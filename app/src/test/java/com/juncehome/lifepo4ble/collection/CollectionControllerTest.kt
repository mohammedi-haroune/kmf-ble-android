package com.juncehome.lifepo4ble.collection

import com.juncehome.lifepo4ble.ble.BleEvent
import com.juncehome.lifepo4ble.ble.BleRepositoryContract
import com.juncehome.lifepo4ble.ble.GattProfile
import com.juncehome.lifepo4ble.ble.ScannedDevice
import com.juncehome.lifepo4ble.data.DeviceSnapshot
import com.juncehome.lifepo4ble.data.KmfBatterySampleEntity
import com.juncehome.lifepo4ble.data.KmfFrameEventEntity
import com.juncehome.lifepo4ble.data.KmfHistoryStore
import com.juncehome.lifepo4ble.data.KmfNotificationObservation
import com.juncehome.lifepo4ble.data.KmfWriteObservation
import com.juncehome.lifepo4ble.protocol.KmfFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionControllerTest {
    @Test
    fun backgroundReadyConnectionPollsEveryFiveMinutes() = runTest {
        val fixture = fixture(testScheduler)
        try {
            fixture.controller.start(configuration)
            fixture.emitReadyAAndBFrames()
            fixture.controller.setVisibility(CollectionVisibility.BACKGROUND)

            advanceTimeBy(299_999)
            assertEquals(0, fixture.repository.countWrites(":C\n"))
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, fixture.repository.countWrites(":C\n"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun userStopPreventsReconnect() = runTest {
        val fixture = fixture(testScheduler)
        try {
            fixture.controller.start(configuration)
            fixture.controller.stopByUser()
            fixture.repository.emit(BleEvent.Disconnected("manual"))
            runCurrent()

            assertEquals(1, fixture.repository.connectCalls.size)
            assertEquals(CollectionPhase.STOPPED, fixture.controller.state.value.phase)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun bootstrapStopsAfterParsedAAndBFrames() = runTest {
        val fixture = fixture(testScheduler)
        try {
            fixture.controller.start(configuration)
            fixture.repository.emit(BleEvent.ServicesDiscovered(profile))
            advanceTimeBy(1_500)
            runCurrent()
            assertEquals(100, fixture.repository.requestedMtus.single())
            assertEquals(1, fixture.repository.countWrites(":*"))

            fixture.repository.emit(BleEvent.NotificationReceived(profile.notifyUuid, ":A=1326,1080,1,5793,57930,1000\n".encodeToByteArray()))
            fixture.repository.emit(BleEvent.NotificationReceived(profile.notifyUuid, ":B=1\n".encodeToByteArray()))
            advanceTimeBy(10_000)

            assertEquals(CollectionPhase.COLLECTING, fixture.controller.state.value.phase)
            assertEquals(1, fixture.repository.countWrites(":*"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun foregroundReadyConnectionPollsEveryThirtySeconds() = runTest {
        val fixture = fixture(testScheduler)
        try {
            fixture.controller.start(configuration)
            fixture.emitReadyAAndBFrames()

            advanceTimeBy(29_999)
            assertEquals(0, fixture.repository.countWrites(":C\n"))
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, fixture.repository.countWrites(":C\n"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun appearanceForAnotherAddressDoesNotConnect() = runTest {
        val fixture = fixture(testScheduler)
        try {
            fixture.controller.start(configuration)
            fixture.controller.onDeviceDisappeared(configuration.snapshot.address)
            fixture.controller.onDeviceAppeared("11:22:33:44:55:66")

            assertEquals(1, fixture.repository.connectCalls.size)
            assertEquals(1, fixture.repository.disconnectCalls)
            assertEquals(CollectionPhase.WAITING_FOR_DEVICE, fixture.controller.state.value.phase)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun readyConnectionResetsReconnectBackoffToOneSecond() = runTest {
        val fixture = fixture(testScheduler)
        try {
            fixture.controller.start(configuration)
            fixture.repository.emit(BleEvent.Disconnected("out of range"))
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(2, fixture.repository.connectCalls.size)

            fixture.emitReadyAAndBFrames()
            assertNull(fixture.controller.state.value.nextReconnectDelayMs)
            fixture.repository.emit(BleEvent.Disconnected("out of range"))
            advanceTimeBy(999)
            assertEquals(2, fixture.repository.connectCalls.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(3, fixture.repository.connectCalls.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun notificationPersistsParsedBatteryReadingForConfiguredMeter() = runTest {
        val fixture = fixture(testScheduler)
        try {
            fixture.controller.start(configuration)
            fixture.repository.emit(BleEvent.ServicesDiscovered(profile))
            fixture.repository.emit(
                BleEvent.NotificationReceived(
                    profile.notifyUuid,
                    ":A=1326,1080,1,5793,57930,1000\n".encodeToByteArray(),
                )
            )
            runCurrent()

            val saved = fixture.history.notifications.single()
            assertEquals(configuration.snapshot.address, saved.deviceAddress)
            assertEquals(profile.serviceUuid.toString(), saved.serviceUuid)
            assertEquals(profile.notifyUuid.toString(), saved.notifyUuid)
            assertEquals(13.26, saved.mergedReading.voltageV, 0.001)
            assertEquals(100.0, saved.mergedReading.capacityAh, 0.001)
            assertEquals(listOf(KmfFrame.A::class), saved.frames.map { it::class })
        } finally {
            fixture.close()
        }
    }

    private fun fixture(testScheduler: TestCoroutineScheduler): Fixture {
        val repository = FakeBleRepository()
        val history = FakeHistoryStore()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        return Fixture(
            repository = repository,
            history = history,
            scope = scope,
            controller = CollectionControllerImpl(repository, history, scope, clock = { 42L }),
        )
    }

    private data class Fixture(
        val repository: FakeBleRepository,
        val history: FakeHistoryStore,
        val scope: CoroutineScope,
        val controller: CollectionController,
    ) {
        suspend fun emitReadyAAndBFrames() {
            repository.emit(BleEvent.ServicesDiscovered(profile))
            repository.emit(BleEvent.NotificationReceived(profile.notifyUuid, ":A=1326,1080,1,5793,57930,1000\n".encodeToByteArray()))
            repository.emit(BleEvent.NotificationReceived(profile.notifyUuid, ":B=1\n".encodeToByteArray()))
        }

        fun close() {
            controller.close()
            scope.cancel()
        }
    }

    private class FakeBleRepository : BleRepositoryContract {
        override val events = MutableSharedFlow<BleEvent>(extraBufferCapacity = 32)
        val connectCalls = mutableListOf<Pair<ScannedDevice, DeviceSnapshot?>>()
        val writes = mutableListOf<String>()
        val requestedMtus = mutableListOf<Int>()
        var disconnectCalls = 0

        override fun startScan() = Unit
        override fun stopScan() = Unit
        override fun connect(device: ScannedDevice, preferred: DeviceSnapshot?) {
            connectCalls += device to preferred
        }
        override fun write(bytes: ByteArray): Boolean {
            writes += bytes.toString(Charsets.US_ASCII)
            return true
        }
        override fun requestMtu(mtu: Int): Boolean {
            requestedMtus += mtu
            return true
        }
        override fun disconnect() {
            disconnectCalls += 1
        }
        suspend fun emit(event: BleEvent) {
            events.emit(event)
        }
        fun countWrites(value: String): Int = writes.count { it == value }
    }

    private class FakeHistoryStore : KmfHistoryStore {
        val notifications = mutableListOf<KmfNotificationObservation>()
        override suspend fun resetSession(deviceAddress: String) = Unit
        override suspend fun recordNotification(observation: KmfNotificationObservation) {
            notifications += observation
        }
        override suspend fun recordWrite(observation: KmfWriteObservation) = Unit
        override fun observeLatestBatterySample(deviceAddress: String): Flow<KmfBatterySampleEntity?> = emptyFlow()
        override fun observeRecentBatterySamples(deviceAddress: String, limit: Int): Flow<List<KmfBatterySampleEntity>> = emptyFlow()
        override fun observeRecentFrameEvents(deviceAddress: String, limit: Int): Flow<List<KmfFrameEventEntity>> = emptyFlow()
    }

    private companion object {
        val configuration = CollectionConfiguration(
            snapshot = DeviceSnapshot(
                address = "AA:BB:CC:DD:EE:FF",
                name = "KMF",
                serviceUuid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                notifyUuid = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                writeUuid = "cccccccc-cccc-cccc-cccc-cccccccccccc",
            ),
            associationId = 7,
            autonomousEnabled = true,
        )
        val profile = GattProfile(
            serviceUuid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            notifyUuid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
            writeUuid = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
            usesIndications = false,
        )
    }
}
