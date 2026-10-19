package com.juncehome.lifepo4ble.collection

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.juncehome.lifepo4ble.AppLog
import com.juncehome.lifepo4ble.BleApplication
import com.juncehome.lifepo4ble.platform.BluetoothAdapterStateObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
    private lateinit var bluetoothStateObserver: BluetoothAdapterStateObserver
    private var startedConfiguration: CollectionConfiguration? = null

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
            controller.state.collect { state ->
                statusRelay.publish(state)
                getSystemService(android.app.NotificationManager::class.java).notify(
                    CollectionNotification.NOTIFICATION_ID,
                    CollectionNotification.build(this@BatteryCollectionService, state),
                )
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
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun promoteToForeground() {
        val notification = CollectionNotification.build(this, controller.state.value)
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
    }
}
