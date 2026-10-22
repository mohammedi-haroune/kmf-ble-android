package com.juncehome.lifepo4ble.collection

import com.juncehome.lifepo4ble.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Handles collection service commands one at a time, in arrival order, on the service scope (main thread).
 *
 * onStartCommand only queues here and must never block the main thread on this work. DataStore runs an
 * edit's transform in the caller's context and applies edits in order, so an edit started from a Main
 * coroutine (Device tab Connect saves the remote address right after sending STOP) needs the main thread
 * before STOP's own disable edit can finish. Blocking Main on STOP deadlocked into an ANR.
 */
class CollectionServiceCommandRunner(
    scope: CoroutineScope,
    private val controller: CollectionController,
    private val router: CollectionServiceCommandRouter,
    private val loadConfiguration: suspend () -> CollectionConfiguration?,
    /** Ends one command's start; [leaveForeground] is false when a newer command already promoted the service. */
    private val stopService: (startId: Int, leaveForeground: Boolean) -> Unit,
) {
    private val pending = Channel<PendingCommand>(Channel.UNLIMITED)

    // Confined to the scope's single thread, like onStartCommand.
    private var latestStartId = 0
    private var startedConfiguration: CollectionConfiguration? = null

    init {
        scope.launch {
            for (next in pending) {
                handle(next)
            }
        }
    }

    /** Called from onStartCommand on the scope's thread, after the service was promoted to the foreground. */
    fun submit(command: CollectionServiceCommand?, startId: Int) {
        latestStartId = startId
        pending.trySend(PendingCommand(command, startId))
    }

    private suspend fun handle(next: PendingCommand) {
        val command = next.command
        val action = command?.action
        val startedAtMs = System.currentTimeMillis()
        AppLog.d("collection service command handling action=$action startId=${next.startId} latest=$latestStartId", TAG)
        if (command != null && action == CollectionServiceAction.STOP) {
            // Durably disabled before stopping, so a START queued behind this one reads the disabled configuration.
            router.route(command)
            finish(next.startId)
        } else {
            val configuration = loadConfiguration()
            if (configuration?.autonomousEnabled != true) {
                AppLog.w("collection service command ignored without autonomous configuration", TAG)
                finish(next.startId)
            } else {
                if (startedConfiguration != configuration) {
                    controller.start(configuration)
                    startedConfiguration = configuration
                }
                command?.let { router.route(it) }
            }
        }
        AppLog.d(
            "collection service command handled action=$action startId=${next.startId} " +
                "in ${System.currentTimeMillis() - startedAtMs} ms",
            TAG,
        )
    }

    private fun finish(startId: Int) {
        // A newer command was promoted after this one and makes its own foreground decision.
        val leaveForeground = startId == latestStartId
        AppLog.d("collection service stopping startId=$startId leaveForeground=$leaveForeground", TAG)
        stopService(startId, leaveForeground)
    }

    private data class PendingCommand(val command: CollectionServiceCommand?, val startId: Int)

    private companion object {
        const val TAG = "KMF-BLE"
    }
}
