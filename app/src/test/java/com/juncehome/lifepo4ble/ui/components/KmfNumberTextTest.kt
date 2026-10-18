package com.juncehome.lifepo4ble.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class KmfNumberTextTest {
    @Test
    fun leadingHyphenBecomesTrueMinus() {
        assertEquals("\u221286.3", KmfNumberText.withTrueMinus("-86.3"))
    }

    @Test
    fun nonNegativeTextIsUnchanged() {
        assertEquals("13.28", KmfNumberText.withTrueMinus("13.28"))
        assertEquals("--", KmfNumberText.withTrueMinus(KmfNumberText.MISSING))
        assertEquals("", KmfNumberText.withTrueMinus(""))
    }

    @Test
    fun signedAddsPlusForPositiveAndTrueMinusForNegative() {
        assertEquals("+164.7", KmfNumberText.signed("164.7", 164.7))
        assertEquals("+164.7", KmfNumberText.signed("+164.7", 164.7))
        assertEquals("\u22126.66", KmfNumberText.signed("-6.66", -6.66))
        assertEquals("0.00", KmfNumberText.signed("0.00", 0.0))
    }
}
