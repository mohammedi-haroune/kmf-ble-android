package com.juncehome.lifepo4ble.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals(listOf(1326, 1080, 0, 5793), frame.rawFields)
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
    fun preservesRawFieldsForCFrames() {
        val parser = KmfLineParser()

        val frame = parser.offer(":C=1820,486,0,0,0,0,".encodeToByteArray()).single() as KmfFrame.C

        assertEquals(listOf(1820, 486, 0, 0, 0, 0), frame.rawFields)
    }

    @Test
    fun discardsOversizedLineWithoutThrowing() {
        val parser = KmfLineParser(maxLineChars = 200)

        val frames = parser.offer(("A=" + "1".repeat(250) + "\n").encodeToByteArray())

        assertTrue(frames.isEmpty())
    }

    @Test
    fun offerExposesTerminatedLines() {
        val parser = KmfLineParser()

        parser.offer(":A=1331,5200,1,2380,152340,1800,\r\n:B=1,0,0,0,\r\n".encodeToByteArray())

        assertEquals(
            listOf(":A=1331,5200,1,2380,152340,1800,", ":B=1,0,0,0,"),
            parser.lastOfferLines.map { it.text },
        )
        assertTrue(parser.lastOfferLines[0].frame is KmfFrame.A)
        assertTrue(parser.lastOfferLines[1].frame is KmfFrame.B)
    }

    @Test
    fun offerExposesSplitLineOnlyWhenComplete() {
        val parser = KmfLineParser()

        parser.offer(":A=1331,52".encodeToByteArray())
        assertTrue(parser.lastOfferLines.isEmpty())

        parser.offer("00,1,2380,152340,1800,\r\n".encodeToByteArray())
        assertEquals(
            listOf(":A=1331,5200,1,2380,152340,1800,"),
            parser.lastOfferLines.map { it.text },
        )
        assertTrue(parser.lastOfferLines.single().frame is KmfFrame.A)
    }

    @Test
    fun offerExposesUnterminatedFastPathLine() {
        val parser = KmfLineParser()

        parser.offer(":C=41200,38900,".encodeToByteArray())

        val line = parser.lastOfferLines.single()
        assertEquals(":C=41200,38900,", line.text)
        assertTrue(line.frame is KmfFrame.C)
    }

    @Test
    fun offerExposesIgnoredLinesWithNullFrame() {
        val parser = KmfLineParser()

        parser.offer(":X=1,2\r\n".encodeToByteArray())

        assertEquals(listOf(KmfParsedLine(":X=1,2", null)), parser.lastOfferLines)
        assertNull(parser.lastOfferLines.single().frame)
    }

    @Test
    fun offerSkipsEmptyLines() {
        val parser = KmfLineParser()

        parser.offer("\r\n\r\n".encodeToByteArray())

        assertTrue(parser.lastOfferLines.isEmpty())
    }

    @Test
    fun offerExposesFlushedBufferedLineBeforeTheNewOne() {
        val parser = KmfLineParser()

        parser.offer(":A=1331,52".encodeToByteArray())
        parser.offer(":B=1,0,0,0,".encodeToByteArray())

        assertEquals(listOf(":A=1331,52", ":B=1,0,0,0,"), parser.lastOfferLines.map { it.text })
        assertNull(parser.lastOfferLines[0].frame)
        assertTrue(parser.lastOfferLines[1].frame is KmfFrame.B)
    }

    @Test
    fun offerOmitsDiscardedOversizedLine() {
        val parser = KmfLineParser(maxLineChars = 10)

        parser.offer("0123456789012345\r\n:B=1,\r\n".encodeToByteArray())

        assertEquals(listOf(":B=1,"), parser.lastOfferLines.map { it.text })
    }

    @Test
    fun offerKeepsReturnValueAndLastOfferFramesInStepWithLines() {
        val parser = KmfLineParser()

        val frames = parser.offer(":A=1331,5200,1,2380,152340,1800,\r\n:X=1,2\r\n:B=1,0,0,0,\r\n".encodeToByteArray())

        assertEquals(2, frames.size)
        assertEquals(frames, parser.lastOfferFrames)
        assertEquals(frames, parser.lastOfferLines.mapNotNull { it.frame })
        assertEquals(3, parser.lastOfferLines.size)
    }
}
