package com.juncehome.lifepo4ble.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.dp
import com.juncehome.lifepo4ble.ui.components.LocalReduceMotion
import com.juncehome.lifepo4ble.ui.components.rememberReduceMotion

private val DarkColorScheme = darkColorScheme(
    primary = KmfPalette.Blue,
    onPrimary = KmfPalette.Background,
    primaryContainer = KmfPalette.Blue.tint(KmfTint.TILE_FILL),
    onPrimaryContainer = KmfPalette.Blue,
    secondary = KmfPalette.Green,
    onSecondary = KmfPalette.Background,
    secondaryContainer = KmfPalette.Surface2,
    onSecondaryContainer = KmfPalette.Text,
    tertiary = KmfPalette.Amber,
    onTertiary = KmfPalette.Background,
    tertiaryContainer = KmfPalette.Amber.tint(KmfTint.TILE_FILL),
    onTertiaryContainer = KmfPalette.AmberOnTint,
    background = KmfPalette.Background,
    onBackground = KmfPalette.Text,
    surface = KmfPalette.Background,
    onSurface = KmfPalette.Text,
    surfaceVariant = KmfPalette.Surface,
    onSurfaceVariant = KmfPalette.TextSecondary,
    surfaceContainerLowest = KmfPalette.Background,
    surfaceContainerLow = KmfPalette.Surface,
    surfaceContainer = KmfPalette.Surface,
    surfaceContainerHigh = KmfPalette.Surface2,
    surfaceContainerHighest = KmfPalette.Surface2,
    inverseSurface = KmfPalette.Text,
    inverseOnSurface = KmfPalette.Background,
    outline = KmfPalette.Border,
    outlineVariant = KmfPalette.Surface2,
    error = KmfPalette.Red,
    onError = KmfPalette.Background,
    errorContainer = KmfPalette.Red.tint(KmfTint.PILL_FILL),
    onErrorContainer = KmfPalette.RedOnTint,
    scrim = KmfPalette.Background,
)

/** Spacing and radius tokens from docs/design/README.md. */
object KmfDimens {
    val ScreenPadding = 20.dp
    val SectionGap = 16.dp
    val CardRadius = 16.dp
    val TileRadius = 12.dp
    val BannerRadius = 14.dp
    val MinTouchTarget = 44.dp
    val NavBarHeight = 72.dp
    val Hairline = 1.dp
}

/**
 * Entry point for the app-specific tokens: `KmfTheme.colors`, `KmfTheme.numbers`,
 * `KmfTheme.reduceMotion`.
 */
object KmfTheme {
    val colors: KmfColors
        @Composable
        @ReadOnlyComposable
        get() = LocalKmfColors.current

    val numbers: KmfNumberStyles
        @Composable
        @ReadOnlyComposable
        get() = LocalKmfNumberStyles.current

    /** True when Android "remove animations" is on; skip decorative animations. */
    val reduceMotion: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalReduceMotion.current
}

/** The app is dark only; [darkTheme] is kept for call-site compatibility. */
@Composable
fun KmfBleTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalKmfColors provides KmfColors(),
        LocalKmfNumberStyles provides KmfNumberStyles(),
        LocalReduceMotion provides rememberReduceMotion(),
    ) {
        MaterialTheme(
            colorScheme = DarkColorScheme,
            typography = Typography,
            content = content,
        )
    }
}
