package com.juncehome.lifepo4ble.collection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionServiceCommandTest {
    private val fakeController = FakeCollectionController()
    private val router = CollectionServiceCommandRouter(fakeController)

    @Test
    fun stopRoutesToControllerUserStop() {
        router.route(CollectionServiceCommand(action = CollectionServiceAction.STOP))

        assertEquals(1, fakeController.userStops)
    }

    @Test
    fun appearedAddressRoutesToController() {
        router.route(
            CollectionServiceCommand(
                action = CollectionServiceAction.DEVICE_APPEARED,
                address = ADDRESS,
            ),
        )

        assertEquals(listOf(ADDRESS), fakeController.appearedAddresses)
    }

    @Test
    fun disappearedAddressRoutesToController() {
        router.route(
            CollectionServiceCommand(
                action = CollectionServiceAction.DEVICE_DISAPPEARED,
                address = ADDRESS,
            ),
        )

        assertEquals(listOf(ADDRESS), fakeController.disappearedAddresses)
    }

    @Test
    fun visibilityExtraRoutesToController() {
        router.route(
            CollectionServiceCommand(
                action = CollectionServiceAction.VISIBILITY_CHANGED,
                visibility = CollectionVisibility.BACKGROUND,
            ),
        )

        assertEquals(listOf(CollectionVisibility.BACKGROUND), fakeController.visibilities)
    }

    private class FakeCollectionController : CollectionController {
        override val state: StateFlow<CollectionState> = MutableStateFlow(
            CollectionState(CollectionPhase.STOPPED, CollectionVisibility.FOREGROUND),
        )
        val appearedAddresses = mutableListOf<String>()
        val disappearedAddresses = mutableListOf<String>()
        val visibilities = mutableListOf<CollectionVisibility>()
        var userStops = 0

        override fun start(configuration: CollectionConfiguration) = Unit

        override fun setVisibility(visibility: CollectionVisibility) {
            visibilities += visibility
        }

        override fun onDeviceAppeared(address: String) {
            appearedAddresses += address
        }

        override fun onDeviceDisappeared(address: String) {
            disappearedAddresses += address
        }

        override fun stopByUser() {
            userStops += 1
        }

        override fun close() = Unit
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}
