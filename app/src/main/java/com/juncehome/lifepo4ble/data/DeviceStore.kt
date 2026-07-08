package com.juncehome.lifepo4ble.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DeviceStore(
    private val dataStore: DataStore<Preferences>,
) {
    val snapshot: Flow<DeviceSnapshot?> = dataStore.data.map { preferences ->
        val address = preferences[ADDRESS] ?: return@map null
        DeviceSnapshot(
            address = address,
            name = preferences[NAME],
            serviceUuid = preferences[SERVICE_UUID],
            notifyUuid = preferences[NOTIFY_UUID],
            writeUuid = preferences[WRITE_UUID],
        )
    }

    suspend fun save(snapshot: DeviceSnapshot) {
        dataStore.edit { preferences ->
            preferences[ADDRESS] = snapshot.address
            preferences.setOrRemove(NAME, snapshot.name)
            preferences.setOrRemove(SERVICE_UUID, snapshot.serviceUuid)
            preferences.setOrRemove(NOTIFY_UUID, snapshot.notifyUuid)
            preferences.setOrRemove(WRITE_UUID, snapshot.writeUuid)
        }
    }

    suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(ADDRESS)
            preferences.remove(NAME)
            preferences.remove(SERVICE_UUID)
            preferences.remove(NOTIFY_UUID)
            preferences.remove(WRITE_UUID)
        }
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
    }
}
