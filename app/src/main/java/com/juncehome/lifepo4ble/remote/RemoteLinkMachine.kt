package com.juncehome.lifepo4ble.remote

import com.juncehome.lifepo4ble.AppLog
import com.juncehome.lifepo4ble.ble.BleEvent
import com.juncehome.lifepo4ble.ble.ScannedDevice
import com.juncehome.lifepo4ble.collection.CollectionPhase
import com.juncehome.lifepo4ble.collection.CollectionState
import com.juncehome.lifepo4ble.collection.CollectionVisibility
import java.util.UUID

/** A [BleEvent] with the wall-clock time (carried phone clock) it should be recorded at. */
data class TimedBleEvent(val event: BleEvent, val atMs: Long)

/** What to do after the live stream ended: feed [events] to the reducer, then connect again after [delayMs]. */
data class RemoteRetry(val events: List<TimedBleEvent>, val delayMs: Long)

/**
 * Turns the home phone's live feed back into what the Bluetooth path would have produced: the app's own [BleEvent]s
 * and a [CollectionState]. Pure and single-threaded; the caller owns the network, the clock and the retry timer.
 *
 * The meter shows up as the id [RemoteAddress.id], never as a Bluetooth address. A home reconnect to the meter
 * ([CollectionPhase.CONNECTING]) is replayed as [BleEvent.Connecting] so the reducer resyncs exactly like after a
 * Bluetooth reconnect: values are kept, the A/B flags are cleared.
 */
class RemoteLinkMachine(private val address: RemoteAddress) {
    private var meterName: String? = null
    private var clockOffsetMs = 0L
    private var homePhase: CollectionPhase? = null
    private var backoffIndex = 0
    private var failureStreak = 0
    private var linkDroppedAtMs: Long? = null

    var state: CollectionState = stateOf(CollectionPhase.STOPPED)
        private set

    /** A connection to the home phone is about to be opened. */
    fun onConnectAttempt(nowMs: Long): List<TimedBleEvent> {
        state = stateOf(CollectionPhase.CONNECTING)
        AppLog.d("remote link connecting to ${address.display} attempt=$failureStreak", TAG)
        return listOf(TimedBleEvent(BleEvent.Connecting(device()), nowMs))
    }

    fun onMessage(message: LiveFeedMessage, nowMs: Long): List<TimedBleEvent> = when (message) {
        is LiveFeedMessage.Hello -> onHello(message, nowMs)
        is LiveFeedMessage.Frame -> onFrame(message, nowMs)
        is LiveFeedMessage.Link -> onLink(message, nowMs)
    }

    /** The stream closed or the connection failed. */
    fun onStreamEnded(reason: String, nowMs: Long): RemoteRetry {
        val events = listOf(TimedBleEvent(BleEvent.Disconnected(reason), nowMs))
        if (homePhase == CollectionPhase.STOPPED) {
            // The home phone said it stopped on purpose: stay on the paused state, not "unreachable", and look slowly.
            state = pausedState()
            AppLog.d(
                "remote link ended while the home phone is stopped reason=${reason.forLog()} " +
                    "retryInMs=$PAUSED_RETRY_MS host=${address.display}",
                TAG,
            )
            return RemoteRetry(events, PAUSED_RETRY_MS)
        }
        failureStreak += 1
        if (linkDroppedAtMs == null) linkDroppedAtMs = nowMs
        val delayMs = RECONNECT_DELAYS_MS[backoffIndex]
        if (backoffIndex < RECONNECT_DELAYS_MS.lastIndex) backoffIndex += 1
        state = stateOf(
            CollectionPhase.ERROR,
            error = reason,
            nextReconnectAtMs = nowMs + delayMs,
            remoteUnreachable = true,
        )
        AppLog.d(
            "remote link ended attempt=$failureStreak reason=${reason.forLog()} retryInMs=$delayMs host=${address.display}",
            TAG,
        )
        return RemoteRetry(events, delayMs)
    }

    /** The user asked to connect right now: the next failure starts the backoff over. */
    fun onReconnectNow() {
        resetBackoff()
        AppLog.d("remote link reconnect now requested host=${address.display}", TAG)
    }

