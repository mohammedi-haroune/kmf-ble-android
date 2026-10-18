package com.juncehome.lifepo4ble.collection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bridges the service-owned [CollectionController] state to the UI. The service publishes every
 * state change; the ViewModel observes [state]. Null means no service has published yet in this
 * process (for example right after the app starts and before the service runs).
 */
class CollectionStatusRelay {
    private val mutableState = MutableStateFlow<CollectionState?>(null)
    val state: StateFlow<CollectionState?> = mutableState.asStateFlow()

    fun publish(state: CollectionState) {
        mutableState.value = state
    }
}
