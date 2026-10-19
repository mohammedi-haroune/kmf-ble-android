package com.juncehome.lifepo4ble.collection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionServiceCommandTest {
    private val fakeController = FakeCollectionController()
    private val router = CollectionServiceCommandRouter(
        controller = fakeController,
        autonomousCollectionStopper = FakeAutonomousCollectionStopper(mutableListOf()),
    )

    @Test
    fun stopRoutesToControllerUserStop() = runTest {
        router.route(CollectionServiceCommand(action = CollectionServiceAction.STOP))

        assertEquals(1, fakeController.userStops)
    }

    @Test
    fun notificationStopDurablyDisablesBeforeStoppingTheController() = runTest {
        val operationLog = mutableListOf<String>()
        val controller = FakeCollectionController(operationLog)
        val router = CollectionServiceCommandRouter(
            controller = controller,
            autonomousCollectionStopper = FakeAutonomousCollectionStopper(operationLog),
        )

        router.route(CollectionServiceCommand(action = CollectionServiceAction.STOP))

        assertEquals(listOf("disable", "controller-stop"), operationLog)
    }

    @Test
    fun appearedAddressRoutesToController() = runTest {
        router.route(
            CollectionServiceCommand(
                action = CollectionServiceAction.DEVICE_APPEARED,
                address = ADDRESS,
            ),
        )

        assertEquals(listOf(ADDRESS), fakeController.appearedAddresses)
    }

    @Test
    fun disappearedAddressRoutesToController() = runTest {
        router.route(
            CollectionServiceCommand(
                action = CollectionServiceAction.DEVICE_DISAPPEARED,
                address = ADDRESS,
            ),
        )

        assertEquals(listOf(ADDRESS), fakeController.disappearedAddresses)
    }

    @Test
    fun reconnectNowRoutesToController() = runTest {
        router.route(CollectionServiceCommand(action = CollectionServiceAction.RECONNECT_NOW))

        assertEquals(1, fakeController.reconnectNowCalls)
    }

    @Test
    fun visibilityExtraRoutesToController() = runTest {
        router.route(
            CollectionServiceCommand(
                action = CollectionServiceAction.VISIBILITY_CHANGED,
                visibility = CollectionVisibility.BACKGROUND,
            ),
        )

        assertEquals(listOf(CollectionVisibility.BACKGROUND), fakeController.visibilities)
    }

    private class FakeCollectionController(
        private val operationLog: MutableList<String>? = null,
    ) : CollectionController {
        override val state: StateFlow<CollectionState> = MutableStateFlow(
            CollectionState(CollectionPhase.STOPPED, CollectionVisibility.FOREGROUND),
        )
        val appearedAddresses = mutableListOf<String>()
        val disappearedAddresses = mutableListOf<String>()
        val visibilities = mutableListOf<CollectionVisibility>()
        var userStops = 0
        var reconnectNowCalls = 0

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

        override fun onBluetoothStateChanged(enabled: Boolean) = Unit

        override fun stopByUser() {
            userStops += 1
            operationLog?.add("controller-stop")
        }

        override fun reconnectNow() {
            reconnectNowCalls += 1
        }

        override fun close() = Unit
    }

    private class FakeAutonomousCollectionStopper(
        private val operationLog: MutableList<String>,
    ) : AutonomousCollectionStopper {
        override suspend fun disable() {
            operationLog += "disable"
        }
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:FF"
    }
}
