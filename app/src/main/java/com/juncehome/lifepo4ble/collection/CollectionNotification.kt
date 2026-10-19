package com.juncehome.lifepo4ble.collection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import com.juncehome.lifepo4ble.MainActivity
import com.juncehome.lifepo4ble.R

object CollectionNotification {
    const val CHANNEL_ID = "battery_collection"
    const val NOTIFICATION_ID = 41

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Battery collection",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows the status of background battery collection"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun build(context: Context, state: CollectionState): Notification = Notification.Builder(
        context,
        CHANNEL_ID,
    )
        .setSmallIcon(R.drawable.ic_stat_cell_watch)
        .setContentTitle("KMF battery collection")
        .setContentText(statusText(state))
        .setCategory(Notification.CATEGORY_SERVICE)
        .setOngoing(true)
        .setContentIntent(appPendingIntent(context))
        .addAction(
            Notification.Action.Builder(
                Icon.createWithResource(context, android.R.drawable.ic_media_pause),
                "Stop",
                stopPendingIntent(context),
            ).build(),
        )
        .build()

    private fun appPendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        APP_PENDING_INTENT_REQUEST_CODE,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun stopPendingIntent(context: Context): PendingIntent = PendingIntent.getService(
        context,
        STOP_PENDING_INTENT_REQUEST_CODE,
        Intent(context, BatteryCollectionService::class.java).setAction(CollectionServiceAction.STOP),
        PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun statusText(state: CollectionState): String = when (state.phase) {
        CollectionPhase.COLLECTING -> "Collecting battery data"
        CollectionPhase.CONNECTING,
        CollectionPhase.BOOTSTRAPPING -> "Connecting to meter"
        CollectionPhase.WAITING_FOR_DEVICE -> "Meter is out of range"
        CollectionPhase.BLUETOOTH_OFF -> "Bluetooth is off; collection resumes when it is back on"
        CollectionPhase.ERROR -> "Collection paused: ${state.error ?: "connection error"}"
        CollectionPhase.STOPPED -> "Collection stopped"
    }

    private const val APP_PENDING_INTENT_REQUEST_CODE = 4101
    private const val STOP_PENDING_INTENT_REQUEST_CODE = 4102
}
