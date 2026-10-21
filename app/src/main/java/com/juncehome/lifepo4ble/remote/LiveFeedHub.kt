package com.juncehome.lifepo4ble.remote

import com.juncehome.lifepo4ble.AppLog
import com.juncehome.lifepo4ble.ble.BleEvent
import com.juncehome.lifepo4ble.collection.CollectionPhase
import com.juncehome.lifepo4ble.collection.CollectionState
import com.juncehome.lifepo4ble.protocol.KmfFrame
import com.juncehome.lifepo4ble.protocol.KmfLineParser
import com.juncehome.lifepo4ble.protocol.KmfReading
import com.juncehome.lifepo4ble.protocol.KmfReadingMerger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The home phone's in-memory view of the meter session, fanned out to every connected viewer.
 *
 * The producer (BLE events, collection state) runs on Main and subscribers are drained on IO, so every public
 * method is synchronized. Producing never blocks: a subscriber whose buffer is full is closed and removed.
 */
class LiveFeedHub(
    private val clock: () -> Long = System::currentTimeMillis,
    private val clientBufferCapacity: Int = 256,
) {
    private val subscribers = mutableListOf<Channel<LiveFeedMessage>>()
    private val clientCountState = MutableStateFlow(0)
    private var meterName = DEFAULT_METER_NAME
    private var phase = CollectionPhase.STOPPED
    private var session = Session()

    /** Number of connected viewers. */
    val clientCount: StateFlow<Int> = clientCountState.asStateFlow()

    @Synchronized
    fun onBleEvent(event: BleEvent) {
        when (event) {
            is BleEvent.Connecting -> {
                meterName = event.device.name ?: DEFAULT_METER_NAME
                resetSession("connecting")
            }
            is BleEvent.ServicesDiscovered -> resetSession("services discovered")
            is BleEvent.NotificationReceived -> relay(event.bytes)
            else -> Unit
        }
    }

    @Synchronized
    fun onCollectionState(state: CollectionState) {
        if (state.phase == phase) return
        val ms = clock()
        val nextReconnectInMs = state.nextReconnectAtMs?.minus(ms)?.coerceAtLeast(0L)
        AppLog.d(
            "live feed link phase=$phase->${state.phase} error=${state.error} " +
                "nextReconnectInMs=$nextReconnectInMs clients=${subscribers.size}",
            TAG,
        )
        phase = state.phase
        publish(LiveFeedMessage.Link(ms, state.phase, state.error, nextReconnectInMs))
    }

    /**
     * Opens a viewer stream: [LiveFeedMessage.Hello], then one replay frame per line the viewer needs to rebuild
     * the home reading, then live messages. A viewer whose catch-up does not fit its buffer is closed right away.
     */
    @Synchronized
    fun subscribe(): LiveFeedSubscription {
        val channel = Channel<LiveFeedMessage>(clientBufferCapacity)
        val reading = session.readingOrNull()
        val replay = session.replayLines()
        val catchUp = buildList<LiveFeedMessage> {
            add(LiveFeedMessage.Hello(clock(), meterName, phase))
            replay.forEach { add(LiveFeedMessage.Frame(it.ms, it.text, reading, replay = true)) }
        }
        if (catchUp.all { channel.trySend(it).isSuccess }) {
            subscribers.add(channel)
            clientCountState.value = subscribers.size
            AppLog.d(
                "live feed client subscribed clients=${subscribers.size} phase=$phase " +
                    "replay=${replay.map { it.text }} readingReady=${reading != null}",
                TAG,
            )
        } else {
            channel.close()
            AppLog.w(
                "live feed client dropped during catch-up capacity=$clientBufferCapacity " +
                    "catchUp=${catchUp.size} clients=${subscribers.size}",
                TAG,
            )
        }
        return LiveFeedSubscription(channel) { unsubscribe(channel) }
    }

    @Synchronized
    fun now(): LiveFeedNow = LiveFeedNow(
        ms = clock(),
        meterName = meterName,
        phase = phase,
        reading = session.readingOrNull(),
        lines = session.lastLines(),
    )

    private fun resetSession(reason: String) {
        session = Session()
        AppLog.d("live feed session reset reason=$reason meter=$meterName clients=${subscribers.size}", TAG)
    }

    private fun relay(bytes: ByteArray) {
        session.parser.offer(bytes)
        session.parser.lastOfferLines.forEach { line ->
            val ms = clock()
            line.frame?.let { session.record(it, line.text, ms) }
            publish(LiveFeedMessage.Frame(ms, line.text, session.readingOrNull()))
        }
    }

    private fun publish(message: LiveFeedMessage) {
        val iterator = subscribers.iterator()
        while (iterator.hasNext()) {
            val channel = iterator.next()
            val result = channel.trySend(message)
            if (result.isFailure) {
                iterator.remove()
                channel.close()
                AppLog.w(
                    "live feed client dropped reason=${if (result.isClosed) "closed" else "buffer full"} " +
                        "capacity=$clientBufferCapacity clients=${subscribers.size}",
                    TAG,
                )
            }
        }
        clientCountState.value = subscribers.size
    }

    @Synchronized
    private fun unsubscribe(channel: Channel<LiveFeedMessage>) {
        if (subscribers.remove(channel)) {
            clientCountState.value = subscribers.size
            AppLog.d("live feed client unsubscribed clients=${subscribers.size}", TAG)
        }
    }

    private class ReplayLine(val text: String, val ms: Long)

    /** Everything the hub knows about the current meter connection. Replaced as a whole on every reset. */
    private class Session {
        val parser = KmfLineParser()
        private var reading = KmfReading()
        private var seenA = false
        private var seenB = false
        private var seenC = false
        private var lastA: ReplayLine? = null
        private var lastFullA: ReplayLine? = null
        private var lastB: ReplayLine? = null
        private var lastC: ReplayLine? = null

        fun record(frame: KmfFrame, text: String, ms: Long) {
            reading = KmfReadingMerger.apply(reading, frame)
            val line = ReplayLine(text, ms)
            when (frame) {
                is KmfFrame.A -> {
                    seenA = true
                    lastA = line
                    if (frame.capacityAh > 0.0) lastFullA = line
                }
                is KmfFrame.B -> {
                    seenB = true
                    lastB = line
                }
                is KmfFrame.C -> {
                    seenC = true
                    lastC = line
                }
            }
        }

        /** Null until A, B and C were all seen: a reading built from zeros and partial frames must never be shown. */
        fun readingOrNull(): KmfReading? = reading.takeIf { seenA && seenB && seenC }

        /**
         * The lines a viewer needs to fold into the same reading as [reading]. The last A that carried a capacity
         * comes first (it sets the capacity), then a later short A (its remaining Ah depends on that capacity),
         * then B and C.
         */
        fun replayLines(): List<ReplayLine> = listOfNotNull(
            lastFullA,
            lastA?.takeIf { it !== lastFullA },
            lastB,
            lastC,
        )

        fun lastLines(): Map<Char, String?> = mapOf('A' to lastA?.text, 'B' to lastB?.text, 'C' to lastC?.text)
    }

    private companion object {
        const val TAG = "KMF-BLE"
        const val DEFAULT_METER_NAME = "KMF Meter"
    }
}

/** One viewer's stream of [LiveFeedMessage]s. [close] is idempotent and safe from any thread. */
class LiveFeedSubscription(
    val messages: ReceiveChannel<LiveFeedMessage>,
    private val onClose: () -> Unit,
) {
    fun close() {
        onClose()
        messages.cancel()
    }
}
