package com.juncehome.lifepo4ble.ui.components

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** An energy value and the unit it was formatted in ("450" Wh, "1.20" kWh). */
data class EnergyText(val value: String, val unit: String) {
    companion object {
        val MISSING: EnergyText = EnergyText(KmfNumberText.MISSING, KmfNumberText.KWH)
    }
}

/** Text helpers for numbers shown in the redesigned UI. */
object KmfNumberText {
    /** U+2212 MINUS SIGN; the design requires it instead of the ASCII hyphen. */
    const val TRUE_MINUS: String = "\u2212"

    /** Placeholder for a missing value, e.g. during first sync. */
    const val MISSING: String = "--"

    /**
     * Replaces a leading ASCII hyphen-minus with the true minus sign when it starts a number
     * ("-86.3"); placeholders such as "--" are left alone.
     */
    fun withTrueMinus(text: String): String {
        val startsNegativeNumber = text.length > 1 && text[0] == '-' && (text[1].isDigit() || text[1] == '.')
        return if (startsNegativeNumber) TRUE_MINUS + text.substring(1) else text
    }

    /** Prefixes "+" for positive values (flow card power/current); keeps true minus for negatives. */
    fun signed(text: String, value: Double): String = when {
        value > 0.0 && !text.startsWith("+") -> "+$text"
        else -> withTrueMinus(text)
    }

    const val WH: String = "Wh"
    const val KWH: String = "kWh"

    /**
     * Formats an energy amount given in kWh: whole Wh below 1000 Wh ("450 Wh"), kWh with two
     * decimals from 1000 Wh up ("1.20 kWh"). The switch uses the rounded Wh so 999.6 Wh shows as
     * "1.00 kWh", not "1000 Wh". With [signed], positives get "+" and negatives a true minus;
     * zero stays unsigned.
     */
    fun energy(kwh: Double, locale: Locale = Locale.US, signed: Boolean = false): EnergyText {
        val wh = abs(kwh) * 1000.0
        val inKwh = wh.roundToLong() >= 1000L
        val magnitude = if (inKwh) String.format(locale, "%.2f", wh / 1000.0) else String.format(locale, "%.0f", wh)
        val isZero = magnitude.none { it in '1'..'9' }
        val value = when {
            isZero -> magnitude
            kwh < 0.0 -> TRUE_MINUS + magnitude
            signed -> "+$magnitude"
            else -> magnitude
        }
        return EnergyText(value, if (inKwh) KWH else WH)
    }
}
