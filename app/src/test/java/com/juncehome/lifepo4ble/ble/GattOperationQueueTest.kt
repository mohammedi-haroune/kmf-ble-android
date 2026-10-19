package com.juncehome.lifepo4ble.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        runCurrent()
        assertEquals(listOf("descriptor"), started)

        queue.completeCurrent(success = true)
        runCurrent()
        assertEquals(listOf("descriptor", "characteristic"), started)

        queue.completeCurrent(success = true)
        assertTrue(first.await())
        assertTrue(second.await())
    }

    @Test
    fun missingCallbackFailsOperationAndFreesQueue() = runTest {
        val started = mutableListOf<String>()
        val queue = GattOperationQueue(testScope = this, operationTimeoutMs = 5_000L)

        val lost = async { queue.enqueue("cccd") { started += "cccd" } }
        val next = async { queue.enqueue("characteristic") { started += "characteristic" } }
        runCurrent()
        assertEquals(listOf("cccd"), started)

        advanceTimeBy(5_001)
        assertFalse(lost.await())
        assertEquals(listOf("cccd", "characteristic"), started)

        queue.completeCurrent(success = true)
        assertTrue(next.await())
    }
}
