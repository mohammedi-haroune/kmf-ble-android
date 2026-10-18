package com.juncehome.lifepo4ble.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.juncehome.lifepo4ble.ui.theme.KmfBleTheme
import com.juncehome.lifepo4ble.ui.theme.KmfTheme

/**
 * Mono number followed by a smaller unit in secondary color, drawn as one Text so both share
 * a baseline (e.g. `13.28 V`, `82%`). A leading ASCII "-" in [value] is rendered as the true
 * minus sign.
 *
 * Pick a matching pair from `KmfTheme.numbers`: hero/heroUnit, large/largeUnit,
 * medium/mediumUnit. Use [unitSeparator] = "" for "%".
 */
@Composable
fun ValueWithUnit(
    value: String,
    unit: String?,
    modifier: Modifier = Modifier,
    valueStyle: TextStyle = KmfTheme.numbers.large,
    unitStyle: TextStyle = KmfTheme.numbers.largeUnit,
    valueColor: Color = KmfTheme.colors.textPrimary,
    unitColor: Color = KmfTheme.colors.textSecondary,
    unitSeparator: String = " ",
    contentDescription: String? = null,
) {
    val text = buildAnnotatedString {
        append(KmfNumberText.withTrueMinus(value))
        if (!unit.isNullOrEmpty()) {
            withStyle(
                SpanStyle(
                    fontFamily = unitStyle.fontFamily,
                    fontWeight = unitStyle.fontWeight,
                    fontSize = unitStyle.fontSize,
                    letterSpacing = unitStyle.letterSpacing,
                    fontFeatureSettings = unitStyle.fontFeatureSettings,
                    color = unitColor,
                ),
            ) {
                append(unitSeparator)
                append(unit)
            }
        }
    }
    val description = contentDescription
    Text(
        text = text,
        modifier = if (description != null) {
            modifier.semantics { this.contentDescription = description }
        } else {
            modifier
        },
        style = valueStyle,
        color = valueColor,
        maxLines = 1,
    )
}

@Preview(name = "ValueWithUnit sizes", backgroundColor = 0xFF0D0F12, showBackground = true)
@Composable
private fun ValueWithUnitPreview() {
    KmfBleTheme {
        val numbers = KmfTheme.numbers
        val colors = KmfTheme.colors
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ValueWithUnit("82", "%", valueStyle = numbers.hero, unitStyle = numbers.heroUnit, unitSeparator = "")
            ValueWithUnit("13.28", "V")
            ValueWithUnit("-86.3", "W", valueColor = colors.discharge.accent)
            ValueWithUnit("48.31", "kWh", valueStyle = numbers.medium, unitStyle = numbers.mediumUnit)
            ValueWithUnit("--", "A")
        }
    }
}
