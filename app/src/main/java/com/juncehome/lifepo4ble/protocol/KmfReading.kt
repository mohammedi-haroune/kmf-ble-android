package com.juncehome.lifepo4ble.protocol

data class KmfReading(
    val voltageV: Double = 0.0,
    val currentA: Double = 0.0,
    val powerW: Double = 0.0,
    val charging: Boolean = false,
    val minutesRemaining: Int = 0,
    val remainingAh: Double = 0.0,
    val capacityAh: Double = 0.0,
    val socPercent: Double = 0.0,
    val status: String = "",
    val chargeKwh: Double = 0.0,
    val dischargeKwh: Double = 0.0,
)