    private fun onHello(hello: LiveFeedMessage.Hello, nowMs: Long): List<TimedBleEvent> {
        clockOffsetMs = nowMs - hello.ms
        meterName = hello.meterName
        homePhase = hello.phase
        resetBackoff()
        state = homeState(hello.phase, error = null, nextReconnectAtMs = null)
        AppLog.d(
            "remote link hello meter=${hello.meterName} homePhase=${hello.phase} " +
                "clockOffsetMs=$clockOffsetMs host=${address.display}",
            TAG,
        )
        return listOf(TimedBleEvent(BleEvent.Connected(device()), nowMs))
    }

    private fun onFrame(frame: LiveFeedMessage.Frame, nowMs: Long): List<TimedBleEvent> {
        val bytes = (frame.line + LINE_TERMINATOR).toByteArray(Charsets.US_ASCII)
        val atMs = minOf(nowMs, frame.ms + clockOffsetMs)
        return listOf(TimedBleEvent(BleEvent.NotificationReceived(REMOTE_CHARACTERISTIC, bytes), atMs))
    }

    private fun onLink(link: LiveFeedMessage.Link, nowMs: Long): List<TimedBleEvent> {
        val previousPhase = homePhase
        homePhase = link.phase
        state = homeState(link.phase, link.error, link.nextReconnectInMs?.let { nowMs + it })
        if (previousPhase != link.phase) {
            AppLog.d(
                "remote link home phase $previousPhase -> ${link.phase} error=${link.error?.forLog()} " +
                    "nextReconnectInMs=${link.nextReconnectInMs} host=${address.display}",
                TAG,
            )
        }
        if (link.phase == CollectionPhase.CONNECTING && previousPhase != CollectionPhase.CONNECTING) {
            return listOf(TimedBleEvent(BleEvent.Connecting(device()), nowMs))
        }
        return emptyList()
    }

    /** The home phone's own state; a stopped home phone is a paused link, not a failure. */
    private fun homeState(phase: CollectionPhase, error: String?, nextReconnectAtMs: Long?): CollectionState =
        if (phase == CollectionPhase.STOPPED) {
            pausedState()
        } else {
            stateOf(phase, error = error, nextReconnectAtMs = nextReconnectAtMs)
        }

    /** The home phone turned collection off: nothing to count down, nothing unreachable. */
    private fun pausedState() = stateOf(CollectionPhase.ERROR, error = HOME_STOPPED_ERROR)

    private fun stateOf(
        phase: CollectionPhase,
        error: String? = null,
        nextReconnectAtMs: Long? = null,
        remoteUnreachable: Boolean = false,
    ) = CollectionState(
        phase = phase,
        visibility = CollectionVisibility.FOREGROUND,
        deviceAddress = address.id,
        error = error,
        nextReconnectAtMs = nextReconnectAtMs,
        reconnectAttempt = failureStreak,
        linkDroppedAtMs = linkDroppedAtMs,
        remoteUnreachable = remoteUnreachable,
    )

    private fun device() = ScannedDevice(name = meterName, address = address.id, rssi = 0, serviceUuids = emptyList())

    private fun resetBackoff() {
        backoffIndex = 0
        failureStreak = 0
        linkDroppedAtMs = null
    }

    /** Log lines carry the start of an error text only. */
    private fun String.forLog(): String =
        if (length <= MAX_LOGGED_ERROR_CHARS) this else take(MAX_LOGGED_ERROR_CHARS) + "..."

    private companion object {
        const val TAG = "KMF-BLE"
        const val LINE_TERMINATOR = "\r\n"
        const val HOME_STOPPED_ERROR = "Collection is off on the home phone"
        const val PAUSED_RETRY_MS = 15_000L
        const val MAX_LOGGED_ERROR_CHARS = 200

        /** 1 s, 2 s, 5 s, then every 15 s. */
        val RECONNECT_DELAYS_MS = longArrayOf(1_000L, 2_000L, 5_000L, 15_000L)

        /** Marks notifications that come from the live feed; it is never a meter characteristic. */
        val REMOTE_CHARACTERISTIC = UUID(0L, 0L)
    }
}
