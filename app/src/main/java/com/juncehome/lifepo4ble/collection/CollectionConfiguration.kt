package com.juncehome.lifepo4ble.collection

import com.juncehome.lifepo4ble.data.DeviceSnapshot

data class CollectionConfiguration(
    val snapshot: DeviceSnapshot,
    /**
     * API 31-32 associations complete successfully without exposing an association ID in the
     * activity result. The saved address remains the authoritative identifier on those releases.
     */
    val associationId: Int?,
    val autonomousEnabled: Boolean,
)
