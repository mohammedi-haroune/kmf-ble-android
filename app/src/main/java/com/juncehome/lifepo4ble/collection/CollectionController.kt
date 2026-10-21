package com.juncehome.lifepo4ble.collection

import com.juncehome.lifepo4ble.AppLog
import com.juncehome.lifepo4ble.ble.AndroidBleSession
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
import kotlinx.coroutines.CoroutineStart
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

enum class CollectionPhase { STOPPED, BLUETOOTH_OFF, WAITING_FOR_DEVICE, CONNECTING, BOOTSTRAPPING, COLLECTING, ERROR }

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
    /** True while the carried phone cannot reach the home phone's live feed; always false for a Bluetooth link. */
    val remoteUnreachable: Boolean = false,
)

interface CollectionController {
    val state: StateFlow<CollectionState>

    fun start(configuration: CollectionConfiguration)

    fun setVisibility(visibility: CollectionVisibility)

    fun onDeviceAppeared(address: String)

    fun onDeviceDisappeared(address: String)

    /** Bluetooth adapter on/off; off parks collection, on reconnects right away. */
    fun onBluetoothStateChanged(enabled: Boolean)

    fun stopByUser()

    /** User-requested immediate reconnect; acts whenever the meter is not linked (retry pending, waiting, stuck connecting). */
    fun reconnectNow()

    fun close()
}

/**
 * Owns collection policy only. The caller supplies the BLE transport and the scope that schedules work,
 * allowing the same policy to run from a foreground service or a companion-device callback.
 *
 * The scope must be single-threaded (the service uses the main thread): all state here is unsynchronized.
 *
 * Reconnect policy: every stage has a deadline so no state can hang.
 * - Direct connect attempts (fast, [CONNECT_TIMEOUT_MS] each) after 1 s, 2 s and 5 s.
 * - Then a background autoConnect that the Bluetooth controller holds until the meter advertises again.
 *   It needs no app timer, so it also works while the phone dozes; it is refreshed every
 *   [BACKGROUND_REFRESH_MS] in case the stack dropped it.
 * - From GATT connected to parsed A+B frames: [SETUP_TIMEOUT_MS].
 * - While collecting: after [LIVENESS_PROBE_AFTER_MS] of silence a `:C` probe is sent; with no
 *   notification at all for [LIVENESS_DEAD_AFTER_MS] the link is treated as dead.
 * - Bluetooth off parks collection in BLUETOOTH_OFF; Bluetooth on reconnects immediately.
 */
