package com.juncehome.lifepo4ble.ui.firstrun

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juncehome.lifepo4ble.AppLog
import com.juncehome.lifepo4ble.ble.ScannedDevice
import com.juncehome.lifepo4ble.ui.AutonomousCollectionStatus
import com.juncehome.lifepo4ble.ui.BleUiState
import com.juncehome.lifepo4ble.ui.components.KmfIcons
import com.juncehome.lifepo4ble.ui.theme.KmfAccent
import com.juncehome.lifepo4ble.ui.theme.KmfBleTheme
import com.juncehome.lifepo4ble.ui.theme.KmfDimens
import com.juncehome.lifepo4ble.ui.theme.KmfTheme
import com.juncehome.lifepo4ble.ui.theme.tint

private const val TAG = "KMF-BLE"

/** Activity- and ViewModel-backed actions for the first-run flow. */
@Immutable
data class FirstRunCallbacks(
    val onRequestBluetoothPermissions: () -> Unit = {},
    val onRequestNotificationPermission: () -> Unit = {},
    val onRequestEnableBluetooth: () -> Unit = {},
    val onOpenAppSettings: () -> Unit = {},
    val onStartScan: () -> Unit = {},
    val onStopScan: () -> Unit = {},
    val onSelectMeter: (ScannedDevice) -> Unit = {},
    val onContinueApproval: () -> Unit = {},
    val onChooseAnotherMeter: () -> Unit = {},
    val onOpenDashboard: () -> Unit = {},
    val onClearNotice: () -> Unit = {},
)

/** Copy from docs/design/first-run.dc.html renderVals(). */
private data class StepCopy(
    val icon: ImageVector,
    val title: String,
    val body: String,
    val cta: String,
    val secondary: String,
)

private fun copyFor(step: FirstRunStep): StepCopy? = when (step) {
    FirstRunStep.BluetoothOff -> StepCopy(
        icon = KmfIcons.Bluetooth,
        title = "Bluetooth is off",
        body = "The app reads your battery meter over Bluetooth. Turn it on to continue.",
        cta = "Turn on Bluetooth",
        secondary = "Not now",
    )
    FirstRunStep.NearbyDevices -> StepCopy(
        icon = KmfIcons.Bluetooth,
        title = "Allow nearby devices",
        body = "Android needs your permission before the app can find and connect to your meter. " +
            "Nothing leaves your phone.",
        cta = "Allow access",
        secondary = "Why is this needed?",
    )
    FirstRunStep.FindMeter -> null
    FirstRunStep.AndroidApproval -> StepCopy(
        icon = KmfIcons.Link,
        title = "Pair your meter with Android",
        body = "Android will ask you to confirm this meter. After that it wakes the app whenever the meter " +
            "is nearby, so readings keep coming in even when the app is closed.",
        cta = "Continue",
        secondary = "Choose another meter",
    )
    FirstRunStep.Notifications -> StepCopy(
        icon = KmfIcons.Bell,
        title = "Allow notifications",
        body = "Background collection always shows a notification with a Stop button, so you stay in control.",
        cta = "Allow notifications",
        secondary = "Why is this needed?",
    )
    FirstRunStep.Done -> StepCopy(
        icon = KmfIcons.Check,
        title = "Collection is on",
        body = "The app now collects battery readings in the background whenever your meter is in range. " +
            "Stop it any time from the notification or the Device tab.",
        cta = "Open dashboard",
        secondary = "",
    )
}

private const val NEARBY_WHY =
    "Android groups Bluetooth scanning and connecting under the Nearby devices permission. The app uses " +
        "it only to find your battery meter and read its values. Readings stay on this phone."

private const val NOTIFICATIONS_WHY =
    "Android requires a visible notification while an app keeps collecting in the background. It shows " +
        "the latest reading and a Stop button, so collection never runs without you knowing."

/** Step 3 error line derived from the existing companion-setup status. */
internal fun approvalErrorFor(state: BleUiState): String? = when (state.autonomousCollectionStatus) {
    AutonomousCollectionStatus.UNSUPPORTED -> "This phone cannot keep the meter paired for background collection."
    AutonomousCollectionStatus.ERROR -> state.autonomousCollectionDetail ?: "Android approval failed. Try again."
    AutonomousCollectionStatus.NOT_CONFIGURED ->
        if (state.autonomousCollectionDetail != null) "Android approval was canceled. Try again." else null
    else -> null
}

