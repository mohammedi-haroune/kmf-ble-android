package com.juncehome.lifepo4ble.collection

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.juncehome.lifepo4ble.AppGraph
import com.juncehome.lifepo4ble.AppLog
import com.juncehome.lifepo4ble.BleApplication
import com.juncehome.lifepo4ble.platform.BluetoothAdapterStateObserver
import com.juncehome.lifepo4ble.remote.LiveFeedHttpServer
import com.juncehome.lifepo4ble.remote.LiveFeedHub
import com.juncehome.lifepo4ble.remote.NetworkAddresses
import com.juncehome.lifepo4ble.remote.SHARING_COULD_NOT_START
import com.juncehome.lifepo4ble.remote.SharingStatus
import com.juncehome.lifepo4ble.remote.SharingStatusRelay
import com.juncehome.lifepo4ble.remote.guardedLiveFeed
import com.juncehome.lifepo4ble.remote.liveFeedScope
import com.juncehome.lifepo4ble.remote.liveFeedStartError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

object CollectionServiceAction {
    const val START = "com.juncehome.lifepo4ble.collection.START"
    const val STOP = "com.juncehome.lifepo4ble.collection.STOP"
    const val DEVICE_APPEARED = "com.juncehome.lifepo4ble.collection.DEVICE_APPEARED"
    const val DEVICE_DISAPPEARED = "com.juncehome.lifepo4ble.collection.DEVICE_DISAPPEARED"
    const val VISIBILITY_CHANGED = "com.juncehome.lifepo4ble.collection.VISIBILITY_CHANGED"
    const val RECONNECT_NOW = "com.juncehome.lifepo4ble.collection.RECONNECT_NOW"
}

class CollectionServiceCommandRouter(
    private val controller: CollectionController,
    private val autonomousCollectionStopper: AutonomousCollectionStopper,
) {
    suspend fun route(command: CollectionServiceCommand) {
        when (command.action) {
            CollectionServiceAction.START -> Unit
            CollectionServiceAction.STOP -> {
                autonomousCollectionStopper.disable()
                controller.stopByUser()
            }
            CollectionServiceAction.DEVICE_APPEARED -> {
                command.address?.let(controller::onDeviceAppeared)
            }
            CollectionServiceAction.DEVICE_DISAPPEARED -> {
                command.address?.let(controller::onDeviceDisappeared)
            }
            CollectionServiceAction.VISIBILITY_CHANGED -> {
                command.visibility?.let(controller::setVisibility)
            }
            CollectionServiceAction.RECONNECT_NOW -> {
                AppLog.d("collection service: reconnect-now command received", TAG)
                controller.reconnectNow()
            }
            null -> Unit
            else -> AppLog.w("ignored unknown collection service action=${command.action}", TAG)
        }
    }

    companion object {
        const val EXTRA_ADDRESS = "com.juncehome.lifepo4ble.collection.extra.ADDRESS"
        const val EXTRA_VISIBILITY = "com.juncehome.lifepo4ble.collection.extra.VISIBILITY"
        private const val TAG = "KMF-BLE"
    }
}

data class CollectionServiceCommand(
    val action: String?,
    val address: String? = null,
    val visibility: CollectionVisibility? = null,
) {
    companion object {
        fun from(intent: Intent): CollectionServiceCommand = CollectionServiceCommand(
            action = intent.action,
            address = intent.getStringExtra(CollectionServiceCommandRouter.EXTRA_ADDRESS),
            visibility = intent.getStringExtra(CollectionServiceCommandRouter.EXTRA_VISIBILITY)
                ?.let { value -> CollectionVisibility.entries.firstOrNull { it.name == value } },
        )
    }
}

class BatteryCollectionService : Service() {
    // The controller is not thread-safe; the main thread serializes BLE events, service commands,
    // adapter broadcasts and its own timers.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var controller: CollectionController
    private lateinit var commandRouter: CollectionServiceCommandRouter
    private lateinit var statusRelay: CollectionStatusRelay
    private lateinit var sharingRelay: SharingStatusRelay
    private lateinit var bluetoothStateObserver: BluetoothAdapterStateObserver
    private var startedConfiguration: CollectionConfiguration? = null
    private var lastNotifiedPhase: CollectionPhase? = null
    private var lastNotifiedAtMs = 0L
    private var lastNotifiedReading: NotificationReading? = null

