package com.juncehome.lifepo4ble.data

import com.juncehome.lifepo4ble.protocol.KmfFrame
import com.juncehome.lifepo4ble.protocol.KmfReading
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KmfHistoryRepositoryTest {
    @Test
    fun recordNotificationPersistsFrameEvidenceAndMergedSample() = runTest {
        val frameEventDao = FakeKmfFrameEventDao()
        val batterySampleDao = FakeKmfBatterySampleDao()
        val repository = KmfHistoryRepository(frameEventDao, batterySampleDao)

        repository.recordNotification(
            KmfNotificationObservation(
                timestampMs = 123L,
                deviceAddress = "AA:BB:CC:DD:EE:FF",
                deviceName = "KMF",
                serviceUuid = "service",
                notifyUuid = "notify",
                writeUuid = "write",
                connectionState = "READY",
                bytes = ":A=1326,1080,0,5793,\n:C=1820,486,0,0,0,0,\n".encodeToByteArray(),
                frames = listOf(
                    KmfFrame.A(
                        voltageV = 13.26,
                        currentA = -1.08,
                        powerW = -14.3208,
                        charging = false,
                        minutesRemaining = 5793,
                        remainingAh = 104.274,
                        capacityAh = 0.0,
                        socPercent = 0.0,
                        status = "Discharging",
                        rawFields = listOf(1326, 1080, 0, 5793),
                    ),
                    KmfFrame.C(
                        chargeKwh = 1.820,
                        dischargeKwh = 0.486,
                        rawFields = listOf(1820, 486, 0, 0, 0, 0),
                    ),
                ),
                mergedReading = KmfReading(
                    voltageV = 13.26,
                    currentA = -1.08,
                    powerW = -14.3208,
                    charging = false,
                    minutesRemaining = 5793,
                    remainingAh = 104.274,
                    capacityAh = 0.0,
                    socPercent = 0.0,
                    status = "Discharging",
                    chargeKwh = 1.820,
                    dischargeKwh = 0.486,
                ),
            )
        )

        assertEquals(listOf("A", "C"), frameEventDao.inserted.map { it.frameType })
        assertEquals(
            listOf("1326,1080,0,5793", "1820,486,0,0,0,0"),
            frameEventDao.inserted.map { it.parsedFieldsCsv },
        )

        val sample = batterySampleDao.inserted.single()
        assertEquals("AA:BB:CC:DD:EE:FF", sample.deviceAddress)
        assertEquals(13.26, sample.voltageV, 0.001)
        assertEquals(-1.08, sample.currentA, 0.001)
        assertEquals("1326,1080,0,5793", sample.rawAFieldsCsv)
        assertNull(sample.rawBFieldsCsv)
        assertEquals("1820,486,0,0,0,0", sample.rawCFieldsCsv)
        assertTrue(sample.hasAFrame)
        assertTrue(sample.hasCFrame)
    }

    @Test
    fun recordNotificationStoresUnknownInboundPacketWithoutBatterySample() = runTest {
        val frameEventDao = FakeKmfFrameEventDao()
        val batterySampleDao = FakeKmfBatterySampleDao()
        val repository = KmfHistoryRepository(frameEventDao, batterySampleDao)

        repository.recordNotification(
            KmfNotificationObservation(
                timestampMs = 456L,
                deviceAddress = "AA:BB:CC:DD:EE:FF",
                deviceName = "KMF",
                serviceUuid = "service",
                notifyUuid = "notify",
                writeUuid = "write",
                connectionState = "READY",
                bytes = "garbage".encodeToByteArray(),
                frames = emptyList(),
                mergedReading = KmfReading(),
            )
        )

        val event = frameEventDao.inserted.single()
        assertEquals("UNKNOWN", event.frameType)
        assertNull(event.parsedFieldsCsv)
        assertTrue(batterySampleDao.inserted.isEmpty())
    }

    private class FakeKmfFrameEventDao : KmfFrameEventDao {
        val inserted = mutableListOf<KmfFrameEventEntity>()

        override suspend fun insert(entity: KmfFrameEventEntity) {
            inserted += entity
        }

        override fun observeRecent(deviceAddress: String, limit: Int): Flow<List<KmfFrameEventEntity>> =
            emptyFlow()
    }

    private class FakeKmfBatterySampleDao : KmfBatterySampleDao {
        val inserted = mutableListOf<KmfBatterySampleEntity>()

        override suspend fun insert(entity: KmfBatterySampleEntity) {
            inserted += entity
        }

        override fun observeLatest(deviceAddress: String): Flow<KmfBatterySampleEntity?> = emptyFlow()

        override fun observeRecent(deviceAddress: String, limit: Int): Flow<List<KmfBatterySampleEntity>> =
            emptyFlow()
    }
}
