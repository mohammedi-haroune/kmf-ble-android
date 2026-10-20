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

    @Test
    fun energyBelowOneThousandWhUsesWholeWh() {
        assertEquals(EnergyText("0", "Wh"), KmfNumberText.energy(0.0))
        assertEquals(EnergyText("12", "Wh"), KmfNumberText.energy(0.012))
        assertEquals(EnergyText("999", "Wh"), KmfNumberText.energy(0.999))
    }

    @Test
    fun energyFromOneThousandWhUsesKwh() {
        assertEquals(EnergyText("1.00", "kWh"), KmfNumberText.energy(1.0))
        assertEquals(EnergyText("1.00", "kWh"), KmfNumberText.energy(0.9996))
        assertEquals(EnergyText("48.31", "kWh"), KmfNumberText.energy(48.314))
    }

    @Test
    fun signedEnergyUsesPlusTrueMinusAndUnsignedZero() {
        assertEquals(EnergyText("+550", "Wh"), KmfNumberText.energy(0.55, signed = true))
        assertEquals(EnergyText("\u22121.20", "kWh"), KmfNumberText.energy(-1.2, signed = true))
        assertEquals(EnergyText("0", "Wh"), KmfNumberText.energy(0.0001, signed = true))
    }
}
