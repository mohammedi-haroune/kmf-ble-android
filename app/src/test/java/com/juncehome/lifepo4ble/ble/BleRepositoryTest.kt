package com.juncehome.lifepo4ble.ble

import com.juncehome.lifepo4ble.data.DeviceSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BleRepositoryTest {
    @Test
    fun repositoryStopsScanBeforeConnecting() = runTest {
        val scanner = FakeBleScanner()
        val session = FakeBleSession()
        val repository = BleRepository(scanner, session, scope = this)
        val device = ScannedDevice(
            name = "KMF",
            address = "AA:BB:CC:DD:EE:FF",
            rssi = -60,
            serviceUuids = emptyList(),
        )

        repository.startScan()
        advanceUntilIdle()
        repository.connect(device, preferred = null)
        advanceUntilIdle()

        assertTrue(scanner.stopCalled)
        assertEquals(device, session.connectedDevice)
    }

    @Test
    fun repositoryEmitsScanResultsFromScanner() = runTest {
        val device = ScannedDevice(
            name = "BTG",
            address = "11:22:33:44:55:66",
            rssi = -70,
            serviceUuids = emptyList(),
        )
        val scanner = FakeBleScanner(results = flowOf(listOf(device)))
        val session = FakeBleSession()
        val repository = BleRepository(scanner, session, scope = this)
        val events = mutableListOf<BleEvent>()

        val collectJob = launchCollect(repository.events, events)
        advanceUntilIdle()
        repository.startScan()
        advanceUntilIdle()

        assertEquals(BleEvent.Scanning, events.first())
        assertEquals(BleEvent.ScanResults(listOf(device)), events.last())
        collectJob.cancel()
    }

    private class FakeBleScanner(
        private val results: Flow<List<ScannedDevice>>? = null,
    ) : BleScanner {
        var stopCalled = false

        override fun scan(): Flow<List<ScannedDevice>> = results ?: callbackFlow {
            awaitClose { stopCalled = true }
        }
    }

    private class FakeBleSession : BleSession {
        var connectedDevice: ScannedDevice? = null

        override fun connect(device: ScannedDevice, preferred: DeviceSnapshot?): Flow<BleEvent> {
            connectedDevice = device
            return emptyFlow()
        }

        override fun write(bytes: ByteArray): Boolean = true

        override fun disconnect() = Unit
    }

    private fun CoroutineScope.launchCollect(
        events: SharedFlow<BleEvent>,
        sink: MutableList<BleEvent>,
    ): Job = launch {
        events.collect { sink += it }
    }
}
