package com.juncehome.lifepo4ble.ui.device

import com.juncehome.lifepo4ble.collection.CollectionCadence
import com.juncehome.lifepo4ble.collection.CollectionPhase
import com.juncehome.lifepo4ble.protocol.FrameLogEntry
import com.juncehome.lifepo4ble.protocol.PacketDirection
import com.juncehome.lifepo4ble.remote.RemoteLinkMachine
import com.juncehome.lifepo4ble.ui.BleUiState
import com.juncehome.lifepo4ble.ui.ConnectionState
import com.juncehome.lifepo4ble.ui.firstrun.FirstRunNotice
import com.juncehome.lifepo4ble.ui.firstrun.FirstRunStep
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The four states of docs/design/device-tab.dc.html (`mode` enum). */
enum class DeviceMode { Collecting, CollectionOff, TestRunning, NotPaired }

/** Accent roles used by the Device tab; mapped to `KmfTheme.colors` in Compose. */
enum class DeviceTone { Live, Neutral, Warning, Error }

data class CollectionCadences(
    val appOpen: String,
    val background: String,
)

data class DeviceCollectionCard(
    val on: Boolean,
    /** False while the meter is not paired; the pairing CTA replaces the switch action. */
    val switchEnabled: Boolean,
    val tone: DeviceTone,
    val pulse: Boolean,
    val status: String,
    val detail: String,
    /** Shown only while collection is on. */
    val cadences: CollectionCadences?,
    val pairingRequired: Boolean,
)

data class DeviceMeterCard(
    val name: String,
    val address: String,
    val paired: Boolean,
    val pairingLabel: String,
    val wakeLabel: String,
    val lastReading: String,
    val showPairButton: Boolean,
    val pairingInProgress: Boolean,
    val meterSaved: Boolean,
    /** False while the meter is read through another phone: pair, change and forget stay in place but disabled. */
    val actionsEnabled: Boolean = true,
)

enum class PermissionKind { Bluetooth, NearbyDevices, Notifications }

enum class PermissionAction { None, EnableBluetooth, RequestNearbyDevices, RequestNotifications, OpenAppSettings }

data class DevicePermissionRow(
    val kind: PermissionKind,
    val label: String,
    val value: String,
    val tone: DeviceTone,
    val action: PermissionAction,
)

enum class TestConnectionStatus { Disabled, Idle, Running }

data class FrameLine(
    val id: Long,
    val time: String,
    val direction: PacketDirection,
    val arrow: String,
    val text: String,
)

data class DeviceTestCard(
    val status: TestConnectionStatus,
    val hint: String,
    val connectEnabled: Boolean,
    /** Why "Connect once" is unavailable while idle; null when it is available. */
    val blockedReason: String?,
    /** "Connected" / "Connecting..." while running. */
    val connectionLabel: String?,
    val connected: Boolean,
    /** Oldest first, newest last, at most [DeviceScreenReducer.MAX_FRAME_LINES]. */
    val frames: List<FrameLine>,
    /** Outcome of the last test while idle. */
    val lastResult: String?,
)

/** The Network card: this phone sharing its live feed, and this phone reading another phone's feed. */
data class DeviceNetworkCard(
    val shareOn: Boolean,
    /** False in remote mode: sharing needs this phone's own Bluetooth collection. */
    val shareEnabled: Boolean,
    val shareStatus: String,
    /** Always exactly [DeviceScreenReducer.SHARE_ADDRESS_SLOTS] entries, "--" for a missing one. */
    val shareAddresses: List<String>,
    /** "0 viewers", "1 viewer", "12 viewers". */
    val viewers: String,
    /** Remote mode is on: the Now tab shows the meter of another phone. */
    val remoteConnected: Boolean,
    /** The saved "host:port" in remote mode, "" otherwise. */
    val remoteAddress: String,
    val remoteStatus: String,
    val remoteTone: DeviceTone,
    /** Why the typed address was refused; null when it is fine. */
    val remoteError: String?,
    val note: String,
)

data class DeviceScreenState(
    val mode: DeviceMode,
    val collection: DeviceCollectionCard,
    val meter: DeviceMeterCard,
    val network: DeviceNetworkCard,
    val permissions: List<DevicePermissionRow>,
    val test: DeviceTestCard,
)

