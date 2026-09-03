package com.juncehome.lifepo4ble.collection

import com.juncehome.lifepo4ble.data.DeviceSnapshot
import com.juncehome.lifepo4ble.data.DeviceSnapshotStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AutonomousCollectionDisablerTest {
    @Test
    fun disableClearsSavedConfigurationBeforeFutureRestoreAndStopsPresence() = runTest {
        val configuration = CollectionConfiguration(snapshot(), associationId = 42, autonomousEnabled = true)
        val store = FakeStore(configuration)
        val gateway = FakeGateway { store.currentConfiguration }

        AutonomousCollectionDisabler(store, gateway).disable()

        assertNull(store.collectionConfiguration.first())
        assertFalse(AutonomousRestorePolicy.shouldRestore(store.collectionConfiguration.first(), apiLevel = 35))
        assertEquals(listOf(configuration.snapshot.address), gateway.stoppedAddresses)
        assertNull(gateway.configurationWhenPresenceStopped)
    }

    private class FakeStore(configuration: CollectionConfiguration?) : DeviceSnapshotStore {
        private val configurations = MutableStateFlow(configuration)

        override val snapshot: Flow<DeviceSnapshot?> = MutableStateFlow(configuration?.snapshot)
        override val collectionConfiguration: Flow<CollectionConfiguration?> = configurations

        val currentConfiguration: CollectionConfiguration?
            get() = configurations.value

        override suspend fun save(snapshot: DeviceSnapshot) = Unit

        override suspend fun enableAutonomousCollection(snapshot: DeviceSnapshot, associationId: Int?) = Unit

        override suspend fun disableAutonomousCollection() {
            configurations.value = null
        }

        override suspend fun clear() = Unit
    }

    private class FakeGateway(
        private val currentConfiguration: () -> CollectionConfiguration?,
    ) : CompanionAssociationGateway {
        val stoppedAddresses = mutableListOf<String>()
        var configurationWhenPresenceStopped: CollectionConfiguration? = null
            private set

        override fun isSupported(): Boolean = true

        override fun requestAssociation(
            address: String,
            onPending: (android.content.IntentSender) -> Unit,
            onAssociationCreated: (Int) -> Unit,
            onFailure: (Throwable) -> Unit,
        ) = Unit

        override fun associationIdFromResult(successfulResultData: android.content.Intent): Int? = null

        override fun startPresenceObservation(address: String) = Unit

        override fun stopPresenceObservation(address: String) {
            configurationWhenPresenceStopped = currentConfiguration()
            stoppedAddresses += address
        }
    }

    private fun snapshot() = DeviceSnapshot(
        address = "AA:BB:CC:DD:EE:FF",
        name = "KMF",
        serviceUuid = null,
        notifyUuid = null,
        writeUuid = null,
    )
}
