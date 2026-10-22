package com.juncehome.lifepo4ble.ui.device

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juncehome.lifepo4ble.AppLog
import com.juncehome.lifepo4ble.ble.ScannedDevice
import com.juncehome.lifepo4ble.collection.CollectionPhase
import com.juncehome.lifepo4ble.collection.CollectionState
import com.juncehome.lifepo4ble.collection.CollectionVisibility
import com.juncehome.lifepo4ble.protocol.PacketDirection
import com.juncehome.lifepo4ble.protocol.PacketFormatter
import com.juncehome.lifepo4ble.remote.SharingStatus
import com.juncehome.lifepo4ble.ui.BleUiState
import com.juncehome.lifepo4ble.ui.ConnectionState
import com.juncehome.lifepo4ble.ui.components.ConnectionPill
import com.juncehome.lifepo4ble.ui.components.screenHeader
import com.juncehome.lifepo4ble.ui.components.KmfIcons
import com.juncehome.lifepo4ble.ui.components.KmfLightButton
import com.juncehome.lifepo4ble.ui.components.SectionCard
import com.juncehome.lifepo4ble.ui.components.StatusDot
import com.juncehome.lifepo4ble.ui.components.StatusPillIndicator
import com.juncehome.lifepo4ble.ui.now.NowDisplayState
import com.juncehome.lifepo4ble.ui.now.NowPill
import com.juncehome.lifepo4ble.ui.now.NowScreenReducer
import com.juncehome.lifepo4ble.ui.firstrun.FirstRunNotice
import com.juncehome.lifepo4ble.ui.theme.KmfAccent
import com.juncehome.lifepo4ble.ui.theme.KmfBleTheme
import com.juncehome.lifepo4ble.ui.theme.KmfDimens
import com.juncehome.lifepo4ble.ui.theme.KmfTheme
import com.juncehome.lifepo4ble.ui.theme.tint
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

private const val TAG = "KMF-BLE"

/** Widest text each fixed slot of the Network card has to hold; the slot keeps this width whatever it shows. */
private const val WIDEST_VIEWERS = "999 viewers"
private const val WIDEST_REMOTE_ACTION = "Disconnect"

/**
 * From this font scale on the Network card stacks: the viewer count gets its own line below the addresses, and the
 * address field and its button sit on separate lines. Decided by the font scale setting, never by a value. Below it,
 * two 15 character mono addresses plus the viewer slot fit the card down to a 340 dp wide screen; above it every
 * address has the full card width (still fits at scale 2.0 on 360 dp), so an address is never cut.
 */
private const val STACKED_FONT_SCALE = 1.1f

/** The keyboard animates in and shrinks the scrolling area; the focused field is scrolled into view once it rests. */
private const val IME_SETTLE_MS = 120L

/** Everything the Device tab can ask the app to do. */
data class DeviceCallbacks(
    val onEnableCollection: () -> Unit = {},
    val onDisableCollection: () -> Unit = {},
    val onPairWithAndroid: () -> Unit = {},
    val onChangeMeter: () -> Unit = {},
    val onForgetMeter: () -> Unit = {},
    val onPermissionAction: (PermissionAction) -> Unit = {},
    val onConnectOnce: () -> Unit = {},
    val onClearLog: () -> Unit = {},
    val onDisconnectTest: () -> Unit = {},
    val onShareOnNetworkChange: (Boolean) -> Unit = {},
    val onConnectRemote: (String) -> Unit = {},
    val onDisconnectRemote: () -> Unit = {},
)

