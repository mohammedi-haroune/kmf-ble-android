package com.juncehome.lifepo4ble.collection

import com.juncehome.lifepo4ble.AppLog
import com.juncehome.lifepo4ble.data.DeviceSnapshotStore
import kotlinx.coroutines.flow.first

/** Performs the durable half of an explicit autonomous-collection stop. */
interface AutonomousCollectionStopper {
    suspend fun disable()
}

class AutonomousCollectionDisabler(
    private val deviceStore: DeviceSnapshotStore,
    private val companionAssociationGateway: CompanionAssociationGateway,
) : AutonomousCollectionStopper {
    override suspend fun disable() {
        val configuration = deviceStore.collectionConfiguration.first()
        // Persist this before touching the system observer so every later start path rejects us.
        deviceStore.disableAutonomousCollection()
        configuration?.let {
            try {
                companionAssociationGateway.stopPresenceObservation(it.snapshot.address)
            } catch (error: Throwable) {
                AppLog.e("failed to stop companion presence observation", error, TAG)
            }
        }
    }

    private companion object {
        const val TAG = "KMF-BLE"
    }
}
