package com.juncehome.lifepo4ble.ui.trends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.juncehome.lifepo4ble.AppLog
import com.juncehome.lifepo4ble.data.KmfBatteryHistoryExportInfo
import com.juncehome.lifepo4ble.data.KmfBatteryHistoryExportStore
import com.juncehome.lifepo4ble.data.KmfTrendsStore
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Loads saved history for the Trends tab. Reloads when the range changes, when samples are
 * inserted or deleted, and on a ticker so the Live window slides.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrendsViewModel(
    private val store: KmfTrendsStore,
    private val exportStore: KmfBatteryHistoryExportStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope? = null,
    private val refreshMs: Long = 30_000L,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private val workScope: CoroutineScope
        get() = scope ?: viewModelScope

    private val _range = MutableStateFlow(TrendsRange.Day)
    val range: StateFlow<TrendsRange> = _range.asStateFlow()

    private val _metric = MutableStateFlow(TrendsMetric.Power)
    val metric: StateFlow<TrendsMetric> = _metric.asStateFlow()

    private val _data = MutableStateFlow<TrendsData?>(null)
    val data: StateFlow<TrendsData?> = _data.asStateFlow()

    private val _exportStatus = MutableStateFlow<TrendsExportStatus>(TrendsExportStatus.Idle)
    val exportStatus: StateFlow<TrendsExportStatus> = _exportStatus.asStateFlow()

    private val _lastExport = MutableStateFlow<KmfBatteryHistoryExportInfo?>(null)
    val lastExport: StateFlow<KmfBatteryHistoryExportInfo?> = _lastExport.asStateFlow()

    private val manualReload = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        AppLog.d("Trends: view model created refreshMs=$refreshMs", TAG)
        workScope.launch {
            val ticker = flow {
                while (true) {
                    emit(Unit)
                    delay(refreshMs)
                }
            }
            val triggers = merge(store.observeChanges().map { }, ticker, manualReload)
            combine(_range, triggers) { range, _ -> range }.collectLatest { load(it) }
        }
        workScope.launch { refreshLastExport() }
    }

    fun selectRange(range: TrendsRange) {
        if (_range.value == range) return
        AppLog.d("Trends: range ${_range.value.label} -> ${range.label}", TAG)
        _data.value = null
        _range.value = range
    }

    fun selectMetric(metric: TrendsMetric) {
        if (_metric.value == metric) return
        AppLog.d("Trends: metric ${_metric.value.label} -> ${metric.label}", TAG)
        _metric.value = metric
    }

    fun exportCsv() {
        if (_exportStatus.value is TrendsExportStatus.Running) {
            AppLog.d("Trends: export ignored, already running", TAG)
            return
        }
        _exportStatus.value = TrendsExportStatus.Running(0)
        workScope.launch {
            AppLog.d("Trends: export started", TAG)
            try {
                val result = exportStore.export { rows -> _exportStatus.value = TrendsExportStatus.Running(rows) }
                _exportStatus.value = TrendsExportStatus.Done(result.rowCount)
                AppLog.d("Trends: export done rows=${result.rowCount} file=${result.file.name}", TAG)
                refreshLastExport()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.e("Trends: export failed", error, TAG)
                _exportStatus.value = TrendsExportStatus.Failed(error.message ?: error::class.java.simpleName)
            }
        }
    }

    fun clearHistory() {
        workScope.launch {
            AppLog.d("Trends: clear saved history requested", TAG)
            try {
                exportStore.clear()
                _exportStatus.value = TrendsExportStatus.Cleared
                AppLog.d("Trends: saved history cleared", TAG)
                refreshLastExport()
                manualReload.tryEmit(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.e("Trends: clear failed", error, TAG)
                _exportStatus.value = TrendsExportStatus.ClearFailed(error.message ?: error::class.java.simpleName)
            }
        }
    }

    private suspend fun refreshLastExport() {
        try {
            _lastExport.value = exportStore.lastExport()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.e("Trends: reading last export failed", error, TAG)
        }
    }

    private suspend fun load(range: TrendsRange) {
        val now = clock()
        val started = System.nanoTime()
        try {
            val buckets = store.readBuckets(now - range.windowMs, now, range.bucketMs)
            val latest = store.readLatest(0L, now)
            val counters = store.readCounterBuckets(
                TrendsEnergySlots.energyReadStartMs(range, now, zone),
                now,
                TrendsEnergyMath.FINE_BUCKET_MS,
            )
            val summary = store.readSummary()
            _data.value = TrendsData(now, buckets, latest, counters, summary)
            AppLog.d(
                "Trends: loaded range=${range.label} buckets=${buckets.size} counterBuckets=${counters.size} " +
                    "count=${summary.count} in ${(System.nanoTime() - started) / 1_000_000} ms",
                TAG,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.e("Trends: load failed range=${range.label}", error, TAG)
        }
    }

    companion object {
        private const val TAG = "KMF-BLE"

        fun factory(store: KmfTrendsStore, exportStore: KmfBatteryHistoryExportStore): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(TrendsViewModel::class.java)) {
                        return TrendsViewModel(store, exportStore) as T
                    }
                    error("Unknown ViewModel class ${modelClass.name}")
                }
            }
    }
}