/** Stateful entry: ticks the clock once per second and reduces [state] into the Device model. */
@Composable
fun DeviceRoute(
    state: BleUiState,
    notice: FirstRunNotice?,
    contentPadding: PaddingValues,
    callbacks: DeviceCallbacks,
    headerTrailing: @Composable () -> Unit = {},
) {
    val nowMs by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val model = remember(state, notice, nowMs) {
        DeviceScreenReducer.reduce(state, nowMs, Build.VERSION.SDK_INT, notice, ZoneId.systemDefault())
    }
    LaunchedEffect(model.mode, model.test.status, model.collection.status.substringBefore(" \u00B7 ")) {
        AppLog.d(
            "Device: mode=${model.mode} collection='${model.collection.status}' on=${model.collection.on} " +
                "test=${model.test.status} paired=${model.meter.paired} phase=${state.collectionState?.phase}",
            TAG,
        )
    }
    LaunchedEffect(model.network.shareStatus, model.network.remoteStatus.substringBefore(" \u00B7 ")) {
        AppLog.d(
            "Device: network share='${model.network.shareStatus}' viewers='${model.network.viewers}' " +
                "remote='${model.network.remoteStatus}' remoteMode=${model.network.remoteConnected} " +
                "remoteError=${model.network.remoteError != null}",
            TAG,
        )
    }
    DeviceScreen(model = model, contentPadding = contentPadding, callbacks = callbacks, headerTrailing = headerTrailing)
}

@Composable
fun DeviceScreen(
    model: DeviceScreenState,
    contentPadding: PaddingValues,
    callbacks: DeviceCallbacks,
    headerTrailing: @Composable () -> Unit = {},
) {
    var confirmForget by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KmfTheme.colors.background)
            // The address field is the only text input: the scrolling area must end at the keyboard, not under it.
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = KmfDimens.ScreenPadding, vertical = KmfDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(KmfDimens.SectionGap),
    ) {
        Row(
            modifier = Modifier.screenHeader(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Device",
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp, lineHeight = 28.sp),
                color = KmfTheme.colors.textPrimary,
                modifier = Modifier.semantics { heading() },
            )
            headerTrailing()
        }
        CollectionCard(model.collection, callbacks)
        MeterCard(
            meter = model.meter,
            onPair = callbacks.onPairWithAndroid,
            onChange = callbacks.onChangeMeter,
            onForget = {
                AppLog.d("Device: forget meter tapped; asking for confirmation", TAG)
                confirmForget = true
            },
        )
        NetworkCard(model.network, callbacks)
        PermissionsCard(model.permissions, callbacks.onPermissionAction)
        TestConnectionCard(model.test, callbacks)
    }
    if (confirmForget) {
        ForgetMeterDialog(
            meterName = model.meter.name,
            onConfirm = {
                confirmForget = false
                callbacks.onForgetMeter()
            },
            onDismiss = {
                AppLog.d("Device: forget meter canceled", TAG)
                confirmForget = false
            },
        )
    }
}

@Composable
private fun DeviceTone.accent(): KmfAccent {
    val colors = KmfTheme.colors
    return when (this) {
        DeviceTone.Live -> colors.live
        DeviceTone.Neutral -> colors.neutral
        DeviceTone.Warning -> colors.discharge
        DeviceTone.Error -> colors.error
    }
}

/** Value text color for rows: neutral uses plain secondary text, others the on-tint accent. */
@Composable
private fun DeviceTone.valueColor(): Color = when (this) {
    DeviceTone.Neutral -> KmfTheme.colors.textSecondary
    else -> accent().onTint
}

