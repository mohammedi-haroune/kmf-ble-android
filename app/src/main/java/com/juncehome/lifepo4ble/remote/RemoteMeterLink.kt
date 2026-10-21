package com.juncehome.lifepo4ble.remote

import com.juncehome.lifepo4ble.AppLog
import com.juncehome.lifepo4ble.collection.CollectionState
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The carried phone's view of a home phone's live feed; the contract lets screens and tests use a fake. */
interface RemoteMeterLinkContract {
    /** What the Bluetooth path would have produced, timed on this phone's clock. No replay: collect before [start]. */
    val events: SharedFlow<TimedBleEvent>

    /** Link state for the Now tab; null while stopped. */
    val state: StateFlow<CollectionState?>

    /** Reads [address]'s live feed. No-op while already running for that address; another address restarts the link. */
    fun start(address: RemoteAddress)

    /** Drops the connection, ends the retry loop and sets [state] to null. */
    fun stop()

    /**
     * Skips the wait before the next attempt. A request made while an attempt or a connection is open is kept and
     * skips the next wait.
     */
    fun reconnectNow()
}

/**
 * HTTP client loop for the home phone's `GET /v1/live` stream (see [LiveFeedHttpServer]).
 *
 * One loop coroutine owns the [RemoteLinkMachine]: it opens the connection, feeds every decoded message to the
 * machine and republishes the machine's [BleEvent][com.juncehome.lifepo4ble.ble.BleEvent]s and
 * [CollectionState]. When the stream ends it waits the machine's backoff delay (or until [reconnectNow]) and tries
 * again, until [stop].
 *
 * Reading happens on [ioDispatcher] and blocks, which coroutine cancellation cannot interrupt, so every attempt has a
 * child that disconnects the [HttpURLConnection] as soon as the loop is cancelled. [readTimeoutMs] is the socket read
 * timeout: any line from the home phone, including its `: ping` comment, restarts it.
 */
