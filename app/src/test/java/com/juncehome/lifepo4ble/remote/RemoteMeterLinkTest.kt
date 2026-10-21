package com.juncehome.lifepo4ble.remote

import com.juncehome.lifepo4ble.ble.BleEvent
import com.juncehome.lifepo4ble.ble.ScannedDevice
import com.juncehome.lifepo4ble.collection.CollectionPhase
import com.juncehome.lifepo4ble.collection.CollectionState
import com.juncehome.lifepo4ble.collection.CollectionVisibility
import com.juncehome.lifepo4ble.protocol.KmfLineParser
import com.juncehome.lifepo4ble.protocol.KmfReading
import com.juncehome.lifepo4ble.protocol.KmfReadingMerger
import com.juncehome.lifepo4ble.ui.BleStateReducer
import com.juncehome.lifepo4ble.ui.BleUiState
import com.juncehome.lifepo4ble.ui.ConnectionState
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMeterLinkTest {
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()
    private val hub = LiveFeedHub()
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val linkScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val servers = mutableListOf<LiveFeedHttpServer>()
    private val links = mutableListOf<RemoteMeterLink>()
    private val raw = CopyOnWriteArrayList<Closeable>()

    @After
    fun tearDown() {
        links.forEach { it.stop() }
        servers.forEach { it.stop() }
        raw.forEach { it.close() }
        collectorScope.cancel()
        linkScope.cancel()
        serverScope.cancel()
    }

    /** Real sockets and real time; every wait is a suspension, so the timeout always applies. */
    private fun timed(block: suspend () -> Unit) = runBlocking { withTimeout(10_000L) { block() } }

    private fun startServer(
        hub: LiveFeedHub = this.hub,
        port: Int = 0,
        pingIntervalMs: Long = 100L,
    ): LiveFeedHttpServer {
        val server = LiveFeedHttpServer(hub = hub, port = port, pingIntervalMs = pingIntervalMs, bindAddress = loopback)
        servers += server
        server.start(serverScope)
        return server
    }

    private fun newLink(readTimeoutMs: Int = 40_000): RemoteMeterLink =
        RemoteMeterLink(scope = linkScope, readTimeoutMs = readTimeoutMs).also { links += it }

    private fun addressOf(port: Int) = RemoteAddress(loopback.hostAddress.orEmpty(), port)

    /** Collects the link events from now on; a SharedFlow has no replay, so this runs before `start`. */
    private fun collectEvents(link: RemoteMeterLink): Channel<TimedBleEvent> {
        val received = Channel<TimedBleEvent>(Channel.UNLIMITED)
        collectorScope.launch(start = CoroutineStart.UNDISPATCHED) { link.events.collect { received.send(it) } }
        return received
    }

    private suspend fun awaitState(link: RemoteMeterLink, matches: (CollectionState) -> Boolean): CollectionState =
        link.state.filterNotNull().first(matches)

    private fun notify(text: String) = BleEvent.NotificationReceived(UUID(0, 0), text.toByteArray())

    private fun feedHome(into: LiveFeedHub = hub) {
        into.onBleEvent(BleEvent.Connecting(ScannedDevice("Meter 7", "00:00:00:00:00:00", -50, emptyList())))
        into.onCollectionState(CollectionState(CollectionPhase.COLLECTING, CollectionVisibility.BACKGROUND))
        listOf(FULL_A, B, C, SHORT_A).forEach { into.onBleEvent(notify(it + "\r\n")) }
    }

    /** What the home phone does with the same lines: parse and merge every frame. */
    private fun homeReading(vararg lines: String): KmfReading {
        val parser = KmfLineParser()
        var reading = KmfReading()
        lines.forEach { line ->
            parser.offer((line + "\r\n").toByteArray())
            parser.lastOfferFrames.forEach { reading = KmfReadingMerger.apply(reading, it) }
        }
        return reading
    }

    /**
     * Accepts connections on a plain socket, answers each with the stream headers and a `hello`, then says nothing.
     * Every answered connection is announced on the returned channel.
     */
    private fun silentServer(): Pair<ServerSocket, Channel<Unit>> {
        val socket = ServerSocket(0, 50, loopback)
        raw += socket
        val answered = Channel<Unit>(Channel.UNLIMITED)
        val hello = LiveFeedMessage.Hello(System.currentTimeMillis(), "Meter 7", CollectionPhase.COLLECTING)
        val response = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\n" +
            "Connection: close\r\n\r\n" + SseFormat.event(LiveFeedJson.eventName(hello), LiveFeedJson.encode(hello))
        thread(isDaemon = true, name = "silent-server") {
            try {
                while (true) {
                    val client = socket.accept()
                    raw += client
                    val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    while (reader.readLine().orEmpty().isNotEmpty()) {
                        // Skip the request head.
                    }
                    client.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
                    client.getOutputStream().flush()
                    answered.trySend(Unit)
                }
            } catch (_: IOException) {
                // The test closed the sockets.
            }
        }
        return socket to answered
    }

    @Test
    fun sameCodePathReadingMatchesHome() = timed {
        feedHome()
        val server = startServer()
        val link = newLink()
        val received = collectEvents(link)

        link.start(addressOf(server.boundPort))

        // Connecting, Connected and the four replayed lines (full A, short A, B, C).
        val parser = KmfLineParser()
        var ui = BleUiState()
        repeat(6) {
            val timed = received.receive()
            ui = BleStateReducer.reduce(ui, timed.event, parser, timed.atMs)
        }
        assertEquals(homeReading(FULL_A, B, C, SHORT_A), ui.latestReading)
        assertTrue(ui.hasAFrame && ui.hasBFrame)
        assertEquals(ConnectionState.CONNECTED, ui.connectionState)
        assertEquals("remote:127.0.0.1:${server.boundPort}", ui.selectedDevice?.address)
        assertEquals("Meter 7", ui.selectedDevice?.name)
        assertEquals(CollectionPhase.COLLECTING, link.state.value?.phase)
    }

    @Test
    fun serverGoneMarksUnreachableAndRetries() = timed {
        feedHome()
        val server = startServer()
        val port = server.boundPort
        val link = newLink()
        link.start(addressOf(port))
        awaitState(link) { it.phase == CollectionPhase.COLLECTING }

        server.stop()

        val unreachable = awaitState(link) { it.phase == CollectionPhase.ERROR && it.remoteUnreachable }
        assertNotNull(unreachable.nextReconnectAtMs)
        assertNotNull(unreachable.error)
        startServer(port = port)
        val back = awaitState(link) { it.phase == CollectionPhase.COLLECTING }
        assertFalse(back.remoteUnreachable)
        assertNull(back.nextReconnectAtMs)
    }

    @Test
    fun silentServerTriggersReconnectAfterReadTimeout() = timed {
        val (socket, answered) = silentServer()
        val link = newLink(readTimeoutMs = 300)

        link.start(addressOf(socket.localPort))

        answered.receive()
        val timedOut = awaitState(link) { it.phase == CollectionPhase.ERROR && it.remoteUnreachable }
        assertTrue(timedOut.error.orEmpty(), timedOut.error.orEmpty().contains("timed out", ignoreCase = true))
        withTimeout(3_000L) { answered.receive() }
    }

    @Test
    fun stopSetsStateNullAndClosesTheConnection() = timed {
        feedHome()
        // No pings: once the replay is read, the link sits in a blocked read and nothing but a disconnect can end it.
        val server = startServer(pingIntervalMs = 60_000L)
        val link = newLink()
        val received = collectEvents(link)
        link.start(addressOf(server.boundPort))
        repeat(6) { received.receive() }
        assertEquals(1, hub.clientCount.value)

        link.stop()

        assertNull(link.state.value)
        hub.clientCount.first { it == 0 }
        delay(300L)
        assertNull(link.state.value)
    }

    @Test
    fun startingAnotherAddressReplacesTheRunningLoop() = timed {
        feedHome()
        val otherHub = LiveFeedHub()
        feedHome(otherHub)
        val first = startServer()
        val second = startServer(otherHub)
        val link = newLink()
        link.start(addressOf(first.boundPort))
        awaitState(link) { it.phase == CollectionPhase.COLLECTING }

        link.start(addressOf(second.boundPort))

        val moved = awaitState(link) {
            it.deviceAddress == "remote:127.0.0.1:${second.boundPort}" && it.phase == CollectionPhase.COLLECTING
        }
        assertNull(moved.error)
        hub.clientCount.first { it == 0 }
        assertEquals(1, otherHub.clientCount.value)
    }

    @Test
    fun startingTheSameAddressAgainKeepsTheConnection() = timed {
        feedHome()
        val server = startServer()
        val link = newLink()
        val received = collectEvents(link)
        val address = addressOf(server.boundPort)
        link.start(address)
        // Connecting, Connected and the four replayed lines: everything the first connection produces.
        repeat(6) { received.receive() }

        link.start(address)
        delay(300L)

        assertTrue(received.tryReceive().isFailure)
        assertEquals(1, hub.clientCount.value)
    }

    @Test
    fun reconnectNowSkipsTheWaitBeforeTheNextAttempt() = timed {
        // A stopped home phone is retried only every 15 s, which is longer than this test's timeout.
        val server = startServer(LiveFeedHub())
        val port = server.boundPort
        val link = newLink()
        link.start(addressOf(port))
        awaitState(link) { it.phase == CollectionPhase.ERROR && !it.remoteUnreachable }
        server.stop()
        feedHome()
        startServer(port = port)

        link.reconnectNow()

        awaitState(link) { it.phase == CollectionPhase.COLLECTING }
    }

    @Test
    fun unexpectedExceptionIsRetriedLikeAStreamEnd() = timed {
        // The JDK throws a RuntimeException, not an IOException, for a port outside 1..65535.
        val link = newLink()

        link.start(RemoteAddress("127.0.0.1", 70000))

        val failed = awaitState(link) { it.phase == CollectionPhase.ERROR && it.remoteUnreachable }
        assertNotNull(failed.nextReconnectAtMs)
        assertTrue(failed.error.orEmpty().isNotBlank())
        // The loop is alive: it tries again after its backoff and counts the second failure.
        awaitState(link) { it.reconnectAttempt >= 2 }
    }

    @Test
    fun deadLoopClearsStateAndCanBeStartedAgain() = timed {
        val broken = AtomicBoolean(false)
        val link = RemoteMeterLink(scope = linkScope, clock = {
            check(!broken.get()) { "clock broke" }
            System.currentTimeMillis()
        }).also { links += it }
        val address = RemoteAddress("127.0.0.1", 70000)
        link.start(address)
        awaitState(link) { it.phase == CollectionPhase.ERROR }

        // The next attempt reads the clock outside the network code, which nothing catches: the loop dies.
        broken.set(true)

        link.state.first { it == null }
        broken.set(false)
        link.start(address)
        awaitState(link) { it.phase == CollectionPhase.ERROR }
    }

    private companion object {
        const val FULL_A = ":A=1331,5200,1,320,150000,1800,"
        const val SHORT_A = ":A=1331,5200,1,320,1,"
        const val B = ":B=1,2,3,"
        const val C = ":C=1234,5678,"
    }
}