@Composable
private fun CollectionCard(card: DeviceCollectionCard, callbacks: DeviceCallbacks) {
    val colors = KmfTheme.colors
    val statusColor = if (card.on) card.tone.accent().accent else colors.textSecondary
    SectionCard(
        containerColor = if (card.on) colors.live.accent.tint(0.06f) else colors.surface,
        borderColor = if (card.on) colors.live.borderSubtle else colors.hairline,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Background collection",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    StatusDot(
                        color = statusColor,
                        indicator = if (card.pulse && !KmfTheme.reduceMotion) {
                            StatusPillIndicator.Pulse
                        } else {
                            StatusPillIndicator.Steady
                        },
                    )
                    Text(text = card.status, style = MaterialTheme.typography.bodySmall, color = statusColor)
                }
            }
            Switch(
                checked = card.on,
                enabled = card.switchEnabled,
                onCheckedChange = { checked ->
                    AppLog.d("Device: collection switch toggled checked=$checked (was on=${card.on})", TAG)
                    if (checked) callbacks.onEnableCollection() else callbacks.onDisableCollection()
                },
                modifier = Modifier.semantics { contentDescription = "Background collection" },
                colors = deviceSwitchColors(),
            )
        }
        Text(text = card.detail, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        card.cadences?.let { cadences ->
            HorizontalDivider(thickness = KmfDimens.Hairline, color = colors.hairline)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CadenceCell(label = "App open", value = cadences.appOpen, modifier = Modifier.weight(1f))
                CadenceCell(label = "In background", value = cadences.background, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun deviceSwitchColors(): SwitchColors {
    val colors = KmfTheme.colors
    return SwitchDefaults.colors(
        checkedThumbColor = colors.background,
        checkedTrackColor = colors.live.accent,
        checkedBorderColor = colors.live.accent,
        uncheckedThumbColor = colors.neutral.accent,
        uncheckedTrackColor = colors.border,
        uncheckedBorderColor = colors.border,
        disabledUncheckedThumbColor = colors.textTertiary.copy(alpha = 0.5f),
        disabledUncheckedTrackColor = colors.surface2,
        disabledUncheckedBorderColor = colors.surface2,
    )
}

@Composable
private fun CadenceCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = KmfTheme.colors.textSecondary)
        Text(text = value, style = KmfTheme.numbers.body, color = KmfTheme.colors.textPrimary)
    }
}

@Composable
private fun MeterCard(
    meter: DeviceMeterCard,
    onPair: () -> Unit,
    onChange: () -> Unit,
    onForget: () -> Unit,
) {
    val colors = KmfTheme.colors
    SectionCard(contentPadding = PaddingValues(0.dp), verticalSpacing = 0.dp) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(colors.surface2, RoundedCornerShape(KmfDimens.TileRadius)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = KmfIcons.Battery,
                    contentDescription = null,
                    tint = colors.textPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = meter.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(text = meter.address, style = KmfTheme.numbers.caption, color = colors.textSecondary)
            }
        }
        MeterRow(label = "Android pairing") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val pairColor = if (meter.paired) colors.live.accent else colors.discharge.onTint
                if (meter.paired) {
                    Icon(
                        imageVector = KmfIcons.Check,
                        contentDescription = null,
                        tint = pairColor,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    text = meter.pairingLabel,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = pairColor,
                )
            }
        }
        MeterRow(label = "Wakes app when nearby") {
            Text(
                text = meter.wakeLabel,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            )
        }
        MeterRow(label = "Last reading") {
            Text(text = meter.lastReading, style = KmfTheme.numbers.body)
        }
        if (meter.showPairButton) {
            KmfLightButton(
                label = if (meter.pairingInProgress) "Waiting for Android\u2026" else "Pair with Android",
                onClick = {
                    AppLog.d("Device: pair with Android tapped", TAG)
                    onPair()
                },
                enabled = !meter.pairingInProgress && meter.actionsEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            )
        }
        HorizontalDivider(thickness = KmfDimens.Hairline, color = colors.hairline)
        Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            TextButton(
                onClick = {
                    AppLog.d("Device: change meter tapped", TAG)
                    onChange()
                },
                enabled = meter.actionsEnabled,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                shape = RoundedCornerShape(0.dp),
            ) {
                Text(
                    text = if (meter.meterSaved) "Change meter" else "Choose meter",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = if (meter.actionsEnabled) colors.charge.accent else colors.textTertiary,
                )
            }
            if (meter.meterSaved) {
                VerticalDivider(
                    modifier = Modifier.height(48.dp),
                    thickness = KmfDimens.Hairline,
                    color = colors.hairline,
                )
                TextButton(
                    onClick = onForget,
                    enabled = meter.actionsEnabled,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(0.dp),
                ) {
                    Text(
                        text = "Forget meter",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = if (meter.actionsEnabled) colors.error.onTint else colors.textTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun MeterRow(label: String, value: @Composable () -> Unit) {
    HorizontalDivider(thickness = KmfDimens.Hairline, color = KmfTheme.colors.hairline)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = KmfDimens.MinTouchTarget)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = KmfTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        value()
    }
}

@Composable
private fun OverlineLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Normal,
            letterSpacing = 0.4.sp,
        ),
        color = KmfTheme.colors.textSecondary,
    )
}

