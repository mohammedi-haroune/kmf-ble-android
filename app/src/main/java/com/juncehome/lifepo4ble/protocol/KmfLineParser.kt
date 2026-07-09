package com.juncehome.lifepo4ble.protocol

import com.juncehome.lifepo4ble.AppLog
import kotlin.math.abs

class KmfLineParser(
    private val maxLineChars: Int = 200,
) {
    private val buffer = StringBuilder()
    private var discardingOversizedLine = false
    var lastOfferFrames: List<KmfFrame> = emptyList()
        private set

    private companion object {
        const val TAG = "KMF-BLE"
        const val A_MARKER = "A="
        const val B_MARKER = "B="
        const val C_MARKER = "C="
        const val A_FIELD_COUNT = 6
        const val B_FIELD_COUNT = 1
        const val C_FIELD_COUNT = 2
    }

    fun offer(bytes: ByteArray): List<KmfFrame> {
        val text = String(bytes, Charsets.US_ASCII)
        val frames = mutableListOf<KmfFrame>()
        AppLog.d("parser.offer bytes=${bytes.size} ascii=$text", TAG)
        val hasLineTerminator = text.any { it == '\r' || it == '\n' }

        if (!hasLineTerminator && buffer.isNotEmpty() && text.startsWithFrameMarker()) {
            flushBufferedLine(frames)
        }

        if (!hasLineTerminator && buffer.isEmpty()) {
            parseLine(text)?.let { frame ->
                AppLog.d("parser frame=${frame.describe()}", TAG)
                frames.add(frame)
                return finish(frames)
            }
            if (text.startsWithKnownFrameMarker()) {
                AppLog.d("parser buffering partial packet=$text", TAG)
                buffer.append(text)
                return finish(frames)
            }
            if (text.startsWithFrameMarker()) {
                AppLog.d("parser ignored standalone packet=$text", TAG)
                return finish(frames)
            }
        }

        for (char in text) {
            when (char) {
                '\r', '\n' -> {
                    if (!discardingOversizedLine) {
                        val line = buffer.toString()
                        AppLog.d("parser line=$line", TAG)
                        parseLine(line)?.let { frame ->
                            AppLog.d("parser frame=${frame.describe()}", TAG)
                            frames.add(frame)
                        } ?: AppLog.d("parser ignored line=$line", TAG)
                    }
                    buffer.clear()
                    discardingOversizedLine = false
                }
                else -> {
                    if (!discardingOversizedLine) {
                        buffer.append(char)
                        if (buffer.length > maxLineChars) {
                            AppLog.w("parser discarding oversized line length=${buffer.length}", TAG)
                            buffer.clear()
                            discardingOversizedLine = true
                        }
                    }
                }
            }
        }

        return finish(frames)
    }

    private fun flushBufferedLine(frames: MutableList<KmfFrame>) {
        val line = buffer.toString()
        if (line.isNotEmpty()) {
            AppLog.d("parser flushing buffered line=$line", TAG)
            parseLine(line)?.let { frame ->
                AppLog.d("parser frame=${frame.describe()}", TAG)
                frames.add(frame)
            } ?: AppLog.d("parser ignored buffered line=$line", TAG)
        }
        buffer.clear()
        discardingOversizedLine = false
    }

    private fun parseLine(line: String): KmfFrame? {
        val aIndex = line.indexOf(A_MARKER)
        val bIndex = line.indexOf(B_MARKER)
        val cIndex = line.indexOf(C_MARKER)
        return when {
            aIndex >= 0 && aIndex <= smallestPositiveIndex(bIndex, cIndex) ->
                parseA(line.substring(aIndex + A_MARKER.length))
            bIndex >= 0 && bIndex <= smallestPositiveIndex(aIndex, cIndex) ->
                parseB(line.substring(bIndex + B_MARKER.length))
            cIndex >= 0 -> parseC(line.substring(cIndex + C_MARKER.length))
            else -> null
        }
    }

    private fun parseA(payload: String): KmfFrame.A? {
        val fields = payload.integerFields()
        return when {
            fields.size >= 6 -> {
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

                KmfFrame.A(
                    voltageV = voltageRaw / 100.0,
                    currentA = currentMagnitudeRaw / 1000.0 * sign,
                    powerW = voltageRaw * currentMagnitudeRaw / 100000.0 * sign,
                    charging = charging,
                    minutesRemaining = fields[3],
                    remainingAh = remainingAh,
                    capacityAh = capacityAh,
                    socPercent = socPercent,
                    status = if (charging) "Charging" else "Discharging",
                    rawFields = fields,
                )
            }
            fields.size >= 4 && (fields.size == 4 || fields[4] in 0..1) -> {
                val voltageRaw = fields[0]
                val currentMagnitudeRaw = abs(fields[1])
                val charging = fields[2] == 1
                val sign = if (charging) 1.0 else -1.0
                val minutesRemaining = fields[3]
                val remainingAh = currentMagnitudeRaw / 1000.0 * minutesRemaining / 60.0

                KmfFrame.A(
                    voltageV = voltageRaw / 100.0,
                    currentA = currentMagnitudeRaw / 1000.0 * sign,
                    powerW = voltageRaw * currentMagnitudeRaw / 100000.0 * sign,
                    charging = charging,
                    minutesRemaining = minutesRemaining,
                    remainingAh = remainingAh,
                    capacityAh = 0.0,
                    socPercent = 0.0,
                    status = if (charging) "Charging" else "Discharging",
                    rawFields = fields,
                )
            }
            else -> null
        }
    }

    private fun parseC(payload: String): KmfFrame.C? {
        val fields = payload.integerFields()
        if (fields.size < C_FIELD_COUNT) return null

        return KmfFrame.C(
            chargeKwh = fields[0] / 1000.0,
            dischargeKwh = fields[1] / 1000.0,
            rawFields = fields,
        )
    }

    private fun parseB(payload: String): KmfFrame.B? {
        val fields = payload.integerFields()
        if (fields.size < B_FIELD_COUNT) return null
        return KmfFrame.B(fields)
    }

    private fun String.integerFields(): List<Int> =
        split(",")
            .map { field -> field.trim() }
            .dropLastWhile { it.isEmpty() }
            .map { field -> field.toIntOrNull() ?: run {
                AppLog.w("parser rejected non-integer field='$field'", TAG)
                return emptyList()
            } }

    private fun String.startsWithFrameMarker(): Boolean =
        startsWith(":A=") || startsWith(":B=") || startsWith(":C=")

    private fun String.startsWithKnownFrameMarker(): Boolean =
        startsWith(":A=") || startsWith(":B=") || startsWith(":C=")

    private fun smallestPositiveIndex(first: Int, second: Int): Int =
        listOf(first, second)
            .filter { it >= 0 }
            .minOrNull()
            ?: Int.MAX_VALUE

    private fun finish(frames: List<KmfFrame>): List<KmfFrame> {
        lastOfferFrames = frames.toList()
        return lastOfferFrames
    }
}

private fun KmfFrame.describe(): String =
    when (this) {
        is KmfFrame.A ->
            "A voltage=$voltageV current=$currentA power=$powerW charging=$charging " +
                "minutesRemaining=$minutesRemaining remainingAh=$remainingAh capacityAh=$capacityAh " +
                "soc=$socPercent status=$status"
        is KmfFrame.B -> "B fields=${fields.joinToString(separator = ",")}"
        is KmfFrame.C ->
            "C charge=$chargeKwh discharge=$dischargeKwh"
    }
