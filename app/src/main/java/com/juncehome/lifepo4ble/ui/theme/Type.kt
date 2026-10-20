package com.juncehome.lifepo4ble.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.juncehome.lifepo4ble.R

/** Bundled IBM Plex Sans (OFL, see assets/licenses/IBM-Plex-OFL.txt). */
val PlexSans = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
)

/** Bundled IBM Plex Mono, used for every number. */
val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
)

/** OpenType feature string forcing tabular (fixed-width) figures. */
const val TABULAR_FIGURES = "tnum"

private fun sans(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = PlexSans,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = 0.sp,
)

private fun mono(size: Int, line: Int, weight: FontWeight = FontWeight.Medium, tracking: Double = 0.0) = TextStyle(
    fontFamily = PlexMono,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
    fontFeatureSettings = TABULAR_FIGURES,
)

/** Material typography mapped onto Plex Sans (sizes follow the design boards). */
val Typography = Typography(
    displayLarge = sans(57, 64),
    displayMedium = sans(45, 52),
    displaySmall = sans(36, 44),
    headlineLarge = sans(28, 34, FontWeight.SemiBold),
    headlineMedium = sans(24, 30, FontWeight.SemiBold),
    headlineSmall = sans(20, 26, FontWeight.SemiBold),
    titleLarge = sans(17, 24, FontWeight.SemiBold),
    titleMedium = sans(15, 22, FontWeight.SemiBold),
    titleSmall = sans(14, 20, FontWeight.SemiBold),
    bodyLarge = sans(16, 24),
    bodyMedium = sans(14, 20),
    bodySmall = sans(13, 18),
    labelLarge = sans(14, 20, FontWeight.SemiBold),
    labelMedium = sans(13, 18, FontWeight.Medium),
    labelSmall = sans(12, 16, FontWeight.Medium),
)

/**
 * Number styles (Plex Mono, tabular figures). Access with `KmfTheme.numbers`.
 * Sizes come from the Now / Trends boards.
 */
@Immutable
data class KmfNumberStyles(
    /** Hero SoC number (100 sp in the design). */
    val hero: TextStyle = mono(100, 96, tracking = -4.0),
    /** Unit next to the hero number. */
    val heroUnit: TextStyle = mono(36, 40),
    /** SoC inside the Now orbit ring. */
    val orbitSoc: TextStyle = mono(72, 72, tracking = -3.0),
    /** Decimals and "%" next to [orbitSoc]. */
    val orbitSocUnit: TextStyle = mono(26, 26),
    /** Large secondary value, e.g. voltage or flow power. */
    val large: TextStyle = mono(26, 32),
    val largeUnit: TextStyle = mono(14, 18, FontWeight.Normal),
    /** Card value, e.g. Trends current value or energy totals. */
    val medium: TextStyle = mono(20, 26),
    val mediumUnit: TextStyle = mono(13, 18, FontWeight.Normal),
    /** Inline numbers in body copy. */
    val body: TextStyle = mono(14, 20, FontWeight.Normal),
    val small: TextStyle = mono(13, 18, FontWeight.Normal),
    val caption: TextStyle = mono(12, 16, FontWeight.Normal),
)

val LocalKmfNumberStyles = staticCompositionLocalOf { KmfNumberStyles() }
