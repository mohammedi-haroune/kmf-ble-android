package com.juncehome.lifepo4ble.ble

import java.util.UUID

data class GattCharacteristicInfo(
    val uuid: UUID,
    val canNotify: Boolean,
    val canIndicate: Boolean,
    val canRead: Boolean,
    val canWrite: Boolean,
    val canWriteNoResponse: Boolean,
    val hasClientConfigDescriptor: Boolean,
) {
    val canSubscribe: Boolean
        get() = (canNotify || canIndicate) && hasClientConfigDescriptor

    val canWriteAny: Boolean
        get() = canWrite || canWriteNoResponse
}