/**
 * Text that always takes the width of [widest]: a shorter or longer value never moves its neighbours. Text wider
 * than [widest] ends in an ellipsis instead of growing the slot.
 */
@Composable
private fun SlotText(
    text: String,
    widest: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign = TextAlign.End,
) {
    Box(modifier = modifier) {
        Text(
            text = widest,
            style = style,
            color = Color.Transparent,
            maxLines = 1,
            modifier = Modifier.clearAndSetSemantics { },
        )
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign,
            modifier = Modifier.matchParentSize(),
        )
    }
}

/**
 * Share this phone's live feed, and read another phone's feed. Every slot keeps one position and size for all
 * values and states: two address lines, a fixed-width viewer count, one status line, always the same note.
 */
@Composable
private fun NetworkCard(network: DeviceNetworkCard, callbacks: DeviceCallbacks) {
    val colors = KmfTheme.colors
    var addressText by rememberSaveable { mutableStateOf(network.remoteAddress) }
    // A saved address replaces the typed text; after a disconnect the last text stays for a quick reconnect.
    LaunchedEffect(network.remoteAddress) {
        if (network.remoteAddress.isNotEmpty()) addressText = network.remoteAddress
    }
    val stacked = LocalDensity.current.fontScale >= STACKED_FONT_SCALE
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        keyboard?.hide()
        focusManager.clearFocus()
        if (network.remoteConnected) {
            AppLog.d("Device: disconnect from the other phone tapped", TAG)
            callbacks.onDisconnectRemote()
        } else {
            AppLog.d("Device: connect through another phone tapped typedChars=${addressText.trim().length}", TAG)
            callbacks.onConnectRemote(addressText)
        }
    }
    SectionCard {
        OverlineLabel("NETWORK")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Share on network",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        text = network.shareStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Switch(
                    checked = network.shareOn,
                    enabled = network.shareEnabled,
                    onCheckedChange = { checked ->
                        AppLog.d("Device: share on network switch toggled checked=$checked (was on=${network.shareOn})", TAG)
                        callbacks.onShareOnNetworkChange(checked)
                    },
                    modifier = Modifier.semantics { contentDescription = "Share on network" },
                    colors = deviceSwitchColors(),
                )
            }
            if (stacked) {
                Column {
                    ShareAddressLines(network)
                    Text(
                        text = network.viewers,
                        style = KmfTheme.numbers.body,
                        color = colors.textSecondary,
                        maxLines = 1,
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ShareAddressLines(network, Modifier.weight(1f))
                    SlotText(
                        text = network.viewers,
                        widest = WIDEST_VIEWERS,
                        style = KmfTheme.numbers.body,
                        color = colors.textSecondary,
                    )
                }
            }
        }
        HorizontalDivider(thickness = KmfDimens.Hairline, color = colors.hairline)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Connect through another phone",
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            if (stacked) {
                RemoteAddressField(network, addressText, { addressText = it }, submit, Modifier.fillMaxWidth())
                RemoteActionButton(network, submit, Modifier.fillMaxWidth())
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RemoteAddressField(network, addressText, { addressText = it }, submit, Modifier.weight(1f))
                    RemoteActionButton(network, submit, Modifier)
                }
            }
            RemoteStatusLine(network)
        }
        Text(text = network.note, style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
    }
}

