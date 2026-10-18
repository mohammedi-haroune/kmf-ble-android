package com.juncehome.lifepo4ble.ui.firstrun

/** Activity-owned first-run actions (permission prompts, system intents). */
interface FirstRunPlatformActions {
    fun requestBluetoothPermissions()

    fun requestNotificationPermission()

    fun requestEnableBluetooth()

    fun openAppSettings()

    object None : FirstRunPlatformActions {
        override fun requestBluetoothPermissions() = Unit

        override fun requestNotificationPermission() = Unit

        override fun requestEnableBluetooth() = Unit

        override fun openAppSettings() = Unit
    }
}
