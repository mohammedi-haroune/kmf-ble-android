package com.juncehome.lifepo4ble.remote

import com.juncehome.lifepo4ble.ble.BleEvent
import com.juncehome.lifepo4ble.ble.ScannedDevice
import com.juncehome.lifepo4ble.collection.CollectionPhase
import com.juncehome.lifepo4ble.collection.CollectionVisibility
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteLinkMachineTest {
    private val machine = RemoteLinkMachine(RemoteAddress("h", 8765))

    private fun hello(ms: Long = 1_000L, phase: CollectionPhase = CollectionPhase.COLLECTING) =
        LiveFeedMessage.Hello(ms, "KMF-BT-5A3C", phase)

    private fun frame(ms: Long, line: String = ":B=1,0,0,0,") = LiveFeedMessage.Frame(ms, line, reading = null)

    private fun link(phase: CollectionPhase, error: String? = null, nextReconnectInMs: Long? = null) =
        LiveFeedMessage.Link(ms = 0L, phase = phase, error = error, nextReconnectInMs = nextReconnectInMs)

    private fun List<TimedBleEvent>.connecting() = map { it.event }.filterIsInstance<BleEvent.Connecting>()

    @Test
    fun connectAttemptEmitsConnectingWithRemoteIdentity() {
        val events = machine.onConnectAttempt(nowMs = 5_000L)

        val connecting = events.single()
        assertEquals(5_000L, connecting.atMs)
        val device = (connecting.event as BleEvent.Connecting).device
        assertEquals("remote:h:8765", device.address)
        assertNull(device.name)
        assertEquals(0, device.rssi)
        assertTrue(device.serviceUuids.isEmpty())
        assertEquals(CollectionPhase.CONNECTING, machine.state.phase)
        assertEquals(CollectionVisibility.FOREGROUND, machine.state.visibility)
        assertEquals("remote:h:8765", machine.state.deviceAddress)
    }

    @Test
    fun helloEmitsConnectedAndMapsPhase() {
        machine.onConnectAttempt(nowMs = 0L)

        val events = machine.onMessage(hello(), nowMs = 61_000L)

        val connected = events.single()
        assertEquals(61_000L, connected.atMs)
        assertEquals(
            BleEvent.Connected(ScannedDevice("KMF-BT-5A3C", "remote:h:8765", 0, emptyList())),
            connected.event,
        )
        assertEquals(CollectionPhase.COLLECTING, machine.state.phase)
        assertFalse(machine.state.remoteUnreachable)
        assertNull(machine.state.error)
        assertNull(machine.state.nextReconnectAtMs)
        assertEquals(0, machine.state.reconnectAttempt)
        assertNull(machine.state.linkDroppedAtMs)
    }

    @Test
    fun helloPhaseOtherThanCollectingIsMappedAsIs() {
        machine.onMessage(hello(phase = CollectionPhase.BOOTSTRAPPING), nowMs = 10L)

        assertEquals(CollectionPhase.BOOTSTRAPPING, machine.state.phase)
    }

    @Test
    fun frameBecomesNotificationWithTerminatorAndHomeTime() {
        machine.onMessage(hello(ms = 1_000L), nowMs = 1_000L)

        val event = machine.onMessage(frame(ms = 1_200L), nowMs = 1_300L).single()

        val notification = event.event as BleEvent.NotificationReceived
        assertEquals(UUID(0L, 0L), notification.characteristicUuid)
        assertArrayEquals(":B=1,0,0,0,\r\n".toByteArray(Charsets.US_ASCII), notification.bytes)
        assertEquals(1_200L, event.atMs)
    }

    @Test
    fun frameTimeUsesClockOffsetAndNeverFuture() {
        machine.onMessage(hello(ms = 1_000L), nowMs = 61_000L)

        assertEquals(62_000L, machine.onMessage(frame(ms = 2_000L), nowMs = 62_500L).single().atMs)
        assertEquals(62_500L, machine.onMessage(frame(ms = 5_000L), nowMs = 62_500L).single().atMs)
    }

    @Test
    fun linkErrorUsesRelativeDelay() {
        machine.onMessage(hello(), nowMs = 90_000L)

        machine.onMessage(link(CollectionPhase.ERROR, "GATT 8", nextReconnectInMs = 5_000L), nowMs = 100_000L)

        assertEquals(CollectionPhase.ERROR, machine.state.phase)
        assertEquals("GATT 8", machine.state.error)
        assertEquals(105_000L, machine.state.nextReconnectAtMs)
        assertFalse(machine.state.remoteUnreachable)
    }

    @Test
    fun linkRightAfterHelloWithSamePhaseEmitsNothing() {
        machine.onMessage(hello(phase = CollectionPhase.CONNECTING), nowMs = 1_000L)

        assertTrue(machine.onMessage(link(CollectionPhase.CONNECTING), nowMs = 1_000L).isEmpty())
        assertEquals(CollectionPhase.CONNECTING, machine.state.phase)
    }

    @Test
    fun homeReconnectEmitsConnectingForSameMeter() {
        val first = machine.onMessage(hello(), nowMs = 1_000L).single().event as BleEvent.Connected
        machine.onMessage(link(CollectionPhase.COLLECTING), nowMs = 1_000L)

        val events = machine.onMessage(link(CollectionPhase.CONNECTING), nowMs = 2_000L)

        val connecting = events.single()
        assertEquals(2_000L, connecting.atMs)
        val device = (connecting.event as BleEvent.Connecting).device
        assertEquals(first.device, device)
        assertEquals("remote:h:8765", device.address)
        assertEquals(CollectionPhase.CONNECTING, machine.state.phase)
    }

    @Test
    fun homeStaysConnectingEmitsConnectingOnlyOnce() {
        machine.onMessage(hello(), nowMs = 1_000L)

        assertEquals(1, machine.onMessage(link(CollectionPhase.CONNECTING), nowMs = 2_000L).connecting().size)
        assertTrue(machine.onMessage(link(CollectionPhase.CONNECTING, error = "late"), nowMs = 3_000L).isEmpty())
    }

    @Test
    fun homeStoppedMapsToPausedWithoutTimer() {
        machine.onMessage(hello(phase = CollectionPhase.STOPPED), nowMs = 1_000L)
        assertPaused()

        machine.onMessage(hello(), nowMs = 2_000L)
        assertEquals(CollectionPhase.COLLECTING, machine.state.phase)

        val events = machine.onMessage(
            link(CollectionPhase.STOPPED, error = "Sharing is off on the home phone", nextReconnectInMs = 5_000L),
            nowMs = 3_000L,
        )

        assertTrue(events.isEmpty())
        assertPaused(error = "Sharing is off on the home phone")
    }

    @Test
    fun homeStoppedWithoutErrorTextUsesTheCollectionOffText() {
        machine.onMessage(hello(), nowMs = 1_000L)

        machine.onMessage(link(CollectionPhase.STOPPED), nowMs = 2_000L)

        assertPaused()
    }

    @Test
    fun helloWhileHomeStoppedHasNoErrorFieldSoItUsesTheCollectionOffText() {
        machine.onMessage(hello(phase = CollectionPhase.STOPPED), nowMs = 1_000L)

        assertPaused()
    }

    @Test
    fun streamEndAfterAStoppedLinkKeepsTheHomeProvidedText() {
        machine.onMessage(hello(), nowMs = 1_000L)
        machine.onMessage(link(CollectionPhase.STOPPED, error = "Sharing is off on the home phone"), nowMs = 2_000L)

        machine.onStreamEnded("Home phone closed the stream", nowMs = 2_050L)

        assertPaused(error = "Sharing is off on the home phone")
        machine.onConnectAttempt(nowMs = 17_000L)
        machine.onStreamEnded("connection refused", nowMs = 17_100L)
        assertPaused(error = "Sharing is off on the home phone")
    }

    @Test
    fun homeStoppedClearsWhenTheHomePhoneResumes() {
        machine.onMessage(hello(), nowMs = 1_000L)
        machine.onMessage(link(CollectionPhase.STOPPED, error = "Sharing is off on the home phone"), nowMs = 2_000L)
        assertTrue(machine.state.homeStopped)

        machine.onMessage(hello(ms = 20_000L), nowMs = 20_000L)

        assertFalse(machine.state.homeStopped)
        assertEquals(CollectionPhase.COLLECTING, machine.state.phase)
        assertNull(machine.state.error)
    }

    @Test
    fun onlyThePausedStateIsMarkedHomeStopped() {
        assertFalse(machine.state.homeStopped)
        machine.onConnectAttempt(nowMs = 0L)
        assertFalse(machine.state.homeStopped)
        machine.onMessage(hello(), nowMs = 1_000L)
        assertFalse(machine.state.homeStopped)
        machine.onMessage(link(CollectionPhase.ERROR, error = "GATT 8", nextReconnectInMs = 5_000L), nowMs = 2_000L)
        assertFalse(machine.state.homeStopped)
        machine.onStreamEnded("timeout", nowMs = 3_000L)
        assertFalse(machine.state.homeStopped)
    }

    @Test
    fun streamEndAfterHomeStoppedKeepsPausedAndRetriesSlowly() {
        machine.onMessage(hello(), nowMs = 1_000L)
        machine.onMessage(link(CollectionPhase.STOPPED), nowMs = 2_000L)

        val retry = machine.onStreamEnded("stream closed", nowMs = 3_000L)

        assertEquals(15_000L, retry.delayMs)
        assertEquals(listOf(BleEvent.Disconnected("stream closed")), retry.events.map { it.event })
        assertEquals(3_000L, retry.events.single().atMs)
        assertPaused()
    }

    @Test
    fun failedRetryAfterHomeStoppedShowsPausedAgain() {
        machine.onMessage(hello(phase = CollectionPhase.STOPPED), nowMs = 1_000L)
        machine.onStreamEnded("stream closed", nowMs = 2_000L)
        machine.onConnectAttempt(nowMs = 17_000L)
        assertEquals(CollectionPhase.CONNECTING, machine.state.phase)

        val retry = machine.onStreamEnded("connection refused", nowMs = 17_100L)

        assertEquals(15_000L, retry.delayMs)
        assertPaused()
    }

    @Test
    fun streamEndSchedulesBackoffAndMarksUnreachable() {
        machine.onMessage(hello(), nowMs = 1_000L)

        val delays = mutableListOf<Long>()
        val attempts = mutableListOf<Int>()
        val firstDrop = 50_000L
        var now = firstDrop
        repeat(5) {
            val retry = machine.onStreamEnded("timeout", nowMs = now)
            delays += retry.delayMs
            attempts += machine.state.reconnectAttempt
            assertEquals(CollectionPhase.ERROR, machine.state.phase)
            assertEquals("timeout", machine.state.error)
            assertEquals(now + retry.delayMs, machine.state.nextReconnectAtMs)
            assertTrue(machine.state.remoteUnreachable)
            assertEquals(firstDrop, machine.state.linkDroppedAtMs)
            assertEquals(listOf(BleEvent.Disconnected("timeout")), retry.events.map { it.event })
            now += retry.delayMs + 100L
            machine.onConnectAttempt(nowMs = now)
        }

        assertEquals(listOf(1_000L, 2_000L, 5_000L, 15_000L, 15_000L), delays)
        assertEquals(listOf(1, 2, 3, 4, 5), attempts)
    }

    @Test
    fun connectingStateKeepsTheFailureStreak() {
        machine.onMessage(hello(), nowMs = 1_000L)
        machine.onStreamEnded("timeout", nowMs = 2_000L)

        machine.onConnectAttempt(nowMs = 3_000L)

        assertEquals(CollectionPhase.CONNECTING, machine.state.phase)
        assertEquals(1, machine.state.reconnectAttempt)
        assertEquals(2_000L, machine.state.linkDroppedAtMs)
        assertFalse(machine.state.remoteUnreachable)
        assertNull(machine.state.nextReconnectAtMs)
    }

    @Test
    fun helloResetsBackoff() {
        machine.onStreamEnded("timeout", nowMs = 1_000L)
        machine.onStreamEnded("timeout", nowMs = 2_000L)

        machine.onMessage(hello(), nowMs = 3_000L)

        assertEquals(0, machine.state.reconnectAttempt)
        assertNull(machine.state.linkDroppedAtMs)
        assertFalse(machine.state.remoteUnreachable)
        val retry = machine.onStreamEnded("timeout", nowMs = 4_000L)
        assertEquals(1_000L, retry.delayMs)
        assertEquals(1, machine.state.reconnectAttempt)
        assertEquals(4_000L, machine.state.linkDroppedAtMs)
    }

    @Test
    fun reconnectNowResetsBackoff() {
        machine.onStreamEnded("timeout", nowMs = 1_000L)
        machine.onStreamEnded("timeout", nowMs = 2_000L)
        assertEquals(5_000L, machine.onStreamEnded("timeout", nowMs = 3_000L).delayMs)

        machine.onReconnectNow()

        assertEquals(1_000L, machine.onStreamEnded("timeout", nowMs = 4_000L).delayMs)
        assertEquals(1, machine.state.reconnectAttempt)
    }

    private fun assertPaused(error: String = "Collection is off on the home phone") {
        assertEquals(CollectionPhase.ERROR, machine.state.phase)
        assertEquals(error, machine.state.error)
        assertTrue(machine.state.homeStopped)
        assertNull(machine.state.nextReconnectAtMs)
        assertFalse(machine.state.remoteUnreachable)
        assertEquals(0, machine.state.reconnectAttempt)
        assertNull(machine.state.linkDroppedAtMs)
    }
}