/** The two address slots, one line each; the user types them on the other phone, so they are never cut short. */
@Composable
private fun ShareAddressLines(network: DeviceNetworkCard, modifier: Modifier = Modifier) {
    val colors = KmfTheme.colors
    Column(modifier = modifier) {
        network.shareAddresses.forEach { address ->
            Text(
                text = address,
                style = KmfTheme.numbers.body,
                color = if (network.shareOn) colors.textPrimary else colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RemoteAddressField(
    network: DeviceNetworkCard,
    text: String,
    onTextChange: (String) -> Unit,
    onGo: () -> Unit,
    modifier: Modifier,
) {
    val colors = KmfTheme.colors
    val bringIntoView = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    LaunchedEffect(focused) {
        if (!focused) return@LaunchedEffect
        // Every keyboard animation step restarts the wait, so the scroll happens once the keyboard has settled.
        snapshotFlow { ime.getBottom(density) }.collectLatest { keyboardHeight ->
            if (keyboardHeight > 0) {
                delay(IME_SETTLE_MS)
                bringIntoView.bringIntoView()
            }
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        modifier = modifier
            .bringIntoViewRequester(bringIntoView)
            .onFocusChanged { focused = it.isFocused },
        readOnly = network.remoteConnected,
        singleLine = true,
        isError = network.remoteError != null,
        textStyle = KmfTheme.numbers.body,
        label = { Text(text = "Home phone address", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        placeholder = { Text(text = "192.168.1.23", style = KmfTheme.numbers.body) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { if (!network.remoteConnected) onGo() }),
        shape = RoundedCornerShape(KmfDimens.TileRadius),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.textPrimary,
            unfocusedTextColor = colors.textPrimary,
            errorTextColor = colors.textPrimary,
            focusedBorderColor = colors.charge.accent,
            unfocusedBorderColor = colors.border,
            errorBorderColor = colors.error.accent,
            focusedLabelColor = colors.charge.accent,
            unfocusedLabelColor = colors.textSecondary,
            errorLabelColor = colors.error.onTint,
            focusedPlaceholderColor = colors.textTertiary,
            unfocusedPlaceholderColor = colors.textTertiary,
            cursorColor = colors.charge.accent,
            errorCursorColor = colors.error.accent,
        ),
    )
}

/** One button for both states; it keeps the width of the widest label so Connect and Disconnect never differ. */
@Composable
private fun RemoteActionButton(network: DeviceNetworkCard, onClick: () -> Unit, modifier: Modifier) {
    val colors = KmfTheme.colors
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(OutlinedTextFieldDefaults.MinHeight),
        shape = RoundedCornerShape(KmfDimens.TileRadius),
        border = BorderStroke(KmfDimens.Hairline, colors.charge.accent.tint(0.5f)),
        contentPadding = PaddingValues(horizontal = 16.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.charge.accent),
    ) {
        SlotText(
            text = if (network.remoteConnected) "Disconnect" else "Connect",
            widest = WIDEST_REMOTE_ACTION,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The refused address wins over the link status; both use the same single ellipsized line. Not a live region: the
 * "retry in n s" countdown would be announced every second.
 */
@Composable
private fun RemoteStatusLine(network: DeviceNetworkCard) {
    val tone = if (network.remoteError != null) DeviceTone.Error else network.remoteTone
    val color = tone.accent().accent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusDot(color = color)
        Text(
            text = network.remoteError ?: network.remoteStatus,
            style = MaterialTheme.typography.bodySmall,
            color = tone.valueColor(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PermissionsCard(rows: List<DevicePermissionRow>, onAction: (PermissionAction) -> Unit) {
    val colors = KmfTheme.colors
    SectionCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalSpacing = 0.dp) {
        rows.forEachIndexed { index, row ->
            val actionable = row.action != PermissionAction.None
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = KmfDimens.MinTouchTarget)
                    .then(
                        if (actionable) {
                            Modifier.clickable(role = Role.Button, onClickLabel = "Fix ${row.label}") {
                                AppLog.d("Device: permission row tapped ${row.kind} action=${row.action}", TAG)
                                onAction(row.action)
                            }
                        } else {
                            Modifier
                        },
                    )
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = row.value,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = row.tone.valueColor(),
                )
                if (actionable) {
                    Icon(
                        imageVector = KmfIcons.ChevronRight,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            if (index < rows.lastIndex) {
                HorizontalDivider(thickness = KmfDimens.Hairline, color = colors.hairline)
            }
        }
    }
}

@Composable
private fun TestConnectionCard(test: DeviceTestCard, callbacks: DeviceCallbacks) {
    val colors = KmfTheme.colors
    val disabled = test.status == TestConnectionStatus.Disabled
    val dashColor = if (disabled) colors.border else colors.charge.accent.tint(0.4f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(KmfDimens.CardRadius))
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    color = dashColor,
                    topLeft = androidx.compose.ui.geometry.Offset(stroke / 2f, stroke / 2f),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(KmfDimens.CardRadius.toPx()),
                    style = Stroke(
                        width = stroke,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                    ),
                )
            }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                OverlineLabel("DIAGNOSTICS")
                Text(
                    text = "One-off test connection",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = if (disabled) colors.textTertiary else colors.textPrimary,
                )
            }
            if (test.status == TestConnectionStatus.Running && test.connectionLabel != null) {
                val labelColor = if (test.connected) colors.live.accent else colors.charge.accent
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    StatusDot(
                        color = labelColor,
                        indicator = when {
                            KmfTheme.reduceMotion -> StatusPillIndicator.Steady
                            test.connected -> StatusPillIndicator.Pulse
                            else -> StatusPillIndicator.Blink
                        },
                    )
                    Text(text = test.connectionLabel, style = MaterialTheme.typography.bodySmall, color = labelColor)
                }
            }
        }
        Text(text = test.hint, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        when (test.status) {
            TestConnectionStatus.Disabled, TestConnectionStatus.Idle -> {
                val enabled = test.connectEnabled
                OutlinedButton(
                    onClick = {
                        AppLog.d("Device: connect once tapped", TAG)
                        callbacks.onConnectOnce()
                    },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = KmfDimens.MinTouchTarget),
                    shape = RoundedCornerShape(KmfDimens.TileRadius),
                    border = BorderStroke(
                        KmfDimens.Hairline,
                        if (enabled) colors.charge.accent.tint(0.5f) else colors.hairline,
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = colors.charge.accent,
                        disabledContentColor = colors.textTertiary.copy(alpha = 0.6f),
                    ),
                ) {
                    Text(
                        text = "Connect once",
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
                    )
                }
                val footnote = test.blockedReason ?: test.lastResult?.let { "Last test: $it" }
                if (!disabled && footnote != null) {
                    Text(text = footnote, style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
                }
            }
            TestConnectionStatus.Running -> {
                FrameLog(test.frames)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = callbacks.onClearLog,
                        modifier = Modifier.weight(1f).heightIn(min = KmfDimens.MinTouchTarget),
                        shape = RoundedCornerShape(KmfDimens.TileRadius),
                        border = BorderStroke(KmfDimens.Hairline, colors.border),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textPrimary),
                    ) {
                        Text(text = "Clear log", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                    }
                    KmfLightButton(
                        label = "Disconnect",
                        onClick = {
                            AppLog.d("Device: test disconnect tapped", TAG)
                            callbacks.onDisconnectTest()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun FrameLog(frames: List<FrameLine>) {
    val colors = KmfTheme.colors
    val listState = rememberLazyListState()
    val reduceMotion = KmfTheme.reduceMotion
    val newestId = frames.lastOrNull()?.id
    LaunchedEffect(frames.size, newestId) {
        if (frames.isNotEmpty()) {
            if (reduceMotion) listState.scrollToItem(frames.lastIndex) else listState.animateScrollToItem(frames.lastIndex)
        }
    }
    val lineStyle = KmfTheme.numbers.caption.copy(fontSize = 11.sp, lineHeight = 17.sp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp)
            .background(colors.background, RoundedCornerShape(10.dp))
            .semantics { contentDescription = "Raw frames, ${frames.size} lines" },
    ) {
        if (frames.isEmpty()) {
            Text(
                text = "Waiting for frames\u2026",
                style = lineStyle,
                color = colors.textTertiary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            ) {
                items(frames, key = { it.id }) { line ->
                    val arrowColor = if (line.direction == PacketDirection.OUTBOUND) colors.charge.accent else colors.live.accent
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(color = colors.textTertiary)) { append(line.time) }
                            append(' ')
                            withStyle(SpanStyle(color = arrowColor)) { append(line.arrow) }
                            append(' ')
                            append(line.text)
                        },
                        style = lineStyle,
                        color = colors.textSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ForgetMeterDialog(meterName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = KmfTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textSoft,
        title = { Text("Forget $meterName?") },
        text = {
            Text(
                "Background collection stops and the Android pairing is removed. Saved battery history is kept. " +
                    "You will set up a meter again.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Forget meter", color = colors.error.onTint, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textPrimary)
            }
        },
    )
}

// ---- Previews (one per board mode) ----

private const val PREVIEW_NOW_MS = 1_760_798_000_000L
private const val PREVIEW_ADDRESS = "AA:BB:CC:DD:EE:FF"

private fun previewState(mode: DeviceMode): BleUiState {
    val base = BleUiState(
        hasBleFeature = true,
        hasBluetoothAdapter = true,
        bluetoothEnabled = true,
        savedMeterAddress = PREVIEW_ADDRESS,
        savedMeterName = "KMF Meter",
        savedMeterLoaded = true,
        collectionConfigurationLoaded = true,
        firstRunCompleted = true,
        firstRunCompletedLoaded = true,
        companionAssociated = true,
        lastReadingTimestampMs = PREVIEW_NOW_MS - 2_000L,
        selectedDevice = ScannedDevice("KMF Meter", PREVIEW_ADDRESS, -60, emptyList()),
    )
    return when (mode) {
        DeviceMode.Collecting -> base.copy(
            autonomousCollectionConfigured = true,
            collectionState = CollectionState(
                phase = CollectionPhase.COLLECTING,
                visibility = CollectionVisibility.FOREGROUND,
                deviceAddress = PREVIEW_ADDRESS,
            ),
        )
        DeviceMode.CollectionOff -> base
        DeviceMode.TestRunning -> base.copy(
            testConnectionActive = true,
            connectionState = ConnectionState.READY,
            packetLog = listOf(
                ":*" to PacketDirection.OUTBOUND,
                ":A=1328,1240,1,174,1\n" to PacketDirection.INBOUND,
                ":B=4,100,82\n" to PacketDirection.INBOUND,
                ":A=1328,1238,1,174,1\n" to PacketDirection.INBOUND,
                ":C\n" to PacketDirection.OUTBOUND,
            ).mapIndexed { index, (text, direction) ->
                PacketFormatter.toEntry(PREVIEW_NOW_MS - 5_000L + index * 1_000L, direction, text.encodeToByteArray())
            },
        )
        DeviceMode.NotPaired -> base.copy(companionAssociated = false, lastReadingTimestampMs = null)
    }
}

@Composable
private fun DevicePreview(
    mode: DeviceMode,
    pill: NowPill? = null,
    state: BleUiState = previewState(mode),
    adjust: (DeviceScreenState) -> DeviceScreenState = { it },
) {
    KmfBleTheme {
        DeviceScreen(
            model = adjust(
                DeviceScreenReducer.reduce(
                    state = state,
                    nowMs = PREVIEW_NOW_MS,
                    sdkInt = 34,
                    zoneId = ZoneId.of("UTC"),
                ),
            ),
            contentPadding = PaddingValues(0.dp),
            callbacks = DeviceCallbacks(),
            headerTrailing = { if (pill != null) ConnectionPill(pill = pill, onAction = {}) },
        )
    }
}

@Preview(name = "Device - Collecting", backgroundColor = 0xFF0D0F12, showBackground = true, widthDp = 390, heightDp = 1520)
@Composable
private fun DeviceCollectingPreview() = DevicePreview(
    DeviceMode.Collecting,
    NowScreenReducer.pill(NowDisplayState.Charging, frameAgeMs = 2_000L),
)

@Preview(name = "Device - Collection off", backgroundColor = 0xFF0D0F12, showBackground = true, widthDp = 390, heightDp = 1520)
@Composable
private fun DeviceOffPreview() = DevicePreview(
    DeviceMode.CollectionOff,
    NowScreenReducer.pill(NowDisplayState.CollectionOff, frameAgeMs = null),
)

@Preview(name = "Device - Test running", backgroundColor = 0xFF0D0F12, showBackground = true, widthDp = 390, heightDp = 1520)
@Composable
private fun DeviceTestRunningPreview() = DevicePreview(
    DeviceMode.TestRunning,
    NowScreenReducer.pill(NowDisplayState.Reconnecting, frameAgeMs = 40_000L),
)

@Preview(name = "Device - Not paired", backgroundColor = 0xFF0D0F12, showBackground = true, widthDp = 390, heightDp = 1520)
@Composable
private fun DeviceNotPairedPreview() = DevicePreview(DeviceMode.NotPaired)

@Preview(
    name = "Device - Collecting, large font",
    backgroundColor = 0xFF0D0F12,
    showBackground = true,
    widthDp = 390,
    heightDp = 2200,
    fontScale = 1.6f,
)
@Composable
private fun DeviceLargeFontPreview() = DevicePreview(DeviceMode.Collecting)

private const val PREVIEW_HOME_ADDRESS = "192.168.1.23:8765"
private const val PREVIEW_WORST_CASE_HOST = "home-phone-with-a-very-long-name.example"

private fun remotePreviewState(link: CollectionState): BleUiState = previewState(DeviceMode.CollectionOff).copy(
    remoteAddress = PREVIEW_HOME_ADDRESS,
    selectedDevice = ScannedDevice("KMF-BT-5A3C", "remote:$PREVIEW_HOME_ADDRESS", 0, emptyList()),
    collectionState = link,
)

private fun remoteLink(phase: CollectionPhase, offline: Boolean = false) = CollectionState(
    phase = phase,
    visibility = CollectionVisibility.FOREGROUND,
    deviceAddress = "remote:$PREVIEW_HOME_ADDRESS",
    error = if (offline) "Connection refused" else null,
    nextReconnectAtMs = if (offline) PREVIEW_NOW_MS + 5_000L else null,
    remoteUnreachable = offline,
)

@Preview(name = "Device - Remote connected", backgroundColor = 0xFF0D0F12, showBackground = true, widthDp = 390, heightDp = 1520)
@Composable
private fun DeviceRemoteConnectedPreview() = DevicePreview(
    DeviceMode.CollectionOff,
    NowScreenReducer.pill(NowDisplayState.Charging, frameAgeMs = 2_000L),
    state = remotePreviewState(remoteLink(CollectionPhase.COLLECTING)),
)

@Preview(name = "Device - Remote, phone offline", backgroundColor = 0xFF0D0F12, showBackground = true, widthDp = 390, heightDp = 1520)
@Composable
private fun DeviceRemotePhoneOfflinePreview() = DevicePreview(
    DeviceMode.CollectionOff,
    NowScreenReducer.pill(NowDisplayState.Stale, frameAgeMs = 40_000L),
    state = remotePreviewState(remoteLink(CollectionPhase.ERROR, offline = true)),
)

private fun sharingWorstCaseState(): BleUiState = previewState(DeviceMode.Collecting).copy(
    shareOnNetwork = true,
    sharingStatus = SharingStatus(
        running = true,
        port = 8765,
        addresses = listOf("100.100.100.100", "100.100.100.100"),
        viewers = 12,
    ),
)

private fun withWorstCaseHost(model: DeviceScreenState): DeviceScreenState =
    model.copy(network = model.network.copy(remoteAddress = PREVIEW_WORST_CASE_HOST))

/** Two `100.100.100.100` addresses, `12 viewers` and a 40 character host typed into the field. */
@Preview(name = "Device - Sharing, worst case", backgroundColor = 0xFF0D0F12, showBackground = true, widthDp = 390, heightDp = 1520)
@Composable
private fun DeviceSharingWorstCasePreview() = DevicePreview(
    DeviceMode.Collecting,
    state = sharingWorstCaseState(),
    adjust = ::withWorstCaseHost,
)

/** The same worst case at the largest font scale: addresses keep the full card width, the viewer count its own line. */
@Preview(
    name = "Device - Sharing, worst case, font 2.0",
    backgroundColor = 0xFF0D0F12,
    showBackground = true,
    widthDp = 390,
    heightDp = 3000,
    fontScale = 2.0f,
)
@Composable
private fun DeviceSharingWorstCaseLargeFontPreview() = DevicePreview(
    DeviceMode.Collecting,
    state = sharingWorstCaseState(),
    adjust = ::withWorstCaseHost,
)
