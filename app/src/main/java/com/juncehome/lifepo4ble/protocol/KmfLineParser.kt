package com.juncehome.lifepo4ble.protocol

import kotlin.math.abs

class KmfLineParser(
    private val maxLineChars: Int = 200,
) {
    private val buffer = StringBuilder()
    private var discardingOversizedLine = false

    fun offer(bytes: ByteArray): List<KmfFrame> {
        val text = String(bytes, Charsets.US_ASCII)
        val frames = mutableListOf<KmfFrame>()

        for (char in text) {
            when (char) {
                '\r', '\n' -> {
                    if (!discardingOversizedLine) {
                        parseLine(buffer.toString())?.let(frames::add)
                    }
                    buffer.clear()
                    discardingOversizedLine = false
                }
                else -> {
                    if (!discardingOversizedLine) {
                        buffer.append(char)
                        if (buffer.length > maxLineChars) {
                            buffer.clear()
                            discardingOversizedLine = true
                        }
                    }
                }
            }
        }

        return frames
    }

    private fun parseLine(line: String): KmfFrame? {
        val aIndex = line.indexOf(A_MARKER)
        val cIndex = line.indexOf(C_MARKER)
        return when {
            aIndex >= 0 && (cIndex < 0 || aIndex <= cIndex) -> parseA(line.substring(aIndex + A_MARKER.length))
            cIndex >= 0 -> parseC(line.substring(cIndex + C_MARKER.length))
            else -> null
        }
    }

    private fun parseA(payload: String): KmfFrame.A? {
        val fields = payload.integerFields()
        if (fields.size < A_FIELD_COUNT) return null

        val voltageRaw = fields[0]
        val currentMagnitudeRaw = abs(fields[1])
        val charging = fields[2] == 1
        val sign = if (charging) 1.0 else -1.0
        val remainingAh = fields[4] / 1000.0
        val capacityAh = fields[5] / 10.0
        val socPercent = if (capacityAh > 0.0) {
            (remainingAh / capacityAh * 100.0).coerceIn(0.0, 100.0)
        } else {
            0.0
        }

        return KmfFrame.A(
            voltageV = voltageRaw / 100.0,
            currentA = currentMagnitudeRaw / 1000.0 * sign,
            powerW = voltageRaw * currentMagnitudeRaw / 100000.0 * sign,
            charging = charging,
            minutesRemaining = fields[3],
            remainingAh = remainingAh,
            capacityAh = capacityAh,
            socPercent = socPercent,
            status = if (charging) "Charging" else "Discharging",
        )
    }

    private fun parseC(payload: String): KmfFrame.C? {
        val fields = payload.integerFields()
        if (fields.size < C_FIELD_COUNT) return null

        return KmfFrame.C(
            chargeKwh = fields[0] / 1000.0,
            dischargeKwh = fields[1] / 1000.0,
        )
    }

    private fun String.integerFields(): List<Int> =
        split(",").map { field -> field.trim().toIntOrNull() ?: return emptyList() }

    private companion object {
        const val A_MARKER = "A="
        const val C_MARKER = "C="
        const val A_FIELD_COUNT = 6
        const val C_FIELD_COUNT = 2
    }
}