@Composable
fun FirstRunRoute(
    state: BleUiState,
    notice: FirstRunNotice?,
    callbacks: FirstRunCallbacks,
    sdkInt: Int = Build.VERSION.SDK_INT,
) {
    var bluetoothOffDismissed by rememberSaveable { mutableStateOf(false) }
    val input = state.toFirstRunInput(sdkInt, bluetoothOffDismissed)
    val step = FirstRunResolver.resolve(input)

    LaunchedEffect(step) {
        AppLog.d(
            "FirstRun: step=$step bluetoothEnabled=${input.bluetoothEnabled} btPermissions=${input.bluetoothPermissionsGranted} " +
                "meterSaved=${input.meterSaved} associated=${input.companionAssociated} " +
                "notifications=${input.notificationPermissionGranted} sdk=$sdkInt btOffDismissed=$bluetoothOffDismissed",
            TAG,
        )
    }
    LaunchedEffect(step, notice) {
        if (notice != null && notice.step != step) {
            AppLog.d("FirstRun: clearing notice for ${notice.step} after moving to $step", TAG)
            callbacks.onClearNotice()
        }
    }
    if (step == FirstRunStep.FindMeter) {
        DisposableEffect(Unit) {
            AppLog.d("FirstRun: Find meter shown; starting scan", TAG)
            callbacks.onStartScan()
            onDispose {
                AppLog.d("FirstRun: leaving Find meter; stopping scan", TAG)
                callbacks.onStopScan()
            }
        }
    }

    val rows = remember(state.devices) { NearbyMeterList.rows(state.devices) }
    val stepNotice = notice?.takeIf { it.step == step }
    val errorLine = stepNotice?.message
        ?: if (step == FirstRunStep.AndroidApproval) approvalErrorFor(state) else null

    FirstRunScreen(
        step = step,
        rows = rows,
        errorLine = errorLine,
        offerAppSettings = stepNotice?.offerAppSettings == true,
        approvalInProgress = state.autonomousCollectionStatus == AutonomousCollectionStatus.SETUP_IN_PROGRESS,
        callbacks = callbacks.copy(
            onRequestEnableBluetooth = {
                AppLog.d("FirstRun: Turn on Bluetooth tapped", TAG)
                callbacks.onRequestEnableBluetooth()
            },
        ),
        onBluetoothNotNow = {
            AppLog.d("FirstRun: Bluetooth off dismissed (Not now)", TAG)
            bluetoothOffDismissed = true
        },
    )
}

@Composable
fun FirstRunScreen(
    step: FirstRunStep,
    rows: List<NearbyMeterRow>,
    errorLine: String?,
    offerAppSettings: Boolean,
    callbacks: FirstRunCallbacks,
    onBluetoothNotNow: () -> Unit,
    modifier: Modifier = Modifier,
    approvalInProgress: Boolean = false,
) {
    val colors = KmfTheme.colors
    var whyText by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        FirstRunHeader(step)
        if (step == FirstRunStep.FindMeter) {
            FindMeterContent(
                rows = rows,
                errorLine = errorLine,
                onSelect = { row ->
                    AppLog.d("FirstRun: Select tapped likely=${row.likelyMeter} signal=${row.signal}", TAG)
                    callbacks.onSelectMeter(row.device)
                },
                modifier = Modifier.weight(1f),
            )
        } else {
            val copy = requireNotNull(copyFor(step))
            BlockedStepContent(
                step = step,
                copy = copy,
                errorLine = errorLine,
                offerAppSettings = offerAppSettings,
                onOpenAppSettings = {
                    AppLog.d("FirstRun: Open app settings tapped on $step", TAG)
                    callbacks.onOpenAppSettings()
                },
                modifier = Modifier.weight(1f),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    label = copy.cta,
                    enabled = !(step == FirstRunStep.AndroidApproval && approvalInProgress),
                    onClick = {
                        AppLog.d("FirstRun: primary '${copy.cta}' tapped on $step", TAG)
                        when (step) {
                            FirstRunStep.BluetoothOff -> callbacks.onRequestEnableBluetooth()
                            FirstRunStep.NearbyDevices -> callbacks.onRequestBluetoothPermissions()
                            FirstRunStep.AndroidApproval -> callbacks.onContinueApproval()
                            FirstRunStep.Notifications -> callbacks.onRequestNotificationPermission()
                            FirstRunStep.Done -> callbacks.onOpenDashboard()
                            FirstRunStep.FindMeter -> Unit
                        }
                    },
                )
                if (copy.secondary.isNotEmpty()) {
                    SecondaryButton(
                        label = copy.secondary,
                        onClick = {
                            AppLog.d("FirstRun: secondary '${copy.secondary}' tapped on $step", TAG)
                            when (step) {
                                FirstRunStep.BluetoothOff -> onBluetoothNotNow()
                                FirstRunStep.NearbyDevices -> whyText = NEARBY_WHY
                                FirstRunStep.AndroidApproval -> callbacks.onChooseAnotherMeter()
                                FirstRunStep.Notifications -> whyText = NOTIFICATIONS_WHY
                                else -> Unit
                            }
                        },
                    )
                }
            }
        }
    }

    whyText?.let { text ->
        AlertDialog(
            onDismissRequest = { whyText = null },
            confirmButton = {
                TextButton(onClick = { whyText = null }) { Text("OK", color = colors.charge.accent) }
            },
            title = { Text("Why is this needed?") },
            text = { Text(text, color = colors.textSecondary) },
            containerColor = colors.surface,
        )
    }
}

