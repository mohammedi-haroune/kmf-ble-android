package com.juncehome.lifepo4ble.data

data class DeviceSnapshot(
    val address: String,
    val name: String?,
    val serviceUuid: String?,
    val notifyUuid: String?,
    val writeUuid: String?,
)
