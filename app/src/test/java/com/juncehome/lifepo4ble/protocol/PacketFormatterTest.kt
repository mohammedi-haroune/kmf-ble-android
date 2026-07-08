package com.juncehome.lifepo4ble.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class PacketFormatterTest {
    @Test
    fun formatsBytesForLogDisplay() {
        val entry = PacketFormatter.toEntry(
            timestampMs = 123L,
            direction = PacketDirection.INBOUND,
            bytes = byteArrayOf(0x41, 0x42, 0x00, 0x10),
        )

        assertEquals("41 42 00 10", entry.hex)
        assertEquals("AB..", entry.ascii)
        assertEquals(4, entry.length)
    }
}
