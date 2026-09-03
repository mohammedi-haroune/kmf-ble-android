package com.juncehome.lifepo4ble.collection

object AutonomousRestorePolicy {
    const val MIN_AUTONOMOUS_WAKE_API = 31

    fun shouldRestore(
        configuration: CollectionConfiguration?,
        apiLevel: Int,
    ): Boolean = configuration?.autonomousEnabled == true && apiLevel >= MIN_AUTONOMOUS_WAKE_API
}
