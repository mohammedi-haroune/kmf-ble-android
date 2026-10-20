package com.juncehome.lifepo4ble.ui.trends

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juncehome.lifepo4ble.AppLog
import com.juncehome.lifepo4ble.ui.components.KmfIcons
import com.juncehome.lifepo4ble.ui.components.KmfLightButton
import com.juncehome.lifepo4ble.ui.theme.KmfDimens
import com.juncehome.lifepo4ble.ui.theme.KmfTheme
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.roundToInt

private const val TAG = "KMF-BLE"

/**
 * Horizontally scrollable preset chips, edge to edge inside the padded screen column.
 *
 * The selected chip is scrolled into view by this row's own [ScrollState] only. A
 * BringIntoViewRequester would also scroll the screen's vertical scroll, and because the effect
 * re-runs whenever the tab is recomposed, returning to Trends jumped the page up to this row.
 */
@Composable
internal fun RangeChips(selected: TrendsRange, onSelect: (TrendsRange) -> Unit) {
    val scroll = rememberScrollState()
    val bleed = KmfDimens.ScreenPadding
    val density = LocalDensity.current
    // Plain holder, not snapshot state: placement callbacks must not trigger recomposition.
    val placed = remember { PlacedChips() }
    LaunchedEffect(selected) {
        // Wait one frame so the chips have been placed for the current selection.
        withFrameNanos { }
        val viewport = placed.viewport ?: return@LaunchedEffect
        val chip = placed.chips[selected] ?: return@LaunchedEffect
        if (!viewport.isAttached || !chip.isAttached) return@LaunchedEffect
        val marginPx = with(density) { bleed.toPx() }
        val left = viewport.localPositionOf(chip, Offset.Zero).x
        val right = left + chip.size.width
        val delta = when {
            left < marginPx -> left - marginPx
            right > viewport.size.width - marginPx -> right - (viewport.size.width - marginPx)
            else -> 0f
        }
        if (delta != 0f) {
            val target = (scroll.value + delta).roundToInt().coerceIn(0, scroll.maxValue)
            AppLog.d("Trends: range chips scrolled to ${selected.label} ${scroll.value} -> $target", TAG)
            scroll.animateScrollTo(target)
        }
    }
    Row(
        modifier = Modifier
            .layout { measurable, constraints ->
                val extra = (bleed * 2).roundToPx()
                val widened = constraints.copy(
                    minWidth = constraints.maxWidth + extra,
                    maxWidth = constraints.maxWidth + extra,
                )
                val placeable = measurable.measure(widened)
                layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(-bleed.roundToPx(), 0) }
            }
            .onPlaced { placed.viewport = it }
            .horizontalScroll(scroll)
            .padding(horizontal = bleed)
            .semantics { contentDescription = "Time range" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrendsRange.entries.forEach { range ->
            RangeChip(
                range = range,
                selected = range == selected,
                modifier = Modifier,
                onPlaced = { placed.chips[range] = it },
                onSelect = onSelect,
            )
        }
    }
}

private class PlacedChips {
    var viewport: LayoutCoordinates? = null
    val chips = HashMap<TrendsRange, LayoutCoordinates>()
}

@Composable
private fun RangeChip(
    range: TrendsRange,
    selected: Boolean,
    modifier: Modifier,
    onPlaced: (LayoutCoordinates) -> Unit,
    onSelect: (TrendsRange) -> Unit,
) {
    val colors = KmfTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .onPlaced(onPlaced)
            .height(40.dp)
            .widthIn(min = 48.dp)
            .background(if (selected) colors.surface2 else Color.Transparent, shape)
            .border(BorderStroke(1.dp, if (selected) colors.textTertiary else colors.border), shape)
            .clip(shape)
            .clickable(role = Role.Tab) { onSelect(range) }
            .semantics {
                this.selected = selected
                contentDescription = range.description
            }
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (selected) colors.textPrimary else colors.textSecondary
        if (range == TrendsRange.Custom) {
            Icon(KmfIcons.Calendar, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = range.label,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            color = tint,
            maxLines = 1,
        )
    }
}

