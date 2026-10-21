package com.juncehome.lifepo4ble.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KmfMeterFilterTest {
    @Test
    fun acceptsBtgModuleNames() {
        assertTrue(KmfMeterFilter.isKmfMeter("BTG001"))
        assertTrue(KmfMeterFilter.isKmfMeter("btg123"))
        assertTrue(KmfMeterFilter.isKmfMeter("  BTG065  "))
    }

    @Test
    fun acceptsNamesCarryingKmf() {
        assertTrue(KmfMeterFilter.isKmfMeter("KMF Meter"))
        assertTrue(KmfMeterFilter.isKmfMeter("my-kmf-100A"))
    }

    @Test
    fun rejectsUnnamedDevices() {
        assertFalse(KmfMeterFilter.isKmfMeter(null as String?))
        assertFalse(KmfMeterFilter.isKmfMeter(""))
        assertFalse(KmfMeterFilter.isKmfMeter("   "))
    }

    @Test
    fun rejectsOtherDevices() {
        assertFalse(KmfMeterFilter.isKmfMeter("Galaxy Buds2"))
        assertFalse(KmfMeterFilter.isKmfMeter("CH-9102"))
        assertFalse(KmfMeterFilter.isKmfMeter("MyBTG"))
        assertFalse(KmfMeterFilter.isKmfMeter("[TV] Samsung"))
    }

    @Test
    fun filtersScannedDevicesByName() {
        val meter = ScannedDevice(name = "BTG042", address = "A", rssi = -60, serviceUuids = emptyList())
        val other = ScannedDevice(name = "Watch", address = "B", rssi = -40, serviceUuids = emptyList())

        assertTrue(KmfMeterFilter.isKmfMeter(meter))
        assertFalse(KmfMeterFilter.isKmfMeter(other))
    }
}
