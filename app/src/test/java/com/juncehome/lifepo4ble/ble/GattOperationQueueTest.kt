package com.juncehome.lifepo4ble.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GattOperationQueueTest {
    @Test
    fun runsOnlyOneGattWriteAtATime() = runTest {
        val started = mutableListOf<String>()
        val queue = GattOperationQueue(testScope = this)

        val first = async { queue.enqueue("descriptor") { started += "descriptor" } }
        val second = async { queue.enqueue("characteristic") { started += "characteristic" } }

        advanceUntilIdle()
        assertEquals(listOf("descriptor"), started)

        queue.completeCurrent(success = true)
        advanceUntilIdle()
        assertEquals(listOf("descriptor", "characteristic"), started)

        queue.completeCurrent(success = true)
        assertTrue(first.await())
        assertTrue(second.await())
    }
}
