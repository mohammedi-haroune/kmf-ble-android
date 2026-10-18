package com.juncehome.lifepo4ble.collection

import com.juncehome.lifepo4ble.AppLog
import com.juncehome.lifepo4ble.ble.BleEvent
import com.juncehome.lifepo4ble.ble.BleRepositoryContract
import com.juncehome.lifepo4ble.ble.GattProfile
import com.juncehome.lifepo4ble.ble.ScannedDevice
import com.juncehome.lifepo4ble.data.DeviceSnapshot
import com.juncehome.lifepo4ble.data.KmfHistoryStore
import com.juncehome.lifepo4ble.data.KmfNotificationObservation
import com.juncehome.lifepo4ble.protocol.KmfFrame
import com.juncehome.lifepo4ble.protocol.KmfLineParser
import com.juncehome.lifepo4ble.protocol.KmfReading
import com.juncehome.lifepo4ble.protocol.KmfReadingMerger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class CollectionVisibility { FOREGROUND, BACKGROUND }

/** Follow-up polling cadence of the collection service; the Device tab shows these values. */
object CollectionCadence {
    /** While an activity is visible. */
    const val FOREGROUND_POLL_INTERVAL_MS = 30_000L

    /** While the app is in the background. */
    const val BACKGROUND_POLL_INTERVAL_MS = 300_000L
}

enum class CollectionPhase { STOPPED, WAITING_FOR_DEVICE, CONNECTING, BOOTSTRAPPING, COLLECTING, ERROR }

data class CollectionState(
    val phase: CollectionPhase,
    val visibility: CollectionVisibility,
    val deviceAddress: String? = null,
    val nextReconnectDelayMs: Long? = null,
    val error: String? = null,
    /** Wall-clock time of the scheduled retry while [phase] is ERROR; null when none is pending. */
    val nextReconnectAtMs: Long? = null,
    /** 1-based number of the reconnect attempt in the current failure streak; 0 while healthy. */
    val reconnectAttempt: Int = 0,
    /** When the link first dropped in the current failure streak; cleared once collecting again. */
    val linkDroppedAtMs: Long? = null,
)

interface CollectionController {
    val state: StateFlow<CollectionState>

    fun start(configuration: CollectionConfiguration)

    fun setVisibility(visibility: CollectionVisibility)

    fun onDeviceAppeared(address: String)

    fun onDeviceDisappeared(address: String)

    fun stopByUser()

    /** User-requested immediate reconnect from the Now tab; only acts while a retry is pending (ERROR). */
    fun reconnectNow()

    fun close()
}

/**
 * Owns collection policy only. The caller supplies the BLE transport and the scope that schedules work,
 * allowing the same policy to run from a foreground service or a companion-device callback.
 */
