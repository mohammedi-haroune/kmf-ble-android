package com.juncehome.lifepo4ble.remote

import com.juncehome.lifepo4ble.AppLog
import java.net.BindException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/*
 * Failure containment for the home phone's live feed. The feed runs inside the Bluetooth collection service, on
 * every phone with background collection on (sharing or not), so a bug here must be logged and skipped, never
 * crash the process that owns the meter session and the battery history.
 */

private const val TAG = "KMF-BLE"

/**
 * A child of [parent] for all live feed work: it ends with [parent], but a failure in one of its tasks only reaches the
 * logging handler. It never cancels [parent], its siblings or the Bluetooth controller.
 */
internal fun liveFeedScope(parent: CoroutineScope): CoroutineScope {
    val handler = CoroutineExceptionHandler { _, cause ->
        AppLog.e("live feed task failed; Bluetooth collection is unaffected", cause, TAG)
    }
    return CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + handler)
}

/** Runs one hub call: a failure is logged and the event skipped, so a collector keeps running. Cancellation passes through. */
internal inline fun guardedLiveFeed(what: String, block: () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.e("live feed $what failed; skipped", e, TAG)
    }
}

/**
 * Runs the server start and returns the Device tab text for why it failed, or null once it is listening. Any failure
 * (port in use, I/O, security, state) becomes a status; cancellation passes through.
 */
internal fun liveFeedStartError(port: Int, start: () -> Unit): String? = try {
    start()
    null
} catch (e: BindException) {
    AppLog.w("live feed server port $port is in use: ${e.message}", TAG)
    "Port $port is in use"
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    AppLog.e("live feed server failed to start", e, TAG)
    SHARING_COULD_NOT_START
}

internal const val SHARING_COULD_NOT_START = "Sharing could not start"
