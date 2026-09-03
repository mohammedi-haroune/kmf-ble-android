package com.juncehome.lifepo4ble.collection

import com.juncehome.lifepo4ble.data.DeviceSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomousRestorePolicyTest {
    private val configuration = CollectionConfiguration(
        snapshot = DeviceSnapshot(
            address = "AA:BB:CC:DD:EE:FF",
            name = null,
            serviceUuid = null,
            notifyUuid = null,
            writeUuid = null,
        ),
        associationId = 42,
        autonomousEnabled = true,
    )

    @Test
    fun enabledApi31ConfigurationRestoresPresence() {
        assertTrue(AutonomousRestorePolicy.shouldRestore(configuration, 31))
    }

    @Test
    fun disabledConfigurationDoesNotRestorePresence() {
        assertFalse(
            AutonomousRestorePolicy.shouldRestore(
                configuration.copy(autonomousEnabled = false),
                35,
            ),
        )
    }

    @Test
    fun api30DoesNotClaimWake() {
        assertFalse(AutonomousRestorePolicy.shouldRestore(configuration, 30))
    }
}
