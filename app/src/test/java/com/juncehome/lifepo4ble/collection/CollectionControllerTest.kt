package com.juncehome.lifepo4ble.collection

import com.juncehome.lifepo4ble.ble.AndroidBleSession
import com.juncehome.lifepo4ble.ble.BleEvent
import com.juncehome.lifepo4ble.ble.BleRepositoryContract
import com.juncehome.lifepo4ble.ble.GattProfile
import com.juncehome.lifepo4ble.ble.ScannedDevice
import com.juncehome.lifepo4ble.data.DeviceSnapshot
import com.juncehome.lifepo4ble.data.KmfBatterySampleEntity
import com.juncehome.lifepo4ble.data.KmfFrameEventEntity
import com.juncehome.lifepo4ble.data.KmfHistoryStore
import com.juncehome.lifepo4ble.data.KmfNotificationObservation
import com.juncehome.lifepo4ble.data.KmfWriteObservation
import com.juncehome.lifepo4ble.protocol.KmfFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionControllerTest {
    @Test
    fun backgroundReadyConnectionPollsEveryFiveMinutes() = runTest {
        val fixture = fixture(testScheduler)
        try {
            fixture.controller.start(configuration)
            fixture.emitReadyAAndBFrames()
            runCurrent()
            assertEquals(1, fixture.repository.countWrites(":C\n"))
            fixture.emitTotalsFrame()
            fixture.controller.setVisibility(CollectionVisibility.BACKGROUND)

            advanceTimeBy(299_999)
            assertEquals(1, fixture.repository.countWrites(":C\n"))
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, fixture.repository.countWrites(":C\n"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun userStopPreventsReconnect() = runTest {
        val fixture = fixture(testScheduler)
        try {
            fixture.controller.start(configuration)
            fixture.controller.stopByUser()
            fixture.repository.emit(BleEvent.Disconnected("manual"))
            runCurrent()

            assertEquals(1, fixture.repository.connectCalls.size)
            assertEquals(CollectionPhase.STOPPED, fixture.controller.state.value.phase)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun bootstrapStopsAfterParsedAAndBFrames() = runTest {
        val fixture = fixture(testScheduler)
        try {
            fixture.controller.start(configuration)
            fixture.repository.emit(BleEvent.ServicesDiscovered(profile))
            advanceTimeBy(1_500)
            runCurrent()
            assertEquals(100, fixture.repository.requestedMtus.single())
            assertEquals(1, fixture.repository.countWrites(":*"))

            fixture.repository.emit(BleEvent.NotificationReceived(profile.notifyUuid, ":A=1326,1080,1,5793,57930,1000\n".encodeToByteArray()))
            fixture.repository.emit(BleEvent.NotificationReceived(profile.notifyUuid, ":B=1\n".encodeToByteArray()))
            advanceTimeBy(10_000)

            assertEquals(CollectionPhase.COLLECTING, fixture.controller.state.value.phase)
            assertEquals(1, fixture.repository.countWrites(":*"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun serviceOwnedControllerDefaultsToBackgroundFiveMinuteCadence() = runTest {
        val fixture = fixture(testScheduler)
        try {
            fixture.controller.start(configuration)
            fixture.emitReadyAAndBFrames()
            runCurrent()
            assertEquals(1, fixture.repository.countWrites(":C\n"))
            fixture.emitTotalsFrame()

            // The pending 2 s totals retry finishes first, then the regular cadence starts.
            advanceTimeBy(2_000 + 299_999)
            assertEquals(1, fixture.repository.countWrites(":C\n"))
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, fixture.repository.countWrites(":C\n"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun foregroundReadyConnectionPollsEveryThirtySecondsAfterActivityVisibility() = runTest {
        val fixture = fixture(testScheduler)
        try {
            fixture.controller.start(configuration)
            fixture.controller.setVisibility(CollectionVisibility.FOREGROUND)
            fixture.emitReadyAAndBFrames()
            runCurrent()
            assertEquals(1, fixture.repository.countWrites(":C\n"))
            fixture.emitTotalsFrame()

            advanceTimeBy(2_000 + 29_999)
            assertEquals(1, fixture.repository.countWrites(":C\n"))
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, fixture.repository.countWrites(":C\n"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun totalsPollRetriesEveryTwoSecondsUntilCFrameArrives() = runTest {
        val fixture = fixture(testScheduler)
        try {
            fixture.controller.start(configuration)
            fixture.emitReadyAAndBFrames()
            runCurrent()
            assertEquals(1, fixture.repository.countWrites(":C\n"))

            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(2, fixture.repository.countWrites(":C\n"))
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(3, fixture.repository.countWrites(":C\n"))

            fixture.emitTotalsFrame()
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(3, fixture.repository.countWrites(":C\n"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun reconnectAsksForTotalsAgainRightAway() = runTest {
        val fixture = fixture(testScheduler)
        try {
            fixture.controller.start(configuration)
            fixture.emitReadyAAndBFrames()
            fixture.emitTotalsFrame()
            runCurrent()
            val beforeReconnect = fixture.repository.countWrites(":C\n")

            fixture.repository.emit(BleEvent.Disconnected("link lost"))
            fixture.emitReadyAAndBFrames()
            runCurrent()

            assertEquals(beforeReconnect + 1, fixture.repository.countWrites(":C\n"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun appearanceForAnotherAddressDoesNotConnect() = runTest {
        val fixture = fixture(testScheduler)
        try {
            fixture.controller.start(configuration)
            fixture.controller.onDeviceDisappeared(configuration.snapshot.address)
            fixture.controller.onDeviceAppeared("11:22:33:44:55:66")

            // Disappearing hands the attempt to a background autoConnect; another address changes nothing.
            assertEquals(listOf(false, true), fixture.repository.autoConnectFlags)
            assertEquals(1, fixture.repository.disconnectCalls)
            assertEquals(CollectionPhase.WAITING_FOR_DEVICE, fixture.controller.state.value.phase)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun readyConnectionResetsReconnectBackoffToOneSecond() = runTest {
        val fixture = fixture(testScheduler)
        try {
            fixture.controller.start(configuration)
            fixture.repository.emit(BleEvent.Disconnected("out of range"))
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(2, fixture.repository.connectCalls.size)

            fixture.emitReadyAAndBFrames()
            assertNull(fixture.controller.state.value.nextReconnectDelayMs)
            fixture.repository.emit(BleEvent.Disconnected("out of range"))
            advanceTimeBy(999)
            assertEquals(2, fixture.repository.connectCalls.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(3, fixture.repository.connectCalls.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun scheduledReconnectExposesAttemptRetryTimeAndDropTime() = runTest {
        val fixture = fixture(testScheduler)
        try {
            fixture.controller.start(configuration)
            fixture.repository.emit(BleEvent.Disconnected("out of range"))

            val first = fixture.controller.state.value
            assertEquals(CollectionPhase.ERROR, first.phase)
            assertEquals(1, first.reconnectAttempt)
            assertEquals(42L + 1_000L, first.nextReconnectAtMs)
            assertEquals(42L, first.linkDroppedAtMs)
            assertEquals("out of range", first.error)

            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(CollectionPhase.CONNECTING, fixture.controller.state.value.phase)
            assertNull(fixture.controller.state.value.nextReconnectAtMs)
            fixture.repository.emit(BleEvent.Disconnected("again"))
            val second = fixture.controller.state.value
            assertEquals(2, second.reconnectAttempt)
            assertEquals(42L + 2_000L, second.nextReconnectAtMs)

            fixture.emitReadyAAndBFrames()
            val collecting = fixture.controller.state.value
            assertEquals(CollectionPhase.COLLECTING, collecting.phase)
            assertEquals(0, collecting.reconnectAttempt)
            assertNull(collecting.linkDroppedAtMs)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun reconnectNowSkipsPendingBackoff() = runTest {
        val fixture = fixture(testScheduler)
        try {
            fixture.controller.start(configuration)
            fixture.repository.emit(BleEvent.Disconnected("out of range"))
            advanceTimeBy(1_000)
            runCurrent()
            fixture.repository.emit(BleEvent.Disconnected("out of range"))
            assertEquals(2, fixture.repository.connectCalls.size)

            fixture.controller.reconnectNow()
            runCurrent()
            assertEquals(3, fixture.repository.connectCalls.size)
            assertEquals(CollectionPhase.CONNECTING, fixture.controller.state.value.phase)

            // The cancelled 2 s retry must not fire a second connect.
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(3, fixture.repository.connectCalls.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun reconnectNowIgnoredWhenNotInError() = runTest {
        val fixture = fixture(testScheduler)
        try {
            fixture.controller.start(configuration)
            fixture.emitReadyAAndBFrames()
            fixture.controller.reconnectNow()
            assertEquals(1, fixture.repository.connectCalls.size)

            fixture.controller.stopByUser()
            fixture.controller.reconnectNow()
            assertEquals(1, fixture.repository.connectCalls.size)
            assertEquals(CollectionPhase.STOPPED, fixture.controller.state.value.phase)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun reconnectWithOnlyFreshAFrameKeepsBootstrapRunning() = runTest {
        val fixture = fixture(testScheduler)
        try {
            fixture.controller.start(configuration)
            fixture.emitReadyAAndBFrames()
            assertEquals(CollectionPhase.COLLECTING, fixture.controller.state.value.phase)

            fixture.repository.emit(BleEvent.Disconnected("out of range"))
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(2, fixture.repository.connectCalls.size)

            fixture.repository.emit(BleEvent.ServicesDiscovered(profile))
            fixture.repository.emit(
                BleEvent.NotificationReceived(
                    profile.notifyUuid,
                    ":A=1326,1080,1,5793,57930,1000\n".encodeToByteArray(),
                )
            )
            assertEquals(CollectionPhase.BOOTSTRAPPING, fixture.controller.state.value.phase)
            advanceTimeBy(1_500)
            runCurrent()
            assertEquals(1, fixture.repository.countWrites(":*"))

            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(2, fixture.repository.countWrites(":*"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun historyResetFailureDoesNotTerminateDisconnectHandling() = runTest {
        val fixture = fixture(testScheduler)
        try {
            fixture.history.throwOnReset = true
            fixture.controller.start(configuration)
            fixture.repository.emit(
                BleEvent.Connecting(
                    ScannedDevice(configuration.snapshot.name, configuration.snapshot.address, 0, emptyList())
                )
            )
            fixture.repository.emit(BleEvent.Disconnected("out of range"))
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(2, fixture.repository.connectCalls.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun historyNotificationFailureDoesNotTerminateErrorHandling() = runTest {
        val fixture = fixture(testScheduler)
        try {
            fixture.history.throwOnNotification = true
            fixture.controller.start(configuration)
            fixture.repository.emit(BleEvent.ServicesDiscovered(profile))
            fixture.repository.emit(
                BleEvent.NotificationReceived(
                    profile.notifyUuid,
                    ":A=1326,1080,1,5793,57930,1000\n".encodeToByteArray(),
                )
            )
            fixture.repository.emit(BleEvent.Error("GATT failed"))
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(2, fixture.repository.connectCalls.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun notificationPersistsParsedBatteryReadingForConfiguredMeter() = runTest {
        val fixture = fixture(testScheduler)
        try {
            fixture.controller.start(configuration)
            fixture.repository.emit(BleEvent.ServicesDiscovered(profile))
            fixture.repository.emit(
                BleEvent.NotificationReceived(
                    profile.notifyUuid,
                    ":A=1326,1080,1,5793,57930,1000\n".encodeToByteArray(),
                )
            )
            runCurrent()

            val saved = fixture.history.notifications.single()
            assertEquals(configuration.snapshot.address, saved.deviceAddress)
            assertEquals(profile.serviceUuid.toString(), saved.serviceUuid)
            assertEquals(profile.notifyUuid.toString(), saved.notifyUuid)
            assertEquals(13.26, saved.mergedReading.voltageV, 0.001)
            assertEquals(100.0, saved.mergedReading.capacityAh, 0.001)
            assertEquals(listOf(KmfFrame.A::class), saved.frames.map { it::class })
        } finally {
            fixture.close()
        }
    }

    // Connection-flow corner cases. Each one used to leave collection stuck until the user toggled it.

    @Test
    fun lowercaseCompanionAddressesAreMatched() = runTest {
        val fixture = fixture(testScheduler)
        try {
            fixture.controller.start(configuration)
            fixture.repository.emit(BleEvent.Disconnected("GATT disconnected with status 8"))
            assertEquals(CollectionPhase.ERROR, fixture.controller.state.value.phase)

            // CompanionDeviceManager reports lowercase MACs; the saved snapshot is uppercase.
            fixture.controller.onDeviceDisappeared(configuration.snapshot.address.lowercase())
            assertEquals(CollectionPhase.WAITING_FOR_DEVICE, fixture.controller.state.value.phase)
            assertEquals(listOf(false, true), fixture.repository.autoConnectFlags)

            // The cancelled 1 s direct retry must not fire behind the background request.
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(2, fixture.repository.connectCalls.size)

            fixture.controller.onDeviceAppeared(configuration.snapshot.address.lowercase())
            assertEquals(CollectionPhase.CONNECTING, fixture.controller.state.value.phase)
            assertEquals(listOf(false, true, false), fixture.repository.autoConnectFlags)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun connectWithoutAnyCallbackTimesOutAndRetries() = runTest {
        val fixture = fixture(testScheduler)
        try {
            fixture.controller.start(configuration)
            advanceTimeBy(19_999)
            runCurrent()
            assertEquals(CollectionPhase.CONNECTING, fixture.controller.state.value.phase)
            assertEquals(0, fixture.repository.disconnectCalls)

            advanceTimeBy(1)
            runCurrent()
            val failed = fixture.controller.state.value
            assertEquals(CollectionPhase.ERROR, failed.phase)
            assertEquals("Connection attempt timed out", failed.error)
            assertEquals(1, failed.reconnectAttempt)
            assertEquals(1, fixture.repository.disconnectCalls)

            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(2, fixture.repository.connectCalls.size)
            assertEquals(CollectionPhase.CONNECTING, fixture.controller.state.value.phase)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun repeatedDirectFailuresHandOverToBackgroundAutoConnect() = runTest {
        val fixture = fixture(testScheduler)
        try {
            fixture.controller.start(configuration)
            // Three timed-out direct attempts, each followed by its 1 s / 2 s / 5 s backoff.
            for (backoffMs in listOf(1_000L, 2_000L, 5_000L)) {
                advanceTimeBy(20_000)
                runCurrent()
                assertEquals(CollectionPhase.ERROR, fixture.controller.state.value.phase)
                advanceTimeBy(backoffMs)
                runCurrent()
            }
            assertEquals(listOf(false, false, false, false), fixture.repository.autoConnectFlags)

            advanceTimeBy(20_000)
            runCurrent()
            val waiting = fixture.controller.state.value
            assertEquals(CollectionPhase.WAITING_FOR_DEVICE, waiting.phase)
            assertEquals(4, waiting.reconnectAttempt)
            assertEquals(listOf(false, false, false, false, true), fixture.repository.autoConnectFlags)

            // The background request has no deadline; it is only refreshed every 10 minutes.
            advanceTimeBy(10 * 60_000L - 1)
            runCurrent()
            assertEquals(5, fixture.repository.connectCalls.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(false, false, false, false, true, true), fixture.repository.autoConnectFlags)

            // When the controller completes it, setup proceeds as usual.
            fixture.repository.emit(BleEvent.Connected(device))
            assertEquals(CollectionPhase.CONNECTING, fixture.controller.state.value.phase)
            fixture.emitReadyAAndBFrames()
            assertEquals(CollectionPhase.COLLECTING, fixture.controller.state.value.phase)
            assertEquals(0, fixture.controller.state.value.reconnectAttempt)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun failingBackgroundRequestDoesNotSpin() = runTest {
        val fixture = fixture(testScheduler)
        try {
            fixture.controller.start(configuration)
            fixture.controller.onDeviceDisappeared(configuration.snapshot.address)
            assertEquals(2, fixture.repository.connectCalls.size)

            repeat(3) { fixture.repository.emit(BleEvent.Error("connectGatt returned null")) }
            assertEquals(2, fixture.repository.connectCalls.size)
            advanceTimeBy(15_000)
            runCurrent()
            assertEquals(3, fixture.repository.connectCalls.size)
            assertTrue(fixture.repository.autoConnectFlags.last())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun bluetoothOffParksCollectionAndBluetoothOnReconnectsImmediately() = runTest {
        val fixture = fixture(testScheduler)
        try {
            fixture.controller.start(configuration)
            fixture.emitReadyAAndBFrames()

            fixture.controller.onBluetoothStateChanged(enabled = false)
            assertEquals(CollectionPhase.BLUETOOTH_OFF, fixture.controller.state.value.phase)
            assertEquals(1, fixture.repository.disconnectCalls)

            // Late link-loss events from the dying stack must not schedule retries while it is off.
            fixture.repository.emit(BleEvent.Disconnected("GATT disconnected with status 0"))
            fixture.repository.emit(BleEvent.Error("connectGatt returned null"))
            advanceTimeBy(30 * 60_000L)
            runCurrent()
            assertEquals(1, fixture.repository.connectCalls.size)
            assertEquals(CollectionPhase.BLUETOOTH_OFF, fixture.controller.state.value.phase)

            fixture.controller.onBluetoothStateChanged(enabled = true)
            val reconnecting = fixture.controller.state.value
            assertEquals(CollectionPhase.CONNECTING, reconnecting.phase)
            assertEquals(0, reconnecting.reconnectAttempt)
            assertEquals(listOf(false, false), fixture.repository.autoConnectFlags)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun startWithBluetoothOffWaitsForItToTurnOn() = runTest {
        var adapterOn = false
        val fixture = fixture(testScheduler, isBluetoothEnabled = { adapterOn })
        try {
            fixture.controller.start(configuration)
            assertEquals(CollectionPhase.BLUETOOTH_OFF, fixture.controller.state.value.phase)
            assertEquals(0, fixture.repository.connectCalls.size)

            adapterOn = true
            fixture.controller.onBluetoothStateChanged(enabled = true)
            assertEquals(CollectionPhase.CONNECTING, fixture.controller.state.value.phase)
            assertEquals(1, fixture.repository.connectCalls.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun sessionBluetoothOffErrorParksWhenTheBroadcastWasMissed() = runTest {
        var adapterOn = true
        val fixture = fixture(testScheduler, isBluetoothEnabled = { adapterOn })
        try {
            fixture.controller.start(configuration)
            adapterOn = false
            fixture.repository.emit(BleEvent.Error(AndroidBleSession.BLUETOOTH_OFF_MESSAGE))
            assertEquals(CollectionPhase.BLUETOOTH_OFF, fixture.controller.state.value.phase)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, fixture.repository.connectCalls.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun connectedLinkThatNeverFinishesSetupIsReconnected() = runTest {
        val fixture = fixture(testScheduler)
        try {
            fixture.controller.start(configuration)
            fixture.repository.emit(BleEvent.Connected(device))
            // Service discovery or the CCCD write never completes.
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(CollectionPhase.ERROR, fixture.controller.state.value.phase)
            assertEquals("Meter setup timed out", fixture.controller.state.value.error)
            assertEquals(1, fixture.repository.disconnectCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun meterThatNeverSendsBFrameIsReconnectedAndBootstrapStops() = runTest {
        val fixture = fixture(testScheduler)
        try {
            fixture.controller.start(configuration)
            fixture.repository.emit(BleEvent.ServicesDiscovered(profile))
            fixture.repository.emit(BleEvent.NotificationReceived(profile.notifyUuid, ":A=1326,1080,1,5793,57930,1000\n".encodeToByteArray()))
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(CollectionPhase.ERROR, fixture.controller.state.value.phase)
            assertEquals("Meter did not start streaming", fixture.controller.state.value.error)

            val bootstrapWrites = fixture.repository.countWrites(":*")
            advanceTimeBy(900)
            runCurrent()
            assertEquals(bootstrapWrites, fixture.repository.countWrites(":*"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun errorOnLiveLinkReleasesItAndCountsOneFailure() = runTest {
        val fixture = fixture(testScheduler)
        try {
            fixture.controller.start(configuration)
            fixture.repository.emit(BleEvent.Connected(device))
            fixture.repository.emit(BleEvent.Error("Failed to write CCCD descriptor"))

            val failed = fixture.controller.state.value
            assertEquals(CollectionPhase.ERROR, failed.phase)
            assertEquals(1, failed.reconnectAttempt)
            assertEquals(1, fixture.repository.disconnectCalls)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(2, fixture.repository.connectCalls.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun silentLinkIsProbedThenReconnected() = runTest {
        val fixture = fixture(testScheduler, virtualMonotonicClock = true)
        try {
            fixture.controller.start(configuration)
            fixture.emitReadyAAndBFrames()
            fixture.emitTotalsFrame()
            advanceTimeBy(2_000)
            runCurrent()
            val pollsBeforeSilence = fixture.repository.countWrites(":C\n")

            // Last notification at t=0; liveness checks run every 15 s.
            advanceTimeBy(57_000)
            runCurrent()
            assertEquals(pollsBeforeSilence, fixture.repository.countWrites(":C\n"))
            advanceTimeBy(15_000)
            runCurrent()
            assertTrue(fixture.repository.countWrites(":C\n") > pollsBeforeSilence)
            assertEquals(CollectionPhase.COLLECTING, fixture.controller.state.value.phase)

            advanceTimeBy(46_500)
            runCurrent()
            val failed = fixture.controller.state.value
            assertEquals(CollectionPhase.ERROR, failed.phase)
            assertEquals("No data from the meter for 120 s", failed.error)
            assertEquals(1, fixture.repository.disconnectCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun slowButAliveMeterKeepsItsLink() = runTest {
        val fixture = fixture(testScheduler, virtualMonotonicClock = true)
        try {
            fixture.controller.start(configuration)
            fixture.emitReadyAAndBFrames()
            fixture.emitTotalsFrame()
            repeat(20) {
                advanceTimeBy(50_000)
                runCurrent()
                fixture.emitTotalsFrame()
            }
            assertEquals(CollectionPhase.COLLECTING, fixture.controller.state.value.phase)
            assertEquals(0, fixture.repository.disconnectCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun presenceLossWhileCollectingKeepsTheLink() = runTest {
        val fixture = fixture(testScheduler)
        try {
            fixture.controller.start(configuration)
            fixture.emitReadyAAndBFrames()
            fixture.controller.onDeviceDisappeared(configuration.snapshot.address)
            assertEquals(CollectionPhase.COLLECTING, fixture.controller.state.value.phase)
            assertEquals(0, fixture.repository.disconnectCalls)

            // If the link then drops, the meter is known to be away: go straight to background connect.
            fixture.repository.emit(BleEvent.Disconnected("GATT disconnected with status 8"))
            assertEquals(CollectionPhase.WAITING_FOR_DEVICE, fixture.controller.state.value.phase)
            assertTrue(fixture.repository.autoConnectFlags.last())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun reconnectNowRestartsStuckOrWaitingAttempts() = runTest {
        val fixture = fixture(testScheduler)
        try {
            fixture.controller.start(configuration)
            fixture.controller.reconnectNow()
            assertEquals(2, fixture.repository.connectCalls.size)

            fixture.controller.onDeviceDisappeared(configuration.snapshot.address)
            assertEquals(CollectionPhase.WAITING_FOR_DEVICE, fixture.controller.state.value.phase)
            fixture.controller.reconnectNow()
            assertEquals(CollectionPhase.CONNECTING, fixture.controller.state.value.phase)
            assertEquals(listOf(false, false, true, false), fixture.repository.autoConnectFlags)

            fixture.controller.onBluetoothStateChanged(enabled = false)
            fixture.controller.reconnectNow()
            assertEquals(4, fixture.repository.connectCalls.size)
        } finally {
            fixture.close()
        }
    }

    private fun fixture(
        testScheduler: TestCoroutineScheduler,
        isBluetoothEnabled: () -> Boolean = { true },
        virtualMonotonicClock: Boolean = false,
    ): Fixture {
        val repository = FakeBleRepository()
        val history = FakeHistoryStore()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        return Fixture(
            repository = repository,
            history = history,
            scope = scope,
            controller = CollectionControllerImpl(
                repository,
                history,
                scope,
                clock = { 42L },
                // Real time by default so older tests that idle for minutes are not probed.
                monotonicClock = if (virtualMonotonicClock) {
                    { testScheduler.currentTime }
                } else {
                    { System.nanoTime() / 1_000_000L }
                },
                isBluetoothEnabled = isBluetoothEnabled,
            ),
        )
    }

    private data class Fixture(
        val repository: FakeBleRepository,
        val history: FakeHistoryStore,
        val scope: CoroutineScope,
        val controller: CollectionController,
    ) {
        suspend fun emitReadyAAndBFrames() {
            repository.emit(BleEvent.ServicesDiscovered(profile))
            repository.emit(BleEvent.NotificationReceived(profile.notifyUuid, ":A=1326,1080,1,5793,57930,1000\n".encodeToByteArray()))
            repository.emit(BleEvent.NotificationReceived(profile.notifyUuid, ":B=1\n".encodeToByteArray()))
        }

        suspend fun emitTotalsFrame() {
            repository.emit(BleEvent.NotificationReceived(profile.notifyUuid, ":C=1820,486,0,0,0,0\n".encodeToByteArray()))
        }

        fun close() {
            controller.close()
            scope.cancel()
        }
    }

    private class FakeBleRepository : BleRepositoryContract {
        override val events = MutableSharedFlow<BleEvent>(extraBufferCapacity = 32)
        val connectCalls = mutableListOf<Pair<ScannedDevice, DeviceSnapshot?>>()
        val autoConnectFlags = mutableListOf<Boolean>()
        val writes = mutableListOf<String>()
        val requestedMtus = mutableListOf<Int>()
        var disconnectCalls = 0

        override fun startScan() = Unit
        override fun stopScan() = Unit
        override fun connect(device: ScannedDevice, preferred: DeviceSnapshot?, autoConnect: Boolean) {
            connectCalls += device to preferred
            autoConnectFlags += autoConnect
        }
        override fun write(bytes: ByteArray): Boolean {
            writes += bytes.toString(Charsets.US_ASCII)
            return true
        }
        override fun requestMtu(mtu: Int): Boolean {
            requestedMtus += mtu
            return true
        }
        override fun disconnect() {
            disconnectCalls += 1
            // Like BleRepository: every disconnect() emits a Disconnected event.
            events.tryEmit(BleEvent.Disconnected())
        }
        suspend fun emit(event: BleEvent) {
            events.emit(event)
        }
        fun countWrites(value: String): Int = writes.count { it == value }
    }

    private class FakeHistoryStore : KmfHistoryStore {
        val notifications = mutableListOf<KmfNotificationObservation>()
        var throwOnReset = false
        var throwOnNotification = false

        override suspend fun resetSession(deviceAddress: String) {
            if (throwOnReset) error("history reset failed")
        }
        override suspend fun recordNotification(observation: KmfNotificationObservation) {
            if (throwOnNotification) error("history notification failed")
            notifications += observation
        }
        override suspend fun recordWrite(observation: KmfWriteObservation) = Unit
        override fun observeLatestBatterySample(deviceAddress: String): Flow<KmfBatterySampleEntity?> = emptyFlow()
        override fun observeRecentBatterySamples(deviceAddress: String, limit: Int): Flow<List<KmfBatterySampleEntity>> = emptyFlow()
        override fun observeRecentFrameEvents(deviceAddress: String, limit: Int): Flow<List<KmfFrameEventEntity>> = emptyFlow()
    }

    private companion object {
        val configuration = CollectionConfiguration(
            snapshot = DeviceSnapshot(
                address = "AA:BB:CC:DD:EE:FF",
                name = "KMF",
                serviceUuid = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                notifyUuid = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                writeUuid = "cccccccc-cccc-cccc-cccc-cccccccccccc",
            ),
            associationId = 7,
            autonomousEnabled = true,
        )
        val device = ScannedDevice(name = "KMF", address = "AA:BB:CC:DD:EE:FF", rssi = 0, serviceUuids = emptyList())
        val profile = GattProfile(
            serviceUuid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            notifyUuid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
            writeUuid = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
            usesIndications = false,
        )
    }
}
