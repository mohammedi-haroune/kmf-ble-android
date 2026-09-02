package com.juncehome.lifepo4ble.data

import org.junit.Assert.assertEquals
import org.junit.Test

class KmfBatteryCsvTest {
    @Test
    fun rendersTimestampedBatteryMeasurementsAsCsv() {
        val sample = KmfBatterySampleEntity(
            id = 7,
            timestampMs = 1_725_345_678_901L,
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            deviceName = "KMF, cabin",
            voltageV = 13.26,
            currentA = -1.08,
            powerW = -14.3208,
            charging = false,
            minutesRemaining = 95,
            remainingAh = 57.93,
            capacityAh = 100.0,
            socPercent = 57.93,
            chargeKwh = 12.34,
            dischargeKwh = 7.89,
            hasAFrame = true,
            hasBFrame = true,
            hasCFrame = true,
            rawAFieldsCsv = "1326,1080,0,95,57930,1000",
            rawBFieldsCsv = "1,2",
            rawCFieldsCsv = "12340,7890",
            connectionState = "READY",
            serviceUuid = "service",
            notifyUuid = "notify",
            writeUuid = "write",
        )

        val csvClass = Class.forName("com.juncehome.lifepo4ble.data.KmfBatteryCsv")
        val csv = csvClass.getField("INSTANCE").get(null)
        val rendered = csvClass.getMethod("render", List::class.java)
            .invoke(csv, listOf(sample)) as String

        assertEquals(
            "timestamp_iso8601,timestamp_ms,device_address,device_name,voltage_v,current_a,power_w,charging,minutes_remaining,remaining_ah,capacity_ah,soc_percent,charge_kwh,discharge_kwh,has_a_frame,has_b_frame,has_c_frame,raw_a_fields,raw_b_fields,raw_c_fields\n" +
                "2024-09-03T06:41:18.901Z,1725345678901,AA:BB:CC:DD:EE:FF,\"KMF, cabin\",13.26,-1.08,-14.3208,false,95,57.93,100.0,57.93,12.34,7.89,true,true,true,\"1326,1080,0,95,57930,1000\",\"1,2\",\"12340,7890\"\n",
            rendered,
        )
    }
}
