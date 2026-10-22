package com.juncehome.lifepo4ble.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the home phone's live feed server is doing, for the Device tab. */
data class SharingStatus(
    val running: Boolean,
    val port: Int,
    val addresses: List<String>,
    val viewers: Int,
    val error: String? = null,
)

/**
 * Bridges the service-owned live feed server status to the UI. The service publishes every change;
 * null means no service is running in this process, so nothing is shared.
 */
class SharingStatusRelay {
    private val mutableState = MutableStateFlow<SharingStatus?>(null)
    val state: StateFlow<SharingStatus?> = mutableState.asStateFlow()

    fun publish(status: SharingStatus?) {
        mutableState.value = status
    }
}
