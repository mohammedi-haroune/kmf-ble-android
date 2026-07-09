package com.juncehome.lifepo4ble.ui

import com.juncehome.lifepo4ble.ble.BleEvent
import com.juncehome.lifepo4ble.ble.GattProfile
import com.juncehome.lifepo4ble.protocol.KmfLineParser
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleStateReducerTest {
    @Test
    fun notificationUpdatesLogAndReading() {
        val parser = KmfLineParser()
        val dataUuid = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val profile = GattProfile(
            serviceUuid = UUID.fromString("33333333-3333-3333-3333-333333333333"),
            notifyUuid = dataUuid,
            writeUuid = dataUuid,
            usesIndications = false,
        )
        val withProfile = BleStateReducer.reduce(
            state = BleUiState(),
            event = BleEvent.ServicesDiscovered(profile),
            parser = parser,
            nowMs = 1L,
        )

        val afterPacket = BleStateReducer.reduce(
            state = withProfile,
            event = BleEvent.NotificationReceived(
                dataUuid,
                ":A=1234,2500,1,120,2400,1000\n".encodeToByteArray(),
            ),
            parser = parser,
            nowMs = 2L,
        )

        assertEquals(profile.serviceUuid.toString(), afterPacket.serviceUuid)
        assertEquals(1, afterPacket.packetLog.size)
        assertEquals(12.34, afterPacket.latestReading.voltageV, 0.001)
        assertTrue(afterPacket.hasAFrame)
        assertFalse(afterPacket.hasBFrame)
    }

    @Test
    fun bFrameMarksBootstrapReadinessWithoutChangingReading() {
        val parser = KmfLineParser()
        val dataUuid = UUID.fromString("11111111-1111-1111-1111-111111111111")

        val afterPacket = BleStateReducer.reduce(
            state = BleUiState(),
            event = BleEvent.NotificationReceived(
                dataUuid,
                ":B=1226110,0,271158,\n".encodeToByteArray(),
            ),
            parser = parser,
            nowMs = 2L,
        )

        assertFalse(afterPacket.hasAFrame)
        assertTrue(afterPacket.hasBFrame)
        assertEquals(0.0, afterPacket.latestReading.capacityAh, 0.001)
    }
}
