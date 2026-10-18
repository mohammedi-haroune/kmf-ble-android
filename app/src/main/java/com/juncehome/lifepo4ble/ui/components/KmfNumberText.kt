package com.juncehome.lifepo4ble.ui.components

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
}
