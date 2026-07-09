package com.juncehome.lifepo4ble.protocol

object KmfReadingMerger {
    fun apply(reading: KmfReading, frame: KmfFrame): KmfReading =
        when (frame) {
            is KmfFrame.A -> {
                val capacityAh = if (frame.capacityAh > 0.0) frame.capacityAh else reading.capacityAh
                val socPercent = if (frame.capacityAh > 0.0) {
                    frame.socPercent
                } else if (capacityAh > 0.0) {
                    (frame.remainingAh / capacityAh * 100.0).coerceIn(0.0, 100.0)
                } else {
                    0.0
                }

                reading.copy(
                    voltageV = frame.voltageV,
                    currentA = frame.currentA,
                    powerW = frame.powerW,
                    charging = frame.charging,
                    minutesRemaining = frame.minutesRemaining,
                    remainingAh = frame.remainingAh,
                    capacityAh = capacityAh,
                    socPercent = socPercent,
                    status = frame.status,
                )
            }
            is KmfFrame.B -> reading
            is KmfFrame.C -> reading.copy(
                chargeKwh = frame.chargeKwh,
                dischargeKwh = frame.dischargeKwh,
            )
        }
}
