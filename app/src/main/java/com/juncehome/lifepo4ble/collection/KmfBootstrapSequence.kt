package com.juncehome.lifepo4ble.collection

import com.juncehome.lifepo4ble.AppLog
import com.juncehome.lifepo4ble.ble.BleRepositoryContract
import com.juncehome.lifepo4ble.protocol.KmfCommands
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * KMF startup after GATT setup (AGENTS.md "KMF BLE Behavior Notes"). The session has already
 * enabled notifications when ServicesDiscovered arrives; this requests MTU 100 and then retries
 * `:*` about every 2 s while [shouldContinue] holds (callers stop once both A and B frames are
 * parsed). Shared by the collection service and the Device tab test connection.
 */
object KmfBootstrapSequence {
    const val PREFERRED_MTU = 100
    const val INITIAL_DELAY_MS = 1_000L
    const val POST_MTU_DELAY_MS = 500L
    const val RETRY_DELAY_MS = 2_000L

    suspend fun run(
        repository: BleRepositoryContract,
        source: String,
        shouldContinue: () -> Boolean,
    ) {
        delay(INITIAL_DELAY_MS)
        if (!currentCoroutineContext().isActive || !shouldContinue()) return
        val mtuRequested = repository.requestMtu(PREFERRED_MTU)
        AppLog.d("$source bootstrap: requested MTU $PREFERRED_MTU accepted=$mtuRequested", TAG)
        delay(POST_MTU_DELAY_MS)
        var attempt = 0
        while (currentCoroutineContext().isActive && shouldContinue()) {
            attempt += 1
            val queued = repository.write(KmfCommands.bootstrapRequest())
            AppLog.d("$source bootstrap: sent :* attempt=$attempt queued=$queued", TAG)
            delay(RETRY_DELAY_MS)
        }
        AppLog.d("$source bootstrap: loop ended after $attempt attempt(s)", TAG)
    }

    private const val TAG = "KMF-BLE"
}
