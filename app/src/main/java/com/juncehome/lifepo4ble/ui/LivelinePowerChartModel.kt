package com.juncehome.lifepo4ble.ui

data class LivelinePowerChartPayload(
    val points: List<LivelinePowerChartPoint>,
    val latestValue: Double,
    val nativeSentAtMs: Long,
) {
    fun toJson(): String = buildString {
        append('{')
        append("\"points\":")
        append(points.joinToString(separator = ",", prefix = "[", postfix = "]") { point -> point.toJson() })
        append(",\"latestValue\":")
        append(latestValue.toJsonNumber())
        append(",\"nativeSentAtMs\":")
        append(nativeSentAtMs)
        append('}')
    }
}

data class LivelinePowerChartPoint(
    val time: Double,
    val value: Double,
) {
    fun toJson(): String = buildString {
        append('{')
        append("\"time\":")
        append(time)
        append(",\"value\":")
        append(value.toJsonNumber())
        append('}')
    }
}

fun LiveChartModel.toLivelinePowerChartPayload(
    nativeSentAtMs: Long = System.currentTimeMillis(),
): LivelinePowerChartPayload? {
    val renderedPoints = renderedPoints()
    if (renderedPoints.isEmpty()) {
        return null
    }

    return LivelinePowerChartPayload(
        points = renderedPoints.map { point ->
            LivelinePowerChartPoint(
                time = point.timestampMs / 1_000.0,
                value = point.y,
            )
        },
        latestValue = renderedPoints.last().y,
        nativeSentAtMs = nativeSentAtMs,
    )
}

private fun Double.toJsonNumber(): String =
    if (isFinite()) {
        if (this % 1.0 == 0.0) {
            toLong().toString()
        } else {
            toString()
        }
    } else {
        "null"
    }
