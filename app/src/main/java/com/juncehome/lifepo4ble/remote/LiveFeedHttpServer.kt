package com.juncehome.lifepo4ble.remote

import com.juncehome.lifepo4ble.AppLog
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import org.json.JSONException

/**
 * Minimal read-only HTTP/1.1 server for the home phone's live feed, on a plain [ServerSocket] (no new dependency).
 *
 * - `GET /v1/live`: Server-Sent Events from [LiveFeedHub.subscribe], plus an SSE `: ping` comment every
 *   [pingIntervalMs].
 * - `GET /v1/now`: one JSON snapshot.
 * - Anything else: `404`; a method other than GET: `405`; a `/v1/live` request while [maxClients] streams are
 *   open: `503` (`/v1/now` is always served). Query strings are ignored.
 *
 * Every connection is served by its own coroutine on [dispatcher]. A failure on one connection (write error, client
 * hang-up, bad request) only ends that connection. Call [stop] to release the port; cancelling the scope given to
 * [start] does not unblock the blocking socket calls by itself.
 *
 * [requestTimeoutMs] bounds how long a client may take to send its request line and headers (at most 8 KiB).
 */
class LiveFeedHttpServer(
    private val hub: LiveFeedHub,
    private val port: Int = DEFAULT_PORT,
    private val maxClients: Int = 4,
    private val pingIntervalMs: Long = 15_000L,
    private val bindAddress: InetAddress? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val requestTimeoutMs: Long = 10_000L,
) {
    private val lock = Any()
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val connections = ConcurrentHashMap.newKeySet<Connection>()
    private val openStreams = AtomicInteger(0)

    @Volatile
    private var stopping = false

    /** The port the server listens on, valid after [start] (so tests can pass port 0); -1 before the first start. */
    @Volatile
    var boundPort: Int = -1
        private set

    /** Binds the port and starts accepting. Throws [java.net.BindException] when the port is taken. */
    fun start(scope: CoroutineScope) {
        synchronized(lock) {
            check(serverSocket == null) { "live feed server already started" }
            val socket = ServerSocket()
            try {
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(bindAddress, port))
            } catch (e: IOException) {
                socket.closeQuietly()
                AppLog.e("live feed server bind failed port=$port address=${bindAddress ?: "all"}", e, TAG)
                throw e
            }
            stopping = false
            boundPort = socket.localPort
            val job = SupervisorJob(scope.coroutineContext[Job])
            val serverScope = CoroutineScope(scope.coroutineContext + job + dispatcher)
            serverSocket = socket
            serverJob = job
            serverScope.launch { acceptLoop(socket, serverScope) }
            AppLog.d(
                "live feed server listening port=$boundPort address=${bindAddress ?: "all"} " +
                    "maxClients=$maxClients pingMs=$pingIntervalMs",
                TAG,
            )
        }
    }

    /** Closes the server socket and every open connection, and releases their hub subscriptions. */
    fun stop() {
        val socket: ServerSocket
        val job: Job?
        synchronized(lock) {
            socket = serverSocket ?: return
            job = serverJob
            serverSocket = null
            serverJob = null
        }
        stopping = true
        socket.closeQuietly()
        job?.cancel()
        val open = connections.toList()
        open.forEach { it.close() }
        AppLog.d("live feed server stopped port=$boundPort closedConnections=${open.size}", TAG)
    }

    private suspend fun acceptLoop(server: ServerSocket, scope: CoroutineScope) {
        while (true) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                if (server.isClosed) return
                AppLog.w("live feed accept failed: ${e.message}", TAG)
                delay(ACCEPT_RETRY_DELAY_MS)
                continue
            }
            val connection = Connection(socket)
            connections.add(connection)
            // Cleanup runs on completion, also when the scope was cancelled before the body started.
            scope.launch { handle(connection) }.invokeOnCompletion {
                connections.remove(connection)
                connection.close()
            }
        }
    }

    private suspend fun handle(connection: Connection) {
        val socket = connection.socket
        val remote = "${socket.inetAddress?.hostAddress}:${socket.port}"
        try {
            socket.tcpNoDelay = true
            val exchange = Exchange(connection, BufferedInputStream(socket.getInputStream()), remote)
            when (val head = readHead(exchange)) {
                RequestHead.Silent -> AppLog.d("live feed http remote=$remote closed before a full request", TAG)
                RequestHead.Malformed -> exchange.respondText(400, "Bad Request")
                RequestHead.TooLarge -> exchange.respondText(431, "Request Header Fields Too Large")
                is RequestHead.Request -> {
                    exchange.request = "${head.method} ${head.path}"
                    route(exchange, head)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            AppLog.w("live feed http remote=$remote failed: ${e.message}", TAG)
        } catch (e: Exception) {
            AppLog.e("live feed http remote=$remote unexpected failure", e, TAG)
        } finally {
            connection.close()
        }
    }

    private suspend fun route(exchange: Exchange, request: RequestHead.Request) {
        when {
            request.method != "GET" -> exchange.respondText(405, "Method Not Allowed", "Allow: GET\r\n")
            request.path == PATH_LIVE -> serveLive(exchange)
            request.path == PATH_NOW -> serveNow(exchange)
            else -> exchange.respondText(404, "Not Found")
        }
    }

    private fun serveNow(exchange: Exchange) {
        val body = try {
            LiveFeedJson.encodeNow(hub.now())
        } catch (e: JSONException) {
            AppLog.e("live feed snapshot could not be encoded", e, TAG)
            exchange.respondText(500, "Internal Server Error")
            return
        }
        exchange.respond(
            200,
            "OK",
            "application/json; charset=utf-8",
            body.toByteArray(Charsets.UTF_8),
            "Cache-Control: no-store\r\n",
        )
    }

    private suspend fun serveLive(exchange: Exchange) {
        val streams = openStreams.incrementAndGet()
        try {
            if (streams > maxClients) {
                AppLog.w(
                    "live feed client refused remote=${exchange.remote} streams=${streams - 1} max=$maxClients",
                    TAG,
                )
                exchange.respondText(503, "Service Unavailable")
                return
            }
            stream(exchange)
        } finally {
            openStreams.decrementAndGet()
        }
    }

    /** Sends the SSE head, then the subscription's messages and pings until the client or the hub ends the stream. */
    private suspend fun stream(exchange: Exchange) {
        val socket = exchange.connection.socket
        val subscription = hub.subscribe()
        exchange.connection.subscription = subscription
        val clientHungUp = AtomicBoolean(false)
        var reason = "server stopped" // what is left when the scope is cancelled
        AppLog.d("live feed client open remote=${exchange.remote} streams=${openStreams.get()} max=$maxClients", TAG)
        try {
            coroutineScope {
                socket.soTimeout = 0
                // A hang-up wakes the pump at once instead of at the next write.
                launch {
                    awaitHangUp(exchange.input)
                    clientHungUp.set(true)
                    subscription.close()
                }
                val pings = Channel<Unit>(Channel.CONFLATED)
                val ticker = launch {
                    while (true) {
                        delay(pingIntervalMs)
                        pings.trySend(Unit)
                    }
                }
                try {
                    exchange.writeStreamHead()
                    reason = pump(subscription, pings, exchange.out, clientHungUp)
                } catch (e: IOException) {
                    reason = "write failed: ${e.message}"
                } finally {
                    ticker.cancel()
                    socket.closeQuietly() // unblocks the hang-up watcher
                }
            }
        } finally {
            subscription.close()
            AppLog.d(
                "live feed client closed remote=${exchange.remote} reason=$reason streams=${openStreams.get() - 1}",
                TAG,
            )
        }
    }

    /** Returns why the stream ended. An IOException from a write propagates to the caller. */
    private suspend fun pump(
        subscription: LiveFeedSubscription,
        pings: ReceiveChannel<Unit>,
        out: OutputStream,
        clientHungUp: AtomicBoolean,
    ): String {
        while (true) {
            val next = select<ChannelResult<LiveFeedMessage>?> {
                subscription.messages.onReceiveCatching { it }
                pings.onReceive { null }
            }
            if (next == null) {
                out.writeUtf8(SseFormat.comment("ping"))
                continue
            }
            val message = next.getOrNull()
                ?: return when {
                    clientHungUp.get() -> "client closed"
                    stopping -> "server stopped"
                    else -> "dropped by hub (slow client)"
                }
            out.writeUtf8(encodeLiveFeedEvent(message) ?: continue)
        }
    }

    /** Blocks until the client closes its side (or the socket breaks). Anything the client sends is ignored. */
    private fun awaitHangUp(input: InputStream) {
        val buffer = ByteArray(256)
        try {
            while (input.read(buffer) >= 0) {
                // Discard: this is a read-only API, nothing the client sends matters.
            }
        } catch (_: IOException) {
            // A reset or our own close; either way the client is gone.
        }
    }

    /** Reads the request line and headers (at most [MAX_HEAD_BYTES]) within [requestTimeoutMs] in total. */
    private fun readHead(exchange: Exchange): RequestHead {
        val socket = exchange.connection.socket
        val deadlineNs = System.nanoTime() + requestTimeoutMs * NANOS_PER_MS
        val bytes = ByteArray(MAX_HEAD_BYTES)
        var length = 0
        while (true) {
            val remainingMs = (deadlineNs - System.nanoTime()) / NANOS_PER_MS
            if (remainingMs <= 0L) return RequestHead.Silent
            socket.soTimeout = remainingMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val next = try {
                exchange.input.read()
            } catch (_: SocketTimeoutException) {
                return RequestHead.Silent
            }
            if (next < 0) return RequestHead.Silent
            if (length == bytes.size) return RequestHead.TooLarge
            bytes[length++] = next.toByte()
            if (endsHead(bytes, length)) break
        }
        return parseRequestLine(String(bytes, 0, length, Charsets.ISO_8859_1))
    }

    private fun endsHead(bytes: ByteArray, length: Int): Boolean =
        (length >= 2 && bytes[length - 2] == LF && bytes[length - 1] == LF) ||
            (length >= 4 && bytes[length - 4] == CR && bytes[length - 3] == LF &&
                bytes[length - 2] == CR && bytes[length - 1] == LF)

    private fun parseRequestLine(head: String): RequestHead {
        val parts = head.substringBefore('\n').trimEnd('\r').split(' ')
        if (parts.size != 3 || parts.any { it.isEmpty() } || !parts[2].startsWith("HTTP/")) return RequestHead.Malformed
        val path = parts[1].substringBefore('?').substringBefore('#')
        return RequestHead.Request(parts[0], path)
    }

    /** One accepted socket and, while it streams, its hub subscription. */
    private class Connection(val socket: Socket) {
        @Volatile
        var subscription: LiveFeedSubscription? = null

        fun close() {
            subscription?.close()
            socket.closeQuietly()
        }
    }

    /** One request/response on a [Connection]; [request] is the "METHOD path" used in log lines. */
    private class Exchange(val connection: Connection, val input: InputStream, val remote: String) {
        val out: OutputStream = connection.socket.getOutputStream()
        var request = "-"

        fun writeStreamHead() {
            out.writeUtf8(
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/event-stream\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Connection: close\r\n\r\n",
            )
            AppLog.d("live feed http remote=$remote $request -> 200 (event stream)", TAG)
        }

        fun respondText(status: Int, reason: String, extraHeaders: String = "") {
            val body = "$status $reason\n".toByteArray(Charsets.UTF_8)
            respond(status, reason, "text/plain; charset=utf-8", body, extraHeaders)
        }

        /** Sends a complete response, then closes gracefully so a late request body cannot reset the connection. */
        fun respond(status: Int, reason: String, contentType: String, body: ByteArray, extraHeaders: String = "") {
            val head = "HTTP/1.1 $status $reason\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${body.size}\r\n" +
                extraHeaders +
                "Connection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.ISO_8859_1) + body)
            out.flush()
            AppLog.d("live feed http remote=$remote $request -> $status bytes=${body.size}", TAG)
            drainAndShutdown()
        }

        /**
         * Closing a socket that still holds unread request bytes (a POST body) sends a reset that can discard the
         * response the client has not read yet. Half-close, then read what the client sent for a moment.
         */
        private fun drainAndShutdown() {
            val socket = connection.socket
            try {
                socket.shutdownOutput()
                val deadlineNs = System.nanoTime() + LINGER_MS * NANOS_PER_MS
                val buffer = ByteArray(1024)
                var drained = 0
                while (drained < MAX_DRAIN_BYTES) {
                    val remainingMs = (deadlineNs - System.nanoTime()) / NANOS_PER_MS
                    if (remainingMs <= 0L) return
                    socket.soTimeout = remainingMs.toInt()
                    val read = input.read(buffer)
                    if (read < 0) return
                    drained += read
                }
            } catch (_: IOException) {
                // Timed out or already reset: nothing more to deliver.
            }
        }
    }

    private sealed interface RequestHead {
        class Request(val method: String, val path: String) : RequestHead

        /** The client closed or went quiet before a full head arrived: nothing to answer. */
        object Silent : RequestHead

        object Malformed : RequestHead

        object TooLarge : RequestHead
    }

    companion object {
        const val DEFAULT_PORT = 8765

        private const val PATH_LIVE = "/v1/live"
        private const val PATH_NOW = "/v1/now"
        private const val MAX_HEAD_BYTES = 8 * 1024
        private const val ACCEPT_RETRY_DELAY_MS = 100L
        private const val LINGER_MS = 1_000L
        private const val MAX_DRAIN_BYTES = 64 * 1024
        private const val NANOS_PER_MS = 1_000_000L
        private const val CR = '\r'.code.toByte()
        private const val LF = '\n'.code.toByte()
    }
}

private const val TAG = "KMF-BLE"

/**
 * The SSE block for [message], or null when the message cannot be encoded (a NaN or infinite reading makes
 * [LiveFeedJson.encode] throw). One bad message is logged and skipped; it never ends the stream.
 */
internal fun encodeLiveFeedEvent(message: LiveFeedMessage): String? = try {
    SseFormat.event(LiveFeedJson.eventName(message), LiveFeedJson.encode(message))
} catch (e: JSONException) {
    AppLog.w("live feed skipped ${LiveFeedJson.eventName(message)} message: ${e.message}", TAG)
    null
}

private fun OutputStream.writeUtf8(text: String) {
    write(text.toByteArray(Charsets.UTF_8))
    flush()
}

private fun Closeable.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
        // Already closed or broken; there is nothing left to release.
    }
}
