package com.juncehome.lifepo4ble.collection

data class CompanionPresenceAssociation(
    val id: Int,
    val address: String?,
)

object AutonomousRestorePolicy {
    const val MIN_AUTONOMOUS_WAKE_API = 31

    fun shouldRestore(
        configuration: CollectionConfiguration?,
        apiLevel: Int,
    ): Boolean = configuration?.autonomousEnabled == true && apiLevel >= MIN_AUTONOMOUS_WAKE_API

    fun shouldForwardApi36PresenceEvent(
        configuration: CollectionConfiguration?,
        eventAssociationId: Int,
        associations: Iterable<CompanionPresenceAssociation>,
    ): Boolean {
        val address = configuration?.snapshot?.address ?: return false
        val expectedAssociationId = associations
            .firstOrNull { association -> association.address.equals(address, ignoreCase = true) }
            ?.id
        return configuration.autonomousEnabled && eventAssociationId == expectedAssociationId
    }
}
