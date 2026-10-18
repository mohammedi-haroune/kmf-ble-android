package com.juncehome.lifepo4ble.ui.trends

import com.juncehome.lifepo4ble.data.KmfBatteryHistoryExport
import com.juncehome.lifepo4ble.data.KmfBatteryHistoryExportInfo
import com.juncehome.lifepo4ble.data.KmfBatteryHistoryExportStore
import com.juncehome.lifepo4ble.data.KmfBatterySampleEntity
import com.juncehome.lifepo4ble.data.KmfCounterBucket
import com.juncehome.lifepo4ble.data.KmfHistorySummary
import com.juncehome.lifepo4ble.data.KmfSampleBucketRow
import com.juncehome.lifepo4ble.data.KmfTrendsStore
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrendsViewModelTest {
    private class FakeStore : KmfTrendsStore {
        val changes = MutableSharedFlow<Long?>(extraBufferCapacity = 8)
        val bucketCalls = mutableListOf<Triple<Long, Long, Long>>()
        var counterFrom = -1L
        var count = 7L

        override fun observeChanges(): Flow<Long?> = changes

        override suspend fun readBuckets(fromMs: Long, toMs: Long, bucketMs: Long): List<KmfSampleBucketRow> {
            bucketCalls += Triple(fromMs, toMs, bucketMs)
            return emptyList()
        }

        override suspend fun readLatest(fromMs: Long, toMs: Long): KmfBatterySampleEntity? = null

        override suspend fun readCounterBuckets(fromMs: Long, toMs: Long, bucketMs: Long): List<KmfCounterBucket> {
            counterFrom = fromMs
            return emptyList()
        }

        override suspend fun readSummary() = KmfHistorySummary(count, null)
    }

    private class FakeExport : KmfBatteryHistoryExportStore {
        var cleared = 0
        var failure: Exception? = null
        val gate = CompletableDeferred<Unit>()
        var info: KmfBatteryHistoryExportInfo? = null

        override suspend fun export(onProgress: (Int) -> Unit): KmfBatteryHistoryExport {
            onProgress(100)
            gate.await()
            failure?.let { throw it }
            info = KmfBatteryHistoryExportInfo("battery-history.csv", 1L, 2L)
            return KmfBatteryHistoryExport(File("battery-history.csv"), 250)
        }

        override suspend fun clear() {
            cleared += 1
        }

        override suspend fun lastExport() = info
    }

    private val now = 1_800_000_000_000L

    private fun TestScope.model(store: FakeStore, export: FakeExport) = TrendsViewModel(
        store = store,
        exportStore = export,
        clock = { now },
        scope = backgroundScope,
        refreshMs = 60_000L,
        zone = ZoneId.of("UTC"),
    )

    @Test
    fun loadsOnStart() = runTest {
        val store = FakeStore()
        val vm = model(store, FakeExport())
        runCurrent()

        val data = vm.data.value
        assertNotNull(data)
        assertEquals(now, data!!.nowMs)
        assertEquals(7L, data.summary.count)
        val (from, to, bucket) = store.bucketCalls.last()
        assertEquals(now - TrendsRange.Day.windowMs, from)
        assertEquals(now, to)
        assertEquals(TrendsRange.Day.bucketMs, bucket)
        assertEquals(TrendsEnergySlots.energyReadStartMs(TrendsRange.Day, now, ZoneId.of("UTC")), store.counterFrom)
    }

    @Test
    fun reloadsOnRangeChangeAndChangeEmission() = runTest {
        val store = FakeStore()
        val vm = model(store, FakeExport())
        runCurrent()

        vm.selectRange(TrendsRange.Week)
        runCurrent()
        assertEquals(TrendsRange.Week.bucketMs, store.bucketCalls.last().third)
        assertNotNull(vm.data.value)

        val before = store.bucketCalls.size
        store.count = 9
        store.changes.tryEmit(1L)
        runCurrent()
        assertTrue(store.bucketCalls.size > before)
        assertEquals(9L, vm.data.value!!.summary.count)
    }

    @Test
    fun tickerReloadsPeriodically() = runTest {
        val store = FakeStore()
        model(store, FakeExport())
        runCurrent()
        val before = store.bucketCalls.size
        testScheduler.advanceTimeBy(60_001L)
        runCurrent()
        assertTrue(store.bucketCalls.size > before)
    }

    @Test
    fun metricSelectionUpdatesState() = runTest {
        val vm = model(FakeStore(), FakeExport())
        vm.selectMetric(TrendsMetric.Voltage)
        assertEquals(TrendsMetric.Voltage, vm.metric.value)
    }

    @Test
    fun exportReportsProgressThenDone() = runTest {
        val export = FakeExport()
        val vm = model(FakeStore(), export)
        runCurrent()

        vm.exportCsv()
        vm.exportCsv() // ignored while running
        runCurrent()
        assertEquals(TrendsExportStatus.Running(100), vm.exportStatus.value)

        export.gate.complete(Unit)
        runCurrent()
        assertEquals(TrendsExportStatus.Done(250), vm.exportStatus.value)
        assertNotNull(vm.lastExport.value)
    }

    @Test
    fun exportFailureIsReported() = runTest {
        val export = FakeExport().apply { failure = IllegalStateException("disk full"); gate.complete(Unit) }
        val vm = model(FakeStore(), export)
        vm.exportCsv()
        runCurrent()
        assertEquals(TrendsExportStatus.Failed("disk full"), vm.exportStatus.value)
        assertNull(vm.lastExport.value)
    }

    @Test
    fun clearHistoryClearsAndReloads() = runTest {
        val store = FakeStore()
        val export = FakeExport()
        val vm = model(store, export)
        runCurrent()
        val before = store.bucketCalls.size
        store.count = 0

        vm.clearHistory()
        runCurrent()

        assertEquals(1, export.cleared)
        assertEquals(TrendsExportStatus.Cleared, vm.exportStatus.value)
        assertTrue(store.bucketCalls.size > before)
        assertEquals(0L, vm.data.value!!.summary.count)
    }
}