    // Main-confined: set while the live feed server runs so onDestroy can stop it before the scope is cancelled.
    private var sharingServer: LiveFeedHttpServer? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        val graph = (application as BleApplication).graph
        controller = graph.createCollectionController(serviceScope)
        bluetoothStateObserver = BluetoothAdapterStateObserver(this) { state ->
            when (state) {
                BluetoothAdapter.STATE_ON -> controller.onBluetoothStateChanged(enabled = true)
                BluetoothAdapter.STATE_TURNING_OFF,
                BluetoothAdapter.STATE_OFF -> controller.onBluetoothStateChanged(enabled = false)
            }
        }
        bluetoothStateObserver.register()
        statusRelay = graph.collectionStatusRelay
        commandRouter = CollectionServiceCommandRouter(
            controller = controller,
            autonomousCollectionStopper = (application as BleApplication).graph.autonomousCollectionDisabler,
        )
        CollectionNotification.createChannel(this)
        serviceScope.launch {
            controller.state.collect { state -> statusRelay.publish(state) }
        }
        sharingRelay = graph.sharingStatusRelay
        startLiveFeed(graph)
        val historyStore = graph.historyStore
        serviceScope.launch {
            val latestReading = controller.state
                .map { it.deviceAddress }
                .distinctUntilChanged()
                .flatMapLatest { address ->
                    if (address == null) {
                        flowOf(null)
                    } else {
                        historyStore.observeLatestBatterySample(address).map { sample ->
                            sample?.let(NotificationReading::from)
                        }
                    }
                }
            combine(controller.state, latestReading, ::Pair).collectLatest { (state, reading) ->
                // Phase changes post at once; reading-only updates at most every NOTIFICATION_MIN_INTERVAL_MS.
                // collectLatest cancels a pending wait when a newer value arrives, so the last one always posts.
                val waitMs = if (state.phase != lastNotifiedPhase) {
                    0L
                } else {
                    (lastNotifiedAtMs + NOTIFICATION_MIN_INTERVAL_MS - SystemClock.elapsedRealtime())
                        .coerceAtLeast(0L)
                }
                delay(waitMs)
                postNotification(state, reading)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() call needs a matching startForeground(), including
        // commands delivered to an instance that already left the foreground (after STOP or an
        // ignored command), otherwise Android kills the app with
        // ForegroundServiceDidNotStartInTimeException.
        AppLog.d("collection service command action=${intent?.action} startId=$startId", TAG)
        promoteToForeground()
        if (intent?.action == CollectionServiceAction.STOP) {
            runBlocking(Dispatchers.IO) {
                commandRouter.route(CollectionServiceCommand.from(intent))
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val configuration = runBlocking(Dispatchers.IO) {
            (application as BleApplication).graph.collectionConfiguration.first()
        }
        if (configuration?.autonomousEnabled != true) {
            AppLog.w("collection service command ignored without autonomous configuration", TAG)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (startedConfiguration != configuration) {
            controller.start(configuration)
            startedConfiguration = configuration
        }
        intent?.let {
            runBlocking { commandRouter.route(CollectionServiceCommand.from(it)) }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLog.d("collection service task removed; continuing foreground collection", TAG)
    }

    override fun onDestroy() {
        bluetoothStateObserver.unregister()
        controller.close()
        statusRelay.publish(controller.state.value)
        sharingServer?.let { server ->
            // Socket calls stay off the main thread; stop() is quick and idempotent.
            runBlocking(Dispatchers.IO) { server.stop() }
            sharingServer = null
        }
        serviceScope.cancel()
        sharingRelay.publish(null)
        AppLog.d("live feed sharing status cleared on service destroy", TAG)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * The hub always tracks BLE events and controller state while the service runs, so turning sharing on
     * mid-session still hands a new viewer the replay. The server itself follows the Share on network setting.
     */
    private fun startLiveFeed(graph: AppGraph) {
        val hub = LiveFeedHub()
        // All live feed work runs in a contained child scope: a failure is logged there and never reaches
        // the service scope, the controller or the process.
        val feedScope = liveFeedScope(serviceScope)
        // UNDISPATCHED like the controller: the shared event flow has no replay.
        feedScope.launch(start = CoroutineStart.UNDISPATCHED) {
            graph.bleEvents.collect { event -> guardedLiveFeed("hub ble event") { hub.onBleEvent(event) } }
        }
        feedScope.launch {
            controller.state.collect { state -> guardedLiveFeed("hub collection state") { hub.onCollectionState(state) } }
        }
        feedScope.launch {
            graph.shareOnNetwork.distinctUntilChanged().collectLatest { enabled ->
                AppLog.d("live feed share on network enabled=$enabled", TAG)
                if (enabled) {
                    shareLiveFeed(hub, feedScope)
                } else {
                    publishSharing(
                        SharingStatus(
                            running = false,
                            port = LiveFeedHttpServer.DEFAULT_PORT,
                            addresses = emptyList(),
                            viewers = 0,
                        ),
                    )
                }
            }
        }
    }

    /** Runs the live feed server until cancelled (setting turned off or service destroyed). */
    private suspend fun shareLiveFeed(hub: LiveFeedHub, feedScope: CoroutineScope) {
        val server = LiveFeedHttpServer(hub)
        try {
            val failure = startServer(server, feedScope)
            if (failure != null) {
                publishSharing(failure)
                return
            }
            sharingServer = server
            // Republish when viewers come or go, and every minute because addresses change when
            // NetBird or Wi-Fi reconnects.
            val refreshTicks = flow {
                while (true) {
                    emit(Unit)
                    delay(SHARING_REFRESH_MS)
                }
            }
            combine(hub.clientCount, refreshTicks) { viewers, _ -> viewers }.collect { viewers ->
                val addresses = withContext(Dispatchers.IO) { NetworkAddresses.current() }
                publishSharing(SharingStatus(true, server.boundPort, addresses, viewers))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Sharing stops, Bluetooth collection goes on; the Device tab shows why.
            AppLog.e("live feed sharing failed; sharing is stopped", e, TAG)
            publishSharing(failedSharing(SHARING_COULD_NOT_START))
        } finally {
            sharingServer = null
            withContext(NonCancellable + Dispatchers.IO) { server.stop() }
        }
    }

    /** Binds the server off the main thread; returns the failed status, or null once it is listening. */
    private suspend fun startServer(server: LiveFeedHttpServer, feedScope: CoroutineScope): SharingStatus? {
        val error = withContext(Dispatchers.IO) {
            liveFeedStartError(LiveFeedHttpServer.DEFAULT_PORT) { server.start(feedScope) }
        }
        return error?.let(::failedSharing)
    }

    private fun failedSharing(error: String) = SharingStatus(
        running = false,
        port = LiveFeedHttpServer.DEFAULT_PORT,
        addresses = emptyList(),
        viewers = 0,
        error = error,
    )

    private fun publishSharing(status: SharingStatus) {
        AppLog.d(
            "live feed sharing status running=${status.running} port=${status.port} " +
                "addresses=${status.addresses} viewers=${status.viewers} error=${status.error}",
            TAG,
        )
        sharingRelay.publish(status)
    }

    private fun postNotification(state: CollectionState, reading: NotificationReading?) {
        val content = CollectionNotificationContent.from(state, reading, System.currentTimeMillis())
        AppLog.d(
            "collection notification phase=${state.phase} title=\"${content.title}\" text=\"${content.text}\" " +
                "subText=\"${content.subText}\" " +
                "sampleAt=${reading?.timestampMs}",
            TAG,
        )
        getSystemService(android.app.NotificationManager::class.java).notify(
            CollectionNotification.NOTIFICATION_ID,
            CollectionNotification.build(this, state, reading),
        )
        lastNotifiedPhase = state.phase
        lastNotifiedAtMs = SystemClock.elapsedRealtime()
        lastNotifiedReading = reading
    }

    private fun promoteToForeground() {
        val notification = CollectionNotification.build(this, controller.state.value, lastNotifiedReading)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                CollectionNotification.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(CollectionNotification.NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val TAG = "KMF-BLE"
        const val NOTIFICATION_MIN_INTERVAL_MS = 5_000L
        const val SHARING_REFRESH_MS = 60_000L
    }
}