class RemoteMeterLink(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 40_000,
) : RemoteMeterLinkContract {
    private val lock = Any()
    private val eventFlow = MutableSharedFlow<TimedBleEvent>(extraBufferCapacity = EVENT_BUFFER)
    private val stateFlow = MutableStateFlow<CollectionState?>(null)

    /** The running loop, or null while stopped. Guarded by [lock]. */
    private var running: Run? = null

    override val events: SharedFlow<TimedBleEvent> = eventFlow.asSharedFlow()
    override val state: StateFlow<CollectionState?> = stateFlow.asStateFlow()

    override fun start(address: RemoteAddress) {
        val run = synchronized(lock) {
            val current = running
            if (current != null && current.address == address && !current.job.isCompleted) return
            if (current != null) stopLocked()
            Run(address).also { run ->
                val onDeath = CoroutineExceptionHandler { _, cause -> loopDied(run, cause) }
                // Lazy: the job exists before any of its code runs, so code that runs inline can already stop it.
                run.job = scope.launch(onDeath, CoroutineStart.LAZY) { runLoop(run) }
                running = run
            }
        }
        AppLog.d("remote link start host=${address.display}", TAG)
        run.job.start()
    }

    override fun stop() {
        synchronized(lock) { stopLocked() }
    }

    override fun reconnectNow() {
        val run = synchronized(lock) { running } ?: return
        AppLog.d("remote link reconnect requested host=${run.address.display}", TAG)
        run.reconnectRequests.trySend(Unit)
    }

    private fun stopLocked() {
        val run = running ?: return
        running = null
        run.job.cancel()
        stateFlow.value = null
        AppLog.d("remote link stop host=${run.address.display}", TAG)
    }

    /** Last line of defence: if the loop ever dies, the link must not look alive or keep a dead run registered. */
    private fun loopDied(run: Run, cause: Throwable) {
        AppLog.e("remote link loop died host=${run.address.display}", cause, TAG)
        synchronized(lock) {
            if (running === run) {
                running = null
                stateFlow.value = null
            }
        }
    }

    private suspend fun runLoop(run: Run) {
        val machine = RemoteLinkMachine(run.address)
        while (true) {
            publish(run, machine, machine.onConnectAttempt(clock()))
            val reason = readFeed(run, machine)
            val retry = machine.onStreamEnded(reason, clock())
            publish(run, machine, retry.events)
            if (withTimeoutOrNull(retry.delayMs) { run.reconnectRequests.receive() } != null) {
                machine.onReconnectNow()
            }
        }
    }

    /** Opens the stream and handles messages until it ends; returns why it ended. */
    private suspend fun readFeed(run: Run, machine: RemoteLinkMachine): String = try {
        coroutineScope {
            val connection = openConnection(run.address)
            // A blocked socket read ignores cancellation; closing the connection is what makes it return.
            val disconnector = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    connection.disconnect()
                }
            }
            try {
                messages(connection, run.address).collect { handle(run, machine, it) }
                STREAM_CLOSED_BY_HOME
            } finally {
                disconnector.cancel()
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        e.reason()
    } catch (e: Exception) {
        // Not a network failure (the JDK throws a RuntimeException for a port outside 1..65535, for example), but
        // it must not kill the loop or the process: treat it as the end of this stream and back off.
        AppLog.e("remote link unexpected error host=${run.address.display}", e, TAG)
        e.reason()
    }

    private fun Exception.reason(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    private fun openConnection(address: RemoteAddress): HttpURLConnection {
        val connection = URL(address.liveUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.useCaches = false
        connection.setRequestProperty("Accept", "text/event-stream")
        return connection
    }

    /** The decoded messages of one connection. Blocking, so it runs on [ioDispatcher]; ends at EOF or throws. */
    private fun messages(connection: HttpURLConnection, address: RemoteAddress): Flow<LiveFeedMessage> = flow {
        val status = connection.responseCode
        AppLog.d("remote link http status=$status host=${address.display}", TAG)
        if (status != HttpURLConnection.HTTP_OK) throw IOException("HTTP $status")
        val sse = SseLineReader()
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                currentCoroutineContext().ensureActive()
                val event = sse.offer(line) ?: continue
                val message = LiveFeedJson.decode(event.name, event.data)
                if (message == null) {
                    AppLog.d("remote link ignored event name=${event.name} bytes=${event.data.length}", TAG)
                } else {
                    emit(message)
                }
            }
        }
    }.flowOn(ioDispatcher)

    private suspend fun handle(run: Run, machine: RemoteLinkMachine, message: LiveFeedMessage) {
        val nowMs = clock()
        if (message is LiveFeedMessage.Hello) {
            val offsetMs = nowMs - message.ms
            if (abs(offsetMs) > CLOCK_OFFSET_WARN_MS) {
                AppLog.w("remote link clock offset ${offsetMs}ms host=${run.address.display}", TAG)
            }
        }
        publish(run, machine, machine.onMessage(message, nowMs))
    }

    /** Publishes the machine's state, then [events]; nothing reaches the flows once the run was stopped. */
    private suspend fun publish(run: Run, machine: RemoteLinkMachine, events: List<TimedBleEvent>) {
        synchronized(lock) {
            if (running === run) stateFlow.value = machine.state
        }
        for (event in events) {
            currentCoroutineContext().ensureActive()
            eventFlow.emit(event)
        }
    }

    /** One start..stop of the loop for one address. */
    private class Run(val address: RemoteAddress) {
        lateinit var job: Job
        val reconnectRequests = Channel<Unit>(Channel.CONFLATED)
    }

    private companion object {
        const val TAG = "KMF-BLE"
        const val EVENT_BUFFER = 256
        const val CLOCK_OFFSET_WARN_MS = 5_000L
        const val STREAM_CLOSED_BY_HOME = "Home phone closed the stream"
    }
}
