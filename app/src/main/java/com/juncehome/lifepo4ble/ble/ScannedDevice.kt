package com.juncehome.lifepo4ble.ble

import java.util.UUID

data class ScannedDevice(
    val name: String?,
    val address: String,
    val rssi: Int,
    val serviceUuids: List<UUID>,
)
