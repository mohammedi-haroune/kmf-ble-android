package com.juncehome.lifepo4ble.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.juncehome.lifepo4ble.protocol.FrameLogEntry
import com.juncehome.lifepo4ble.protocol.PacketDirection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun DashboardScreen(
    state: BleUiState,
    contentPadding: PaddingValues,
) {
    val content = buildDashboardContent(state)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Dashboard",
            style = MaterialTheme.typography.headlineMedium,
        )

        content.emptyState?.let { emptyState ->
            DashboardEmptyStateCard(
                emptyState = emptyState,
                detail = content.connection.detail,
            )
        } ?: run {
            content.hero?.let { hero ->
                BatteryHeroCard(hero = hero)
            }
            MetricGrid(metrics = content.metrics)
            EnergyTotalsCard(model = content.energyTotals)
        }

        ConnectionSummaryCard(model = content.connection)
    }
}

data class DashboardContent(
    val emptyState: DashboardEmptyState?,
    val hero: BatteryHeroModel?,
    val metrics: List<MetricCardModel>,
    val energyTotals: EnergyTotalsModel,
    val connection: ConnectionSummaryModel,
)

enum class DashboardEmptyState {
    NoDevice,
    NoData,
    Disconnected,
}

data class BatteryHeroModel(
    val socText: String,
    val statusText: String,
    val headlineValue: String,
    val supportingText: String,
)

data class MetricCardModel(
    val label: String,
    val value: String,
)

data class EnergyTotalsModel(
    val chargeTotal: String,
    val dischargeTotal: String,
)

data class ConnectionSummaryModel(
    val deviceName: String,
    val deviceAddress: String?,
    val connectionLabel: String,
    val bootstrapStatus: String,
    val lastUpdateTimestampMs: Long?,
    val detail: String?,
)

fun buildDashboardContent(state: BleUiState): DashboardContent {
    val latestInbound = state.packetLog.latestInboundEntry()
    val connection = ConnectionSummaryModel(
        deviceName = state.selectedDevice?.name.orDashboardDeviceName(),
        deviceAddress = state.selectedDevice?.address,
        connectionLabel = state.connectionState.toDisplayLabel(),
        bootstrapStatus = state.bootstrapStatusLabel(),
        lastUpdateTimestampMs = latestInbound?.timestampMs,
        detail = state.latestError,
    )
    val emptyState = when {
        state.selectedDevice == null -> DashboardEmptyState.NoDevice
        state.connectionState == ConnectionState.DISCONNECTED -> DashboardEmptyState.Disconnected
        !state.hasAFrame -> DashboardEmptyState.NoData
        else -> null
    }

    if (emptyState != null) {
        return DashboardContent(
            emptyState = emptyState,
            hero = null,
            metrics = emptyList(),
            energyTotals = EnergyTotalsModel(
                chargeTotal = state.latestReading.chargeKwh.toKwhText(),
                dischargeTotal = state.latestReading.dischargeKwh.toKwhText(),
            ),
            connection = connection,
        )
    }

    return DashboardContent(
        emptyState = null,
        hero = BatteryHeroModel(
            socText = state.latestReading.socPercent.toPercentText(),
            statusText = if (state.latestReading.charging) "Charging" else "Discharging",
            headlineValue = state.latestReading.voltageV.toUnitText("V"),
            supportingText = "${state.latestReading.remainingAh.toUnitText("Ah")} remaining " +
                "of ${state.latestReading.capacityAh.toUnitText("Ah")}",
        ),
        metrics = listOf(
            MetricCardModel("Current", state.latestReading.currentA.toUnitText("A")),
            MetricCardModel("Power", state.latestReading.powerW.toUnitText("W")),
            MetricCardModel("Time remaining", state.latestReading.minutesRemaining.toDurationText()),
            MetricCardModel("Remaining Ah", state.latestReading.remainingAh.toUnitText("Ah")),
            MetricCardModel("Capacity Ah", state.latestReading.capacityAh.toUnitText("Ah")),
            MetricCardModel("SoC", state.latestReading.socPercent.toPercentText()),
        ),
        energyTotals = EnergyTotalsModel(
            chargeTotal = state.latestReading.chargeKwh.toKwhText(),
            dischargeTotal = state.latestReading.dischargeKwh.toKwhText(),
        ),
        connection = connection,
    )
}

