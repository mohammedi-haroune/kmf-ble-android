package com.juncehome.lifepo4ble.ui.firstrun

import com.juncehome.lifepo4ble.ble.ScannedDevice
import com.juncehome.lifepo4ble.ui.BleUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunResolverTest {
    private val fresh = FirstRunInput(
        hasBluetoothAdapter = true,
        bluetoothEnabled = true,
        bluetoothPermissionsGranted = false,
        meterSaved = false,
        companionAssociated = false,
        sdkInt = 34,
        notificationPermissionGranted = false,
    )

    private val afterPermissions = fresh.copy(bluetoothPermissionsGranted = true)
    private val afterMeter = afterPermissions.copy(meterSaved = true)
    private val afterApproval = afterMeter.copy(companionAssociated = true)
    private val afterNotifications = afterApproval.copy(notificationPermissionGranted = true)

    @Test
    fun missingBluetoothPermissionsStartAtNearbyDevices() {
        assertEquals(FirstRunStep.NearbyDevices, FirstRunResolver.resolve(fresh))
    }

    @Test
    fun grantedPermissionsWithoutMeterMoveToFindMeter() {
        assertEquals(FirstRunStep.FindMeter, FirstRunResolver.resolve(afterPermissions))
    }

    @Test
    fun savedMeterWithoutAssociationMovesToAndroidApproval() {
        assertEquals(FirstRunStep.AndroidApproval, FirstRunResolver.resolve(afterMeter))
    }

    @Test
    fun associationWithoutNotificationsMovesToNotificationsOnApi33AndAbove() {
        assertEquals(FirstRunStep.Notifications, FirstRunResolver.resolve(afterApproval.copy(sdkInt = 33)))
        assertEquals(FirstRunStep.Notifications, FirstRunResolver.resolve(afterApproval.copy(sdkInt = 36)))
    }

    @Test
    fun notificationsStepIsSkippedBelowApi33() {
        assertEquals(FirstRunStep.Done, FirstRunResolver.resolve(afterApproval.copy(sdkInt = 32)))
        assertEquals(FirstRunStep.Done, FirstRunResolver.resolve(afterApproval.copy(sdkInt = 31)))
        assertFalse(FirstRunResolver.notificationPermissionRequired(32))
        assertTrue(FirstRunResolver.notificationPermissionRequired(33))
    }

    @Test
    fun allStepsSatisfiedResolveToDone() {
        assertEquals(FirstRunStep.Done, FirstRunResolver.resolve(afterNotifications))
    }

    @Test
    fun earlierMissingRequirementWinsOverLaterOnes() {
        // Permission revoked after the meter was saved and associated: back to step 1.
        val revoked = afterNotifications.copy(bluetoothPermissionsGranted = false)
        assertEquals(FirstRunStep.NearbyDevices, FirstRunResolver.resolve(revoked))
        // Association without a saved meter still asks for the meter first.
        assertEquals(FirstRunStep.FindMeter, FirstRunResolver.resolve(afterPermissions.copy(companionAssociated = true)))
    }

    @Test
    fun bluetoothOffOverridesEveryStep() {
        listOf(fresh, afterPermissions, afterMeter, afterApproval, afterNotifications).forEach { input ->
            assertEquals(
                "input=$input",
                FirstRunStep.BluetoothOff,
                FirstRunResolver.resolve(input.copy(bluetoothEnabled = false)),
            )
        }
    }

    @Test
    fun bluetoothOffIsNotShownWithoutAnAdapter() {
        assertEquals(
            FirstRunStep.NearbyDevices,
            FirstRunResolver.resolve(fresh.copy(hasBluetoothAdapter = false, bluetoothEnabled = false)),
        )
    }

    @Test
    fun bluetoothOnAgainReturnsToTheSameStep() {
        val off = afterMeter.copy(bluetoothEnabled = false)
        assertEquals(FirstRunStep.BluetoothOff, FirstRunResolver.resolve(off))
        assertEquals(FirstRunStep.AndroidApproval, FirstRunResolver.resolve(off.copy(bluetoothEnabled = true)))
    }

    @Test
    fun dismissingBluetoothOffRevealsOnlyStepsThatDoNotNeedTheRadio() {
        fun dismissed(input: FirstRunInput) = input.copy(bluetoothEnabled = false, bluetoothOffDismissed = true)

        assertEquals(FirstRunStep.NearbyDevices, FirstRunResolver.resolve(dismissed(fresh)))
        assertEquals(FirstRunStep.BluetoothOff, FirstRunResolver.resolve(dismissed(afterPermissions)))
        assertEquals(FirstRunStep.BluetoothOff, FirstRunResolver.resolve(dismissed(afterMeter)))
        assertEquals(FirstRunStep.Notifications, FirstRunResolver.resolve(dismissed(afterApproval)))
        assertEquals(FirstRunStep.Done, FirstRunResolver.resolve(dismissed(afterNotifications)))
    }

    @Test
    fun stepNumbersMatchTheBoard() {
        assertNull(FirstRunStep.BluetoothOff.number)
        assertEquals(1, FirstRunStep.NearbyDevices.number)
        assertEquals(2, FirstRunStep.FindMeter.number)
        assertEquals(3, FirstRunStep.AndroidApproval.number)
        assertEquals(4, FirstRunStep.Notifications.number)
        assertNull(FirstRunStep.Done.number)
        assertEquals(4, FirstRunResolver.TOTAL_STEPS)
    }

    @Test
    fun setupCompleteNeedsMeterAssociationAndConfiguration() {
        assertTrue(FirstRunResolver.setupComplete(meterSaved = true, companionAssociated = true, collectionConfigured = true))
        assertFalse(FirstRunResolver.setupComplete(meterSaved = false, companionAssociated = true, collectionConfigured = true))
        assertFalse(FirstRunResolver.setupComplete(meterSaved = true, companionAssociated = false, collectionConfigured = true))
        assertFalse(FirstRunResolver.setupComplete(meterSaved = true, companionAssociated = true, collectionConfigured = false))
    }

    @Test
    fun setupCompleteFromStateAcceptsEnabledCollectionOrFinishedFirstRun() {
        val base = BleUiState(savedMeterAddress = "meter", companionAssociated = true)
        assertFalse(FirstRunResolver.setupComplete(base))
        assertTrue(FirstRunResolver.setupComplete(base.copy(firstRunCompleted = true)))
        // Enabled configuration implies association even if the platform lookup reports false.
        assertTrue(
            FirstRunResolver.setupComplete(
                base.copy(companionAssociated = false, autonomousCollectionConfigured = true),
            ),
        )
        assertFalse(FirstRunResolver.setupComplete(base.copy(savedMeterAddress = null, firstRunCompleted = true)))
    }

    @Test
    fun shellShowsLoadingUntilSetupStateIsKnown() {
        assertEquals(
            ShellContent.Loading,
            FirstRunResolver.shellContent(setupStateLoaded = false, setupComplete = false, firstRunRequested = false),
        )
    }

    @Test
    fun shellShowsFirstRunWhenIncompleteAndTabsWhenComplete() {
        assertEquals(
            ShellContent.FirstRun,
            FirstRunResolver.shellContent(setupStateLoaded = true, setupComplete = false, firstRunRequested = false),
        )
        assertEquals(
            ShellContent.Tabs,
            FirstRunResolver.shellContent(setupStateLoaded = true, setupComplete = true, firstRunRequested = false),
        )
    }

    @Test
    fun sessionLatchKeepsFirstRunAfterSetupCompletes() {
        assertEquals(
            ShellContent.FirstRun,
            FirstRunResolver.shellContent(setupStateLoaded = true, setupComplete = true, firstRunRequested = true),
        )
    }

    @Test
    fun uiStateMapsToResolverInput() {
        val state = BleUiState(
            requiredPermissions = setOf("scan", "connect", "notify"),
            grantedPermissions = setOf("scan", "connect"),
            bluetoothPermissions = setOf("scan", "connect"),
            notificationPermission = "notify",
            hasBluetoothAdapter = true,
            bluetoothEnabled = true,
            savedMeterAddress = "meter",
            companionAssociated = true,
        )

        val input = state.toFirstRunInput(sdkInt = 34)

        assertTrue(input.bluetoothPermissionsGranted)
        assertFalse(input.notificationPermissionGranted)
        assertTrue(input.meterSaved)
        assertEquals(FirstRunStep.Notifications, FirstRunResolver.resolve(input))
        assertEquals(
            FirstRunStep.Done,
            FirstRunResolver.resolve(state.copy(grantedPermissions = state.requiredPermissions).toFirstRunInput(34)),
        )
    }

    @Test
    fun missingNotificationPermissionNameMeansGranted() {
        val state = BleUiState(notificationPermission = null)
        assertTrue(state.notificationPermissionGranted)
    }

    @Test
    fun nearbyRowsSortBySignalAndFlagTheStrongBestRow() {
        val rows = NearbyMeterList.rows(
            listOf(
                device("weak", null, -90),
                device("best", "KMF Meter", -55),
                device("good", "Other", -72),
                device("unknown", "Snapshot", 0),
            ),
        )

        assertEquals(listOf("best", "good", "weak", "unknown"), rows.map { it.device.address })
        assertTrue(rows[0].likelyMeter)
        assertEquals("Strong signal \u00B7 likely your meter", rows[0].signalLabel)
        assertEquals("Good signal", rows[1].signalLabel)
        assertEquals("Weak signal", rows[2].signalLabel)
        assertEquals("Unnamed device", rows[2].name)
        assertNull(rows[3].signalLabel)
        assertEquals(1, rows.count { it.likelyMeter })
    }

    @Test
    fun bestRowIsNotFlaggedWhenItsSignalIsWeak() {
        val rows = NearbyMeterList.rows(listOf(device("a", "A", -78), device("b", "B", -85)))

        assertFalse(rows.any { it.likelyMeter })
        assertEquals("Good signal", rows[0].signalLabel)
    }

    @Test
    fun unknownRssiIsNeverFlagged() {
        val rows = NearbyMeterList.rows(listOf(device("a", "A", 127)))

        assertFalse(rows.single().likelyMeter)
        assertNull(rows.single().signal)
    }

    @Test
    fun headerCountsDevices() {
        assertEquals("Nearby \u00B7 2 found", NearbyMeterList.headerLabel(2))
        assertEquals("Nearby \u00B7 searching\u2026", NearbyMeterList.headerLabel(0))
    }

    private fun device(address: String, name: String?, rssi: Int) = ScannedDevice(
        name = name,
        address = address,
        rssi = rssi,
        serviceUuids = emptyList(),
    )
}
