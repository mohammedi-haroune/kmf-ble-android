package com.juncehome.lifepo4ble.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeviceStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun savesAndRestoresTheLastConnectionSnapshot() = runTest {
        val file = temporaryFolder.newFile("device.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val store = DeviceStore(dataStore)
        val snapshot = DeviceSnapshot(
            address = "AA:BB:CC:DD:EE:FF",
            name = "KMF",
            serviceUuid = "33333333-3333-3333-3333-333333333333",
            notifyUuid = "11111111-1111-1111-1111-111111111111",
            writeUuid = "11111111-1111-1111-1111-111111111111",
        )

        store.save(snapshot)

        assertEquals(snapshot, store.snapshot.first())
    }

    @Test
    fun clearRemovesTheLastConnectionSnapshot() = runTest {
        val file = temporaryFolder.newFile("device.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val store = DeviceStore(dataStore)
        val snapshot = DeviceSnapshot(
            address = "AA:BB:CC:DD:EE:FF",
            name = null,
            serviceUuid = "33333333-3333-3333-3333-333333333333",
            notifyUuid = "11111111-1111-1111-1111-111111111111",
            writeUuid = "22222222-2222-2222-2222-222222222222",
        )

        store.save(snapshot)
        store.clear()

        assertNull(store.snapshot.first())
    }
}
