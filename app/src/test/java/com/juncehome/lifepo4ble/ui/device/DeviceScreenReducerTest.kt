package com.juncehome.lifepo4ble.ui.device

import com.juncehome.lifepo4ble.ble.ScannedDevice
import com.juncehome.lifepo4ble.collection.CollectionCadence
import com.juncehome.lifepo4ble.collection.CollectionPhase
import com.juncehome.lifepo4ble.collection.CollectionState
import com.juncehome.lifepo4ble.collection.CollectionVisibility
import com.juncehome.lifepo4ble.protocol.PacketDirection
import com.juncehome.lifepo4ble.protocol.PacketFormatter
import com.juncehome.lifepo4ble.remote.SharingStatus
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

    // ---- Network card: sharing and remote connection ----

    private fun sharingStatus(
        addresses: List<String>,
        viewers: Int = 0,
        running: Boolean = true,
        error: String? = null,
    ) = SharingStatus(running = running, port = 8765, addresses = addresses, viewers = viewers, error = error)

    private fun sharing(status: SharingStatus?) = collecting().copy(shareOnNetwork = true, sharingStatus = status)

    private val homeMeter = ScannedDevice("KMF-BT-5A3C", "remote:home:8765", 0, emptyList())

    private fun remote(link: CollectionState?) = base().copy(
        remoteAddress = "home:8765",
        selectedDevice = homeMeter,
        collectionState = link,
    )

    private fun homeLink(
        phase: CollectionPhase,
        error: String? = null,
        nextAt: Long? = null,
        unreachable: Boolean = false,
    ) = CollectionState(
        phase = phase,
        visibility = CollectionVisibility.FOREGROUND,
        deviceAddress = "remote:home:8765",
        error = error,
        nextReconnectAtMs = nextAt,
        remoteUnreachable = unreachable,
    )

    @Test
    fun networkCardSharingShowsPortAddressesAndViewers() {
        val card = reduce(sharing(sharingStatus(listOf("192.168.1.23", "10.0.0.5"), viewers = 1))).network
        assertTrue(card.shareOn)
        assertTrue(card.shareEnabled)
        assertEquals("Sharing \u00B7 port 8765", card.shareStatus)
        assertEquals(listOf("192.168.1.23", "10.0.0.5"), card.shareAddresses)
        assertEquals("1 viewer", card.viewers)
        assertEquals(DeviceScreenReducer.NOTE_NO_PASSWORD, card.note)
    }

    @Test
    fun networkCardCountsViewers() {
        fun viewers(count: Int) = reduce(sharing(sharingStatus(listOf("192.168.1.23"), viewers = count))).network.viewers
        assertEquals("0 viewers", viewers(0))
        assertEquals("1 viewer", viewers(1))
        assertEquals("12 viewers", viewers(12))
    }

    @Test
    fun networkCardAlwaysHasTwoAddressSlots() {
        fun addresses(vararg values: String) = reduce(sharing(sharingStatus(values.toList()))).network.shareAddresses
        assertEquals(listOf("192.168.1.23", "--"), addresses("192.168.1.23"))
        assertEquals(listOf("192.168.1.23", "10.0.0.5"), addresses("192.168.1.23", "10.0.0.5", "172.16.0.9"))
        assertEquals(listOf("--", "--"), addresses())
        assertEquals(listOf("--", "--"), reduce(collecting()).network.shareAddresses)
    }

    @Test
    fun networkCardShareStatusFollowsTheServer() {
        val off = reduce(collecting().copy(sharingStatus = sharingStatus(listOf("192.168.1.23"), viewers = 3))).network
        assertFalse(off.shareOn)
        assertEquals("Off", off.shareStatus)
        assertEquals(listOf("--", "--"), off.shareAddresses)
        assertEquals("0 viewers", off.viewers)

        assertEquals("Waiting for background collection", reduce(sharing(null)).network.shareStatus)
        val stopped = sharing(sharingStatus(emptyList(), running = false))
        assertEquals("Waiting for background collection", reduce(stopped).network.shareStatus)

        val failed = reduce(sharing(sharingStatus(emptyList(), running = false, error = "Port 8765 is in use"))).network
        assertEquals("Port 8765 is in use", failed.shareStatus)
        assertEquals(listOf("--", "--"), failed.shareAddresses)
    }

    @Test
    fun remoteModeDisablesShareAndBluetoothCollection() {
        val model = reduce(remote(homeLink(CollectionPhase.COLLECTING)).copy(shareOnNetwork = true))
        assertFalse(model.network.shareEnabled)
        assertFalse(model.network.shareOn)
        assertEquals("Off", model.network.shareStatus)
        assertEquals(listOf("--", "--"), model.network.shareAddresses)
        assertTrue(model.network.remoteConnected)
        assertEquals("home:8765", model.network.remoteAddress)
        assertFalse(model.collection.on)
        assertFalse(model.collection.switchEnabled)
        assertEquals(DeviceScreenReducer.REMOTE_OFF_REASON, model.collection.detail)
        assertNull(model.collection.cadences)
        assertEquals(DeviceScreenReducer.REMOTE_TEST_BLOCKED, model.test.blockedReason)
        assertEquals(TestConnectionStatus.Idle, model.test.status)
        assertFalse(model.test.connectEnabled)
        assertFalse(model.meter.actionsEnabled)
    }

    @Test
    fun bluetoothModeKeepsShareAndMeterActionsEnabled() {
        val model = reduce(base())
        assertTrue(model.network.shareEnabled)
        assertFalse(model.network.remoteConnected)
        assertEquals("", model.network.remoteAddress)
        assertTrue(model.meter.actionsEnabled)
    }

    @Test
    fun remoteStatusMapsPhases() {
        fun status(link: CollectionState?) = reduce(remote(link)).network.remoteStatus
        fun tone(link: CollectionState?) = reduce(remote(link)).network.remoteTone

        assertEquals("Not connected", reduce(base()).network.remoteStatus)
        assertEquals(DeviceTone.Neutral, reduce(base()).network.remoteTone)

        assertEquals("Connecting\u2026", status(null))
        assertEquals("Connecting\u2026", status(homeLink(CollectionPhase.STOPPED)))
        assertEquals("Connecting\u2026", status(homeLink(CollectionPhase.CONNECTING)))
        assertEquals("Connected \u00B7 KMF-BT-5A3C", status(homeLink(CollectionPhase.COLLECTING)))
        assertEquals("Connected \u00B7 KMF-BT-5A3C", status(homeLink(CollectionPhase.BOOTSTRAPPING)))
        assertEquals(DeviceTone.Live, tone(homeLink(CollectionPhase.COLLECTING)))

        val offline = homeLink(CollectionPhase.ERROR, error = "failed to connect", nextAt = nowMs + 4_200L, unreachable = true)
        assertEquals("Phone offline \u00B7 retry in 5 s", status(offline))
        assertEquals(DeviceTone.Warning, tone(offline))

        val homeRetry = homeLink(CollectionPhase.ERROR, error = "GATT 133", nextAt = nowMs + 5_000L)
        assertEquals("Home phone reconnecting to meter", status(homeRetry))
        assertEquals(DeviceTone.Warning, tone(homeRetry))

        val homeOff = homeLink(CollectionPhase.ERROR, error = "Collection is off on the home phone")
        assertEquals("Collection is off on the home phone", status(homeOff))
        assertEquals(DeviceTone.Warning, tone(homeOff))

        assertEquals("Home phone: meter out of range", status(homeLink(CollectionPhase.WAITING_FOR_DEVICE)))
        assertEquals("Home phone: Bluetooth off", status(homeLink(CollectionPhase.BLUETOOTH_OFF)))
        assertEquals(DeviceTone.Warning, tone(homeLink(CollectionPhase.BLUETOOTH_OFF)))

        val homeError = homeLink(CollectionPhase.ERROR, error = "boom")
        assertEquals("Home phone error \u00B7 boom", status(homeError))
        assertEquals(DeviceTone.Error, tone(homeError))
    }

    @Test
    fun remoteStatusFallsBackToDefaultMeterName() {
        val state = remote(homeLink(CollectionPhase.COLLECTING)).copy(selectedDevice = null)
        assertEquals("Connected \u00B7 ${DeviceScreenReducer.DEFAULT_METER_NAME}", reduce(state).network.remoteStatus)
    }

    @Test
    fun remoteErrorIsShown() {
        val model = reduce(base().copy(remoteAddressError = "Enter a host name or IP, optionally with :port"))
        assertEquals("Enter a host name or IP, optionally with :port", model.network.remoteError)
        assertNull(reduce(base()).network.remoteError)
    }
}
