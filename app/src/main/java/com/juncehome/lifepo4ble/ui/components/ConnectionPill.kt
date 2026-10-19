package com.juncehome.lifepo4ble.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.juncehome.lifepo4ble.AppLog
import com.juncehome.lifepo4ble.ui.BleUiState
import com.juncehome.lifepo4ble.ui.now.NowBannerAction
import com.juncehome.lifepo4ble.ui.now.NowPill
import com.juncehome.lifepo4ble.ui.now.NowPillIcon
import com.juncehome.lifepo4ble.ui.now.NowScreenReducer
import com.juncehome.lifepo4ble.ui.now.NowTone
import com.juncehome.lifepo4ble.ui.theme.KmfAccent
import com.juncehome.lifepo4ble.ui.theme.KmfTheme
import kotlinx.coroutines.delay

private const val TAG = "KMF-BLE"

/** Maps a Now tone to its theme accent. Shared by the connection pill and the Now tab. */
@Composable
fun NowTone.accent(): KmfAccent {
    val colors = KmfTheme.colors
    return when (this) {
        NowTone.Charge -> colors.charge
        NowTone.Discharge -> colors.discharge
        NowTone.Live -> colors.live
        NowTone.Error -> colors.error
        NowTone.Neutral -> colors.neutral
        NowTone.Muted -> KmfAccent(colors.textTertiary)
    }
}

/**
 * The connection status pill shown at the right of the Now, Trends and Device headers. Same
 * label, tone, pulse/blink and tap action everywhere; [onAction] receives the pill's
 * [NowBannerAction] (retry reconnect / retry collection / start collection).
 */
@Composable
fun ConnectionPill(
    pill: NowPill,
    onAction: (NowBannerAction) -> Unit,
    modifier: Modifier = Modifier,
    logSource: String = "header",
) {
    val action = pill.action
    StatusPill(
        label = pill.label,
        accent = pill.tone.accent(),
        modifier = modifier,
        indicator = pill.indicator,
        contentDescription = pill.contentDescription,
        icon = when (pill.icon) {
            NowPillIcon.Retry -> KmfIcons.Retry
            NowPillIcon.Play -> KmfIcons.Play
            NowPillIcon.None -> null
        },
        onClick = action?.let { a ->
            {
                AppLog.d("Pill: $logSource pill tapped action=$a label='${pill.label}'", TAG)
                onAction(a)
            }
        },
        onClickLabel = action?.label,
    )
}

/** Pill for [state], recomputed once per second so ages and reconnect countdowns tick. */
@Composable
fun rememberConnectionPill(state: BleUiState): State<NowPill> {
    val nowMs = produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    return remember(state) {
        derivedStateOf { NowScreenReducer.pillFor(state, nowMs.value) }
    }
}
