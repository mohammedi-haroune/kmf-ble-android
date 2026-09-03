package com.juncehome.lifepo4ble.collection

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionAssociationCallbackPolicyTest {
    @Test
    fun api36DuplicateFailureCallbacksReachApplicationOnlyOnce() {
        val failures = mutableListOf<String>()
        val policy = CompanionAssociationCallbackPolicy(
            onCreated = {},
            onFailure = { failures += it.message.orEmpty() },
        )

        policy.onFailure(IllegalStateException("coded failure"))
        policy.onFailure(IllegalStateException("generic failure"))

        assertEquals(listOf("coded failure"), failures)
    }
}
