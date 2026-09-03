package com.juncehome.lifepo4ble.collection

import android.bluetooth.le.ScanFilter
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
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
import com.juncehome.lifepo4ble.AppLog

interface CompanionAssociationGateway {
    fun isSupported(): Boolean

    fun requestAssociation(
        address: String,
        onPending: (IntentSender) -> Unit,
        onFailure: (Throwable) -> Unit,
    )

    /**
     * Reads an association ID from successful activity-result data only. Callers must preserve the
     * selected address independently because Android exposes this ID only on API 33 and above.
     */
    fun associationIdFromResult(successfulResultData: Intent): Int?

    fun startPresenceObservation(address: String)

    fun stopPresenceObservation(address: String)
}

enum class CompanionAssociationTransport { BLUETOOTH_LE }

data class CompanionAssociationFilter(
    val transport: CompanionAssociationTransport,
    val address: String,
)

object CompanionAssociationPolicy {
    fun canConfigure(apiLevel: Int, hasFeature: Boolean): Boolean = apiLevel >= 31 && hasFeature

    fun filterFor(address: String): CompanionAssociationFilter = CompanionAssociationFilter(
        transport = CompanionAssociationTransport.BLUETOOTH_LE,
        address = address,
    )
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
            AppLog.w("CDM association request rejected locally: companion setup is unsupported")
            onFailure(CompanionAssociationUnsupportedException())
            return
        }

        val filter = CompanionAssociationPolicy.filterFor(address)
        val request = AssociationRequest.Builder()
            .addDeviceFilter(leDeviceFilter(filter))
            .setSingleDevice(true)
            .build()

        try {
            AppLog.d("CDM association request submitted: api=${Build.VERSION.SDK_INT}, filter=bluetooth-le-address")
            companionDeviceManager.associate(
                request,
                object : CompanionDeviceManager.Callback() {
                    override fun onAssociationPending(intentSender: IntentSender) {
                        AppLog.d("CDM association pending: received chooser IntentSender; forwarding to activity")
                        onPending(intentSender)
                    }

                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onDeviceFound(intentSender: IntentSender) {
                        AppLog.d("CDM association device found: received legacy IntentSender; forwarding to activity")
                        onPending(intentSender)
                    }

                    override fun onAssociationCreated(associationInfo: android.companion.AssociationInfo) {
                        AppLog.d("CDM association created: id=${associationInfo.id}")
                    }

                    override fun onFailure(error: CharSequence?) {
                        val detail = error?.toString() ?: "Companion association failed"
                        AppLog.e("CDM association failed: detail=$detail")
                        onFailure(IllegalStateException(detail))
                    }

                    override fun onFailure(errorCode: Int, error: CharSequence?) {
                        val detail = error?.toString() ?: "Companion association failed"
                        AppLog.e("CDM association failed: code=$errorCode detail=$detail")
                        onFailure(IllegalStateException("Companion association failed (code=$errorCode): $detail"))
                    }
                },
                callbackHandler,
            )
        } catch (error: Throwable) {
            AppLog.e("CDM association request threw before callback", error)
            onFailure(error)
        }
    }

    override fun associationIdFromResult(successfulResultData: Intent): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return null
        }
        return successfulResultData
            .getParcelableExtra(
                CompanionDeviceManager.EXTRA_ASSOCIATION,
                android.companion.AssociationInfo::class.java,
            )
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

    private fun leDeviceFilter(filter: CompanionAssociationFilter): BluetoothLeDeviceFilter = when (
        filter.transport
    ) {
        CompanionAssociationTransport.BLUETOOTH_LE -> BluetoothLeDeviceFilter.Builder()
            .setScanFilter(ScanFilter.Builder().setDeviceAddress(filter.address).build())
            .build()
    }
}
