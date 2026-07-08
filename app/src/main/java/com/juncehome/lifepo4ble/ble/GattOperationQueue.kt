package com.juncehome.lifepo4ble.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class GattOperationQueue(
    @Suppress("UNUSED_PARAMETER")
    testScope: CoroutineScope? = null,
) {
    private val mutex = Mutex()
    private var currentResult: CompletableDeferred<Boolean>? = null

    suspend fun enqueue(name: String, start: () -> Unit): Boolean {
        return mutex.withLock {
            val result = CompletableDeferred<Boolean>()
            currentResult = result
            start()
            try {
                result.await()
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
}
