package com.juncehome.lifepo4ble.collection

import com.juncehome.lifepo4ble.data.DeviceSnapshot

data class CollectionConfiguration(
    val snapshot: DeviceSnapshot,
    val associationId: Int,
    val autonomousEnabled: Boolean,
)
