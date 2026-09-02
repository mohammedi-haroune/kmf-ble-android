package com.juncehome.lifepo4ble.collection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionAssociationPolicyTest {
    @Test
    fun api31FeatureDeviceCanConfigure() {
        assertTrue(CompanionAssociationPolicy.canConfigure(31, true))
    }

    @Test
    fun api30DoesNotClaimAutonomousWake() {
        assertFalse(CompanionAssociationPolicy.canConfigure(30, true))
    }

    @Test
    fun missingFeatureDoesNotOfferSetup() {
        assertFalse(CompanionAssociationPolicy.canConfigure(35, false))
    }
}
