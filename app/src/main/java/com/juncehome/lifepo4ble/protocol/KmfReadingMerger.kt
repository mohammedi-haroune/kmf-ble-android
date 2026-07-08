package com.juncehome.lifepo4ble.protocol

object KmfReadingMerger {
    fun apply(reading: KmfReading, frame: KmfFrame): KmfReading =
        when (frame) {
            is KmfFrame.A -> reading.copy(
                voltageV = frame.voltageV,
                currentA = frame.currentA,
                powerW = frame.powerW,
                charging = frame.charging,
                minutesRemaining = frame.minutesRemaining,
                remainingAh = frame.remainingAh,
                capacityAh = frame.capacityAh,
                socPercent = frame.socPercent,
                status = frame.status,
            )
            is KmfFrame.C -> reading.copy(
                chargeKwh = frame.chargeKwh,
                dischargeKwh = frame.dischargeKwh,
            )
        }
}
