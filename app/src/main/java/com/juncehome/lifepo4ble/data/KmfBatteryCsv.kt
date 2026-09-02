package com.juncehome.lifepo4ble.data

import java.time.Instant

object KmfBatteryCsv {
    private const val HEADER =
        "timestamp_iso8601,timestamp_ms,device_address,device_name,voltage_v,current_a,power_w," +
            "charging,minutes_remaining,remaining_ah,capacity_ah,soc_percent,charge_kwh," +
            "discharge_kwh,has_a_frame,has_b_frame,has_c_frame,raw_a_fields,raw_b_fields,raw_c_fields"

    fun render(samples: List<KmfBatterySampleEntity>): String =
        buildString {
            appendLine(HEADER)
            samples.forEach { sample ->
                append(sample.toCsvRow())
                append('\n')
            }
        }

    fun appendHeaderTo(output: Appendable) {
        output.append(HEADER)
        output.append('\n')
    }

    fun appendRowTo(output: Appendable, sample: KmfBatterySampleEntity) {
        output.append(sample.toCsvRow())
        output.append('\n')
    }

}

private fun KmfBatterySampleEntity.toCsvRow(): String =
    listOf(
        Instant.ofEpochMilli(timestampMs).toString(),
        timestampMs,
        deviceAddress,
        deviceName,
        voltageV,
        currentA,
        powerW,
        charging,
        minutesRemaining,
        remainingAh,
        capacityAh,
        socPercent,
        chargeKwh,
        dischargeKwh,
        hasAFrame,
        hasBFrame,
        hasCFrame,
        rawAFieldsCsv,
        rawBFieldsCsv,
        rawCFieldsCsv,
    ).joinToString(separator = ",") { value -> value.toCsvField() }

private fun Any?.toCsvField(): String {
    val text = toString().orEmpty()
    return if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"${text.replace("\"", "\"\"")}\""
    } else {
        text
    }
}
