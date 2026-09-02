package com.juncehome.lifepo4ble.collection

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.juncehome.lifepo4ble.AppLog
import com.juncehome.lifepo4ble.BleApplication
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
}

class CollectionServiceCommandRouter(
    private val controller: CollectionController,
) {
    fun route(command: CollectionServiceCommand) {
        when (command.action) {
            CollectionServiceAction.START -> Unit
            CollectionServiceAction.STOP -> controller.stopByUser()
            CollectionServiceAction.DEVICE_APPEARED -> {
                command.address?.let(controller::onDeviceAppeared)
            }
            CollectionServiceAction.DEVICE_DISAPPEARED -> {
                command.address?.let(controller::onDeviceDisappeared)
            }
            CollectionServiceAction.VISIBILITY_CHANGED -> {
                command.visibility?.let(controller::setVisibility)
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
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var controller: CollectionController
    private lateinit var commandRouter: CollectionServiceCommandRouter
    private var startedConfiguration: CollectionConfiguration? = null

    override fun onCreate() {
        super.onCreate()
        controller = (application as BleApplication).graph.createCollectionController(serviceScope)
        commandRouter = CollectionServiceCommandRouter(controller)
        CollectionNotification.createChannel(this)
        promoteToForeground()
        serviceScope.launch {
            controller.state.collect { state ->
                getSystemService(android.app.NotificationManager::class.java).notify(
                    CollectionNotification.NOTIFICATION_ID,
                    CollectionNotification.build(this@BatteryCollectionService, state),
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CollectionServiceAction.STOP) {
            commandRouter.route(CollectionServiceCommand.from(intent))
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
        intent?.let { commandRouter.route(CollectionServiceCommand.from(it)) }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLog.d("collection service task removed; continuing foreground collection", TAG)
    }

    override fun onDestroy() {
        controller.close()
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