@Composable
fun BatteryHeroCard(hero: BatteryHeroModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = hero.socText,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = hero.statusText,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = hero.headlineValue,
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = hero.supportingText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun MetricGrid(metrics: List<MetricCardModel>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        metrics.chunked(2).forEach { rowMetrics ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowMetrics.forEach { metric ->
                    Box(modifier = Modifier.weight(1f)) {
                        MetricCard(metric = metric)
                    }
                }
                if (rowMetrics.size == 1) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun MetricCard(metric: MetricCardModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = metric.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = metric.value,
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

@Composable
fun EnergyTotalsCard(model: EnergyTotalsModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Energy totals",
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EnergyPill(
                    label = "Charged",
                    value = model.chargeTotal,
                    modifier = Modifier.weight(1f),
                )
                EnergyPill(
                    label = "Discharged",
                    value = model.dischargeTotal,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun EnergyPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
fun ConnectionSummaryCard(model: ConnectionSummaryModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Connection summary",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = model.deviceName,
                style = MaterialTheme.typography.titleLarge,
            )
            model.deviceAddress?.let { address ->
                Text(
                    text = address,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(connected = model.connectionLabel == "Ready" || model.connectionLabel == "Connected")
                Text(
                    text = model.connectionLabel,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Text(
                text = "Bootstrap: ${model.bootstrapStatus}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = model.lastUpdateTimestampMs?.toDashboardTimeText() ?: "Last update: waiting for frames",
                style = MaterialTheme.typography.bodyMedium,
            )
            model.detail?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun StatusDot(connected: Boolean) {
    Box(
        modifier = Modifier
            .width(10.dp)
            .height(10.dp)
            .clip(CircleShape)
            .background(
                if (connected) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
    )
}

@Composable
private fun DashboardEmptyStateCard(
    emptyState: DashboardEmptyState,
    detail: String?,
) {
    val title: String
    val body: String
    when (emptyState) {
        DashboardEmptyState.NoDevice -> {
            title = "No meter selected"
            body = "Open Diagnostics to scan and connect before the dashboard can show live battery state."
        }
        DashboardEmptyState.NoData -> {
            title = "Waiting for battery frames"
            body = "The meter is connected, but the dashboard is still waiting for an A-frame with live battery values."
        }
        DashboardEmptyState.Disconnected -> {
            title = "Meter disconnected"
            body = "Reconnect in Diagnostics to resume live readings and energy totals."
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
            )
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun List<FrameLogEntry>.latestInboundEntry(): FrameLogEntry? =
    asReversed().firstOrNull { entry -> entry.direction == PacketDirection.INBOUND }

private fun ConnectionState.toDisplayLabel(): String =
    name.lowercase().replaceFirstChar { character ->
        character.titlecase(Locale.US)
    }

private fun BleUiState.bootstrapStatusLabel(): String =
    when {
        hasAFrame && hasBFrame -> "Ready"
        hasAFrame || hasBFrame -> "In progress"
        connectionState == ConnectionState.DISCONNECTED -> "Offline"
        else -> "Waiting"
    }

private fun String?.orDashboardDeviceName(): String = this ?: "Unnamed KMF meter"

private fun Double.toPercentText(): String = String.format(Locale.US, "%.0f%%", this)

private fun Double.toUnitText(unit: String): String = String.format(Locale.US, "%.2f %s", this, unit)

private fun Double.toKwhText(): String = String.format(Locale.US, "%.2f kWh", this)

private fun Int.toDurationText(): String =
    if (this <= 0) {
        "Unavailable"
    } else {
        val hours = this / 60
        val minutes = this % 60
        when {
            hours == 0 -> "${minutes}m"
            minutes == 0 -> "${hours}h"
            else -> "${hours}h ${minutes}m"
        }
    }

private fun Long.toDashboardTimeText(): String =
    "Last update: " + DASHBOARD_TIME_FORMATTER.format(
        Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault())
    )

private val DASHBOARD_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss")
