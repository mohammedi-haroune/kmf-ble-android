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

    private val _offset = MutableStateFlow(0)

    /** Whole windows the chart is shifted back from now (0 = ends now). */
    val offset: StateFlow<Int> = _offset.asStateFlow()

    private val _custom = MutableStateFlow<TrendsCustomRange?>(null)

    /** Applied custom range, or null until Custom was first selected. */
    val custom: StateFlow<TrendsCustomRange?> = _custom.asStateFlow()

    private val _metric = MutableStateFlow(TrendsMetric.Power)
    val metric: StateFlow<TrendsMetric> = _metric.asStateFlow()

    private val _data = MutableStateFlow<TrendsData?>(null)
    val data: StateFlow<TrendsData?> = _data.asStateFlow()

    private val _exportStatus = MutableStateFlow<TrendsExportStatus>(TrendsExportStatus.Idle)
    val exportStatus: StateFlow<TrendsExportStatus> = _exportStatus.asStateFlow()

    private val _lastExport = MutableStateFlow<KmfBatteryHistoryExportInfo?>(null)
    val lastExport: StateFlow<KmfBatteryHistoryExportInfo?> = _lastExport.asStateFlow()

    private val manualReload = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Volatile
    private var forceReload = false

    private fun requestReload() {
        forceReload = true
        manualReload.tryEmit(Unit)
    }

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
            var lastKey: Triple<TrendsRange, Int, TrendsCustomRange?>? = null
            combine(_range, _offset, _custom, triggers) { range, offset, custom, _ -> Triple(range, offset, custom) }
                .collectLatest { key ->
                    val (range, offset, custom) = key
                    // A past window (stepped back or custom) has no new samples to show; reload it only
                    // when the selection changes or a manual reload (export/clear) asks for it.
                    val historical = offset > 0 || range == TrendsRange.Custom
                    if (historical && key == lastKey && !forceReload && _data.value != null) return@collectLatest
                    forceReload = false
                    lastKey = key
                    load(range, offset, custom)
                }
        }
        workScope.launch { refreshLastExport() }
    }

    /** Selecting a preset always resets the window to end now; tapping the active preset while shifted does too. */
    fun selectRange(range: TrendsRange) {
        if (_range.value == range && _offset.value == 0) return
        AppLog.d("Trends: range ${_range.value.label} -> ${range.label} offset=${_offset.value} -> 0", TAG)
        _data.value = null
        if (range == TrendsRange.Custom && _custom.value == null) {
            _custom.value = TrendsCustomRanges.default(clock())
            AppLog.d("Trends: custom range defaulted to last 2 d", TAG)
        }
        _offset.value = 0
        _range.value = range
    }

    /** Shifts the window back by one window length. Ignored for Live and Custom. */
    fun stepEarlier() {
        val range = _range.value
        if (range == TrendsRange.Live || range == TrendsRange.Custom) {
            AppLog.d("Trends: earlier ignored for ${range.label}", TAG)
            return
        }
        val earliest = _data.value?.summary?.earliestMs
        val window = _data.value?.window
        if (earliest == null || (window != null && window.startMs <= earliest)) {
            AppLog.d("Trends: earlier ignored, no older history earliest=$earliest", TAG)
            return
        }
        _data.value = null
        _offset.value += 1
        AppLog.d("Trends: window earlier offset=${_offset.value} range=${range.label}", TAG)
    }

    /** Shifts the window forward by one window length; stops at now. */
    fun stepLater() {
        if (_range.value == TrendsRange.Live || _range.value == TrendsRange.Custom || _offset.value <= 0) {
            AppLog.d("Trends: later ignored range=${_range.value.label} offset=${_offset.value}", TAG)
            return
        }
        _data.value = null
        _offset.value -= 1
        AppLog.d("Trends: window later offset=${_offset.value} range=${_range.value.label}", TAG)
    }

    /** Applies a validated custom range and switches to Custom. Returns the validation error, if any. */
    fun applyCustom(fromMs: Long, toMs: Long): TrendsCustomError? {
        val error = TrendsCustomRanges.validate(fromMs, toMs, clock())
        if (error != null) {
            AppLog.d("Trends: custom range rejected from=$fromMs to=$toMs reason=$error", TAG)
            return error
        }
        val next = TrendsCustomRange(fromMs, toMs)
        if (_range.value == TrendsRange.Custom && _custom.value == next && _offset.value == 0) {
            // Same window is already loaded; clearing data here would leave "Loading" with no reload.
            AppLog.d("Trends: custom range unchanged from=$fromMs to=$toMs, keeping loaded data", TAG)
            return null
        }
        AppLog.d("Trends: custom range applied from=$fromMs to=$toMs spanMs=${toMs - fromMs}", TAG)
        _data.value = null
        _offset.value = 0
        _custom.value = TrendsCustomRange(fromMs, toMs)
        _range.value = TrendsRange.Custom
        return null
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
                requestReload()
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

    private suspend fun load(range: TrendsRange, offset: Int, custom: TrendsCustomRange?) {
        val now = clock()
        val started = System.nanoTime()
        try {
            val window = TrendsWindows.resolve(range, offset, custom, now)
            val layout = TrendsEnergySlots.layout(window, zone)
            val buckets = store.readBuckets(window.startMs, window.endMs, window.bucketMs)
            val latest = store.readLatest(0L, window.endMs)
            val counters = store.readCounterBuckets(
                TrendsEnergySlots.energyReadStartMs(layout, window.startMs),
                window.endMs,
                layout.fineMs,
            )
            val summary = store.readSummary()
            _data.value = TrendsData(now, buckets, latest, counters, summary, window)
            AppLog.d(
                "Trends: loaded range=${range.label} offset=$offset window=${window.startMs}..${window.endMs} " +
                    "bucketMs=${window.bucketMs} buckets=${buckets.size} counterBuckets=${counters.size} " +
                    "slots=${layout.starts.size} count=${summary.count} in ${(System.nanoTime() - started) / 1_000_000} ms",
                TAG,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.e("Trends: load failed range=${range.label} offset=$offset", error, TAG)
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
