package com.juncehome.lifepo4ble.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseTest {
    @Test
    fun eventFormatMatchesTheWireLayout() {
        assertEquals("event: frame\ndata: {\"a\":1}\n\n", SseFormat.event("frame", "{\"a\":1}"))
    }

    @Test
    fun newlinesInDataBecomeExtraDataLines() {
        assertEquals("event: x\ndata: a\ndata: b\n\n", SseFormat.event("x", "a\nb"))
        assertEquals("event: x\ndata: a\ndata: b\ndata: c\n\n", SseFormat.event("x", "a\r\nb\rc"))
    }

    @Test
    fun commentFormatIsColonSpaceText() {
        assertEquals(": ping\n\n", SseFormat.comment("ping"))
    }

    @Test
    fun eventAndReaderRoundTrip() {
        val reader = SseLineReader()
        val wire = SseFormat.event("frame", "{\"a\":1}")
        val lines = wire.removeSuffix("\n").split("\n")

        val events = lines.mapNotNull { reader.offer(it) }

        assertEquals(listOf(SseEvent("frame", "{\"a\":1}")), events)
    }

    @Test
    fun eventIsOnlyEmittedOnTheBlankLine() {
        val reader = SseLineReader()

        assertNull(reader.offer("event: frame"))
        assertNull(reader.offer("data: x"))
        assertEquals(SseEvent("frame", "x"), reader.offer(""))
        assertNull(reader.offer(""))
    }

    @Test
    fun readerJoinsMultipleDataLinesAndSkipsComments() {
        val reader = SseLineReader()
        val events = listOf(": ping", "", "event: x", "data: a", "data: b", "")
            .mapNotNull { reader.offer(it) }

        assertEquals(listOf(SseEvent("x", "a\nb")), events)
    }

    @Test
    fun readerDefaultsEventNameToMessage() {
        val reader = SseLineReader()
        val events = listOf("data: x", "").mapNotNull { reader.offer(it) }

        assertEquals(listOf(SseEvent("message", "x")), events)
    }

    @Test
    fun readerResetsEventNameBetweenEvents() {
        val reader = SseLineReader()
        val events = listOf("event: a", "data: 1", "", "data: 2", "").mapNotNull { reader.offer(it) }

        assertEquals(listOf(SseEvent("a", "1"), SseEvent("message", "2")), events)
    }

    @Test
    fun readerStripsOnlyOneLeadingSpaceAndHandlesFieldsWithoutColon() {
        val reader = SseLineReader()
        val events = listOf("data:  two", "data", "data:none", "").mapNotNull { reader.offer(it) }

        assertEquals(listOf(SseEvent("message", " two\n\nnone")), events)
    }

    @Test
    fun readerIgnoresUnknownFieldsAndDataFreeBlocks() {
        val reader = SseLineReader()
        val events = listOf("retry: 3000", "id: 7", "event: lonely", "", "data: kept", "")
            .mapNotNull { reader.offer(it) }

        assertEquals(listOf(SseEvent("message", "kept")), events)
    }
}
