package com.juncehome.lifepo4ble.collection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.juncehome.lifepo4ble.AppLog
import com.juncehome.lifepo4ble.BleApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RESTORE_ACTIONS) {
            AppLog.d("boot restore ignored action=${intent.action}", TAG)
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val graph = (context.applicationContext as BleApplication).graph
                val configuration = graph.collectionConfiguration.first()
                val shouldRestore = AutonomousRestorePolicy.shouldRestore(
                    configuration = configuration,
                    apiLevel = Build.VERSION.SDK_INT,
                )
                AppLog.d(
                    "boot restore action=${intent.action} api=${Build.VERSION.SDK_INT} " +
                        "enabled=${configuration?.autonomousEnabled} restore=$shouldRestore",
                    TAG,
                )
                if (shouldRestore) {
                    graph.companionAssociationGateway.startPresenceObservation(
                        requireNotNull(configuration).snapshot.address,
                    )
                    AppLog.d("boot restore presence observation started", TAG)
                }
            } catch (error: Throwable) {
                AppLog.e("boot restore failed", error, TAG)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "KMF-BLE"
        val RESTORE_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