@Composable
private fun FirstRunHeader(step: FirstRunStep) {
    val colors = KmfTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Set up collection",
            style = MaterialTheme.typography.titleLarge,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        val number = step.number ?: return@Row
        val label = "Step $number of ${FirstRunResolver.TOTAL_STEPS}"
        Row(
            modifier = Modifier.clearAndSetSemantics { contentDescription = label },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = label, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (index in 1..FirstRunResolver.TOTAL_STEPS) {
                    Box(
                        modifier = Modifier
                            .size(width = 18.dp, height = 4.dp)
                            .background(
                                color = if (index <= number) colors.charge.accent else colors.border,
                                shape = RoundedCornerShape(2.dp),
                            ),
                    )
                }
            }
        }
    }
}

private fun FirstRunStep.accent(colors: com.juncehome.lifepo4ble.ui.theme.KmfColors): KmfAccent = when (this) {
    FirstRunStep.BluetoothOff -> colors.discharge
    FirstRunStep.Done -> colors.live
    else -> colors.charge
}

@Composable
private fun BlockedStepContent(
    step: FirstRunStep,
    copy: StepCopy,
    errorLine: String?,
    offerAppSettings: Boolean,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KmfTheme.colors
    val accent = step.accent(colors)
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .background(accent.tileFill, RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = copy.icon,
                    contentDescription = null,
                    tint = accent.accent,
                    modifier = Modifier.size(40.dp),
                )
            }
            Text(
                text = copy.title,
                style = MaterialTheme.typography.headlineMedium.copy(lineHeight = 32.sp),
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = copy.body,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 310.dp),
            )
            if (step == FirstRunStep.Notifications) {
                NotificationPreview()
            }
            if (errorLine != null) {
                ErrorLine(errorLine)
            }
            if (offerAppSettings) {
                TextButton(
                    onClick = onOpenAppSettings,
                    modifier = Modifier.heightIn(min = KmfDimens.MinTouchTarget),
                ) {
                    Text(
                        text = "Open app settings",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.charge.accent,
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = KmfTheme.colors.error.onTint,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun NotificationPreview() {
    val colors = KmfTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(KmfDimens.CardRadius))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(colors.charge.tileFill, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = KmfIcons.Bluetooth,
                    contentDescription = null,
                    tint = colors.charge.accent,
                    modifier = Modifier.size(16.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Battery 82% \u00B7 charging +165 W",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Collecting in background",
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = colors.textSecondary,
                )
            }
            Text(
                text = "Stop",
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                color = colors.charge.accent,
            )
        }
        Text(
            text = "Example of the ongoing notification",
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Normal),
            color = colors.textTertiary,
        )
    }
}

@Composable
private fun FindMeterContent(
    rows: List<NearbyMeterRow>,
    errorLine: String?,
    onSelect: (NearbyMeterRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KmfTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PulsingRings(color = colors.charge.accent, tile = colors.charge.tileFill)
            Text(
                text = "Looking for your meter",
                style = MaterialTheme.typography.headlineMedium.copy(lineHeight = 32.sp),
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = "Keep the phone close to the battery meter and make sure it is powered on.",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 300.dp),
            )
        }
        Column(
            modifier = Modifier.semantics { contentDescription = "Nearby devices" },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = NearbyMeterList.headerLabel(rows.size),
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            rows.forEach { row -> NearbyDeviceRow(row = row, onSelect = { onSelect(row) }) }
            if (errorLine != null) {
                ErrorLine(errorLine)
            }
        }
    }
}

