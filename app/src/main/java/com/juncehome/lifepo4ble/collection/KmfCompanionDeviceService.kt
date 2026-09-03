package com.juncehome.lifepo4ble.collection

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.content.Intent
import android.os.Build
import com.juncehome.lifepo4ble.AppLog
import com.juncehome.lifepo4ble.BleApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class KmfCompanionDeviceService : CompanionDeviceService() {
    override fun onDeviceAppeared(address: String) {
        forward(CollectionServiceAction.DEVICE_APPEARED, address)
    }

    override fun onDeviceDisappeared(address: String) {
        forward(CollectionServiceAction.DEVICE_DISAPPEARED, address)
    }

    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        forward(CollectionServiceAction.DEVICE_APPEARED, associationInfo.deviceMacAddress.toString())
    }

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        forward(CollectionServiceAction.DEVICE_DISAPPEARED, associationInfo.deviceMacAddress.toString())
    }

    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED,
            DevicePresenceEvent.EVENT_BT_CONNECTED,
            -> forwardSavedAddress(CollectionServiceAction.DEVICE_APPEARED)

            DevicePresenceEvent.EVENT_BLE_DISAPPEARED,
            DevicePresenceEvent.EVENT_BT_DISCONNECTED,
            -> forwardSavedAddress(CollectionServiceAction.DEVICE_DISAPPEARED)

            else -> AppLog.d("companion ignored presence event=${event.event}", TAG)
        }
    }

    private fun forwardSavedAddress(action: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val configuration = (application as BleApplication).graph.collectionConfiguration.first()
            if (configuration?.autonomousEnabled == true) {
                forward(action, configuration.snapshot.address)
            } else {
                AppLog.d("companion ignored $action without autonomous configuration", TAG)
            }
        }
    }

    private fun forward(action: String, address: String) {
        AppLog.d("companion $action address=$address", TAG)
        val command = Intent(this, BatteryCollectionService::class.java)
            .setAction(action)
            .putExtra(CollectionServiceCommandRouter.EXTRA_ADDRESS, address)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(command)
        } else {
            startService(command)
        }
    }

    private companion object {
        const val TAG = "KMF-BLE"
    }
}
