package com.juncehome.lifepo4ble.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.juncehome.lifepo4ble.protocol.KmfReading

@Composable
fun ReadingPanel(reading: KmfReading) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Latest Reading",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(text = "Voltage: ${reading.voltageV.format(2)} V")
        Text(text = "Current: ${reading.currentA.format(3)} A")
        Text(text = "Power: ${reading.powerW.format(2)} W")
        Text(text = "Charging: ${reading.charging.yesNo()}")
        Text(text = "Minutes remaining: ${reading.minutesRemaining}")
        Text(text = "Remaining Ah: ${reading.remainingAh.format(3)}")
        Text(text = "Capacity Ah: ${reading.capacityAh.format(1)}")
        Text(text = "SoC: ${reading.socPercent.format(1)}%")
        Text(text = "Charge: ${reading.chargeKwh.format(3)} kWh")
        Text(text = "Discharge: ${reading.dischargeKwh.format(3)} kWh")
    }
}

private fun Double.format(decimals: Int): String = "%.${decimals}f".format(this)

private fun Boolean.yesNo(): String = if (this) "yes" else "no"
