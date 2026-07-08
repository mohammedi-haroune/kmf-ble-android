package com.juncehome.lifepo4ble.ble

import java.util.UUID

data class GattProfile(
    val serviceUuid: UUID,
    val notifyUuid: UUID,
    val writeUuid: UUID,
    val usesIndications: Boolean,
)
