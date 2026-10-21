package com.juncehome.lifepo4ble.remote

import com.juncehome.lifepo4ble.ble.BleEvent
import com.juncehome.lifepo4ble.ble.GattProfile
import com.juncehome.lifepo4ble.ble.ScannedDevice
import com.juncehome.lifepo4ble.collection.CollectionPhase
import com.juncehome.lifepo4ble.collection.CollectionState
import com.juncehome.lifepo4ble.collection.CollectionVisibility
import com.juncehome.lifepo4ble.protocol.KmfLineParser
import com.juncehome.lifepo4ble.protocol.KmfReading
import com.juncehome.lifepo4ble.protocol.KmfReadingMerger
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveFeedHubTest {
    private var nowMs = 1_000L
    private val hub = LiveFeedHub(clock = { nowMs })

    private fun notify(text: String) = BleEvent.NotificationReceived(UUID(0, 0), text.toByteArray())

    private fun device(name: String?) = ScannedDevice(name, "00:00:00:00:00:00", -50, emptyList())

    private fun state(
        phase: CollectionPhase,
        error: String? = null,
        nextReconnectAtMs: Long? = null,
    ) = CollectionState(phase, CollectionVisibility.BACKGROUND, error = error, nextReconnectAtMs = nextReconnectAtMs)

    /** Everything currently buffered for [subscription], without suspending. */
    private fun drain(subscription: LiveFeedSubscription): List<LiveFeedMessage> {
        val messages = mutableListOf<LiveFeedMessage>()
        while (true) {
            messages += subscription.messages.tryReceive().getOrNull() ?: return messages
        }
    }

    private fun drainFrames(subscription: LiveFeedSubscription) =
        drain(subscription).filterIsInstance<LiveFeedMessage.Frame>()

    /** What the carried phone does: parse each replayed line again and merge it. */
    private fun viewerReading(replay: List<LiveFeedMessage.Frame>): KmfReading {
        val parser = KmfLineParser()
        var reading = KmfReading()
        replay.forEach { frame ->
            parser.offer((frame.line + "\r\n").toByteArray())
            parser.lastOfferFrames.forEach { reading = KmfReadingMerger.apply(reading, it) }
        }
        return reading
    }

    /** What the home phone does: parse the same notifications and merge every frame. */
    private fun referenceReading(texts: List<String>): KmfReading {
        val parser = KmfLineParser()
        var reading = KmfReading()
        texts.forEach { text ->
            parser.offer(text.toByteArray())
            parser.lastOfferFrames.forEach { reading = KmfReadingMerger.apply(reading, it) }
        }
        return reading
    }

    private fun feed(vararg texts: String) = texts.forEach { hub.onBleEvent(notify(it)) }

    @Test
    fun subscribeSendsHelloThenReplayThenLive() {
        hub.onBleEvent(BleEvent.Connecting(device("Meter 7")))
        hub.onCollectionState(state(CollectionPhase.COLLECTING))
        nowMs = 2_000L
        feed(FULL_A_CHARGING + "\r\n")
        nowMs = 3_000L
        feed(B + "\r\n")
        nowMs = 4_000L
        feed(C + "\r\n")
        nowMs = 5_000L
        feed(SHORT_A_CHARGING + "\r\n")
        val expected = referenceReading(listOf(FULL_A_CHARGING, B, C, SHORT_A_CHARGING).map { it + "\r\n" })
        nowMs = 6_000L

        val subscription = hub.subscribe()
        val replay = drain(subscription)

        assertEquals(
            listOf(
                LiveFeedMessage.Hello(6_000L, "Meter 7", CollectionPhase.COLLECTING),
                LiveFeedMessage.Link(6_000L, CollectionPhase.COLLECTING, null, null),
                LiveFeedMessage.Frame(2_000L, FULL_A_CHARGING, expected, replay = true),
                LiveFeedMessage.Frame(5_000L, SHORT_A_CHARGING, expected, replay = true),
                LiveFeedMessage.Frame(3_000L, B, expected, replay = true),
                LiveFeedMessage.Frame(4_000L, C, expected, replay = true),
            ),
            replay,
        )

        nowMs = 7_000L
        feed(FULL_A_DISCHARGING + "\r\n")
        val live = drain(subscription).single() as LiveFeedMessage.Frame
        assertEquals(7_000L, live.ms)
        assertEquals(FULL_A_DISCHARGING, live.line)
        assertFalse(live.replay)
        assertNotNull(live.reading)
    }

    @Test
    fun replayHasOneAFrameWhenTheLastAIsTheFullOne() {
        feed(SHORT_A_CHARGING + "\r\n", FULL_A_CHARGING + "\r\n", B + "\r\n")

        val lines = drainFrames(hub.subscribe()).map { it.line }

        assertEquals(listOf(FULL_A_CHARGING, B), lines)
    }

    @Test
    fun replayRebuildsSameReadingAsHome() {
        val sequences = listOf(
            listOf(FULL_A_CHARGING, B, C),
            listOf(FULL_A_CHARGING, B, C, SHORT_A_CHARGING),
            listOf(SHORT_A_DISCHARGING, B, C),
            listOf(
                FULL_A_CHARGING, SHORT_A_DISCHARGING, FULL_A_DISCHARGING, B, C, SHORT_A_CHARGING, SHORT_A_DISCHARGING,
            ),
        )
        sequences.forEach { texts ->
            val sequenceHub = LiveFeedHub(clock = { nowMs })
            texts.forEach { sequenceHub.onBleEvent(notify(it + "\r\n")) }
            val reference = referenceReading(texts.map { it + "\r\n" })

            val replay = drainFrames(sequenceHub.subscribe())

            assertTrue(replay.isNotEmpty())
            assertEquals("viewer reading for $texts", reference, viewerReading(replay))
            assertEquals("now reading for $texts", reference, sequenceHub.now().reading)
            assertNotNull(sequenceHub.now().reading)
            replay.forEach { assertEquals(reference, it.reading) }
        }
    }

    @Test
    fun readingIsNullUntilSessionHasABC() {
        val subscription = hub.subscribe()
        drain(subscription)

        feed(FULL_A_CHARGING + "\r\n")
        feed(B + "\r\n")
        val early = drainFrames(subscription)
        assertEquals(listOf(FULL_A_CHARGING, B), early.map { it.line })
        assertTrue(early.all { it.reading == null })
        assertNull(hub.now().reading)

        feed(C + "\r\n")
        val last = drainFrames(subscription).single()
        assertEquals(referenceReading(listOf(FULL_A_CHARGING, B, C).map { it + "\r\n" }), last.reading)
    }

    @Test
    fun ignoredLinesAreRelayed() {
        val subscription = hub.subscribe()
        drain(subscription)

        feed(":X=1,2\r\n")

        val frame = drain(subscription).single() as LiveFeedMessage.Frame
        assertEquals(":X=1,2", frame.line)
        assertNull(frame.reading)
        assertFalse(frame.replay)
        assertTrue(drain(hub.subscribe()).none { it is LiveFeedMessage.Frame })
        assertTrue(hub.now().lines.values.all { it == null })
    }

    @Test
    fun linesSplitAcrossNotificationsAreRelayedOnceComplete() {
        val subscription = hub.subscribe()
        drain(subscription)

        feed(":A=1331,5200,1,", "320,1,\r\n")

        assertEquals(listOf(SHORT_A_CHARGING), drainFrames(subscription).map { it.line })
    }

    @Test
    fun connectingResetsReplayAndReading() {
        hub.onBleEvent(BleEvent.Connecting(device("Old meter")))
        feed(FULL_A_CHARGING + "\r\n", B + "\r\n", C + "\r\n")
        assertNotNull(hub.now().reading)

        hub.onBleEvent(BleEvent.Connecting(device("New meter")))

        assertEquals(
            listOf<LiveFeedMessage>(
                LiveFeedMessage.Hello(nowMs, "New meter", CollectionPhase.STOPPED),
                LiveFeedMessage.Link(nowMs, CollectionPhase.STOPPED, null, null),
            ),
            drain(hub.subscribe()),
        )
        val now = hub.now()
        assertNull(now.reading)
        assertEquals("New meter", now.meterName)
        assertEquals(setOf('A', 'B', 'C'), now.lines.keys)
        assertTrue(now.lines.values.all { it == null })
    }

    @Test
    fun connectingWithoutDeviceNameUsesTheDefaultName() {
        hub.onBleEvent(BleEvent.Connecting(device(null)))

        assertEquals("KMF Meter", hub.now().meterName)
    }

    @Test
    fun servicesDiscoveredResetsSessionAndKeepsName() {
        hub.onBleEvent(BleEvent.Connecting(device("Meter 7")))
        feed(FULL_A_CHARGING + "\r\n", B + "\r\n", C + "\r\n")

        hub.onBleEvent(BleEvent.ServicesDiscovered(GattProfile(UUID(1, 1), UUID(2, 2), UUID(3, 3), false)))

        assertEquals(2, drain(hub.subscribe()).size)
        assertNull(hub.now().reading)
        assertEquals("Meter 7", hub.now().meterName)
        assertTrue(hub.now().lines.values.all { it == null })
    }

    @Test
    fun otherBleEventsDoNotTouchTheSession() {
        feed(FULL_A_CHARGING + "\r\n", B + "\r\n", C + "\r\n")
        val before = hub.now()

        hub.onBleEvent(BleEvent.Disconnected("gone"))
        hub.onBleEvent(BleEvent.Error("boom"))
        hub.onBleEvent(BleEvent.WriteCompleted(":*".toByteArray(), true))

        assertEquals(before, hub.now())
    }

    @Test
    fun phaseChangeEmitsLinkWithRelativeDelay() {
        val subscription = hub.subscribe()
        drain(subscription)
        nowMs = 10_000L

        hub.onCollectionState(state(CollectionPhase.ERROR, error = "GATT 8", nextReconnectAtMs = 15_000L))
        hub.onCollectionState(state(CollectionPhase.ERROR, error = "GATT 8", nextReconnectAtMs = 15_000L))

        assertEquals(
            listOf<LiveFeedMessage>(LiveFeedMessage.Link(10_000L, CollectionPhase.ERROR, "GATT 8", 5_000L)),
            drain(subscription),
        )
        assertEquals(CollectionPhase.ERROR, hub.now().phase)
    }

    @Test
    fun errorRetryWithNewTimeEmitsLink() {
        val subscription = hub.subscribe()
        drain(subscription)
        nowMs = 10_000L

        hub.onCollectionState(state(CollectionPhase.ERROR, error = "GATT 8", nextReconnectAtMs = 15_000L))
        nowMs = 12_000L
        hub.onCollectionState(state(CollectionPhase.ERROR, error = "GATT 8", nextReconnectAtMs = 20_000L))

        assertEquals(
            listOf<LiveFeedMessage>(
                LiveFeedMessage.Link(10_000L, CollectionPhase.ERROR, "GATT 8", 5_000L),
                LiveFeedMessage.Link(12_000L, CollectionPhase.ERROR, "GATT 8", 8_000L),
            ),
            drain(subscription),
        )
    }

    @Test
    fun errorTextChangeEmitsLink() {
        val subscription = hub.subscribe()
        drain(subscription)
        nowMs = 10_000L

        hub.onCollectionState(state(CollectionPhase.ERROR, error = "GATT 8"))
        hub.onCollectionState(state(CollectionPhase.ERROR, error = "GATT 133"))

        assertEquals(
            listOf<LiveFeedMessage>(
                LiveFeedMessage.Link(10_000L, CollectionPhase.ERROR, "GATT 8", null),
                LiveFeedMessage.Link(10_000L, CollectionPhase.ERROR, "GATT 133", null),
            ),
            drain(subscription),
        )
    }

    @Test
    fun subscribeDuringErrorSendsCurrentLink() {
        nowMs = 10_000L
        hub.onCollectionState(state(CollectionPhase.ERROR, error = "GATT 8", nextReconnectAtMs = 15_000L))

        val first = drain(hub.subscribe())

        assertEquals(LiveFeedMessage.Hello(10_000L, "KMF Meter", CollectionPhase.ERROR), first[0])
        assertEquals(LiveFeedMessage.Link(10_000L, CollectionPhase.ERROR, "GATT 8", 5_000L), first[1])
        assertEquals(2, first.size)

        nowMs = 12_000L
        val later = drain(hub.subscribe())

        assertEquals(LiveFeedMessage.Link(12_000L, CollectionPhase.ERROR, "GATT 8", 3_000L), later[1])
    }

    @Test
    fun linkDelayIsNullWithoutRetryAndNeverNegative() {
        val subscription = hub.subscribe()
        drain(subscription)
        nowMs = 10_000L

        hub.onCollectionState(state(CollectionPhase.ERROR, error = "late", nextReconnectAtMs = 9_000L))
        hub.onCollectionState(state(CollectionPhase.COLLECTING))

        assertEquals(
            listOf<LiveFeedMessage>(
                LiveFeedMessage.Link(10_000L, CollectionPhase.ERROR, "late", 0L),
                LiveFeedMessage.Link(10_000L, CollectionPhase.COLLECTING, null, null),
            ),
            drain(subscription),
        )
    }

    @Test
    fun initialPhaseEmitsNoLink() {
        val subscription = hub.subscribe()
        drain(subscription)

        hub.onCollectionState(state(CollectionPhase.STOPPED))

        assertTrue(drain(subscription).isEmpty())
    }

    @Test
    fun slowClientIsDroppedWithoutBlockingProducer() {
        val small = LiveFeedHub(clock = { nowMs }, clientBufferCapacity = 4)
        val slow = small.subscribe()
        assertEquals(1, small.clientCount.value)

        repeat(20) { small.onBleEvent(notify(FULL_A_CHARGING + "\r\n")) }

        assertEquals(0, small.clientCount.value)
        assertTrue(drain(slow).size <= 4)
        assertTrue(slow.messages.tryReceive().isClosed)
        val fresh = small.subscribe()
        assertEquals(1, small.clientCount.value)
        assertTrue(drain(fresh).first() is LiveFeedMessage.Hello)
        small.onBleEvent(notify(B + "\r\n"))
        assertEquals(B, (drain(fresh).single() as LiveFeedMessage.Frame).line)
    }

    @Test
    fun clientWhoseReplayDoesNotFitIsClosedImmediately() {
        val small = LiveFeedHub(clock = { nowMs }, clientBufferCapacity = 2)
        small.onBleEvent(notify(FULL_A_CHARGING + "\r\n"))
        small.onBleEvent(notify(B + "\r\n"))
        small.onBleEvent(notify(C + "\r\n"))

        val subscription = small.subscribe()

        assertEquals(0, small.clientCount.value)
        assertEquals(2, drain(subscription).size)
        assertTrue(subscription.messages.tryReceive().isClosed)
    }

    @Test
    fun twoClientsBothReceiveLiveFrames() {
        val first = hub.subscribe()
        val second = hub.subscribe()
        assertEquals(2, hub.clientCount.value)
        drain(first)
        drain(second)

        feed(FULL_A_CHARGING + "\r\n")

        assertEquals(listOf(FULL_A_CHARGING), drainFrames(first).map { it.line })
        assertEquals(listOf(FULL_A_CHARGING), drainFrames(second).map { it.line })
    }

    @Test
    fun closingASubscriptionRemovesItAndStopsDelivery() {
        val first = hub.subscribe()
        val second = hub.subscribe()
        drain(first)
        drain(second)

        first.close()
        first.close()
        feed(FULL_A_CHARGING + "\r\n")

        assertEquals(1, hub.clientCount.value)
        assertTrue(drain(first).isEmpty())
        assertTrue(first.messages.tryReceive().isClosed)
        assertEquals(1, drain(second).size)
    }

    @Test
    fun nowSnapshotHasLastLinePerType() {
        assertEquals(mapOf('A' to null, 'B' to null, 'C' to null), hub.now().lines)

        nowMs = 4_000L
        feed(FULL_A_CHARGING + "\r\n", B + "\r\n", C + "\r\n", SHORT_A_DISCHARGING + "\r\n", ":B=9,9,\r\n")
        hub.onCollectionState(state(CollectionPhase.COLLECTING))

        val now = hub.now()
        assertEquals(4_000L, now.ms)
        assertEquals("KMF Meter", now.meterName)
        assertEquals(CollectionPhase.COLLECTING, now.phase)
        assertEquals(mapOf('A' to SHORT_A_DISCHARGING, 'B' to ":B=9,9,", 'C' to C), now.lines)
        assertNotNull(now.reading)
    }

    private companion object {
        const val FULL_A_CHARGING = ":A=1331,5200,1,320,150000,1800,"
        const val FULL_A_DISCHARGING = ":A=1290,4100,0,2640,120500,1800,"
        const val SHORT_A_CHARGING = ":A=1331,5200,1,320,1,"
        const val SHORT_A_DISCHARGING = ":A=1331,5200,0,320,1,"
        const val B = ":B=1,2,3,"
        const val C = ":C=1234,5678,"
    }
}
