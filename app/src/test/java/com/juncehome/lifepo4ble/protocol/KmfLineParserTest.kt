package com.juncehome.lifepo4ble.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KmfLineParserTest {
    @Test
    fun parsesAFrameAcrossNotificationBoundaries() {
        val parser = KmfLineParser()

        assertTrue(parser.offer(":A=1234,2500,1,120,2400".encodeToByteArray()).isEmpty())
        val frames = parser.offer(",1000\r\n".encodeToByteArray())

        val frame = frames.single() as KmfFrame.A
        assertEquals(12.34, frame.voltageV, 0.001)
        assertEquals(2.5, frame.currentA, 0.001)
        assertEquals(30.85, frame.powerW, 0.001)
        assertTrue(frame.charging)
        assertEquals(120, frame.minutesRemaining)
        assertEquals(2.4, frame.remainingAh, 0.001)
        assertEquals(100.0, frame.capacityAh, 0.001)
        assertEquals(2.4, frame.socPercent, 0.001)
    }

    @Test
    fun parsesCFrameAndKeepsLineParserNonFatal() {
        val parser = KmfLineParser()

        val frames = parser.offer("noise:C=12,34".encodeToByteArray())

        val frame = frames.single() as KmfFrame.C
        assertEquals(0.012, frame.chargeKwh, 0.001)
        assertEquals(0.034, frame.dischargeKwh, 0.001)
    }

    @Test
    fun parsesTrailingCommaFramesFromLiveMeterPackets() {
        val parser = KmfLineParser()

        val frame = parser.offer(":C=1820,441,0,0,0,0,".encodeToByteArray()).single() as KmfFrame.C
        assertEquals(1.820, frame.chargeKwh, 0.001)
        assertEquals(0.441, frame.dischargeKwh, 0.001)
    }

    @Test
    fun parsesLiveAFrameWithoutNewline() {
        val parser = KmfLineParser()

        val frames = parser.offer(":A=1332,80,0,80667,1".encodeToByteArray())

        val frame = frames.single() as KmfFrame.A
        assertEquals(13.32, frame.voltageV, 0.001)
        assertEquals(-0.08, frame.currentA, 0.001)
        assertEquals(-1.0656, frame.powerW, 0.001)
        assertEquals(false, frame.charging)
        assertEquals(80667, frame.minutesRemaining)
        assertEquals(107.556, frame.remainingAh, 0.001)
        assertEquals(0.0, frame.capacityAh, 0.001)
        assertEquals(0.0, frame.socPercent, 0.001)
    }

    @Test
    fun parsesBootstrapTriggeredAFrameWithTrailingComma() {
        val parser = KmfLineParser()

        val frames = parser.offer(":A=1326,1080,0,5793,".encodeToByteArray())

        val frame = frames.single() as KmfFrame.A
        assertEquals(13.26, frame.voltageV, 0.001)
        assertEquals(-1.08, frame.currentA, 0.001)
        assertEquals(-14.3208, frame.powerW, 0.001)
        assertEquals(false, frame.charging)
        assertEquals(5793, frame.minutesRemaining)
        assertEquals(104.274, frame.remainingAh, 0.001)
        assertEquals(0.0, frame.capacityAh, 0.001)
        assertEquals(0.0, frame.socPercent, 0.001)
    }

    @Test
    fun parsesBPacketAndKeepsLaterAAndCFramesValid() {
        val parser = KmfLineParser()

        val firstA = parser.offer(":A=1327,910,0,6865,1".encodeToByteArray()).single() as KmfFrame.A
        assertEquals(104.119, firstA.remainingAh, 0.001)

        val bFrame = parser.offer(":B=1226110,0,271158,".encodeToByteArray()).single() as KmfFrame.B
        assertEquals(listOf(1226110, 0, 271158), bFrame.fields)

        val cFrame = parser.offer(":C=1820,486,0,0,0,0,".encodeToByteArray()).single() as KmfFrame.C
        assertEquals(1.820, cFrame.chargeKwh, 0.001)
        assertEquals(0.486, cFrame.dischargeKwh, 0.001)

        val secondA = parser.offer(":A=1326,910,0,6865,1".encodeToByteArray()).single() as KmfFrame.A
        assertEquals(13.26, secondA.voltageV, 0.001)
        assertEquals(104.119, secondA.remainingAh, 0.001)
    }

    @Test
    fun discardsOversizedLineWithoutThrowing() {
        val parser = KmfLineParser(maxLineChars = 200)

        val frames = parser.offer(("A=" + "1".repeat(250) + "\n").encodeToByteArray())

        assertTrue(frames.isEmpty())
    }
}
