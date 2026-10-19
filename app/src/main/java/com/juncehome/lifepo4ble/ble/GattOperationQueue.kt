package com.juncehome.lifepo4ble.ble

import com.juncehome.lifepo4ble.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Serializes GATT operations. A callback that never arrives (link died mid-operation, stack bug)
 * fails the operation after [operationTimeoutMs] instead of holding the queue forever.
 */
class GattOperationQueue(
    @Suppress("UNUSED_PARAMETER")
    testScope: CoroutineScope? = null,
    private val operationTimeoutMs: Long = DEFAULT_OPERATION_TIMEOUT_MS,
) {
    private val mutex = Mutex()
    private var currentResult: CompletableDeferred<Boolean>? = null

    suspend fun enqueue(name: String, start: () -> Unit): Boolean {
        return mutex.withLock {
            val result = CompletableDeferred<Boolean>()
            currentResult = result
            start()
            try {
                withTimeoutOrNull(operationTimeoutMs) { result.await() } ?: run {
                    AppLog.w("GATT $name operation timed out after $operationTimeoutMs ms", TAG)
                    false
                }
            } finally {
                if (currentResult === result) {
                    currentResult = null
                }
            }
        }
    }

    fun completeCurrent(success: Boolean) {
        currentResult?.complete(success)
    }

    fun clear() {
        currentResult?.complete(false)
        currentResult = null
    }

    companion object {
        const val DEFAULT_OPERATION_TIMEOUT_MS = 5_000L
        private const val TAG = "KMF-BLE"
    }
}