class CollectionControllerImpl(
    private val repository: BleRepositoryContract,
    private val historyStore: KmfHistoryStore,
    scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val monotonicClock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val isBluetoothEnabled: () -> Boolean = { true },
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
    private var bluetoothEnabled = true
    private var linkUp = false
    private var backgroundAttempt = false
    private var lastNotificationAtMs = 0L

    /** Disconnected events that [releaseLink] makes the repository emit; they are not link failures. */
    private var pendingReleaseDisconnects = 0
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
    private var watchdogJob: Job? = null
    private var livenessJob: Job? = null

    // UNDISPATCHED: the service scope runs on Main, so a lazily started collector would miss the
    // Connecting event that start() emits synchronously (the shared flow has no replay).
    private val eventJob = workScope.launch(start = CoroutineStart.UNDISPATCHED) {
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
        bluetoothEnabled = isBluetoothEnabled()
        if (!bluetoothEnabled) {
            AppLog.w("collection start: Bluetooth is off; waiting for it to turn on", TAG)
            updateState(CollectionPhase.BLUETOOTH_OFF, error = AndroidBleSession.BLUETOOTH_OFF_MESSAGE)
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
        // CDM reports lowercase MACs while the saved snapshot is uppercase.
        if (stoppedByUser || !address.equals(activeConfiguration.snapshot.address, ignoreCase = true)) {
            AppLog.d("collection ignored device appeared address=$address", TAG)
            return
        }
        deviceUnavailable = false
        val phase = mutableState.value.phase
        AppLog.d("collection device appeared phase=$phase background=$backgroundAttempt bluetooth=$bluetoothEnabled", TAG)
        if (!bluetoothEnabled) return
        if (phase == CollectionPhase.WAITING_FOR_DEVICE || phase == CollectionPhase.ERROR) {
            // A direct attempt now beats waiting on the backoff timer or the slower background connect.
            resetBackoff()
            connectConfiguredDevice()
        }
    }

    override fun onDeviceDisappeared(address: String) {
        if (configuration == null || !address.equals(configuration?.snapshot?.address, ignoreCase = true)) {
            AppLog.d("collection ignored device disappeared address=$address", TAG)
            return
        }
        deviceUnavailable = true
        val phase = mutableState.value.phase
        if (linkUp || phase == CollectionPhase.BOOTSTRAPPING || phase == CollectionPhase.COLLECTING) {
            // The live link is the source of truth; presence scans can miss a connected meter.
            AppLog.d("collection device disappeared while linked (phase=$phase); keeping the link", TAG)
            return
        }
        if (phase == CollectionPhase.ERROR || (phase == CollectionPhase.CONNECTING && !backgroundAttempt)) {
            AppLog.d("collection device disappeared during reconnect (phase=$phase); switching to background connect", TAG)
            cancelCollectionJobs()
            releaseLink()
            failureStreak += 1
            if (linkDroppedAtMs == null) linkDroppedAtMs = clock()
            startBackgroundConnect(error = "Meter is out of range", delayMs = 0L)
        }
    }

    override fun onBluetoothStateChanged(enabled: Boolean) {
        if (enabled == bluetoothEnabled) return
        bluetoothEnabled = enabled
        AppLog.d("collection Bluetooth ${if (enabled) "on" else "off"} phase=${mutableState.value.phase}", TAG)
        if (configuration == null || stoppedByUser) return
        if (!enabled) {
            enterBluetoothOff()
        } else {
            resetBackoff()
            deviceUnavailable = false
            connectConfiguredDevice()
        }
    }

    override fun stopByUser() {
        stoppedByUser = true
        configuration = null
        cancelCollectionJobs()
        releaseLink()
        updateState(CollectionPhase.STOPPED)
    }

    override fun reconnectNow() {
        val phase = mutableState.value.phase
        if (configuration == null || stoppedByUser) {
            AppLog.w("collection reconnectNow ignored: collection is stopped (phase=$phase)", TAG)
            return
        }
        if (phase !in RECONNECTABLE_PHASES || !bluetoothEnabled) {
            AppLog.d("collection reconnectNow ignored in phase=$phase bluetooth=$bluetoothEnabled", TAG)
            return
        }
        AppLog.d("collection reconnectNow: phase=$phase attempt=$failureStreak; connecting now", TAG)
        deviceUnavailable = false
        resetBackoff()
        connectConfiguredDevice()
    }

    override fun close() {
        stopByUser()
        eventJob.cancel()
        controllerJob.cancel()
    }

    private suspend fun handleEvent(event: BleEvent) {
        if (event is BleEvent.Disconnected && pendingReleaseDisconnects > 0) {
            pendingReleaseDisconnects -= 1
            AppLog.d("collection ignored disconnect caused by releasing the link", TAG)
            return
        }
        val activeConfiguration = configuration ?: return
        if (stoppedByUser) {
            return
        }
        when (event) {
            is BleEvent.Connecting -> {
                if (!event.device.address.equals(activeConfiguration.snapshot.address, ignoreCase = true)) return
                resetProtocolSession()
                persistHistoryReset(activeConfiguration.snapshot.address)
            }
            is BleEvent.Connected -> {
                if (!event.device.address.equals(activeConfiguration.snapshot.address, ignoreCase = true)) return
                linkUp = true
                updateState(CollectionPhase.CONNECTING)
                armWatchdog(SETUP_TIMEOUT_MS, "Meter setup timed out")
            }
            is BleEvent.ServicesDiscovered -> {
                linkUp = true
                resetProtocolSession()
                // Connecting can be missed; stale A/B/C flags would save samples built from zeros.
                persistHistoryReset(activeConfiguration.snapshot.address)
                profile = event.profile
                updateState(CollectionPhase.BOOTSTRAPPING)
                armWatchdog(SETUP_TIMEOUT_MS, "Meter did not start streaming")
                startBootstrap()
            }
            is BleEvent.NotificationReceived -> handleNotification(event, activeConfiguration)
            is BleEvent.Disconnected -> {
                if (!bluetoothEnabled) return
                failAttempt(event.reason ?: "BLE disconnected", releaseLink = false)
            }
            is BleEvent.Error -> {
                if (!bluetoothEnabled) return
                if (event.message == AndroidBleSession.BLUETOOTH_OFF_MESSAGE && !isBluetoothEnabled()) {
                    bluetoothEnabled = false
                    enterBluetoothOff()
                    return
                }
                // The session may still hold a connected GATT (failed discovery or CCCD); free it.
                failAttempt(event.message, releaseLink = true)
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
        lastNotificationAtMs = monotonicClock()
        if (hasAFrame && hasBFrame && mutableState.value.phase != CollectionPhase.COLLECTING) {
            resetBackoff()
            linkUp = true
            deviceUnavailable = false
            cancelReconnect()
            cancelWatchdog()
            bootstrapJob?.cancel()
            bootstrapJob = null
            updateState(CollectionPhase.COLLECTING)
            startPolling()
            startLivenessCheck()
        }
    }

    /**
     * The meter's notification rate varies a lot (about 1 Hz after startup, gaps of minutes later),
     * so silence alone is not a dead link: ask for totals first and only give up when even that
     * gets no answer.
     */
    private fun startLivenessCheck() {
        livenessJob?.cancel()
        livenessJob = workScope.launch {
            while (isActive && mutableState.value.phase == CollectionPhase.COLLECTING) {
                delay(LIVENESS_CHECK_INTERVAL_MS)
                if (mutableState.value.phase != CollectionPhase.COLLECTING) break
                val silentMs = monotonicClock() - lastNotificationAtMs
                when {
                    silentMs >= LIVENESS_DEAD_AFTER_MS -> {
                        AppLog.w("collection liveness: no notification for $silentMs ms; reconnecting", TAG)
                        failAttempt("No data from the meter for ${silentMs / 1_000L} s", releaseLink = true)
                        break
                    }
                    silentMs >= LIVENESS_PROBE_AFTER_MS -> {
                        val queued = repository.write(KMF_TOTALS_POLL)
                        AppLog.d("collection liveness: silent for $silentMs ms; probe :C queued=$queued", TAG)
                    }
                }
            }
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

    /** Ends the current attempt or session after a failure and schedules the next attempt. */
    private fun failAttempt(error: String, releaseLink: Boolean) {
        if (configuration == null || stoppedByUser || !bluetoothEnabled) return
        if (reconnectJob?.isActive == true) {
            AppLog.d("collection failure ignored; reconnect already pending: $error", TAG)
            return
        }
        // A link that came up but never reached COLLECTING is a setup failure, not a range problem.
        val setupFailed = linkUp && mutableState.value.phase != CollectionPhase.COLLECTING
        AppLog.d(
            "collection attempt failed phase=${mutableState.value.phase} setupFailed=$setupFailed " +
                "background=$backgroundAttempt deviceUnavailable=$deviceUnavailable error=$error",
            TAG,
        )
        cancelBootstrapAndPolling()
        cancelWatchdog()
        resetProtocolSession()
        if (releaseLink) releaseLink()
        linkUp = false
        scheduleReconnect(error, setupFailed)
    }

    private fun scheduleReconnect(error: String, setupFailed: Boolean) {
        failureStreak += 1
        val nowMs = clock()
        if (linkDroppedAtMs == null) linkDroppedAtMs = nowMs
        if (deviceUnavailable || reconnectAttempt >= DIRECT_RETRY_DELAYS_MS.size) {
            // A meter that is simply absent is best left to the Bluetooth controller right away.
            // Re-issuing at once after a failed setup or a failed background request would spin.
            val delayMs = when {
                setupFailed -> BACKGROUND_RETRY_AFTER_SETUP_FAILURE_MS
                backgroundAttempt -> BACKGROUND_RETRY_AFTER_FAILURE_MS
                else -> 0L
            }
            startBackgroundConnect(error, delayMs)
            return
        }
        val delayMs = DIRECT_RETRY_DELAYS_MS[reconnectAttempt]
        reconnectAttempt += 1
        AppLog.d("collection reconnect scheduled attempt=$failureStreak delayMs=$delayMs error=$error", TAG)
        updateState(
            CollectionPhase.ERROR,
            nextReconnectDelayMs = delayMs,
            error = error,
            nextReconnectAtMs = nowMs + delayMs,
        )
        launchReconnect(delayMs) { connectConfiguredDevice() }
    }

    /** Parks in WAITING_FOR_DEVICE with an autoConnect request the Bluetooth controller completes. */
    private fun startBackgroundConnect(error: String, delayMs: Long) {
        AppLog.d("collection background connect attempt=$failureStreak delayMs=$delayMs error=$error", TAG)
        updateState(CollectionPhase.WAITING_FOR_DEVICE, error = error)
        if (delayMs == 0L) {
            cancelReconnect()
            connectInBackground()
        } else {
            launchReconnect(delayMs) { connectInBackground() }
        }
    }

    private fun launchReconnect(delayMs: Long, connect: () -> Unit) {
        cancelReconnect()
        reconnectJob = workScope.launch {
            delay(delayMs)
            if (isActive && configuration != null && !stoppedByUser && bluetoothEnabled) {
                connect()
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (reconnectJob === job) reconnectJob = null
            }
        }
    }

    private fun connectConfiguredDevice() {
        val activeConfiguration = configuration ?: return
        cancelCollectionJobs()
        backgroundAttempt = false
        linkUp = false
        updateState(CollectionPhase.CONNECTING)
        armWatchdog(CONNECT_TIMEOUT_MS, "Connection attempt timed out")
        repository.connect(activeConfiguration.snapshot.toScannedDevice(), activeConfiguration.snapshot)
    }

    private fun connectInBackground() {
        val activeConfiguration = configuration ?: return
        backgroundAttempt = true
        linkUp = false
        cancelWatchdog()
        repository.connect(
            activeConfiguration.snapshot.toScannedDevice(),
            activeConfiguration.snapshot,
            autoConnect = true,
        )
        // No connect deadline: the controller waits for the meter. Only re-issue the request in case
        // the stack silently dropped it.
        watchdogJob = workScope.launch {
            delay(BACKGROUND_REFRESH_MS)
            if (mutableState.value.phase == CollectionPhase.WAITING_FOR_DEVICE && backgroundAttempt) {
                AppLog.d("collection background connect refresh after $BACKGROUND_REFRESH_MS ms", TAG)
                connectInBackground()
            }
        }
    }

    private fun enterBluetoothOff() {
        AppLog.w("collection parked: Bluetooth is off", TAG)
        cancelCollectionJobs()
        resetProtocolSession()
        releaseLink()
        linkUp = false
        backgroundAttempt = false
        updateState(CollectionPhase.BLUETOOTH_OFF, error = AndroidBleSession.BLUETOOTH_OFF_MESSAGE)
    }

    /** Closes the GATT; the repository's resulting Disconnected event is consumed in [handleEvent]. */
    private fun releaseLink() {
        pendingReleaseDisconnects += 1
        repository.disconnect()
    }

    private fun armWatchdog(timeoutMs: Long, reason: String) {
        cancelWatchdog()
        watchdogJob = workScope.launch {
            delay(timeoutMs)
            AppLog.w("collection watchdog fired after $timeoutMs ms phase=${mutableState.value.phase}: $reason", TAG)
            watchdogJob = null
            failAttempt(reason, releaseLink = true)
        }
    }

    private fun cancelWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private fun resetBackoff() {
        reconnectAttempt = 0
        failureStreak = 0
        linkDroppedAtMs = null
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
        cancelWatchdog()
    }

    private fun cancelBootstrapAndPolling() {
        bootstrapJob?.cancel()
        bootstrapJob = null
        pollJob?.cancel()
        pollJob = null
        livenessJob?.cancel()
        livenessJob = null
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
        val DIRECT_RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L, 5_000L)
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val SETUP_TIMEOUT_MS = 30_000L
        const val BACKGROUND_RETRY_AFTER_SETUP_FAILURE_MS = 30_000L
        const val BACKGROUND_RETRY_AFTER_FAILURE_MS = 15_000L
        const val BACKGROUND_REFRESH_MS = 10 * 60_000L
        const val LIVENESS_CHECK_INTERVAL_MS = 15_000L
        const val LIVENESS_PROBE_AFTER_MS = 60_000L
        const val LIVENESS_DEAD_AFTER_MS = 120_000L
        val RECONNECTABLE_PHASES = setOf(
            CollectionPhase.ERROR,
            CollectionPhase.WAITING_FOR_DEVICE,
            CollectionPhase.CONNECTING,
        )
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
