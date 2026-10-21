package com.juncehome.lifepo4ble.remote

import com.juncehome.lifepo4ble.collection.CollectionPhase
import com.juncehome.lifepo4ble.protocol.KmfReading
import org.json.JSONException
import org.json.JSONObject

/**
 * JSON codec for the live feed wire format. Encoding writes the spec's field names; decoding is tolerant: unknown
 * events and unknown fields are ignored, and anything malformed decodes to null so a newer home phone never breaks
 * an older carried phone.
 *
 * Absent values are checked with [JSONObject.isNull] / `opt` casts instead of `optString`, because Android's
 * `optString` turns a JSON null into the text "null".
 */
object LiveFeedJson {
    const val VERSION = 1

    private const val EVENT_HELLO = "hello"
    private const val EVENT_FRAME = "frame"
    private const val EVENT_LINK = "link"
    private val LINE_TYPES = listOf('A', 'B', 'C')

    fun eventName(message: LiveFeedMessage): String = when (message) {
        is LiveFeedMessage.Hello -> EVENT_HELLO
        is LiveFeedMessage.Frame -> EVENT_FRAME
        is LiveFeedMessage.Link -> EVENT_LINK
    }

    /** One-line JSON object for [message]; the event name comes from [eventName]. */
    fun encode(message: LiveFeedMessage): String {
        val json = JSONObject()
        when (message) {
            is LiveFeedMessage.Hello -> {
                json.put("v", VERSION)
                json.put("ms", message.ms)
                json.put("meter", meterJson(message.meterName))
                json.put("phase", message.phase.name)
            }

            is LiveFeedMessage.Frame -> {
                json.put("ms", message.ms)
                if (message.replay) json.put("replay", true)
                json.put("line", message.line)
                json.put("reading", message.reading?.let(::readingJson) ?: JSONObject.NULL)
            }

            is LiveFeedMessage.Link -> {
                json.put("ms", message.ms)
                json.put("phase", message.phase.name)
                json.put("error", message.error ?: JSONObject.NULL)
                json.put("nextReconnectInMs", message.nextReconnectInMs ?: JSONObject.NULL)
            }
        }
        return json.toString()
    }

    fun encodeNow(now: LiveFeedNow): String {
        val lines = JSONObject()
        for (type in LINE_TYPES) lines.put(type.toString(), now.lines[type] ?: JSONObject.NULL)
        return JSONObject()
            .put("v", VERSION)
            .put("meter", meterJson(now.meterName))
            .put("phase", now.phase.name)
            .put("ms", now.ms)
            .put("reading", now.reading?.let(::readingJson) ?: JSONObject.NULL)
            .put("lines", lines)
            .toString()
    }

    /** Null for an unknown [eventName], invalid JSON, a missing required field or an unknown phase. */
    fun decode(eventName: String, data: String): LiveFeedMessage? {
        val json = try {
            JSONObject(data)
        } catch (_: JSONException) {
            return null
        }
        return when (eventName) {
            EVENT_HELLO -> decodeHello(json)
            EVENT_FRAME -> decodeFrame(json)
            EVENT_LINK -> decodeLink(json)
            else -> null
        }
    }

    private fun decodeHello(json: JSONObject): LiveFeedMessage? {
        val ms = json.longOrNull("ms") ?: return null
        val phase = json.phaseOrNull() ?: return null
        val meterName = json.optJSONObject("meter")?.stringOrNull("name").orEmpty()
        return LiveFeedMessage.Hello(ms = ms, meterName = meterName, phase = phase)
    }

    private fun decodeFrame(json: JSONObject): LiveFeedMessage? {
        val ms = json.longOrNull("ms") ?: return null
        val line = json.stringOrNull("line") ?: return null
        return LiveFeedMessage.Frame(
            ms = ms,
            line = line,
            reading = json.optJSONObject("reading")?.let(::readingFrom),
            replay = json.opt("replay") == true,
        )
    }

    private fun decodeLink(json: JSONObject): LiveFeedMessage? {
        val ms = json.longOrNull("ms") ?: return null
        val phase = json.phaseOrNull() ?: return null
        return LiveFeedMessage.Link(
            ms = ms,
            phase = phase,
            error = json.stringOrNull("error"),
            nextReconnectInMs = json.longOrNull("nextReconnectInMs"),
        )
    }

    private fun meterJson(name: String): JSONObject = JSONObject().put("name", name)

    /** Wire reading: every [KmfReading] field except `status`, which is display text derived on the carried phone. */
    private fun readingJson(reading: KmfReading): JSONObject = JSONObject()
        .put("voltageV", reading.voltageV)
        .put("currentA", reading.currentA)
        .put("powerW", reading.powerW)
        .put("charging", reading.charging)
        .put("minutesRemaining", reading.minutesRemaining)
        .put("remainingAh", reading.remainingAh)
        .put("capacityAh", reading.capacityAh)
        .put("socPercent", reading.socPercent)
        .put("chargeKwh", reading.chargeKwh)
        .put("dischargeKwh", reading.dischargeKwh)

    private fun readingFrom(json: JSONObject): KmfReading {
        val defaults = KmfReading()
        return KmfReading(
            voltageV = json.doubleOrNull("voltageV") ?: defaults.voltageV,
            currentA = json.doubleOrNull("currentA") ?: defaults.currentA,
            powerW = json.doubleOrNull("powerW") ?: defaults.powerW,
            charging = json.opt("charging") == true,
            minutesRemaining = json.longOrNull("minutesRemaining")?.toInt() ?: defaults.minutesRemaining,
            remainingAh = json.doubleOrNull("remainingAh") ?: defaults.remainingAh,
            capacityAh = json.doubleOrNull("capacityAh") ?: defaults.capacityAh,
            socPercent = json.doubleOrNull("socPercent") ?: defaults.socPercent,
            status = "",
            chargeKwh = json.doubleOrNull("chargeKwh") ?: defaults.chargeKwh,
            dischargeKwh = json.doubleOrNull("dischargeKwh") ?: defaults.dischargeKwh,
        )
    }

    private fun JSONObject.stringOrNull(key: String): String? = opt(key) as? String

    private fun JSONObject.longOrNull(key: String): Long? = (opt(key) as? Number)?.toLong()

    private fun JSONObject.doubleOrNull(key: String): Double? = (opt(key) as? Number)?.toDouble()

    private fun JSONObject.phaseOrNull(): CollectionPhase? {
        val name = stringOrNull("phase") ?: return null
        return try {
            CollectionPhase.valueOf(name)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
