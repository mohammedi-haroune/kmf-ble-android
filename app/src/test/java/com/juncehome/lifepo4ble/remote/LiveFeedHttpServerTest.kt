package com.juncehome.lifepo4ble.remote

import com.juncehome.lifepo4ble.ble.BleEvent
import com.juncehome.lifepo4ble.ble.ScannedDevice
import com.juncehome.lifepo4ble.collection.CollectionPhase
import com.juncehome.lifepo4ble.collection.CollectionState
import com.juncehome.lifepo4ble.collection.CollectionVisibility
import com.juncehome.lifepo4ble.protocol.KmfReading
import java.io.Closeable
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveFeedHttpServerTest {
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()
    private val hub = LiveFeedHub(clock = { 1_000L })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val servers = mutableListOf<LiveFeedHttpServer>()
    private val clients = mutableListOf<Closeable>()

    @After
    fun tearDown() {
        clients.forEach { it.close() }
        servers.forEach { it.stop() }
        scope.cancel()
    }

    /** Blocking socket reads cannot be cancelled, so every client also has a socket read timeout. */
    private fun timed(block: suspend () -> Unit) = runBlocking { withTimeout(5_000L) { block() } }

    private fun startServer(
        hub: LiveFeedHub = this.hub,
        port: Int = 0,
        maxClients: Int = 4,
        pingIntervalMs: Long = 15_000L,
        requestTimeoutMs: Long = 10_000L,
        writeStallTimeoutMs: Long = 30_000L,
        finalLinkGraceMs: Long = 300L,
    ): LiveFeedHttpServer {
        val server = LiveFeedHttpServer(
            hub = hub,
            port = port,
            maxClients = maxClients,
            pingIntervalMs = pingIntervalMs,
            bindAddress = loopback,
            requestTimeoutMs = requestTimeoutMs,
            writeStallTimeoutMs = writeStallTimeoutMs,
            finalLinkGraceMs = finalLinkGraceMs,
        )
        servers += server
        server.start(scope)
        return server
    }

    private fun notify(text: String) = BleEvent.NotificationReceived(UUID(0, 0), text.toByteArray())

    private fun feedAbc() {
        hub.onBleEvent(BleEvent.Connecting(ScannedDevice("Meter 7", "00:00:00:00:00:00", -50, emptyList())))
        hub.onCollectionState(CollectionState(CollectionPhase.COLLECTING, CollectionVisibility.BACKGROUND))
        listOf(FULL_A, B, C).forEach { hub.onBleEvent(notify(it + "\r\n")) }
    }

    private fun get(path: String) = "GET $path HTTP/1.1\r\nHost: test\r\n\r\n"

    /** A plain socket client: sends [request] right away and parses the status line and headers. */
    private inner class Client(port: Int, request: String?) {
        val socket = Socket(loopback, port).also {
            it.soTimeout = 5_000
            clients += it
        }
        private val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
        private val sse = SseLineReader()
        lateinit var statusLine: String
        val headers = mutableMapOf<String, String>()

        init {
            if (request != null) {
                socket.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
                socket.getOutputStream().flush()
                statusLine = reader.readLine()
                while (true) {
                    val line = reader.readLine()
                    if (line.isNullOrEmpty()) break
                    headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                }
            }
        }

        val status: Int get() = statusLine.split(' ')[1].toInt()

        fun readLine(): String? = reader.readLine()

        fun readBody(): String = reader.readText()

        fun nextMessage(): LiveFeedMessage {
            while (true) {
                val line = readLine() ?: error("stream ended before the next event")
                val event = sse.offer(line) ?: continue
                return LiveFeedJson.decode(event.name, event.data) ?: error("undecodable event $event")
            }
        }

        /** Every message up to the server closing the connection; fails the test on a reset. */
        fun messagesUntilEof(): List<LiveFeedMessage> {
            val messages = mutableListOf<LiveFeedMessage>()
            while (true) {
                val line = readLine() ?: return messages
                val event = sse.offer(line) ?: continue
                messages += LiveFeedJson.decode(event.name, event.data) ?: error("undecodable event $event")
            }
        }

        /** Reads until the server closes the connection; fails the test on a reset. */
        fun awaitEof() {
            while (readLine() != null) {
                // Keep reading: EOF is the only exit.
            }
        }
    }

    @Test
    fun liveStreamsHelloReplayAndFrames() = timed {
        feedAbc()
        val server = startServer()

        val client = Client(server.boundPort, get("/v1/live"))

        assertEquals(200, client.status)
        assertTrue(client.headers.getValue("content-type").startsWith("text/event-stream"))
        assertEquals("no-cache", client.headers["cache-control"])
        assertEquals("close", client.headers["connection"])
        val hello = client.nextMessage() as LiveFeedMessage.Hello
        assertEquals("Meter 7", hello.meterName)
        assertEquals(CollectionPhase.COLLECTING, hello.phase)
        assertTrue(client.nextMessage() is LiveFeedMessage.Link)
        val replay = List(3) { client.nextMessage() as LiveFeedMessage.Frame }
        assertEquals(listOf(FULL_A, B, C), replay.map { it.line })
        assertTrue(replay.all { it.replay })

        hub.onBleEvent(notify(SHORT_A + "\r\n"))
        val live = client.nextMessage() as LiveFeedMessage.Frame
        assertEquals(SHORT_A, live.line)
        assertFalse(live.replay)
        assertNotNull(live.reading)
    }

    @Test
    fun nowReturnsSnapshotJson() = timed {
        feedAbc()
        val server = startServer()

        val client = Client(server.boundPort, get("/v1/now"))
        val body = client.readBody()

        assertEquals(200, client.status)
        assertTrue(client.headers.getValue("content-type").startsWith("application/json"))
        assertEquals(body.toByteArray(Charsets.UTF_8).size, client.headers.getValue("content-length").toInt())
        val json = JSONObject(body)
        assertEquals(1, json.getInt("v"))
        assertEquals("COLLECTING", json.getString("phase"))
        assertEquals(FULL_A, json.getJSONObject("lines").getString("A"))
        assertFalse(json.isNull("reading"))
    }

    @Test
    fun queryStringIsIgnored() = timed {
        val server = startServer()

        assertEquals(200, Client(server.boundPort, get("/v1/now?pretty=1")).status)
        assertEquals(200, Client(server.boundPort, get("/v1/live?since=0")).status)
    }

    @Test
    fun unknownPathIs404() = timed {
        val server = startServer()

        assertEquals(404, Client(server.boundPort, get("/v2/live")).status)
        assertEquals(404, Client(server.boundPort, get("/")).status)
    }

    @Test
    fun postIs405() = timed {
        val server = startServer()

        val client = Client(
            server.boundPort,
            "POST /v1/live HTTP/1.1\r\nHost: test\r\nContent-Length: 4\r\n\r\nabcd",
        )

        assertEquals(405, client.status)
        assertEquals("GET", client.headers["allow"])
    }

    @Test
    fun malformedRequestLineIs400() = timed {
        val server = startServer()

        assertEquals(400, Client(server.boundPort, "NONSENSE\r\n\r\n").status)
    }

    @Test
    fun oversizedRequestHeadIs431() = timed {
        val server = startServer()

        val client = Client(server.boundPort, "GET /v1/now HTTP/1.1\r\nX-Pad: ${"a".repeat(9_000)}\r\n\r\n")

        assertEquals(431, client.status)
    }

    @Test
    fun silentClientIsClosedAfterRequestTimeout() = timed {
        val server = startServer(requestTimeoutMs = 200L)

        val client = Client(server.boundPort, request = null)

        assertEquals(-1, client.socket.getInputStream().read())
    }

    @Test
    fun clientBeyondCapEvictsOldestStream() = timed {
        val server = startServer(maxClients = 1)
        val first = Client(server.boundPort, get("/v1/live"))
        assertEquals(200, first.status)
        first.nextMessage()
        assertEquals(1, hub.clientCount.value)

        val second = Client(server.boundPort, get("/v1/live"))

        assertEquals(200, second.status)
        assertTrue(second.nextMessage() is LiveFeedMessage.Hello)
        first.awaitEof()
        assertEquals(1, hub.clientCount.value)
        assertEquals(200, Client(server.boundPort, get("/v1/now")).status)
    }

    @Test
    fun evictionTakesTheOldestOfSeveralStreams() = timed {
        val server = startServer(maxClients = 2)
        val oldest = Client(server.boundPort, get("/v1/live"))
        oldest.nextMessage()
        val middle = Client(server.boundPort, get("/v1/live"))
        middle.nextMessage()
        assertEquals(2, hub.clientCount.value)

        val newest = Client(server.boundPort, get("/v1/live"))
        newest.nextMessage()

        oldest.awaitEof()
        assertEquals(2, hub.clientCount.value)
        hub.onBleEvent(notify(FULL_A + "\r\n"))
        listOf(middle, newest).forEach { client ->
            var message = client.nextMessage()
            while (message !is LiveFeedMessage.Frame) message = client.nextMessage()
            assertEquals(FULL_A, message.line)
        }
    }

    @Test
    fun clientDisconnectReleasesSubscription() = timed {
        val server = startServer(maxClients = 1)
        val first = Client(server.boundPort, get("/v1/live"))
        first.nextMessage()
        assertEquals(1, hub.clientCount.value)

        first.socket.close()
        hub.clientCount.first { it == 0 }

        val second = Client(server.boundPort, get("/v1/live"))
        assertEquals(200, second.status)
        assertTrue(second.nextMessage() is LiveFeedMessage.Hello)
        assertEquals(1, hub.clientCount.value)
    }

    @Test
    fun nonReadingClientIsClosedByWriteStallWatchdog() = timed {
        // Roomy hub buffer: the hub must not be the one that drops this client, only the watchdog may.
        val roomy = LiveFeedHub(clock = { 1_000L }, clientBufferCapacity = 1_000)
        val server = startServer(hub = roomy, writeStallTimeoutMs = 300L)
        val silent = Socket().also {
            it.receiveBufferSize = 1_024
            it.soTimeout = 5_000
            clients += it
            it.connect(InetSocketAddress(loopback, server.boundPort))
        }
        silent.getOutputStream().write(get("/v1/live").toByteArray(Charsets.ISO_8859_1))
        silent.getOutputStream().flush()
        roomy.clientCount.first { it == 1 }

        // The kernel absorbs about the client's receive buffer plus the server's 16 KiB send buffer before a write
        // blocks. Linux honors the tiny receive buffer set above; macOS ignores it (its loopback buffer is about
        // 320 KB). Feed twice what can be absorbed, measured on this socket, so the client never reading always
        // blocks the server's write until the watchdog closes the socket. Link events carry the volume because the
        // hub logs only the start of an error text, which keeps the test output small.
        val absorbableBytes = silent.receiveBufferSize + 64 * 1024
        repeat(2 * absorbableBytes / ERROR_CHARS + 1) { index ->
            val error = "$index".padEnd(ERROR_CHARS, 'e')
            roomy.onCollectionState(
                CollectionState(CollectionPhase.ERROR, CollectionVisibility.BACKGROUND, error = error),
            )
        }

        roomy.clientCount.first { it == 0 }
    }

    @Test
    fun pingCommentSentOnInterval() = timed {
        val server = startServer(pingIntervalMs = 50L)
        val client = Client(server.boundPort, get("/v1/live"))

        var pings = 0
        repeat(200) {
            if (pings < 2 && client.readLine() == ": ping") pings++
        }

        assertEquals(2, pings)
    }

    @Test
    fun stopClosesStreams() = timed {
        val server = startServer()
        val client = Client(server.boundPort, get("/v1/live"))
        client.nextMessage()
        assertEquals(1, hub.clientCount.value)

        server.stop()

        assertEquals(0, hub.clientCount.value)
        client.awaitEof()
        assertThrows(java.net.ConnectException::class.java) { Socket(loopback, server.boundPort) }
    }

    @Test
    fun stopWithAFinalLinkWritesItToEveryStreamBeforeClosing() = timed {
        val server = startServer()
        val clientA = Client(server.boundPort, get("/v1/live"))
        val clientB = Client(server.boundPort, get("/v1/live"))
        listOf(clientA, clientB).forEach { client ->
            assertTrue(client.nextMessage() is LiveFeedMessage.Hello)
            assertTrue(client.nextMessage() is LiveFeedMessage.Link)
        }
        assertEquals(2, hub.clientCount.value)

        val finalLink = LiveFeedMessage.Link(
            ms = 2_000L,
            phase = CollectionPhase.STOPPED,
            error = "Sharing is off on the home phone",
            nextReconnectInMs = null,
        )
        server.stop(finalLink)

        listOf(clientA, clientB).forEach { client ->
            assertEquals(listOf<LiveFeedMessage>(finalLink), client.messagesUntilEof())
        }
        assertEquals(0, hub.clientCount.value)
        assertThrows(java.net.ConnectException::class.java) { Socket(loopback, server.boundPort) }
    }

    @Test
    fun stopWithAFinalLinkComesAfterTheMessagesAlreadyQueued() = timed {
        feedAbc()
        val server = startServer()
        val client = Client(server.boundPort, get("/v1/live"))
        assertTrue(client.nextMessage() is LiveFeedMessage.Hello)
        hub.onBleEvent(notify(SHORT_A + "\r\n"))

        val finalLink = LiveFeedMessage.Link(3_000L, CollectionPhase.STOPPED, "Collection is off on the home phone", null)
        server.stop(finalLink)

        val rest = client.messagesUntilEof()
        assertEquals(finalLink, rest.last())
        assertTrue(
            "the queued frame is written before the final link: $rest",
            rest.dropLast(1).filterIsInstance<LiveFeedMessage.Frame>().any { it.line == SHORT_A },
        )
    }

    @Test
    fun stopWithoutAFinalLinkWritesNothingMore() = timed {
        val server = startServer()
        val client = Client(server.boundPort, get("/v1/live"))
        assertTrue(client.nextMessage() is LiveFeedMessage.Hello)
        assertTrue(client.nextMessage() is LiveFeedMessage.Link)

        server.stop()

        assertEquals(emptyList<LiveFeedMessage>(), client.messagesUntilEof())
    }

    @Test
    fun stopWithAFinalLinkIsBoundedWhenAPeerStoppedReading() = timed {
        val roomy = LiveFeedHub(clock = { 1_000L }, clientBufferCapacity = 1_000)
        val server = startServer(hub = roomy, finalLinkGraceMs = 200L)
        val silent = Socket().also {
            it.receiveBufferSize = 1_024
            it.soTimeout = 5_000
            clients += it
            it.connect(InetSocketAddress(loopback, server.boundPort))
        }
        silent.getOutputStream().write(get("/v1/live").toByteArray(Charsets.ISO_8859_1))
        silent.getOutputStream().flush()
        roomy.clientCount.first { it == 1 }
        // Same volume as the write stall test: the client never reads, so the server's write blocks.
        val absorbableBytes = silent.receiveBufferSize + 64 * 1024
        repeat(2 * absorbableBytes / ERROR_CHARS + 1) { index ->
            val error = "$index".padEnd(ERROR_CHARS, 'e')
            roomy.onCollectionState(
                CollectionState(CollectionPhase.ERROR, CollectionVisibility.BACKGROUND, error = error),
            )
        }

        val startNs = System.nanoTime()
        server.stop(LiveFeedMessage.Link(2_000L, CollectionPhase.STOPPED, "Sharing is off on the home phone", null))
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L

        assertTrue("stop took ${elapsedMs}ms; the flush grace must bound it", elapsedMs < 2_000L)
        assertEquals(0, roomy.clientCount.value)
        assertThrows(java.net.ConnectException::class.java) { Socket(loopback, server.boundPort) }
    }

    @Test
    fun restartOnSamePortSucceeds() = timed {
        val first = startServer()
        val port = first.boundPort
        assertEquals(200, Client(port, get("/v1/now")).also { it.readBody() }.status)
        first.stop()

        val second = startServer(port = port)

        assertEquals(port, second.boundPort)
        assertEquals(200, Client(port, get("/v1/now")).status)
    }

    @Test
    fun startThrowsBindExceptionWhenPortTaken() {
        val first = startServer()
        val second = LiveFeedHttpServer(hub, port = first.boundPort, bindAddress = loopback)
        servers += second

        assertThrows(BindException::class.java) { second.start(scope) }
    }

    @Test
    fun clientDroppedByHubEndsTheResponse() = timed {
        val small = LiveFeedHub(clock = { 1_000L }, clientBufferCapacity = 3)
        small.onBleEvent(notify(FULL_A + "\r\n"))
        small.onBleEvent(notify(B + "\r\n"))
        small.onBleEvent(notify(C + "\r\n"))
        val server = startServer(hub = small)

        val client = Client(server.boundPort, get("/v1/live"))

        assertTrue(client.nextMessage() is LiveFeedMessage.Hello)
        client.awaitEof()
        assertEquals(0, small.clientCount.value)
    }

    @Test
    fun unencodableMessageIsSkipped() {
        val broken = LiveFeedMessage.Frame(1L, ":A=x", KmfReading(voltageV = Double.NaN))
        val fine = LiveFeedMessage.Frame(2L, ":B=1,", reading = null)

        assertNull(encodeLiveFeedEvent(broken))
        assertEquals(
            SseFormat.event("frame", LiveFeedJson.encode(fine)),
            encodeLiveFeedEvent(fine),
        )
    }

    private companion object {
        const val ERROR_CHARS = 16_000
        const val FULL_A = ":A=1331,5200,1,320,150000,1800,"
        const val SHORT_A = ":A=1331,5200,1,320,1,"
        const val B = ":B=1,2,3,"
        const val C = ":C=1234,5678,"
    }
}
