package com.juncehome.lifepo4ble.collection

import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.DeviceNotAssociatedException
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper

interface CompanionAssociationGateway {
    fun isSupported(): Boolean

    fun requestAssociation(
        address: String,
        onPending: (IntentSender) -> Unit,
        onFailure: (Throwable) -> Unit,
    )

    fun associationIdFromResult(resultData: Intent): Int?

    fun startPresenceObservation(address: String)

    fun stopPresenceObservation(address: String)
}

object CompanionAssociationPolicy {
    fun canConfigure(apiLevel: Int, hasFeature: Boolean): Boolean = apiLevel >= 31 && hasFeature
}

/**
 * Signals that Android no longer recognizes the saved association, so collection setup must be
 * completed again. Callers must not replace this with an app-owned BLE scan.
 */
class CompanionReSetupRequiredException(
    address: String,
    cause: DeviceNotAssociatedException? = null,
) : IllegalStateException("Companion association for $address must be set up again", cause)

class CompanionAssociationUnsupportedException : UnsupportedOperationException(
    "Companion device setup is unavailable on this device",
)

class AndroidCompanionAssociationGateway(
    context: Context,
) : CompanionAssociationGateway {
    private val companionDeviceManager = requireNotNull(
        context.getSystemService(CompanionDeviceManager::class.java),
    )
    private val packageManager = context.packageManager
    private val callbackHandler = Handler(Looper.getMainLooper())

    override fun isSupported(): Boolean = CompanionAssociationPolicy.canConfigure(
        apiLevel = Build.VERSION.SDK_INT,
        hasFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP),
    )

    override fun requestAssociation(
        address: String,
        onPending: (IntentSender) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        if (!isSupported()) {
            onFailure(CompanionAssociationUnsupportedException())
            return
        }

        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(address).build())
            .setSingleDevice(true)
            .build()

        try {
            companionDeviceManager.associate(
                request,
                object : CompanionDeviceManager.Callback() {
                    override fun onAssociationPending(intentSender: IntentSender) {
                        onPending(intentSender)
                    }

                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onDeviceFound(intentSender: IntentSender) {
                        onPending(intentSender)
                    }

                    override fun onFailure(error: CharSequence?) {
                        onFailure(IllegalStateException(error?.toString() ?: "Companion association failed"))
                    }
                },
                callbackHandler,
            )
        } catch (error: Throwable) {
            onFailure(error)
        }
    }

    override fun associationIdFromResult(resultData: Intent): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return null
        }
        return resultData
            .getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
            ?.id
    }

    override fun startPresenceObservation(address: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                companionDeviceManager.startObservingDevicePresence(api36ObservationRequest(address))
            } else {
                @Suppress("DEPRECATION")
                companionDeviceManager.startObservingDevicePresence(address)
            }
        } catch (error: DeviceNotAssociatedException) {
            throw CompanionReSetupRequiredException(address, error)
        }
    }

    override fun stopPresenceObservation(address: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                companionDeviceManager.stopObservingDevicePresence(api36ObservationRequest(address))
            } else {
                @Suppress("DEPRECATION")
                companionDeviceManager.stopObservingDevicePresence(address)
            }
        } catch (error: DeviceNotAssociatedException) {
            throw CompanionReSetupRequiredException(address, error)
        }
    }

    private fun api36ObservationRequest(address: String): ObservingDevicePresenceRequest {
        val associationId = companionDeviceManager.myAssociations
            .firstOrNull { association ->
                association.deviceMacAddress?.toString()?.equals(address, ignoreCase = true) == true
            }
            ?.id
            ?: throw CompanionReSetupRequiredException(address)
        return ObservingDevicePresenceRequest.Builder()
            .setAssociationId(associationId)
            .build()
    }
}
