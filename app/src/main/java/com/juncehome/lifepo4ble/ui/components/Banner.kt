package com.juncehome.lifepo4ble.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.juncehome.lifepo4ble.ui.theme.KmfBleTheme
import com.juncehome.lifepo4ble.ui.theme.KmfDimens
import com.juncehome.lifepo4ble.ui.theme.KmfTheme

/** Banner tones used on the Now board. */
enum class BannerTone {
    /** Out of range, collection off: plain surface, soft text. */
    Neutral,

    /** Reconnecting: amber tint. */
    Warning,

    /** Collection error: red tint. */
    Error,

    /** Informational: blue tint. */
    Info,
}

@Immutable
data class BannerColors(
    val container: Color,
    val border: Color,
    /** Icon and title. */
    val content: Color,
    val body: Color,
)

object BannerDefaults {
    @Composable
    @ReadOnlyComposable
    fun colors(tone: BannerTone): BannerColors {
        val c = KmfTheme.colors
        return when (tone) {
            BannerTone.Neutral -> BannerColors(c.surface, c.border, c.textSoft, c.textSoft)
            BannerTone.Warning -> BannerColors(
                c.discharge.bannerFill, c.discharge.borderStrong, c.discharge.onTint, c.textSoft,
            )
            BannerTone.Error -> BannerColors(
                c.error.accent.copy(alpha = 0.12f), c.error.borderStrong, c.error.onTint, c.textSoft,
            )
            BannerTone.Info -> BannerColors(
                c.charge.bannerFill, c.charge.borderStrong, c.charge.onTint, c.textSoft,
            )
        }
    }
}

/**
 * Status banner: icon, title, body and an optional light action button (44 dp tall).
 * Announced politely to accessibility services when its content changes.
 */
@Composable
fun Banner(
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    tone: BannerTone = BannerTone.Neutral,
    icon: ImageVector? = KmfIcons.BluetoothOff,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    colors: BannerColors = BannerDefaults.colors(tone),
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(KmfDimens.BannerRadius),
        color = colors.container,
        border = BorderStroke(KmfDimens.Hairline, colors.border),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = KmfDimens.MinTouchTarget)
                .padding(start = 14.dp, top = 10.dp, end = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.content,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.content,
                )
                if (!body.isNullOrEmpty()) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.body,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (actionLabel != null && onAction != null) {
                KmfLightButton(label = actionLabel, onClick = onAction)
            }
        }
    }
}

/** Light pill button used for banner actions ("Retry now", "Start"). */
@Composable
fun KmfLightButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = KmfTheme.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = KmfDimens.MinTouchTarget),
        shape = RoundedCornerShape(KmfDimens.TileRadius),
        contentPadding = PaddingValues(horizontal = 14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.buttonContainer,
            contentColor = colors.onButtonContainer,
            disabledContainerColor = colors.surface2,
            disabledContentColor = colors.textTertiary,
        ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
        )
    }
}

@Preview(name = "Banner tones", backgroundColor = 0xFF0D0F12, showBackground = true, widthDp = 390)
@Composable
private fun BannerPreview() {
    KmfBleTheme {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Banner(
                title = "Reconnecting in 15 s",
                body = "Link dropped at 14:32 \u00b7 attempt 4",
                tone = BannerTone.Warning,
                actionLabel = "Retry now",
                onAction = {},
            )
            Banner(
                title = "Meter out of range",
                body = "Reconnects by itself when it is back \u00b7 last 14:32",
            )
            Banner(
                title = "Collection paused",
                body = "Could not turn on meter notifications",
                tone = BannerTone.Error,
                icon = KmfIcons.Warning,
                actionLabel = "Retry",
                onAction = {},
            )
            Banner(
                title = "Background collection is off",
                body = "Stopped from the notification \u00b7 last 14:32",
                actionLabel = "Start",
                onAction = {},
            )
        }
    }
}
