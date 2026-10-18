package com.juncehome.lifepo4ble.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Raw palette from docs/design/README.md "Tokens". Screens should prefer the semantic
 * [KmfColors] (via `KmfTheme.colors`) over these constants.
 */
object KmfPalette {
    val Background = Color(0xFF0D0F12)
    val Surface = Color(0xFF16191E)
    val Surface2 = Color(0xFF1E2228)
    val Border = Color(0xFF2C323A)

    val Text = Color(0xFFF2F4F7)
    val TextSecondary = Color(0xFF9AA3AE)
    val TextTertiary = Color(0xFF7D8692)
    val TextSoft = Color(0xFFC9D0D8)

    val Blue = Color(0xFF4FB8FF)
    val Amber = Color(0xFFFFB23F)
    val Green = Color(0xFF3DDC97)
    val Red = Color(0xFFFF6B6B)
    val RedOnTint = Color(0xFFFF8A8A)
    val AmberOnTint = Color(0xFFFFC46B)
    val Neutral = Color(0xFF9AA3AE)
}

/** Standard tint strengths from the design spec. */
object KmfTint {
    /** Card fill, e.g. the flow card background. */
    const val CARD_FILL = 0.08f

    /** Banner fill. */
    const val BANNER_FILL = 0.10f

    /** Pills and pulse halos (design uses 12-14 %). */
    const val PILL_FILL = 0.14f

    /** Icon tiles. */
    const val TILE_FILL = 0.14f

    /** Subtle accent border. */
    const val BORDER_SUBTLE = 0.22f

    /** Strong accent border (banners). */
    const val BORDER_STRONG = 0.35f
}

/** Returns this color at [alpha] for tinted fills and borders. */
fun Color.tint(alpha: Float): Color = copy(alpha = alpha.coerceIn(0f, 1f))

/**
 * One accent role (charge, discharge, live, error, neutral) with its derived tints.
 *
 * - [accent]: dots, icons, numbers, chart strokes
 * - [onTint]: text drawn on top of [fill] (lighter for red/amber for contrast)
 */
@Immutable
data class KmfAccent(
    val accent: Color,
    val onTint: Color = accent,
) {
    val cardFill: Color get() = accent.tint(KmfTint.CARD_FILL)
    val bannerFill: Color get() = accent.tint(KmfTint.BANNER_FILL)
    val pillFill: Color get() = accent.tint(KmfTint.PILL_FILL)
    val tileFill: Color get() = accent.tint(KmfTint.TILE_FILL)
    val borderSubtle: Color get() = accent.tint(KmfTint.BORDER_SUBTLE)
    val borderStrong: Color get() = accent.tint(KmfTint.BORDER_STRONG)
}

/** Semantic colors for the redesigned screens. Access with `KmfTheme.colors`. */
@Immutable
data class KmfColors(
    val background: Color = KmfPalette.Background,
    val surface: Color = KmfPalette.Surface,
    val surface2: Color = KmfPalette.Surface2,
    val hairline: Color = KmfPalette.Surface2,
    val border: Color = KmfPalette.Border,
    val textPrimary: Color = KmfPalette.Text,
    val textSecondary: Color = KmfPalette.TextSecondary,
    val textTertiary: Color = KmfPalette.TextTertiary,
    val textSoft: Color = KmfPalette.TextSoft,
    /** Charging / positive values. */
    val charge: KmfAccent = KmfAccent(KmfPalette.Blue),
    /** Discharging / negative values. */
    val discharge: KmfAccent = KmfAccent(KmfPalette.Amber, onTint = KmfPalette.AmberOnTint),
    /** Live / ok. */
    val live: KmfAccent = KmfAccent(KmfPalette.Green),
    val error: KmfAccent = KmfAccent(KmfPalette.Red, onTint = KmfPalette.RedOnTint),
    /** Off / out of range / unknown. */
    val neutral: KmfAccent = KmfAccent(KmfPalette.Neutral, onTint = KmfPalette.TextSoft),
    /** Filled primary button (light pill on dark), e.g. banner actions. */
    val buttonContainer: Color = KmfPalette.Text,
    val onButtonContainer: Color = KmfPalette.Background,
) {
    /** Sign color for power/current: charge for >= 0, discharge for < 0. */
    fun signed(value: Double): KmfAccent = if (value < 0.0) discharge else charge
}

val LocalKmfColors = staticCompositionLocalOf { KmfColors() }
