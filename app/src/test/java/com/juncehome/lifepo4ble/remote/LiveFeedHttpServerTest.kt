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
import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
    ): LiveFeedHttpServer {
        val server = LiveFeedHttpServer(
            hub = hub,
            port = port,
            maxClients = maxClients,
            pingIntervalMs = pingIntervalMs,
            bindAddress = loopback,
            requestTimeoutMs = requestTimeoutMs,
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
    fun clientsBeyondCapGet503() = timed {
        val server = startServer(maxClients = 1)
        val first = Client(server.boundPort, get("/v1/live"))
        assertEquals(200, first.status)
        first.nextMessage()

        assertEquals(503, Client(server.boundPort, get("/v1/live")).status)
        assertEquals(200, Client(server.boundPort, get("/v1/now")).status)
    }

    @Test
    fun clientDisconnectReleasesSubscriptionAndSlot() = timed {
        val server = startServer(maxClients = 1)
        val first = Client(server.boundPort, get("/v1/live"))
        first.nextMessage()
        assertEquals(1, hub.clientCount.value)

        first.socket.close()
        hub.clientCount.first { it == 0 }

        var status = 503
        repeat(50) {
            if (status == 503) {
                status = Client(server.boundPort, get("/v1/live")).status
                if (status == 503) delay(20)
            }
        }
        assertEquals(200, status)
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
        const val FULL_A = ":A=1331,5200,1,320,150000,1800,"
        const val SHORT_A = ":A=1331,5200,1,320,1,"
        const val B = ":B=1,2,3,"
        const val C = ":C=1234,5678,"
    }
}
