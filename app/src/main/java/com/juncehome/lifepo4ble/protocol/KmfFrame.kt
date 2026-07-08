package com.juncehome.lifepo4ble.protocol

sealed interface KmfFrame {
    data class A(
        val voltageV: Double,
        val currentA: Double,
        val powerW: Double,
        val charging: Boolean,
        val minutesRemaining: Int,
        val remainingAh: Double,
        val capacityAh: Double,
        val socPercent: Double,
        val status: String,
    ) : KmfFrame

    data class C(
        val chargeKwh: Double,
        val dischargeKwh: Double,
    ) : KmfFrame
}
