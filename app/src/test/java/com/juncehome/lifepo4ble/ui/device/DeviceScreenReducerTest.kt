package com.juncehome.lifepo4ble.ui.device

import com.juncehome.lifepo4ble.collection.CollectionCadence
import com.juncehome.lifepo4ble.collection.CollectionPhase
import com.juncehome.lifepo4ble.collection.CollectionState
import com.juncehome.lifepo4ble.collection.CollectionVisibility
import com.juncehome.lifepo4ble.protocol.PacketDirection
import com.juncehome.lifepo4ble.protocol.PacketFormatter
import com.juncehome.lifepo4ble.ui.BleUiState
import com.juncehome.lifepo4ble.ui.ConnectionState
import com.juncehome.lifepo4ble.ui.firstrun.FirstRunNotice
import com.juncehome.lifepo4ble.ui.firstrun.FirstRunStep
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceScreenReducerTest {
    private val utc = ZoneId.of("UTC")

    // 2025-10-18T14:32:10Z
    private val nowMs = 1_760_797_930_000L
    private val address = "AA:BB:CC:DD:EE:FF"
    private val btPermission = "android.permission.BLUETOOTH_CONNECT"
    private val notifyPermission = "android.permission.POST_NOTIFICATIONS"

    private fun base() = BleUiState(
        requiredPermissions = setOf(btPermission, notifyPermission),
        grantedPermissions = setOf(btPermission, notifyPermission),
        bluetoothPermissions = setOf(btPermission),
        notificationPermission = notifyPermission,
        hasBleFeature = true,
        hasBluetoothAdapter = true,
        bluetoothEnabled = true,
        savedMeterAddress = address,
        savedMeterName = "KMF Meter",
        savedMeterLoaded = true,
        collectionConfigurationLoaded = true,
        companionAssociated = true,
        lastReadingTimestampMs = nowMs - 2_000L,
    )

    private fun collecting() = base().copy(
        autonomousCollectionConfigured = true,
        collectionState = CollectionState(CollectionPhase.COLLECTING, CollectionVisibility.FOREGROUND, address),
    )

    private fun reduce(state: BleUiState, sdkInt: Int = 34, notice: FirstRunNotice? = null) =
        DeviceScreenReducer.reduce(state, nowMs, sdkInt, notice, utc)

    @Test
    fun collectingMode() {
        val model = reduce(collecting())
        assertEquals(DeviceMode.Collecting, model.mode)
        assertTrue(model.collection.on)
        assertTrue(model.collection.switchEnabled)
        assertTrue(model.collection.pulse)
        assertEquals(DeviceTone.Live, model.collection.tone)
        assertEquals("Collecting \u00B7 last sample 2s ago", model.collection.status)
        assertEquals(DeviceScreenReducer.DETAIL_ON, model.collection.detail)
        assertEquals(CollectionCadences("every 30 s", "every 5 min"), model.collection.cadences)
        assertEquals("Paired", model.meter.pairingLabel)
        assertEquals("Yes", model.meter.wakeLabel)
        assertEquals("14:32:08", model.meter.lastReading)
        assertFalse(model.meter.showPairButton)
        assertEquals(TestConnectionStatus.Disabled, model.test.status)
        assertEquals(DeviceScreenReducer.HINT_DISABLED, model.test.hint)
        assertFalse(model.test.connectEnabled)
    }

    @Test
    fun collectionOffMode() {
        val model = reduce(base())
        assertEquals(DeviceMode.CollectionOff, model.mode)
        assertFalse(model.collection.on)
        assertTrue(model.collection.switchEnabled)
        assertEquals(DeviceScreenReducer.STATUS_OFF, model.collection.status)
        assertEquals(DeviceScreenReducer.DETAIL_OFF, model.collection.detail)
        assertNull(model.collection.cadences)
        assertEquals("Paused", model.meter.wakeLabel)
        assertEquals(TestConnectionStatus.Idle, model.test.status)
        assertEquals(DeviceScreenReducer.HINT_IDLE, model.test.hint)
        assertTrue(model.test.connectEnabled)
        assertNull(model.test.blockedReason)
    }

    @Test
    fun testRunningModeShowsFramesNewestLast() {
        val log = listOf(
            PacketFormatter.toEntry(nowMs - 2_000L, PacketDirection.OUTBOUND, ":*".encodeToByteArray()),
            PacketFormatter.toEntry(nowMs - 1_000L, PacketDirection.INBOUND, ":A=1328,1240,1,174,1\n".encodeToByteArray()),
        )
        val model = reduce(
            base().copy(testConnectionActive = true, connectionState = ConnectionState.READY, packetLog = log),
        )
        assertEquals(DeviceMode.TestRunning, model.mode)
        assertEquals(TestConnectionStatus.Running, model.test.status)
        assertEquals(DeviceScreenReducer.HINT_RUNNING, model.test.hint)
        assertEquals("Connected", model.test.connectionLabel)
        assertTrue(model.test.connected)
        assertEquals(2, model.test.frames.size)
        assertEquals(FrameLine(model.test.frames[0].id, "14:32:08", PacketDirection.OUTBOUND, "\u2192", ":*"), model.test.frames[0])
        assertEquals("\u2190", model.test.frames[1].arrow)
        assertEquals(":A=1328,1240,1,174,1", model.test.frames[1].text)
        assertEquals(2, model.test.frames.map { it.id }.distinct().size)
    }

    @Test
    fun testRunningWhileConnectingShowsConnectingLabel() {
        val model = reduce(base().copy(testConnectionActive = true, connectionState = ConnectionState.CONNECTING))
        assertEquals("Connecting\u2026", model.test.connectionLabel)
        assertFalse(model.test.connected)
    }

    @Test
    fun frameLogIsCappedAtMaxLines() {
        val log = (0 until 250).map { index ->
            PacketFormatter.toEntry(nowMs + index, PacketDirection.INBOUND, ":A=$index\n".encodeToByteArray())
        }
        val lines = DeviceScreenReducer.frameLines(log, utc)
        assertEquals(DeviceScreenReducer.MAX_FRAME_LINES, lines.size)
        assertEquals(":A=249", lines.last().text)
    }

    @Test
    fun notPairedMode() {
        val model = reduce(base().copy(companionAssociated = false))
        assertEquals(DeviceMode.NotPaired, model.mode)
        assertFalse(model.collection.on)
        assertFalse(model.collection.switchEnabled)
        assertTrue(model.collection.pairingRequired)
        assertEquals(DeviceScreenReducer.STATUS_NOT_PAIRED, model.collection.status)
        assertEquals(DeviceScreenReducer.DETAIL_NOT_PAIRED, model.collection.detail)
        assertEquals("Not paired", model.meter.pairingLabel)
        assertEquals("No", model.meter.wakeLabel)
        assertTrue(model.meter.showPairButton)
    }

    @Test
    fun configuredCountsAsPairedEvenIfLookupFails() {
        val model = reduce(collecting().copy(companionAssociated = false))
        assertEquals(DeviceMode.Collecting, model.mode)
        assertTrue(model.meter.paired)
    }

    @Test
    fun collectingStatusFollowsServicePhase() {
        fun status(phase: CollectionPhase?, nextAt: Long? = null, error: String? = null) = reduce(
            collecting().copy(
                collectionState = phase?.let {
                    CollectionState(it, CollectionVisibility.FOREGROUND, address, error = error, nextReconnectAtMs = nextAt)
                },
            ),
        ).collection
        assertEquals("Waiting for the meter \u00B7 last sample 2s ago", status(CollectionPhase.WAITING_FOR_DEVICE).status)
        assertEquals("Connecting to the meter\u2026", status(CollectionPhase.BOOTSTRAPPING).status)
        assertEquals("Reconnecting in 15 s", status(CollectionPhase.ERROR, nextAt = nowMs + 15_000L).status)
        assertEquals(DeviceTone.Warning, status(CollectionPhase.ERROR, nextAt = nowMs + 15_000L).tone)
        assertEquals("Error \u00B7 boom", status(CollectionPhase.ERROR, error = "boom").status)
        assertEquals("On \u00B7 starting\u2026", status(null).status)
        assertFalse(status(CollectionPhase.WAITING_FOR_DEVICE).pulse)
    }

    @Test
    fun cadencesComeFromControllerConstants() {
        assertEquals(
            "every ${DeviceScreenReducer.formatInterval(CollectionCadence.FOREGROUND_POLL_INTERVAL_MS)}",
            DeviceScreenReducer.cadences().appOpen,
        )
        assertEquals("30 s", DeviceScreenReducer.formatInterval(30_000L))
        assertEquals("5 min", DeviceScreenReducer.formatInterval(300_000L))
        assertEquals("90 s", DeviceScreenReducer.formatInterval(90_000L))
        assertEquals("2 h", DeviceScreenReducer.formatInterval(7_200_000L))
    }

    @Test
    fun ageAndTimestampFormatting() {
        assertEquals("59s ago", DeviceScreenReducer.formatAge(59_999L))
        assertEquals("4 min ago", DeviceScreenReducer.formatAge(4 * 60_000L + 5_000L))
        assertEquals("3 h ago", DeviceScreenReducer.formatAge(3 * 3_600_000L))
        assertEquals("2 d ago", DeviceScreenReducer.formatAge(2 * 86_400_000L))
        assertEquals("17 Oct 14:32", DeviceScreenReducer.formatTimestamp(nowMs - 86_400_000L, nowMs, utc))
        assertEquals("No readings yet", reduce(base().copy(lastReadingTimestampMs = null)).meter.lastReading)
    }

    @Test
    fun permissionsAllGranted() {
        val rows = reduce(base()).permissions
        assertEquals(listOf("On", "Allowed", "Allowed"), rows.map { it.value })
        assertTrue(rows.all { it.action == PermissionAction.None && it.tone == DeviceTone.Live })
    }

    @Test
    fun permissionsDeniedOfferFixes() {
        val state = base().copy(bluetoothEnabled = false, grantedPermissions = emptySet())
        val rows = reduce(state).permissions.associateBy { it.kind }
        assertEquals("Off", rows.getValue(PermissionKind.Bluetooth).value)
        assertEquals(PermissionAction.EnableBluetooth, rows.getValue(PermissionKind.Bluetooth).action)
        assertEquals("Denied", rows.getValue(PermissionKind.NearbyDevices).value)
        assertEquals(PermissionAction.RequestNearbyDevices, rows.getValue(PermissionKind.NearbyDevices).action)
        assertEquals("Denied", rows.getValue(PermissionKind.Notifications).value)
        assertEquals(PermissionAction.RequestNotifications, rows.getValue(PermissionKind.Notifications).action)
        assertEquals("Allow Nearby devices first.", reduce(state).test.blockedReason)
        assertFalse(reduce(state).test.connectEnabled)
    }

    @Test
    fun permanentlyDeniedPermissionOpensAppSettings() {
        val state = base().copy(grantedPermissions = setOf(btPermission))
        val notice = FirstRunNotice(FirstRunStep.Notifications, "blocked", offerAppSettings = true)
        val row = reduce(state, notice = notice).permissions.single { it.kind == PermissionKind.Notifications }
        assertEquals(PermissionAction.OpenAppSettings, row.action)
    }

    @Test
    fun notificationsNotRequiredBelowApi33() {
        val state = base().copy(notificationPermission = null, grantedPermissions = setOf(btPermission))
        val row = reduce(state, sdkInt = 31).permissions.single { it.kind == PermissionKind.Notifications }
        assertEquals("Not required", row.value)
        assertEquals(PermissionAction.None, row.action)
        assertEquals(DeviceTone.Neutral, row.tone)
    }

    @Test
    fun idleTestShowsBluetoothBlockAndLastResult() {
        assertEquals("Turn on Bluetooth first.", reduce(base().copy(bluetoothEnabled = false)).test.blockedReason)
        assertEquals("Disconnected", reduce(base().copy(testConnectionResult = "Disconnected")).test.lastResult)
    }

    @Test
    fun configurationNotLoadedKeepsTestDisabled() {
        val model = reduce(base().copy(collectionConfigurationLoaded = false))
        assertEquals(TestConnectionStatus.Disabled, model.test.status)
    }

    @Test
    fun meterNameFallsBackToDefault() {
        assertEquals(DeviceScreenReducer.DEFAULT_METER_NAME, reduce(base().copy(savedMeterName = " ")).meter.name)
        assertEquals(address, reduce(base()).meter.address)
    }
}
