package com.juncehome.lifepo4ble.collection

import android.content.Context
import android.content.Intent
import android.os.Build

/** Sends explicit commands to the already-configured collection service. */
interface CollectionServiceCommander {
    fun start()

    fun stop()

    fun setVisibility(visibility: CollectionVisibility)

    /** Asks the running service to skip the pending backoff and reconnect now. */
    fun reconnectNow()
}

class AndroidCollectionServiceCommander(
    private val context: Context,
) : CollectionServiceCommander {
    override fun start() {
        val command = command(CollectionServiceAction.START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(command)
        } else {
            context.startService(command)
        }
    }

    override fun reconnectNow() {
        val command = command(CollectionServiceAction.RECONNECT_NOW)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(command)
        } else {
            context.startService(command)
        }
    }

    override fun stop() {
        context.startService(command(CollectionServiceAction.STOP))
    }

    override fun setVisibility(visibility: CollectionVisibility) {
        context.startService(
            command(CollectionServiceAction.VISIBILITY_CHANGED)
                .putExtra(CollectionServiceCommandRouter.EXTRA_VISIBILITY, visibility.name),
        )
    }

    private fun command(action: String): Intent =
        Intent(context, BatteryCollectionService::class.java).setAction(action)
}
