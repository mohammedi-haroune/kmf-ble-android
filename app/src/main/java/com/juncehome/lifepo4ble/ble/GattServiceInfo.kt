package com.juncehome.lifepo4ble.ble

import java.util.UUID

data class GattServiceInfo(
    val uuid: UUID,
    val characteristics: List<GattCharacteristicInfo>,
)