class CollectionControllerImpl(
    private val repository: BleRepositoryContract,
    private val historyStore: KmfHistoryStore,
    scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : CollectionController {
    private val controllerJob = SupervisorJob(scope.coroutineContext[Job])
    private val workScope = CoroutineScope(scope.coroutineContext + controllerJob)
    private val mutableState = MutableStateFlow(
        CollectionState(
            phase = CollectionPhase.STOPPED,
            // A controller is created by the foreground service, which can run without an
            // activity. Only a live activity may promote its polling cadence to foreground.
            visibility = CollectionVisibility.BACKGROUND,
        )
    )
    override val state: StateFlow<CollectionState> = mutableState

    private var configuration: CollectionConfiguration? = null
    private var deviceUnavailable = false
    private var stoppedByUser = false
    private var reconnectAttempt = 0
    private var failureStreak = 0
    private var linkDroppedAtMs: Long? = null
    private var parser = KmfLineParser()
    private var reading = KmfReading()
    private var profile: GattProfile? = null
    private var hasAFrame = false
    private var hasBFrame = false
    private var hasCFrame = false
    private var bootstrapJob: Job? = null
    private var pollJob: Job? = null
    private var reconnectJob: Job? = null

    private val eventJob = workScope.launch {
        repository.events.collect(::handleEvent)
    }

    override fun start(configuration: CollectionConfiguration) {
        cancelCollectionJobs()
        this.configuration = configuration
        stoppedByUser = false
        deviceUnavailable = false
        reconnectAttempt = 0
        failureStreak = 0
        linkDroppedAtMs = null
        resetProtocolSession()

        if (!configuration.autonomousEnabled) {
            this.configuration = null
            updateState(CollectionPhase.STOPPED)
            return
        }
        connectConfiguredDevice()
    }

    override fun setVisibility(visibility: CollectionVisibility) {
        mutableState.value = mutableState.value.copy(visibility = visibility)
        if (mutableState.value.phase == CollectionPhase.COLLECTING) {
            startPolling()
        }
    }

    override fun onDeviceAppeared(address: String) {
        val activeConfiguration = configuration ?: return
        if (stoppedByUser || address != activeConfiguration.snapshot.address) {
            return
        }
        deviceUnavailable = false
        if (mutableState.value.phase == CollectionPhase.WAITING_FOR_DEVICE ||
            mutableState.value.phase == CollectionPhase.ERROR
        ) {
            cancelReconnect()
            connectConfiguredDevice()
        }
    }

    override fun onDeviceDisappeared(address: String) {
        if (address != configuration?.snapshot?.address) {
            return
        }
        deviceUnavailable = true
        cancelCollectionJobs()
        repository.disconnect()
        updateState(CollectionPhase.WAITING_FOR_DEVICE)
    }

    override fun stopByUser() {
        stoppedByUser = true
        configuration = null
        cancelCollectionJobs()
        repository.disconnect()
        updateState(CollectionPhase.STOPPED)
    }

    override fun reconnectNow() {
        val phase = mutableState.value.phase
        if (configuration == null || stoppedByUser) {
            AppLog.w("collection reconnectNow ignored: collection is stopped (phase=$phase)", TAG)
            return
        }
        if (phase != CollectionPhase.ERROR) {
            AppLog.d("collection reconnectNow ignored in phase=$phase", TAG)
            return
        }
        AppLog.d("collection reconnectNow: cancelling scheduled retry attempt=$failureStreak and connecting", TAG)
        cancelReconnect()
        deviceUnavailable = false
        connectConfiguredDevice()
    }

    override fun close() {
        stopByUser()
        eventJob.cancel()
        controllerJob.cancel()
    }

    private suspend fun handleEvent(event: BleEvent) {
        val activeConfiguration = configuration ?: return
        if (stoppedByUser) {
            return
        }
        when (event) {
            is BleEvent.Connecting -> {
                if (event.device.address != activeConfiguration.snapshot.address) return
                cancelReconnect()
                resetProtocolSession()
                persistHistoryReset(event.device.address)
                updateState(CollectionPhase.CONNECTING)
            }
            is BleEvent.Connected -> {
                if (event.device.address != activeConfiguration.snapshot.address) return
                updateState(CollectionPhase.CONNECTING)
            }
            is BleEvent.ServicesDiscovered -> {
                resetProtocolSession()
                profile = event.profile
                updateState(CollectionPhase.BOOTSTRAPPING)
                startBootstrap()
            }
            is BleEvent.NotificationReceived -> handleNotification(event, activeConfiguration)
            is BleEvent.Disconnected -> {
                cancelBootstrapAndPolling()
                resetProtocolSession()
                if (deviceUnavailable) {
                    updateState(CollectionPhase.WAITING_FOR_DEVICE)
                } else {
                    scheduleReconnect(event.reason ?: "BLE disconnected")
                }
            }
            is BleEvent.Error -> {
                cancelBootstrapAndPolling()
                resetProtocolSession()
                if (deviceUnavailable) {
                    updateState(CollectionPhase.WAITING_FOR_DEVICE)
                } else {
                    scheduleReconnect(event.message)
                }
            }
            is BleEvent.Scanning,
            is BleEvent.ScanResults,
            is BleEvent.WriteQueued,
            is BleEvent.WriteCompleted -> Unit
        }
    }

    private suspend fun handleNotification(
        event: BleEvent.NotificationReceived,
        activeConfiguration: CollectionConfiguration,
    ) {
        val frames = parser.offer(event.bytes)
        frames.forEach { frame ->
            reading = KmfReadingMerger.apply(reading, frame)
            when (frame) {
                is KmfFrame.A -> hasAFrame = true
                is KmfFrame.B -> hasBFrame = true
                is KmfFrame.C -> hasCFrame = true
            }
        }
        val activeProfile = profile
        persistNotification(
            KmfNotificationObservation(
                timestampMs = clock(),
                deviceAddress = activeConfiguration.snapshot.address,
                deviceName = activeConfiguration.snapshot.name,
                serviceUuid = activeProfile?.serviceUuid?.toString() ?: activeConfiguration.snapshot.serviceUuid,
                notifyUuid = activeProfile?.notifyUuid?.toString() ?: activeConfiguration.snapshot.notifyUuid,
                writeUuid = activeProfile?.writeUuid?.toString() ?: activeConfiguration.snapshot.writeUuid,
                connectionState = mutableState.value.phase.name,
                bytes = event.bytes.copyOf(),
                frames = frames,
                mergedReading = reading,
            ),
        )
        if (hasAFrame && hasBFrame && mutableState.value.phase != CollectionPhase.COLLECTING) {
            reconnectAttempt = 0
            failureStreak = 0
            linkDroppedAtMs = null
            cancelReconnect()
            bootstrapJob?.cancel()
            bootstrapJob = null
            updateState(CollectionPhase.COLLECTING)
            startPolling()
        }
    }

    private fun startBootstrap() {
        cancelBootstrapAndPolling()
        bootstrapJob = workScope.launch {
            KmfBootstrapSequence.run(repository, source = "collection") {
                mutableState.value.phase == CollectionPhase.BOOTSTRAPPING
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = workScope.launch {
            while (isActive && mutableState.value.phase == CollectionPhase.COLLECTING) {
                // Until this session has energy totals, history skips samples, so ask for them right away.
                val awaitingTotals = !hasCFrame
                if (!awaitingTotals) delay(pollIntervalMs())
                if (isActive && mutableState.value.phase == CollectionPhase.COLLECTING) {
                    if (awaitingTotals) AppLog.d("collection totals poll: no C frame yet this session", TAG)
                    repository.write(KMF_TOTALS_POLL)
                }
                if (awaitingTotals) delay(TOTALS_RETRY_INTERVAL_MS)
            }
        }
    }

    private fun scheduleReconnect(error: String) {
        if (reconnectJob?.isActive == true || configuration == null || stoppedByUser || deviceUnavailable) {
            return
        }
        val delayMs = RECONNECT_DELAYS_MS[reconnectAttempt]
        reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(RECONNECT_DELAYS_MS.lastIndex)
        failureStreak += 1
        val nowMs = clock()
        if (linkDroppedAtMs == null) linkDroppedAtMs = nowMs
        AppLog.d("collection reconnect scheduled attempt=$failureStreak delayMs=$delayMs error=$error", TAG)
        updateState(
            CollectionPhase.ERROR,
            nextReconnectDelayMs = delayMs,
            error = error,
            nextReconnectAtMs = nowMs + delayMs,
        )
        reconnectJob = workScope.launch {
            delay(delayMs)
            if (isActive && configuration != null && !stoppedByUser && !deviceUnavailable) {
                connectConfiguredDevice()
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (reconnectJob === job) reconnectJob = null
            }
        }
    }

    private fun connectConfiguredDevice() {
        val activeConfiguration = configuration ?: return
        updateState(CollectionPhase.CONNECTING)
        repository.connect(activeConfiguration.snapshot.toScannedDevice(), activeConfiguration.snapshot)
    }

    private fun resetProtocolSession() {
        parser = KmfLineParser()
        reading = KmfReading()
        profile = null
        hasAFrame = false
        hasBFrame = false
        hasCFrame = false
    }

    private suspend fun persistHistoryReset(deviceAddress: String) {
        try {
            historyStore.resetSession(deviceAddress)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            AppLog.e("failed to reset collection history session", error, TAG)
        }
    }

    private suspend fun persistNotification(observation: KmfNotificationObservation) {
        try {
            historyStore.recordNotification(observation)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            AppLog.e("failed to persist collection notification", error, TAG)
        }
    }

    private fun cancelCollectionJobs() {
        cancelBootstrapAndPolling()
        cancelReconnect()
    }

    private fun cancelBootstrapAndPolling() {
        bootstrapJob?.cancel()
        bootstrapJob = null
        pollJob?.cancel()
        pollJob = null
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun pollIntervalMs(): Long =
        if (mutableState.value.visibility == CollectionVisibility.FOREGROUND) {
            FOREGROUND_POLL_INTERVAL_MS
        } else {
            BACKGROUND_POLL_INTERVAL_MS
        }

    private fun updateState(
        phase: CollectionPhase,
        nextReconnectDelayMs: Long? = null,
        error: String? = null,
        nextReconnectAtMs: Long? = null,
    ) {
        val previous = mutableState.value
        val next = CollectionState(
            phase = phase,
            visibility = previous.visibility,
            deviceAddress = configuration?.snapshot?.address,
            nextReconnectDelayMs = nextReconnectDelayMs,
            error = error,
            nextReconnectAtMs = nextReconnectAtMs,
            reconnectAttempt = failureStreak,
            linkDroppedAtMs = linkDroppedAtMs,
        )
        if (previous.phase != next.phase) {
            AppLog.d(
                "collection phase ${previous.phase} -> ${next.phase} attempt=${next.reconnectAttempt} " +
                    "nextReconnectAtMs=${next.nextReconnectAtMs} error=${next.error}",
                TAG,
            )
        }
        mutableState.value = next
    }

    private companion object {
        const val TAG = "KMF-BLE"
        const val FOREGROUND_POLL_INTERVAL_MS = CollectionCadence.FOREGROUND_POLL_INTERVAL_MS
        const val BACKGROUND_POLL_INTERVAL_MS = CollectionCadence.BACKGROUND_POLL_INTERVAL_MS
        const val TOTALS_RETRY_INTERVAL_MS = 2_000L
        val RECONNECT_DELAYS_MS = longArrayOf(1_000L, 2_000L, 5_000L, 15_000L, 30_000L, 60_000L)
        val KMF_TOTALS_POLL = byteArrayOf(0x3A, 0x43, 0x0A)
    }
}

private fun DeviceSnapshot.toScannedDevice(): ScannedDevice =
    ScannedDevice(
        name = name,
        address = address,
        rssi = 0,
        serviceUuids = emptyList(),
    )
