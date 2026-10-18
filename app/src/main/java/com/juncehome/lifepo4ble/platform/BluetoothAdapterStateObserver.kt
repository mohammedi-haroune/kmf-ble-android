package com.juncehome.lifepo4ble.platform

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.juncehome.lifepo4ble.AppLog

/**
 * Observes BluetoothAdapter.ACTION_STATE_CHANGED so the first-run "Bluetooth is off" screen reacts
 * while the app is visible. [onStateChanged] receives the new BluetoothAdapter.STATE_* value.
 */
class BluetoothAdapterStateObserver(
    private val context: Context,
    private val onStateChanged: (Int) -> Unit,
) {
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            val previous = intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, BluetoothAdapter.ERROR)
            AppLog.d("Bluetooth adapter state ${stateName(previous)} -> ${stateName(state)}", TAG)
            onStateChanged(state)
        }
    }

    fun register() {
        if (registered) return
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true
        AppLog.d("Bluetooth adapter state observer registered", TAG)
    }

    fun unregister() {
        if (!registered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (error: IllegalArgumentException) {
            AppLog.w("Bluetooth adapter state observer was not registered: ${error.message}", TAG)
        }
        registered = false
        AppLog.d("Bluetooth adapter state observer unregistered", TAG)
    }

    companion object {
        private const val TAG = "KMF-BLE"

        fun stateName(state: Int): String = when (state) {
            BluetoothAdapter.STATE_OFF -> "OFF"
            BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
            BluetoothAdapter.STATE_ON -> "ON"
            BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
            else -> "UNKNOWN($state)"
        }
    }
}
