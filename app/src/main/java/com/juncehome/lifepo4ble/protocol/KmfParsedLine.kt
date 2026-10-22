package com.juncehome.lifepo4ble.protocol

/**
 * One complete meter line as [KmfLineParser] considered it, without terminators.
 * [frame] is what the parser decoded from it, or null when the line was ignored.
 */
data class KmfParsedLine(
    val text: String,
    val frame: KmfFrame?,
)
