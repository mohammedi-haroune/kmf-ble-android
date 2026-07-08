package com.juncehome.lifepo4ble.protocol

object PacketFormatter {
    fun toEntry(
        timestampMs: Long,
        direction: PacketDirection,
        bytes: ByteArray,
    ): FrameLogEntry {
        val unsignedBytes = bytes.map { it.toInt() and 0xFF }
        return FrameLogEntry(
            timestampMs = timestampMs,
            direction = direction,
            hex = unsignedBytes.joinToString(separator = " ") { "%02X".format(it) },
            ascii = unsignedBytes.joinToString(separator = "") { byte ->
                if (byte in PRINTABLE_ASCII_RANGE) byte.toChar().toString() else "."
            },
            length = bytes.size,
        )
    }

    private val PRINTABLE_ASCII_RANGE = 0x20..0x7E
}
