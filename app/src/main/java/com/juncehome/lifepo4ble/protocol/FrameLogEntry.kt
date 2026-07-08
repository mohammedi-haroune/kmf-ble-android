package com.juncehome.lifepo4ble.protocol

data class FrameLogEntry(
    val timestampMs: Long,
    val direction: PacketDirection,
    val hex: String,
    val ascii: String,
    val length: Int,
)
