package com.juncehome.lifepo4ble.protocol

object KmfReadingMerger {
    fun apply(reading: KmfReading, frame: KmfFrame): KmfReading =
        when (frame) {
            is KmfFrame.A -> {
                val full = frame.capacityAh > 0.0
                val capacityAh = if (full) frame.capacityAh else reading.capacityAh
                // A short A frame has no remaining Ah; the parser derives current x minutes. That is
                // the charge left to empty when discharging but the charge left to full when
                // charging, so a charging frame must be flipped or SoC reads falsely low.
                val remainingAh = when {
                    full -> frame.remainingAh
                    capacityAh <= 0.0 -> reading.remainingAh
                    frame.charging -> (capacityAh - frame.remainingAh).coerceAtLeast(0.0)
                    else -> frame.remainingAh
                }
                val socPercent = when {
                    full -> frame.socPercent
                    capacityAh > 0.0 -> (remainingAh / capacityAh * 100.0).coerceIn(0.0, 100.0)
                    else -> 0.0
                }

                reading.copy(
                    voltageV = frame.voltageV,
                    currentA = frame.currentA,
                    powerW = frame.powerW,
                    charging = frame.charging,
                    minutesRemaining = frame.minutesRemaining,
                    remainingAh = remainingAh,
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
