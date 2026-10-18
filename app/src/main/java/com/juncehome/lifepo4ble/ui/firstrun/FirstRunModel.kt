package com.juncehome.lifepo4ble.ui.firstrun

import com.juncehome.lifepo4ble.ble.ScannedDevice
import com.juncehome.lifepo4ble.ui.BleUiState

/**
 * First-run steps from docs/design/README.md "First run". [number] is the "Step N of 4" index;
 * Bluetooth off and Done are not numbered and hide the progress header.
 */
enum class FirstRunStep(val number: Int?) {
    BluetoothOff(null),
    NearbyDevices(1),
    FindMeter(2),
    AndroidApproval(3),
    Notifications(4),
    Done(null),
    ;

    /** Steps that cannot make progress while the Bluetooth radio is off. */
    val needsBluetoothRadio: Boolean
        get() = this == FindMeter || this == AndroidApproval
}

/** Everything the resolver needs; built from [BleUiState] with [toFirstRunInput]. */
data class FirstRunInput(
    val hasBluetoothAdapter: Boolean,
    val bluetoothEnabled: Boolean,
    val bluetoothPermissionsGranted: Boolean,
    val meterSaved: Boolean,
    val companionAssociated: Boolean,
    val sdkInt: Int,
    val notificationPermissionGranted: Boolean,
    /** "Not now" on the Bluetooth-off screen; only honored for steps that do not need the radio. */
    val bluetoothOffDismissed: Boolean = false,
)

/** What the app shell shows instead of, or as, the tabs. */
enum class ShellContent { Loading, FirstRun, Tabs }

/** A short error line for one step after a denial; [offerAppSettings] when permanently denied. */
data class FirstRunNotice(
    val step: FirstRunStep,
    val message: String,
    val offerAppSettings: Boolean = false,
)

object FirstRunResolver {
    const val TOTAL_STEPS = 4
    const val NOTIFICATION_PERMISSION_MIN_SDK = 33

    fun notificationPermissionRequired(sdkInt: Int): Boolean = sdkInt >= NOTIFICATION_PERMISSION_MIN_SDK

    /** The numbered step the user still has to pass, ignoring the Bluetooth radio. */
    fun pendingStep(input: FirstRunInput): FirstRunStep = when {
        !input.bluetoothPermissionsGranted -> FirstRunStep.NearbyDevices
        !input.meterSaved -> FirstRunStep.FindMeter
        !input.companionAssociated -> FirstRunStep.AndroidApproval
        notificationPermissionRequired(input.sdkInt) && !input.notificationPermissionGranted ->
            FirstRunStep.Notifications
        else -> FirstRunStep.Done
    }

    fun resolve(input: FirstRunInput): FirstRunStep {
        val pending = pendingStep(input)
        val bluetoothOff = input.hasBluetoothAdapter && !input.bluetoothEnabled
        if (!bluetoothOff) return pending
        return if (canDismissBluetoothOff(input)) pending else FirstRunStep.BluetoothOff
    }

    /** "Not now" can reveal the underlying step only when that step does not need the radio. */
    fun canDismissBluetoothOff(input: FirstRunInput): Boolean =
        input.bluetoothOffDismissed && !pendingStep(input).needsBluetoothRadio

    /**
     * Setup is complete when a meter is saved, Android holds a companion association for it and
     * collection was configured at least once (enabled now, or the first run was finished; a later
     * explicit stop keeps the tabs).
     */
    fun setupComplete(
        meterSaved: Boolean,
        companionAssociated: Boolean,
        collectionConfigured: Boolean,
    ): Boolean = meterSaved && companionAssociated && collectionConfigured

    fun setupComplete(state: BleUiState): Boolean = setupComplete(
        meterSaved = state.savedMeterAddress != null,
        companionAssociated = state.companionAssociatedOrConfigured,
        collectionConfigured = state.autonomousCollectionConfigured || state.firstRunCompleted,
    )

    /**
     * [firstRunRequested] is the session latch: set once first run was needed (or "Set up" was
     * tapped) so steps 4 and Done stay visible after setup becomes complete mid-flow.
     */
    fun shellContent(
        setupStateLoaded: Boolean,
        setupComplete: Boolean,
        firstRunRequested: Boolean,
    ): ShellContent = when {
        firstRunRequested -> ShellContent.FirstRun
        !setupStateLoaded -> ShellContent.Loading
        !setupComplete -> ShellContent.FirstRun
        else -> ShellContent.Tabs
    }
}

private val BleUiState.companionAssociatedOrConfigured: Boolean
    get() = companionAssociated || autonomousCollectionConfigured

fun BleUiState.toFirstRunInput(
    sdkInt: Int,
    bluetoothOffDismissed: Boolean = false,
): FirstRunInput = FirstRunInput(
    hasBluetoothAdapter = hasBluetoothAdapter,
    bluetoothEnabled = bluetoothEnabled,
    bluetoothPermissionsGranted = bluetoothPermissionsGranted,
    meterSaved = savedMeterAddress != null,
    companionAssociated = companionAssociatedOrConfigured,
    sdkInt = sdkInt,
    notificationPermissionGranted = notificationPermissionGranted,
    bluetoothOffDismissed = bluetoothOffDismissed,
)

enum class SignalStrength { Strong, Good, Weak }

/** One row of the Find meter list. */
data class NearbyMeterRow(
    val device: ScannedDevice,
    val name: String,
    val named: Boolean,
    val signal: SignalStrength?,
    val likelyMeter: Boolean,
) {
    val signalLabel: String?
        get() = when {
            likelyMeter -> "Strong signal \u00B7 likely your meter"
            signal == SignalStrength.Strong -> "Strong signal"
            signal == SignalStrength.Good -> "Good signal"
            signal == SignalStrength.Weak -> "Weak signal"
            else -> null
        }
}

object NearbyMeterList {
    const val STRONG_RSSI = -65
    const val GOOD_RSSI = -80

    /** The best row is highlighted only when its RSSI is at least this strong. */
    const val LIKELY_METER_MIN_RSSI = -70

    /** Android reports 127 (or 0 for snapshot-derived devices) when RSSI is unknown. */
    fun rssiKnown(rssi: Int): Boolean = rssi in -127..-1

    fun signalFor(rssi: Int): SignalStrength? = when {
        !rssiKnown(rssi) -> null
        rssi >= STRONG_RSSI -> SignalStrength.Strong
        rssi >= GOOD_RSSI -> SignalStrength.Good
        else -> SignalStrength.Weak
    }

    /** Sorted by signal (strongest first, unknown last); the best known strong row is flagged. */
    fun rows(devices: List<ScannedDevice>): List<NearbyMeterRow> {
        val sorted = devices
            .distinctBy { it.address }
            .sortedWith(
                compareByDescending<ScannedDevice> { rssiKnown(it.rssi) }
                    .thenByDescending { it.rssi }
                    .thenBy { it.name.isNullOrBlank() },
            )
        val best = sorted.firstOrNull()?.takeIf { rssiKnown(it.rssi) && it.rssi >= LIKELY_METER_MIN_RSSI }
        return sorted.map { device ->
            val named = !device.name.isNullOrBlank()
            NearbyMeterRow(
                device = device,
                name = if (named) device.name!!.trim() else "Unnamed device",
                named = named,
                signal = signalFor(device.rssi),
                likelyMeter = device.address == best?.address,
            )
        }
    }

    fun headerLabel(count: Int): String =
        if (count == 0) "Nearby \u00B7 searching\u2026" else "Nearby \u00B7 $count found"
}
