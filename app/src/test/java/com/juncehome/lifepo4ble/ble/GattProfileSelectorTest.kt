package com.juncehome.lifepo4ble.ble

import com.juncehome.lifepo4ble.data.DeviceSnapshot
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GattProfileSelectorTest {
    @Test
    fun prefersPersistedProfileWhenStillValid() {
        val serviceUuid = uuid("33333333-3333-3333-3333-333333333333")
        val dataUuid = uuid("11111111-1111-1111-1111-111111111111")
        val service = GattServiceInfo(
            uuid = serviceUuid,
            characteristics = listOf(
                GattCharacteristicInfo(
                    uuid = dataUuid,
                    canNotify = true,
                    canIndicate = false,
                    canRead = true,
                    canWrite = true,
                    canWriteNoResponse = false,
                    hasClientConfigDescriptor = true,
                )
            )
        )
        val snapshot = DeviceSnapshot(
            address = "AA:BB:CC:DD:EE:FF",
            name = "KMF",
            serviceUuid = serviceUuid.toString(),
            notifyUuid = dataUuid.toString(),
            writeUuid = dataUuid.toString(),
        )

        val profile = GattProfileSelector.select(listOf(service), snapshot)

        assertEquals(serviceUuid, profile?.serviceUuid)
        assertEquals(dataUuid, profile?.notifyUuid)
        assertEquals(dataUuid, profile?.writeUuid)
    }

    @Test
    fun returnsNullWhenNoNotifyOrIndicateCharacteristicHasCccd() {
        val service = GattServiceInfo(
            uuid = uuid("33333333-3333-3333-3333-333333333333"),
            characteristics = listOf(
                GattCharacteristicInfo(
                    uuid = uuid("11111111-1111-1111-1111-111111111111"),
                    canNotify = true,
                    canIndicate = false,
                    canRead = false,
                    canWrite = true,
                    canWriteNoResponse = false,
                    hasClientConfigDescriptor = false,
                )
            )
        )

        assertNull(GattProfileSelector.select(listOf(service), preferred = null))
    }

    @Test
    fun prefersOneCharacteristicThatCanSubscribeAndWrite() {
        val serviceUuid = uuid("33333333-3333-3333-3333-333333333333")
        val notifyOnlyUuid = uuid("11111111-1111-1111-1111-111111111111")
        val writeOnlyUuid = uuid("22222222-2222-2222-2222-222222222222")
        val combinedUuid = uuid("00000000-0000-0000-0000-000000000001")
        val service = GattServiceInfo(
            uuid = serviceUuid,
            characteristics = listOf(
                characteristic(notifyOnlyUuid, canNotify = true, hasCccd = true),
                characteristic(writeOnlyUuid, canWrite = true),
                characteristic(combinedUuid, canNotify = true, canWriteNoResponse = true, hasCccd = true),
            )
        )

        val profile = GattProfileSelector.select(listOf(service), preferred = null)

        assertEquals(serviceUuid, profile?.serviceUuid)
        assertEquals(combinedUuid, profile?.notifyUuid)
        assertEquals(combinedUuid, profile?.writeUuid)
        assertFalse(profile?.usesIndications ?: true)
    }

    @Test
    fun choosesWritableCharacteristicFromSameServiceWhenSubscriberCannotWrite() {
        val serviceUuid = uuid("33333333-3333-3333-3333-333333333333")
        val notifyUuid = uuid("11111111-1111-1111-1111-111111111111")
        val writeUuid = uuid("22222222-2222-2222-2222-222222222222")
        val service = GattServiceInfo(
            uuid = serviceUuid,
            characteristics = listOf(
                characteristic(notifyUuid, canNotify = true, hasCccd = true),
                characteristic(writeUuid, canWrite = true),
            )
        )

        val profile = GattProfileSelector.select(listOf(service), preferred = null)

        assertEquals(serviceUuid, profile?.serviceUuid)
        assertEquals(notifyUuid, profile?.notifyUuid)
        assertEquals(writeUuid, profile?.writeUuid)
    }

    @Test
    fun breaksServiceTiesByFewestCharacteristicsThenUuidStringOrder() {
        val firstServiceUuid = uuid("22222222-2222-2222-2222-222222222222")
        val secondServiceUuid = uuid("33333333-3333-3333-3333-333333333333")
        val dataUuid = uuid("11111111-1111-1111-1111-111111111111")
        val services = listOf(
            GattServiceInfo(
                uuid = secondServiceUuid,
                characteristics = listOf(
                    characteristic(dataUuid, canNotify = true, canWrite = true, hasCccd = true),
                )
            ),
            GattServiceInfo(
                uuid = firstServiceUuid,
                characteristics = listOf(
                    characteristic(dataUuid, canNotify = true, canWrite = true, hasCccd = true),
                )
            ),
            GattServiceInfo(
                uuid = uuid("00000000-0000-0000-0000-000000000000"),
                characteristics = listOf(
                    characteristic(dataUuid, canNotify = true, canWrite = true, hasCccd = true),
                    characteristic(uuid("44444444-4444-4444-4444-444444444444"), canRead = true),
                )
            ),
        )

        val profile = GattProfileSelector.select(services, preferred = null)

        assertEquals(firstServiceUuid, profile?.serviceUuid)
    }

    @Test
    fun usesIndicationsWhenNotifyIsUnavailable() {
        val serviceUuid = uuid("33333333-3333-3333-3333-333333333333")
        val dataUuid = uuid("11111111-1111-1111-1111-111111111111")
        val service = GattServiceInfo(
            uuid = serviceUuid,
            characteristics = listOf(
                characteristic(dataUuid, canIndicate = true, canWrite = true, hasCccd = true),
            )
        )

        val profile = GattProfileSelector.select(listOf(service), preferred = null)

        assertEquals(dataUuid, profile?.notifyUuid)
        assertTrue(profile?.usesIndications ?: false)
    }

    private fun characteristic(
        uuid: UUID,
        canNotify: Boolean = false,
        canIndicate: Boolean = false,
        canRead: Boolean = false,
        canWrite: Boolean = false,
        canWriteNoResponse: Boolean = false,
        hasCccd: Boolean = false,
    ): GattCharacteristicInfo = GattCharacteristicInfo(
        uuid = uuid,
        canNotify = canNotify,
        canIndicate = canIndicate,
        canRead = canRead,
        canWrite = canWrite,
        canWriteNoResponse = canWriteNoResponse,
        hasClientConfigDescriptor = hasCccd,
    )

    private fun uuid(value: String): UUID = UUID.fromString(value)
}