/**
 * Pure mapping from [BleUiState] (+ current time) to the Device tab
 * (docs/design/README.md "Device tab", "Connection ownership"). No Compose so every rule is
 * JVM-testable.
 */
object DeviceScreenReducer {
    const val DEFAULT_METER_NAME = "KMF Meter"
    const val MAX_FRAME_LINES = 200
    const val NOTIFICATION_PERMISSION_MIN_SDK = 33

    private const val DOT = "\u00B7"
    private const val ELLIPSIS = "\u2026"
    private const val MISSING = "--"
    const val ARROW_OUT = "\u2192"
    const val ARROW_IN = "\u2190"

    const val DETAIL_ON = "Runs in the background with a notification. Readings are saved even when the app is closed."
    const val DETAIL_OFF = "Turn on to collect readings in the background whenever the meter is nearby."
    const val DETAIL_NOT_PAIRED = "Pair the meter with Android so the app can be woken when it is nearby."
    const val STATUS_OFF = "Off $DOT no readings are saved"
    const val STATUS_NOT_PAIRED = "Needs Android pairing first"
    const val HINT_DISABLED =
        "Turn off background collection to run a one-off test. Only one connection to the meter can be active."
    const val HINT_IDLE =
        "Connects once for debugging and shows raw frames. Nothing is saved to history, and it does not reconnect."
    const val HINT_RUNNING = "Test connection $DOT raw frames below. Not saved, no automatic reconnect."
    const val REMOTE_OFF_REASON = "Off while connected through another phone."
    const val REMOTE_TEST_BLOCKED = "Disconnect from the other phone first."
    const val NOTE_NO_PASSWORD =
        "No password yet. Anyone who can reach this phone's address can read its live data (read-only)."
    const val SHARE_ADDRESS_SLOTS = 2
    const val SHARE_OFF = "Off"
    const val SHARE_WAITING = "Waiting for background collection"
    const val REMOTE_NOT_CONNECTED = "Not connected"

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
    private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.US)

    fun reduce(
        state: BleUiState,
        nowMs: Long,
        sdkInt: Int,
        notice: FirstRunNotice? = null,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): DeviceScreenState {
        val configured = state.autonomousCollectionConfigured
        val paired = state.companionAssociated || configured
        val testRunning = state.testConnectionActive && state.manualTransportControlsEnabled && !state.remoteMode
        val mode = when {
            testRunning -> DeviceMode.TestRunning
            !paired -> DeviceMode.NotPaired
            configured -> DeviceMode.Collecting
            else -> DeviceMode.CollectionOff
        }
        return DeviceScreenState(
            mode = mode,
            collection = collectionCard(state, paired, nowMs),
            meter = meterCard(state, paired, nowMs, zoneId),
            network = networkCard(state, nowMs),
            permissions = permissionRows(state, sdkInt, notice),
            test = testCard(state, testRunning, zoneId),
        )
    }

    fun cadences(): CollectionCadences = CollectionCadences(
        appOpen = "every ${formatInterval(CollectionCadence.FOREGROUND_POLL_INTERVAL_MS)}",
        background = "every ${formatInterval(CollectionCadence.BACKGROUND_POLL_INTERVAL_MS)}",
    )

    /** 30_000 -> "30 s", 300_000 -> "5 min", 3_600_000 -> "1 h". */
    fun formatInterval(intervalMs: Long): String {
        val seconds = intervalMs / 1_000L
        return when {
            seconds < 60L || seconds % 60L != 0L -> "$seconds s"
            seconds < 3_600L || seconds % 3_600L != 0L -> "${seconds / 60L} min"
            else -> "${seconds / 3_600L} h"
        }
    }

    /** Compact age: "2s ago", "4 min ago", "3 h ago", "2 d ago". */
    fun formatAge(ageMs: Long): String {
        val seconds = (ageMs / 1_000L).coerceAtLeast(0L)
        return when {
            seconds < 60L -> "${seconds}s ago"
            seconds < 3_600L -> "${seconds / 60L} min ago"
            seconds < 86_400L -> "${seconds / 3_600L} h ago"
            else -> "${seconds / 86_400L} d ago"
        }
    }

    private fun collectionCard(state: BleUiState, paired: Boolean, nowMs: Long): DeviceCollectionCard {
        if (state.remoteMode) {
            // The carried phone never collects over Bluetooth, so there is nothing to switch on here.
            return DeviceCollectionCard(
                on = false,
                switchEnabled = false,
                tone = DeviceTone.Neutral,
                pulse = false,
                status = STATUS_OFF,
                detail = REMOTE_OFF_REASON,
                cadences = null,
                pairingRequired = false,
            )
        }
        val configured = state.autonomousCollectionConfigured
        if (!configured) {
            return DeviceCollectionCard(
                on = false,
                switchEnabled = paired && state.savedMeterAddress != null,
                tone = DeviceTone.Neutral,
                pulse = false,
                status = if (paired) STATUS_OFF else STATUS_NOT_PAIRED,
                detail = if (paired) DETAIL_OFF else DETAIL_NOT_PAIRED,
                cadences = null,
                pairingRequired = !paired,
            )
        }
        val collection = state.collectionState
        val ageLabel = state.lastReadingTimestampMs?.let { formatAge(nowMs - it) }
        val (tone, pulse, status) = when (collection?.phase) {
            CollectionPhase.COLLECTING -> Triple(
                DeviceTone.Live,
                true,
                ageLabel?.let { "Collecting $DOT last sample $it" } ?: "Collecting",
            )
            CollectionPhase.CONNECTING, CollectionPhase.BOOTSTRAPPING ->
                Triple(DeviceTone.Live, false, "Connecting to the meter$ELLIPSIS")
            CollectionPhase.BLUETOOTH_OFF -> Triple(
                DeviceTone.Warning,
                false,
                "Bluetooth is off" + (ageLabel?.let { " $DOT last sample $it" } ?: ""),
            )
            CollectionPhase.WAITING_FOR_DEVICE -> Triple(
                DeviceTone.Neutral,
                false,
                "Waiting for the meter" + (ageLabel?.let { " $DOT last sample $it" } ?: ""),
            )
            CollectionPhase.ERROR -> {
                val retryAt = collection.nextReconnectAtMs
                if (retryAt != null && retryAt > nowMs) {
                    val seconds = ((retryAt - nowMs + 999L) / 1_000L).coerceAtLeast(1L)
                    Triple(DeviceTone.Warning, false, "Reconnecting in $seconds s")
                } else {
                    Triple(DeviceTone.Error, false, collection.error?.let { "Error $DOT $it" } ?: "Collection error")
                }
            }
            CollectionPhase.STOPPED, null -> Triple(DeviceTone.Live, false, "On $DOT starting$ELLIPSIS")
        }
        return DeviceCollectionCard(
            on = true,
            switchEnabled = true,
            tone = tone,
            pulse = pulse,
            status = status,
            detail = DETAIL_ON,
            cadences = cadences(),
            pairingRequired = false,
        )
    }

    private fun meterCard(state: BleUiState, paired: Boolean, nowMs: Long, zoneId: ZoneId): DeviceMeterCard {
        val address = state.savedMeterAddress
        val name = state.savedMeterName?.takeIf { it.isNotBlank() }?.trim()
            ?: state.selectedDevice?.takeIf { it.address == address }?.name?.takeIf { it.isNotBlank() }?.trim()
            ?: DEFAULT_METER_NAME
        val wake = when {
            state.autonomousCollectionConfigured -> "Yes"
            paired -> "Paused"
            else -> "No"
        }
        return DeviceMeterCard(
            name = name,
            address = address ?: MISSING,
            paired = paired,
            pairingLabel = if (paired) "Paired" else "Not paired",
            wakeLabel = wake,
            lastReading = state.lastReadingTimestampMs?.let { formatTimestamp(it, nowMs, zoneId) } ?: "No readings yet",
            showPairButton = !paired && address != null,
            pairingInProgress = state.autonomousCollectionStatus ==
                com.juncehome.lifepo4ble.ui.AutonomousCollectionStatus.SETUP_IN_PROGRESS,
            meterSaved = address != null,
            actionsEnabled = !state.remoteMode,
        )
    }

    private fun networkCard(state: BleUiState, nowMs: Long): DeviceNetworkCard {
        val remote = state.remoteMode
        // The feed server belongs to this phone's own collection; it does not run while reading another phone.
        val shareOn = state.shareOnNetwork && !remote
        val sharing = state.sharingStatus?.takeIf { shareOn }
        val running = sharing?.takeIf { it.running }
        val shareError = sharing?.error
        val shareStatus = when {
            !shareOn -> SHARE_OFF
            shareError != null -> shareError
            running != null -> "Sharing $DOT port ${running.port}"
            else -> SHARE_WAITING
        }
        val addresses = running?.addresses.orEmpty().take(SHARE_ADDRESS_SLOTS)
        val (remoteStatus, remoteTone) = remoteStatus(state, nowMs)
        return DeviceNetworkCard(
            shareOn = shareOn,
            shareEnabled = !remote,
            shareStatus = shareStatus,
            shareAddresses = addresses + List(SHARE_ADDRESS_SLOTS - addresses.size) { MISSING },
            viewers = viewerLabel(running?.viewers ?: 0),
            remoteConnected = remote,
            remoteAddress = state.remoteAddress.orEmpty(),
            remoteStatus = remoteStatus,
            remoteTone = remoteTone,
            remoteError = state.remoteAddressError,
            note = NOTE_NO_PASSWORD,
        )
    }

    private fun viewerLabel(count: Int): String = if (count == 1) "1 viewer" else "$count viewers"

    /**
     * Link to the home phone. In remote mode [BleUiState.collectionState] is the carried phone's link state: its
     * phase is the home phone's collection phase, and [CollectionState.remoteUnreachable] marks a lost stream.
     */
    private fun remoteStatus(state: BleUiState, nowMs: Long): Pair<String, DeviceTone> {
        if (!state.remoteMode) return REMOTE_NOT_CONNECTED to DeviceTone.Neutral
        val link = state.collectionState
        if (link != null && link.remoteUnreachable) {
            val retryAt = link.nextReconnectAtMs
            val label = if (retryAt == null) {
                "Phone offline"
            } else {
                "Phone offline $DOT retry in ${((retryAt - nowMs + 999L) / 1_000L).coerceAtLeast(1L)} s"
            }
            return label to DeviceTone.Warning
        }
        return when (link?.phase) {
            // Not started yet (null/STOPPED) counts as connecting: the link starts as soon as the app is visible.
            null, CollectionPhase.STOPPED, CollectionPhase.CONNECTING -> "Connecting$ELLIPSIS" to DeviceTone.Neutral
            CollectionPhase.BOOTSTRAPPING, CollectionPhase.COLLECTING -> {
                val meterName = state.selectedDevice?.name?.takeIf { it.isNotBlank() }?.trim() ?: DEFAULT_METER_NAME
                "Connected $DOT $meterName" to DeviceTone.Live
            }
            CollectionPhase.WAITING_FOR_DEVICE -> "Home phone: meter out of range" to DeviceTone.Neutral
            CollectionPhase.BLUETOOTH_OFF -> "Home phone: Bluetooth off" to DeviceTone.Warning
            CollectionPhase.ERROR -> when {
                link.error == RemoteLinkMachine.HOME_STOPPED_ERROR -> link.error to DeviceTone.Warning
                link.nextReconnectAtMs != null -> "Home phone reconnecting to meter" to DeviceTone.Warning
                else -> "Home phone error" + (link.error?.let { " $DOT $it" } ?: "") to DeviceTone.Error
            }
        }
    }

    /** "14:32:08" for today, "17 Oct 09:15" otherwise. */
    fun formatTimestamp(epochMs: Long, nowMs: Long, zoneId: ZoneId): String {
        val at = Instant.ofEpochMilli(epochMs).atZone(zoneId)
        val today = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate()
        return if (at.toLocalDate() == today) TIME.format(at) else DATE_TIME.format(at)
    }

    private fun permissionRows(state: BleUiState, sdkInt: Int, notice: FirstRunNotice?): List<DevicePermissionRow> {
        val bluetooth = when {
            !state.hasBluetoothAdapter -> DevicePermissionRow(
                PermissionKind.Bluetooth, "Bluetooth", "Unavailable", DeviceTone.Error, PermissionAction.None,
            )
            state.bluetoothEnabled -> DevicePermissionRow(
                PermissionKind.Bluetooth, "Bluetooth", "On", DeviceTone.Live, PermissionAction.None,
            )
            else -> DevicePermissionRow(
                PermissionKind.Bluetooth, "Bluetooth", "Off", DeviceTone.Warning, PermissionAction.EnableBluetooth,
            )
        }
        val nearby = if (state.bluetoothPermissionsGranted) {
            DevicePermissionRow(
                PermissionKind.NearbyDevices, "Nearby devices", "Allowed", DeviceTone.Live, PermissionAction.None,
            )
        } else {
            val blocked = notice?.step == FirstRunStep.NearbyDevices && notice.offerAppSettings
            DevicePermissionRow(
                PermissionKind.NearbyDevices,
                "Nearby devices",
                "Denied",
                DeviceTone.Warning,
                if (blocked) PermissionAction.OpenAppSettings else PermissionAction.RequestNearbyDevices,
            )
        }
        val notifications = when {
            sdkInt < NOTIFICATION_PERMISSION_MIN_SDK || state.notificationPermission == null -> DevicePermissionRow(
                PermissionKind.Notifications, "Notifications", "Not required", DeviceTone.Neutral, PermissionAction.None,
            )
            state.notificationPermissionGranted -> DevicePermissionRow(
                PermissionKind.Notifications, "Notifications", "Allowed", DeviceTone.Live, PermissionAction.None,
            )
            else -> {
                val blocked = notice?.step == FirstRunStep.Notifications && notice.offerAppSettings
                DevicePermissionRow(
                    PermissionKind.Notifications,
                    "Notifications",
                    "Denied",
                    DeviceTone.Warning,
                    if (blocked) PermissionAction.OpenAppSettings else PermissionAction.RequestNotifications,
                )
            }
        }
        return listOf(bluetooth, nearby, notifications)
    }

    private fun testCard(state: BleUiState, testRunning: Boolean, zoneId: ZoneId): DeviceTestCard {
        if (state.remoteMode) {
            return DeviceTestCard(
                status = TestConnectionStatus.Idle,
                hint = HINT_IDLE,
                connectEnabled = false,
                blockedReason = REMOTE_TEST_BLOCKED,
                connectionLabel = null,
                connected = false,
                frames = emptyList(),
                lastResult = state.testConnectionResult,
            )
        }
        if (!state.manualTransportControlsEnabled) {
            return DeviceTestCard(
                status = TestConnectionStatus.Disabled,
                hint = HINT_DISABLED,
                connectEnabled = false,
                blockedReason = null,
                connectionLabel = null,
                connected = false,
                frames = emptyList(),
                lastResult = null,
            )
        }
        if (testRunning) {
            val connected = state.connectionState == ConnectionState.CONNECTED ||
                state.connectionState == ConnectionState.READY
            return DeviceTestCard(
                status = TestConnectionStatus.Running,
                hint = HINT_RUNNING,
                connectEnabled = false,
                blockedReason = null,
                connectionLabel = if (connected) "Connected" else "Connecting$ELLIPSIS",
                connected = connected,
                frames = frameLines(state.packetLog, zoneId),
                lastResult = null,
            )
        }
        val blockedReason = when {
            state.savedMeterAddress == null -> "Choose a meter first."
            !state.bluetoothPermissionsGranted -> "Allow Nearby devices first."
            !state.bluetoothEnabled -> "Turn on Bluetooth first."
            else -> null
        }
        return DeviceTestCard(
            status = TestConnectionStatus.Idle,
            hint = HINT_IDLE,
            connectEnabled = blockedReason == null,
            blockedReason = blockedReason,
            connectionLabel = null,
            connected = false,
            frames = emptyList(),
            lastResult = state.testConnectionResult,
        )
    }

    fun frameLines(log: List<FrameLogEntry>, zoneId: ZoneId): List<FrameLine> {
        val kept = log.takeLast(MAX_FRAME_LINES)
        val firstIndex = (log.size - kept.size).toLong()
        return kept.mapIndexed { index, entry ->
            FrameLine(
                // Stable per entry within a session: timestamp plus position keeps LazyColumn keys unique.
                id = entry.timestampMs * 1_000L + ((firstIndex + index) % 1_000L),
                time = TIME.format(Instant.ofEpochMilli(entry.timestampMs).atZone(zoneId)),
                direction = entry.direction,
                arrow = if (entry.direction == PacketDirection.OUTBOUND) ARROW_OUT else ARROW_IN,
                text = entry.ascii.trimEnd('.').ifEmpty { entry.hex },
            )
        }
    }
}
