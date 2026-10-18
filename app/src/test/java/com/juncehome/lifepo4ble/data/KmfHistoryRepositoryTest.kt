package com.juncehome.lifepo4ble.data

import com.juncehome.lifepo4ble.protocol.KmfFrame
import com.juncehome.lifepo4ble.protocol.KmfReading
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
                bytes = ":A=1326,1080,0,5793,\n:B=1\n:C=1820,486,0,0,0,0,\n".encodeToByteArray(),
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
                    bFrame,
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

        assertTrue(frameEventDao.inserted.isEmpty())

        val sample = batterySampleDao.inserted.single()
        assertEquals("AA:BB:CC:DD:EE:FF", sample.deviceAddress)
        assertEquals(13.26, sample.voltageV, 0.001)
        assertEquals(-1.08, sample.currentA, 0.001)
        assertEquals("1326,1080,0,5793", sample.rawAFieldsCsv)
        assertEquals("1", sample.rawBFieldsCsv)
        assertEquals("1820,486,0,0,0,0", sample.rawCFieldsCsv)
        assertTrue(sample.hasAFrame)
        assertTrue(sample.hasBFrame)
        assertTrue(sample.hasCFrame)
    }

    @Test
    fun recordNotificationSkipsSamplesUntilSessionHasABAndC() = runTest {
        val batterySampleDao = FakeKmfBatterySampleDao()
        val repository = KmfHistoryRepository(FakeKmfFrameEventDao(), batterySampleDao)

        repository.recordNotification(observation(1L, listOf(bFrame), KmfReading()))
        repository.recordNotification(observation(2L, listOf(cFrame), KmfReading(chargeKwh = 1.82, dischargeKwh = 0.486)))
        assertTrue(batterySampleDao.inserted.isEmpty())

        repository.recordNotification(observation(3L, listOf(aFrame), completeReading))

        val sample = batterySampleDao.inserted.single()
        assertEquals(3L, sample.timestampMs)
        assertEquals(13.26, sample.voltageV, 0.001)
        assertEquals(1.82, sample.chargeKwh, 0.001)
    }

    @Test
    fun recordNotificationWithoutBFrameSkipsSample() = runTest {
        val batterySampleDao = FakeKmfBatterySampleDao()
        val repository = KmfHistoryRepository(FakeKmfFrameEventDao(), batterySampleDao)

        repository.recordNotification(observation(1L, listOf(aFrame, cFrame), completeReading))

        assertTrue(batterySampleDao.inserted.isEmpty())
    }

    @Test
    fun resetSessionRequiresABAndCAgain() = runTest {
        val batterySampleDao = FakeKmfBatterySampleDao()
        val repository = KmfHistoryRepository(FakeKmfFrameEventDao(), batterySampleDao)
        repository.recordNotification(observation(1L, listOf(aFrame, bFrame, cFrame), completeReading))
        assertEquals(1, batterySampleDao.inserted.size)

        repository.resetSession(DEVICE)
        repository.recordNotification(observation(2L, listOf(bFrame), KmfReading()))
        repository.recordNotification(observation(3L, listOf(aFrame), completeReading.copy(chargeKwh = 0.0, dischargeKwh = 0.0)))
        assertEquals(1, batterySampleDao.inserted.size)

        repository.recordNotification(observation(4L, listOf(cFrame), completeReading))
        assertEquals(listOf(1L, 4L), batterySampleDao.inserted.map { it.timestampMs })
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

        assertTrue(frameEventDao.inserted.isEmpty())
        assertTrue(batterySampleDao.inserted.isEmpty())
    }

    private fun observation(timestampMs: Long, frames: List<KmfFrame>, reading: KmfReading) =
        KmfNotificationObservation(
            timestampMs = timestampMs,
            deviceAddress = DEVICE,
            deviceName = "KMF",
            serviceUuid = "service",
            notifyUuid = "notify",
            writeUuid = "write",
            connectionState = "COLLECTING",
            bytes = ByteArray(0),
            frames = frames,
            mergedReading = reading,
        )

    private companion object {
        const val DEVICE = "AA:BB:CC:DD:EE:FF"

        val aFrame = KmfFrame.A(
            voltageV = 13.26,
            currentA = -1.08,
            powerW = -14.3208,
            charging = false,
            minutesRemaining = 5793,
            remainingAh = 104.274,
            capacityAh = 100.0,
            socPercent = 100.0,
            status = "Discharging",
            rawFields = listOf(1326, 1080, 0, 5793, 57930, 1000),
        )
        val bFrame = KmfFrame.B(fields = listOf(1))
        val cFrame = KmfFrame.C(chargeKwh = 1.820, dischargeKwh = 0.486, rawFields = listOf(1820, 486, 0, 0, 0, 0))
        val completeReading = KmfReading(
            voltageV = 13.26,
            currentA = -1.08,
            powerW = -14.3208,
            minutesRemaining = 5793,
            remainingAh = 104.274,
            capacityAh = 100.0,
            socPercent = 100.0,
            status = "Discharging",
            chargeKwh = 1.820,
            dischargeKwh = 0.486,
        )
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

        override suspend fun readAfterId(afterId: Long, limit: Int): List<KmfBatterySampleEntity> =
            emptyList()

        override suspend fun deleteAll() = Unit

        override fun observeLatestId(): Flow<Long?> = emptyFlow()

        override suspend fun count(): Long = 0L

        override suspend fun earliestTimestampMs(): Long? = null

        override suspend fun readBetween(fromMs: Long, toMs: Long, limit: Int): List<KmfBatterySampleEntity> =
            emptyList()

        override suspend fun readLatestBetween(fromMs: Long, toMs: Long): KmfBatterySampleEntity? = null

        override suspend fun readBuckets(fromMs: Long, toMs: Long, bucketMs: Long): List<KmfSampleBucketRow> =
            emptyList()

        override suspend fun readFirstCounters(fromMs: Long, toMs: Long, bucketMs: Long): List<KmfCounterEdgeRow> =
            emptyList()

        override suspend fun readLastCounters(fromMs: Long, toMs: Long, bucketMs: Long): List<KmfCounterEdgeRow> =
            emptyList()
    }
}
