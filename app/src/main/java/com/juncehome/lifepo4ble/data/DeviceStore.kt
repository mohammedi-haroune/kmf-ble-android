package com.juncehome.lifepo4ble.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.juncehome.lifepo4ble.collection.CollectionConfiguration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface DeviceSnapshotStore {
    val snapshot: Flow<DeviceSnapshot?>
    val collectionConfiguration: Flow<CollectionConfiguration?>

    suspend fun save(snapshot: DeviceSnapshot)

    suspend fun enableAutonomousCollection(snapshot: DeviceSnapshot, associationId: Int?)

    suspend fun disableAutonomousCollection()

    suspend fun clear()
}

class DeviceStore(
    private val dataStore: DataStore<Preferences>,
) : DeviceSnapshotStore {
    override val snapshot: Flow<DeviceSnapshot?> = dataStore.data.map { preferences ->
        preferences.toSnapshot()
    }

    override val collectionConfiguration: Flow<CollectionConfiguration?> = dataStore.data.map { preferences ->
        val snapshot = preferences.toSnapshot() ?: return@map null
        val autonomousEnabled = preferences[AUTONOMOUS_ENABLED] ?: return@map null
        CollectionConfiguration(snapshot, preferences[ASSOCIATION_ID], autonomousEnabled)
    }

    override suspend fun save(snapshot: DeviceSnapshot) {
        dataStore.edit { preferences ->
            preferences.setSnapshot(snapshot)
        }
    }

    override suspend fun enableAutonomousCollection(snapshot: DeviceSnapshot, associationId: Int?) {
        dataStore.edit { preferences ->
            preferences.setSnapshot(snapshot)
            if (associationId == null) {
                preferences.remove(ASSOCIATION_ID)
            } else {
                preferences[ASSOCIATION_ID] = associationId
            }
            preferences[AUTONOMOUS_ENABLED] = true
        }
    }

    override suspend fun disableAutonomousCollection() {
        dataStore.edit { preferences ->
            preferences.remove(ASSOCIATION_ID)
            preferences.remove(AUTONOMOUS_ENABLED)
        }
    }

    override suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(ADDRESS)
            preferences.remove(NAME)
            preferences.remove(SERVICE_UUID)
            preferences.remove(NOTIFY_UUID)
            preferences.remove(WRITE_UUID)
            preferences.remove(ASSOCIATION_ID)
            preferences.remove(AUTONOMOUS_ENABLED)
        }
    }

    private fun Preferences.toSnapshot(): DeviceSnapshot? {
        val address = this[ADDRESS] ?: return null
        return DeviceSnapshot(
            address = address,
            name = this[NAME],
            serviceUuid = this[SERVICE_UUID],
            notifyUuid = this[NOTIFY_UUID],
            writeUuid = this[WRITE_UUID],
        )
    }

    private fun MutablePreferences.setSnapshot(snapshot: DeviceSnapshot) {
        this[ADDRESS] = snapshot.address
        setOrRemove(NAME, snapshot.name)
        setOrRemove(SERVICE_UUID, snapshot.serviceUuid)
        setOrRemove(NOTIFY_UUID, snapshot.notifyUuid)
        setOrRemove(WRITE_UUID, snapshot.writeUuid)
    }

    private fun MutablePreferences.setOrRemove(
        key: Preferences.Key<String>,
        value: String?,
    ) {
        if (value == null) {
            remove(key)
        } else {
            this[key] = value
        }
    }

    private companion object {
        val ADDRESS = stringPreferencesKey("device_address")
        val NAME = stringPreferencesKey("device_name")
        val SERVICE_UUID = stringPreferencesKey("service_uuid")
        val NOTIFY_UUID = stringPreferencesKey("notify_uuid")
        val WRITE_UUID = stringPreferencesKey("write_uuid")
        val ASSOCIATION_ID = intPreferencesKey("companion_association_id")
        val AUTONOMOUS_ENABLED = booleanPreferencesKey("autonomous_collection_enabled")
    }
}