@Composable
private fun NearbyDeviceRow(row: NearbyMeterRow, onSelect: () -> Unit) {
    val colors = KmfTheme.colors
    val shape = RoundedCornerShape(KmfDimens.CardRadius)
    var rowModifier = Modifier
        .fillMaxWidth()
        .background(colors.surface, shape)
    if (row.likelyMeter) {
        rowModifier = rowModifier.border(1.dp, colors.charge.accent.tint(0.3f), shape)
    }
    Row(
        modifier = rowModifier.padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyLarge.copy(
                    lineHeight = 22.sp,
                    fontWeight = if (row.likelyMeter) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = if (row.likelyMeter || row.named) colors.textPrimary else colors.textSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            row.signalLabel?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.likelyMeter) colors.live.accent else colors.textSecondary,
                )
            }
        }
        val buttonShape = RoundedCornerShape(KmfDimens.TileRadius)
        val buttonModifier = Modifier.height(KmfDimens.MinTouchTarget)
        val padding = PaddingValues(horizontal = 18.dp)
        if (row.likelyMeter) {
            Button(
                onClick = onSelect,
                modifier = buttonModifier,
                shape = buttonShape,
                contentPadding = padding,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.charge.accent,
                    contentColor = colors.background,
                ),
            ) {
                Text("Select", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            OutlinedButton(
                onClick = onSelect,
                modifier = buttonModifier,
                shape = buttonShape,
                contentPadding = padding,
                border = BorderStroke(1.dp, colors.border),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textPrimary),
            ) {
                Text("Select", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** Two expanding rings around the Bluetooth tile; a single static ring when motion is reduced. */
@Composable
private fun PulsingRings(color: Color, tile: Color) {
    val reduceMotion = KmfTheme.reduceMotion
    Box(
        modifier = Modifier
            .size(120.dp)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        if (reduceMotion) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .border(2.dp, color.tint(0.35f), CircleShape),
            )
        } else {
            val transition = rememberInfiniteTransition(label = "firstRunRings")
            val progress = transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(2_000, easing = LinearEasing), RepeatMode.Restart),
                label = "firstRunRingProgress",
            )
            Ring(progress = progress, offset = 0f, color = color)
            Ring(progress = progress, offset = 0.5f, color = color)
        }
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(tile, RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = KmfIcons.Bluetooth,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

@Composable
private fun Ring(progress: State<Float>, offset: Float, color: Color) {
    Box(
        modifier = Modifier
            .size(120.dp)
            .graphicsLayer {
                val linear = (progress.value + offset) % 1f
                val eased = 1f - (1f - linear) * (1f - linear)
                val scale = 0.6f + eased
                scaleX = scale
                scaleY = scale
                alpha = 0.7f * (1f - eased)
            }
            .border(2.dp, color, CircleShape),
    )
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = KmfTheme.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.buttonContainer,
            contentColor = colors.onButtonContainer,
            disabledContainerColor = colors.surface2,
            disabledContentColor = colors.textTertiary,
        ),
    ) {
        Text(text = label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = KmfDimens.MinTouchTarget),
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = KmfTheme.colors.textSecondary,
        )
    }
}

// ----- Previews -----

private val previewRows = NearbyMeterList.rows(
    listOf(
        ScannedDevice(name = "KMF Meter", address = "00:00:00:00:00:01", rssi = -52, serviceUuids = emptyList()),
        ScannedDevice(name = null, address = "00:00:00:00:00:02", rssi = -88, serviceUuids = emptyList()),
    ),
)

@Composable
private fun PreviewStep(step: FirstRunStep, errorLine: String? = null, offerAppSettings: Boolean = false) {
    KmfBleTheme {
        FirstRunScreen(
            step = step,
            rows = previewRows,
            errorLine = errorLine,
            offerAppSettings = offerAppSettings,
            callbacks = FirstRunCallbacks(),
            onBluetoothNotNow = {},
        )
    }
}

@Preview(name = "Bluetooth off", widthDp = 390, heightDp = 844)
@Composable
private fun FirstRunBluetoothOffPreview() = PreviewStep(FirstRunStep.BluetoothOff)

@Preview(name = "1 Nearby devices", widthDp = 390, heightDp = 844)
@Composable
private fun FirstRunNearbyPreview() = PreviewStep(FirstRunStep.NearbyDevices)

@Preview(name = "1 Nearby devices denied", widthDp = 390, heightDp = 844)
@Composable
private fun FirstRunNearbyDeniedPreview() = PreviewStep(
    FirstRunStep.NearbyDevices,
    errorLine = "Nearby devices access is blocked. Allow it in app settings.",
    offerAppSettings = true,
)

@Preview(name = "2 Find meter", widthDp = 390, heightDp = 844)
@Composable
private fun FirstRunFindMeterPreview() = PreviewStep(FirstRunStep.FindMeter)

@Preview(name = "3 Android approval", widthDp = 390, heightDp = 844)
@Composable
private fun FirstRunApprovalPreview() = PreviewStep(FirstRunStep.AndroidApproval)

@Preview(name = "4 Notifications", widthDp = 390, heightDp = 844)
@Composable
private fun FirstRunNotificationsPreview() = PreviewStep(FirstRunStep.Notifications)

@Preview(name = "Done", widthDp = 390, heightDp = 844)
@Composable
private fun FirstRunDonePreview() = PreviewStep(FirstRunStep.Done)
