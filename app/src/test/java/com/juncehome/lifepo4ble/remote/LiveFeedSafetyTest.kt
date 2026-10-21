package com.juncehome.lifepo4ble.remote

import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveFeedSafetyTest {
    @Test
    fun aFailingLiveFeedTaskLeavesTheParentScopeAndSiblingsRunning() = runTest {
        val parentJob = SupervisorJob()
        val parent = CoroutineScope(parentJob + UnconfinedTestDispatcher(testScheduler))
        val feed = liveFeedScope(parent)
        val sibling = feed.launch { awaitCancellation() }
        val bluetooth = parent.launch { awaitCancellation() }

        feed.launch { throw IllegalStateException("live feed bug") }
        advanceUntilIdle()

        assertTrue(parentJob.isActive)
        assertTrue(bluetooth.isActive)
        assertTrue(sibling.isActive)
        feed.launch { throw IllegalArgumentException("a second failure") }
        advanceUntilIdle()
        assertTrue(sibling.isActive)

        parent.cancel()
        advanceUntilIdle()
        assertTrue("cancelling the service scope ends live feed work", sibling.isCancelled)
    }

    @Test
    fun guardedCallSkipsABadEventAndTheCollectorKeepsGoing() = runTest {
        val parent = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val feed = liveFeedScope(parent)
        val events = MutableSharedFlow<Int>(extraBufferCapacity = 8)
        val handled = mutableListOf<Int>()
        feed.launch {
            events.collect { event ->
                guardedLiveFeed("test event") {
                    check(event != 2) { "bad event" }
                    handled += event
                }
            }
        }
        advanceUntilIdle()

        events.emit(1)
        events.emit(2)
        events.emit(3)
        advanceUntilIdle()

        assertEquals(listOf(1, 3), handled)
        parent.cancel()
    }

    @Test
    fun guardedCallRethrowsCancellation() {
        assertThrows(CancellationException::class.java) {
            guardedLiveFeed("cancelled") { throw CancellationException("scope ended") }
        }
    }

    @Test
    fun startErrorIsNullWhenTheServerStarts() {
        assertNull(liveFeedStartError(8765) { })
    }

    @Test
    fun startErrorNamesAPortInUse() {
        assertEquals("Port 8765 is in use", liveFeedStartError(8765) { throw BindException("Address already in use") })
    }

    @Test
    fun startErrorForOtherFailuresIsGeneric() {
        assertEquals("Sharing could not start", liveFeedStartError(8765) { throw IOException("no route") })
        assertEquals("Sharing could not start", liveFeedStartError(8765) { throw SecurityException("denied") })
        assertEquals("Sharing could not start", liveFeedStartError(8765) { error("already started") })
    }

    @Test
    fun startErrorDoesNotSwallowCancellation() {
        assertThrows(CancellationException::class.java) {
            liveFeedStartError(8765) { throw CancellationException("scope ended") }
        }
    }

    @Test
    fun addressScanThatThrowsYieldsNoAddresses() {
        assertEquals(emptyList<String>(), NetworkAddresses.current { throw NullPointerException("interface vanished") })
        assertEquals(emptyList<String>(), NetworkAddresses.current { throw java.net.SocketException("boom") })
    }

    @Test
    fun addressScanReturnsTheSelectedAddresses() {
        val scan = { listOf("wlan0" to listOf(InetAddress.getByName("192.168.1.23"))) }

        assertEquals(listOf("192.168.1.23"), NetworkAddresses.current(scan))
        assertFalse(NetworkAddresses.current(scan).isEmpty())
    }
}
