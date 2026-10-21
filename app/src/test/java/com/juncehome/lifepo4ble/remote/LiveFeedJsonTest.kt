package com.juncehome.lifepo4ble.remote

import com.juncehome.lifepo4ble.collection.CollectionPhase
import com.juncehome.lifepo4ble.protocol.KmfReading
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveFeedJsonTest {
    private val reading = KmfReading(
        voltageV = 13.31,
        currentA = 5.2,
        powerW = 69.21,
        charging = true,
        minutesRemaining = 2380,
        remainingAh = 152.34,
        capacityAh = 180.0,
        socPercent = 84.63,
        chargeKwh = 41.2,
        dischargeKwh = 38.9,
    )

    @Test
    fun frameEncodesSpecFieldNames() {
        val json = JSONObject(
            LiveFeedJson.encode(
                LiveFeedMessage.Frame(
                    ms = 1792591392481,
                    line = ":A=1331,5200,1,2380,152340,1800,",
                    reading = reading,
                    replay = true,
                ),
            ),
        )

        assertEquals(1792591392481, json.getLong("ms"))
        assertTrue(json.getBoolean("replay"))
        assertEquals(":A=1331,5200,1,2380,152340,1800,", json.getString("line"))
        val wireReading = json.getJSONObject("reading")
        assertEquals(84.63, wireReading.getDouble("socPercent"), 0.0)
        assertEquals(13.31, wireReading.getDouble("voltageV"), 0.0)
        assertEquals(5.2, wireReading.getDouble("currentA"), 0.0)
        assertEquals(69.21, wireReading.getDouble("powerW"), 0.0)
        assertTrue(wireReading.getBoolean("charging"))
        assertEquals(2380, wireReading.getInt("minutesRemaining"))
        assertEquals(152.34, wireReading.getDouble("remainingAh"), 0.0)
        assertEquals(180.0, wireReading.getDouble("capacityAh"), 0.0)
        assertEquals(41.2, wireReading.getDouble("chargeKwh"), 0.0)
        assertEquals(38.9, wireReading.getDouble("dischargeKwh"), 0.0)
        assertFalse(wireReading.has("status"))
    }

    @Test
    fun frameWithNullReadingEncodesJsonNull() {
        val json = JSONObject(
            LiveFeedJson.encode(LiveFeedMessage.Frame(ms = 5, line = ":B=1,", reading = null)),
        )

        assertTrue(json.has("reading"))
        assertTrue(json.isNull("reading"))
        assertFalse(json.has("replay"))
    }

    @Test
    fun encodedMessageIsOneLine() {
        val encoded = LiveFeedJson.encode(
            LiveFeedMessage.Frame(ms = 5, line = "a\nb\r\nc", reading = reading),
        )

        assertFalse(encoded.contains('\n'))
        assertFalse(encoded.contains('\r'))
    }

    @Test
    fun helloCarriesVersionClockNameAndPhase() {
        val json = JSONObject(
            LiveFeedJson.encode(
                LiveFeedMessage.Hello(ms = 1792591392600, meterName = "KMF-BT-5A3C", phase = CollectionPhase.COLLECTING),
            ),
        )

        assertEquals(1, json.getInt("v"))
        assertEquals(1792591392600, json.getLong("ms"))
        assertEquals("KMF-BT-5A3C", json.getJSONObject("meter").getString("name"))
        assertEquals("COLLECTING", json.getString("phase"))
    }

    @Test
    fun eventNamesMatchTheSpec() {
        assertEquals("hello", LiveFeedJson.eventName(LiveFeedMessage.Hello(1, "m", CollectionPhase.STOPPED)))
        assertEquals("frame", LiveFeedJson.eventName(LiveFeedMessage.Frame(1, ":C=", null)))
        assertEquals("link", LiveFeedJson.eventName(LiveFeedMessage.Link(1, CollectionPhase.STOPPED, null, null)))
    }

    @Test
    fun linkWritesJsonNullForAbsentErrorAndDelay() {
        val json = JSONObject(
            LiveFeedJson.encode(LiveFeedMessage.Link(ms = 9, phase = CollectionPhase.COLLECTING, error = null, nextReconnectInMs = null)),
        )

        assertEquals("COLLECTING", json.getString("phase"))
        assertTrue(json.has("error"))
        assertTrue(json.isNull("error"))
        assertTrue(json.has("nextReconnectInMs"))
        assertTrue(json.isNull("nextReconnectInMs"))
    }

    @Test
    fun roundTripEveryMessage() {
        val messages = listOf(
            LiveFeedMessage.Hello(ms = 1792591392600, meterName = "KMF-BT-5A3C", phase = CollectionPhase.COLLECTING),
            LiveFeedMessage.Frame(
                ms = 1792591392481,
                line = ":A=1331,5200,1,2380,152340,1800,",
                reading = reading,
                replay = true,
            ),
            LiveFeedMessage.Frame(ms = 1792591393012, line = ":B=1,0,0,0,", reading = null),
            LiveFeedMessage.Link(
                ms = 1792591400000,
                phase = CollectionPhase.ERROR,
                error = "GATT 8",
                nextReconnectInMs = 5000,
            ),
            LiveFeedMessage.Link(ms = 1792591401000, phase = CollectionPhase.COLLECTING, error = null, nextReconnectInMs = null),
        )

        for (message in messages) {
            assertEquals(message, LiveFeedJson.decode(LiveFeedJson.eventName(message), LiveFeedJson.encode(message)))
        }
    }

    @Test
    fun decodeIgnoresUnknownEventsAndFields() {
        assertNull(LiveFeedJson.decode("battery", "{}"))
        assertNull(LiveFeedJson.decode("frame", "not json"))
        assertEquals(
            LiveFeedMessage.Frame(1, ":B=1,", null),
            LiveFeedJson.decode("frame", """{"v":2,"ms":1,"line":":B=1,","reading":null,"extra":{"x":1}}"""),
        )
        assertNull(LiveFeedJson.decode("link", """{"ms":1,"phase":"WARP"}"""))
    }

    @Test
    fun decodeRejectsMessagesMissingRequiredFields() {
        assertNull(LiveFeedJson.decode("frame", """{"ms":1}"""))
        assertNull(LiveFeedJson.decode("frame", """{"line":":B=1,"}"""))
        assertNull(LiveFeedJson.decode("hello", """{"ms":1}"""))
        assertNull(LiveFeedJson.decode("link", """{"phase":"ERROR"}"""))
        assertNull(LiveFeedJson.decode("link", "[]"))
    }

    @Test
    fun decodeTreatsJsonNullErrorAsAbsent() {
        val message = LiveFeedJson.decode(
            "link",
            """{"ms":1,"phase":"CONNECTING","error":null,"nextReconnectInMs":null}""",
        )

        assertEquals(LiveFeedMessage.Link(1, CollectionPhase.CONNECTING, null, null), message)
    }

    @Test
    fun nowEncodesLastLinePerType() {
        val json = JSONObject(
            LiveFeedJson.encodeNow(
                LiveFeedNow(
                    ms = 1792591392600,
                    meterName = "KMF-BT-5A3C",
                    phase = CollectionPhase.COLLECTING,
                    reading = reading,
                    lines = mapOf('A' to ":A=1331,5200,1,2380,152340,1800,", 'B' to ":B=1,0,0,0,", 'C' to null),
                ),
            ),
        )

        assertEquals(1, json.getInt("v"))
        assertEquals(1792591392600, json.getLong("ms"))
        assertEquals("KMF-BT-5A3C", json.getJSONObject("meter").getString("name"))
        assertEquals("COLLECTING", json.getString("phase"))
        assertEquals(84.63, json.getJSONObject("reading").getDouble("socPercent"), 0.0)
        val lines = json.getJSONObject("lines")
        assertEquals(":A=1331,5200,1,2380,152340,1800,", lines.getString("A"))
        assertEquals(":B=1,0,0,0,", lines.getString("B"))
        assertTrue(lines.has("C"))
        assertTrue(lines.isNull("C"))
    }

    @Test
    fun nowWithoutSessionWritesNullReadingAndNullLines() {
        val json = JSONObject(
            LiveFeedJson.encodeNow(
                LiveFeedNow(ms = 1, meterName = "", phase = CollectionPhase.STOPPED, reading = null, lines = emptyMap()),
            ),
        )

        assertTrue(json.isNull("reading"))
        val lines = json.getJSONObject("lines")
        for (key in listOf("A", "B", "C")) {
            assertTrue(lines.has(key))
            assertTrue(lines.isNull(key))
        }
    }
}
