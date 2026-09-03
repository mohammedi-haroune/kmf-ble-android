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

    @Test
    fun matchingApi36AssociationForSavedAddressIsForwarded() {
        assertTrue(
            AutonomousRestorePolicy.shouldForwardApi36PresenceEvent(
                configuration = configuration,
                eventAssociationId = 42,
                associations = listOf(
                    CompanionPresenceAssociation(42, "AA:BB:CC:DD:EE:FF"),
                    CompanionPresenceAssociation(99, "11:22:33:44:55:66"),
                ),
            ),
        )
    }

    @Test
    fun mismatchedApi36AssociationIsIgnored() {
        assertFalse(
            AutonomousRestorePolicy.shouldForwardApi36PresenceEvent(
                configuration = configuration,
                eventAssociationId = 99,
                associations = listOf(
                    CompanionPresenceAssociation(42, "AA:BB:CC:DD:EE:FF"),
                    CompanionPresenceAssociation(99, "11:22:33:44:55:66"),
                ),
            ),
        )
    }
}
