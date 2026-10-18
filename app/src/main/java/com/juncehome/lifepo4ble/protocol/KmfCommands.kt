package com.juncehome.lifepo4ble.protocol

/** KMF ASCII requests shared by the collection service and the Device tab test connection. */
object KmfCommands {
    /** `:*` startup request that drives the meter into producing both A and B frames. */
    fun bootstrapRequest(): ByteArray = byteArrayOf(0x3A, 0x2A)
}