/** 44 dp bar: earlier button, window text, later button, then skip to now (enabled once stepped back). */
@Composable
internal fun WindowStepper(
    state: TrendsScreenState,
    onEarlier: () -> Unit,
    onLater: () -> Unit,
    onJumpToNow: () -> Unit,
) {
    val colors = KmfTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(colors.surface, RoundedCornerShape(14.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(KmfIcons.ChevronLeft, "Earlier period", state.canEarlier, onEarlier)
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.windowLabel,
                style = KmfTheme.numbers.small.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
                color = colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.semantics { contentDescription = "Showing ${state.windowLabel}" },
            )
        }
        StepButton(KmfIcons.ChevronRight, "Later period", state.canLater, onLater)
        Box(Modifier.width(1.dp).height(24.dp).background(colors.hairline))
        StepButton(KmfIcons.SkipToEnd, "Jump to now", state.canLater, onJumpToNow, enabledTint = colors.charge.accent)
    }
}

@Composable
private fun StepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    enabledTint: Color? = null,
) {
    val colors = KmfTheme.colors
    Box(
        modifier = Modifier
            .size(44.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) enabledTint ?: colors.textPrimary else colors.textTertiary.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp),
        )
    }
}

private enum class PickerTarget { From, To }

/** From / To fields (date then time picker) and an Apply button. */
@Composable
internal fun CustomRangePanel(
    state: TrendsScreenState,
    zone: ZoneId,
    onApply: (fromMs: Long, toMs: Long) -> TrendsCustomError?,
) {
    val colors = KmfTheme.colors
    var from by rememberSaveable { mutableLongStateOf(state.customFromMs) }
    var to by rememberSaveable { mutableLongStateOf(state.customToMs) }
    var picking by remember { mutableStateOf<PickerTarget?>(null) }
    var rejected by remember { mutableStateOf<TrendsCustomError?>(null) }
    val validation = TrendsCustomRanges.validate(from, to, System.currentTimeMillis())
    val error = validation ?: rejected
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, shape)
            .border(BorderStroke(1.dp, colors.border), shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DateField("From", TrendsCustomRanges.format(from, zone), Modifier.weight(1f)) { picking = PickerTarget.From }
            DateField("To", TrendsCustomRanges.format(to, zone), Modifier.weight(1f)) { picking = PickerTarget.To }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = error?.message ?: TrendsCustomRanges.hint(state.earliestMs, from, to, zone),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = if (error != null) colors.error.onTint else colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            KmfLightButton(
                label = "Apply",
                enabled = validation == null,
                onClick = {
                    rejected = onApply(from, to)
                    AppLog.d("Trends: custom Apply tapped from=$from to=$to rejected=$rejected", TAG)
                },
            )
        }
    }
    picking?.let { target ->
        DateTimePicker(
            initialMs = if (target == PickerTarget.From) from else to,
            zone = zone,
            onConfirm = { ms ->
                AppLog.d("Trends: custom $target picked $ms", TAG)
                if (target == PickerTarget.From) from = ms else to = ms
                rejected = null
                picking = null
            },
            onDismiss = {
                AppLog.d("Trends: custom $target picker dismissed", TAG)
                picking = null
            },
        )
    }
}

@Composable
private fun DateField(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = KmfTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = colors.textSecondary)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(colors.background, shape)
                .border(BorderStroke(1.dp, colors.border), shape)
                .clip(shape)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = "$label, $value" }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = value,
                style = KmfTheme.numbers.small.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
                color = colors.textPrimary,
                maxLines = 1,
            )
        }
    }
}

/** Date picker followed by a 24 h time picker; confirms with epoch ms in [zone]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimePicker(initialMs: Long, zone: ZoneId, onConfirm: (Long) -> Unit, onDismiss: () -> Unit) {
    val colors = KmfTheme.colors
    val initial = remember(initialMs, zone) { Instant.ofEpochMilli(initialMs).atZone(zone) }
    var stage by remember { mutableIntStateOf(0) }
    var dateMs by remember {
        mutableLongStateOf(initial.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    }
    if (stage == 0) {
        val datePicker = rememberDatePickerState(initialSelectedDateMillis = dateMs)
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    onClick = {
                        datePicker.selectedDateMillis?.let {
                            dateMs = it
                            stage = 1
                        }
                    },
                ) { Text("Next", color = colors.charge.accent) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = colors.textPrimary) } },
        ) {
            DatePicker(state = datePicker)
        }
    } else {
        val timePicker = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = colors.surface,
            text = { TimePicker(state = timePicker) },
            confirmButton = {
                TextButton(
                    onClick = { onConfirm(TrendsCustomRanges.combine(dateMs, timePicker.hour, timePicker.minute, zone)) },
                ) { Text("OK", color = colors.charge.accent, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { stage = 0 }) { Text("Back", color = colors.textPrimary) } },
        )
    }
}
