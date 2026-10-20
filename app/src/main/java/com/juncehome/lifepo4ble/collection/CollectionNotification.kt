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

    fun build(
        context: Context,
        state: CollectionState,
        reading: NotificationReading? = null,
    ): Notification {
        val content = CollectionNotificationContent.from(state, reading, System.currentTimeMillis())
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cell_watch)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setSubText(content.subText)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
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
    }

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

    private const val APP_PENDING_INTENT_REQUEST_CODE = 4101
    private const val STOP_PENDING_INTENT_REQUEST_CODE = 4102
}
