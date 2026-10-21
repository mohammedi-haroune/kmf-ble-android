package com.juncehome.lifepo4ble.remote

import com.juncehome.lifepo4ble.collection.CollectionPhase
import com.juncehome.lifepo4ble.protocol.KmfReading

/** One event of the home phone's `GET /v1/live` stream. See the "Wire format" section of the remote meter link spec. */
sealed interface LiveFeedMessage {
    /** First event of every connection. [ms] is the home phone's clock, used once to compute a clock offset. */
    data class Hello(val ms: Long, val meterName: String, val phase: CollectionPhase) : LiveFeedMessage

    /**
     * One meter line exactly as the home parser saw it ([line] has no terminator). [reading] is the home phone's
     * merged reading after that line, or null until its session has seen A, B and C. [replay] marks the catch-up
     * lines sent right after [Hello].
     */
    data class Frame(
        val ms: Long,
        val line: String,
        val reading: KmfReading?,
        val replay: Boolean = false,
    ) : LiveFeedMessage

    /**
     * The home phone's link state: sent right after [Hello] with the current state, then whenever the home
     * [CollectionPhase], error or next-reconnect time changes. [nextReconnectInMs] is relative so clock skew does not
     * matter.
     */
    data class Link(
        val ms: Long,
        val phase: CollectionPhase,
        val error: String?,
        val nextReconnectInMs: Long?,
    ) : LiveFeedMessage
}

/**
 * The `error` text of a [LiveFeedMessage.Link] with phase [CollectionPhase.STOPPED]: what the home phone turned off on
 * purpose. The carried phone shows it as the paused state; the text is also the wire contract for other readers.
 */
object LiveFeedStopReason {
    const val SHARING_OFF = "Sharing is off on the home phone"
    const val COLLECTION_OFF = "Collection is off on the home phone"
}

/** Snapshot served by `GET /v1/now`: the last line of each type (A, B, C) in the current home session, null when none. */
data class LiveFeedNow(
    val ms: Long,
    val meterName: String,
    val phase: CollectionPhase,
    val reading: KmfReading?,
    val lines: Map<Char, String?>,
)
