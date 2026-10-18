package com.juncehome.lifepo4ble.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.juncehome.lifepo4ble.ui.theme.KmfAccent
import com.juncehome.lifepo4ble.ui.theme.KmfBleTheme
import com.juncehome.lifepo4ble.ui.theme.KmfTheme

/** Dot behaviour from the Now board (`dotClass`). */
enum class StatusPillIndicator {
    /** No dot. */
    None,

    /** Static dot (e.g. "Live" 5-10 s old, Stale, Off). */
    Steady,

    /** Expanding halo every 2 s (fresh "Live" data). */
    Pulse,

    /** Opacity blink every 1.2 s (Syncing, Reconnecting). */
    Blink,
}

/**
 * Rounded status pill: optional dot + label, tinted from one [KmfAccent]
 * (dot and text = accent, background = accent at 14 %). Animations stop when
 * [KmfTheme.reduceMotion] is true.
 *
 * Typical use: `StatusPill("Live \u00b7 2s ago", KmfTheme.colors.live, StatusPillIndicator.Pulse)`.
 */
@Composable
fun StatusPill(
    label: String,
    accent: KmfAccent,
    modifier: Modifier = Modifier,
    indicator: StatusPillIndicator = StatusPillIndicator.Steady,
    contentDescription: String? = null,
) {
    Row(
        modifier = modifier
            .background(accent.pillFill, RoundedCornerShape(percent = 50))
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (indicator != StatusPillIndicator.None) {
            StatusDot(
                color = accent.accent,
                indicator = if (KmfTheme.reduceMotion) StatusPillIndicator.Steady else indicator,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = accent.onTint,
            maxLines = 1,
        )
    }
}

/** 8 dp status dot with optional pulse halo or blink. Exposed for list rows and cards. */
@Composable
fun StatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    indicator: StatusPillIndicator = StatusPillIndicator.Steady,
) {
    when (indicator) {
        StatusPillIndicator.None -> Unit
        StatusPillIndicator.Steady -> Box(modifier.size(8.dp).background(color, CircleShape))
        StatusPillIndicator.Pulse -> {
            val transition = rememberInfiniteTransition(label = "statusPulse")
            // Matches the CSS keyframes: halo grows 0 -> 8 dp and fades out by 70 %, then rests.
            val progress by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 2_000
                        0f at 0 using LinearEasing
                        1f at 1_400 using LinearEasing
                        1f at 2_000
                    },
                    repeatMode = RepeatMode.Restart,
                ),
                label = "statusPulseProgress",
            )
            Box(
                modifier
                    .size(8.dp)
                    .drawBehind {
                        val haloRadius = size.minDimension / 2f + 8.dp.toPx() * progress
                        drawCircle(
                            color = color.copy(alpha = 0.55f * (1f - progress)),
                            radius = haloRadius,
                        )
                    }
                    .background(color, CircleShape),
            )
        }
        StatusPillIndicator.Blink -> {
            val transition = rememberInfiniteTransition(label = "statusBlink")
            val alpha by transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "statusBlinkAlpha",
            )
            Box(modifier.size(8.dp).alpha(alpha).background(color, CircleShape))
        }
    }
}

@Preview(name = "StatusPill states", backgroundColor = 0xFF0D0F12, showBackground = true)
@Composable
private fun StatusPillPreview() {
    KmfBleTheme {
        val colors = KmfTheme.colors
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusPill("Live \u00b7 2s ago", colors.live, indicator = StatusPillIndicator.Pulse)
            StatusPill("Live \u00b7 7s ago", colors.live)
            StatusPill("Syncing", colors.charge, indicator = StatusPillIndicator.Blink)
            StatusPill("No data \u00b7 15s", colors.discharge)
            StatusPill("Reconnecting", colors.discharge, indicator = StatusPillIndicator.Blink)
            StatusPill("Paused", colors.error)
            StatusPill("Off", colors.neutral)
        }
    }
}
