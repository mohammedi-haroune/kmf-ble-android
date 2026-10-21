package com.juncehome.lifepo4ble.ui

import com.juncehome.lifepo4ble.ble.BleEvent
import com.juncehome.lifepo4ble.ble.GattProfile
import com.juncehome.lifepo4ble.ble.ScannedDevice
import com.juncehome.lifepo4ble.protocol.KmfLineParser
import com.juncehome.lifepo4ble.protocol.KmfReading
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun parsedFramesAreRecordedForTheDataLinkAndPrunedAfterTheWindow() {
        val parser = KmfLineParser()
        val uuid = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val old = BleUiState(inboundFrameTimesMs = listOf(1_000L, 50_000L))

        val partial = BleStateReducer.reduce(
            old,
            BleEvent.NotificationReceived(uuid, ":A=1234,25".encodeToByteArray()),
            parser,
            nowMs = 100_000L,
        )
        assertEquals(listOf(50_000L), partial.inboundFrameTimesMs)

        val complete = BleStateReducer.reduce(
            partial,
            BleEvent.NotificationReceived(uuid, "00,1,120,2400,1000\n:B=1\n".encodeToByteArray()),
            parser,
            nowMs = 101_000L,
        )
        assertEquals(listOf(50_000L, 101_000L, 101_000L), complete.inboundFrameTimesMs)
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

    @Test
    fun reconnectingToSameMeterKeepsLastReadingAndMergesPartialFramesOntoIt() {
        val parser = KmfLineParser()
        val uuid = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val meter = ScannedDevice(name = "KMF", address = "AA:BB:CC:DD:EE:FF", rssi = 0, serviceUuids = emptyList())
        val lastGood = KmfReading(
            voltageV = 13.5,
            currentA = -0.08,
            remainingAh = 160.8,
            capacityAh = 180.0,
            socPercent = 89.3,
            chargeKwh = 115.6,
            dischargeKwh = 114.1,
        )
        val before = BleUiState(selectedDevice = meter, latestReading = lastGood, lastReadingTimestampMs = 10L)

        val connecting = BleStateReducer.reduce(before, BleEvent.Connecting(meter), parser, nowMs = 20L)
        assertEquals(lastGood, connecting.latestReading)
        assertEquals(10L, connecting.lastReadingTimestampMs)
        assertFalse(connecting.hasAFrame)
        assertFalse(connecting.hasBFrame)

        // Lean A frame without capacity: live values update, capacity and totals stay.
        val afterLeanA = BleStateReducer.reduce(
            connecting,
            BleEvent.NotificationReceived(uuid, ":A=1352,90,0,5000\n".encodeToByteArray()),
            parser,
            nowMs = 21L,
        )
        assertEquals(13.52, afterLeanA.latestReading.voltageV, 0.001)
        assertEquals(180.0, afterLeanA.latestReading.capacityAh, 0.001)
        assertTrue(afterLeanA.latestReading.socPercent > 0.0)
        assertEquals(115.6, afterLeanA.latestReading.chargeKwh, 0.001)
    }

    @Test
    fun connectingToAnotherMeterClearsReadingAndTimestamp() {
        val parser = KmfLineParser()
        val old = ScannedDevice(name = "KMF", address = "AA:BB:CC:DD:EE:FF", rssi = 0, serviceUuids = emptyList())
        val other = old.copy(address = "11:22:33:44:55:66")
        val before = BleUiState(
            selectedDevice = old,
            latestReading = KmfReading(voltageV = 13.5, capacityAh = 180.0),
            lastReadingTimestampMs = 10L,
        )

        val connecting = BleStateReducer.reduce(before, BleEvent.Connecting(other), parser, nowMs = 20L)

        assertEquals(KmfReading(), connecting.latestReading)
        assertNull(connecting.lastReadingTimestampMs)
    }

    @Test
    fun bluetoothSetsTheReadingTimeEvenWithoutAKnownCapacity() {
        val parser = KmfLineParser()
        val uuid = UUID.fromString("11111111-1111-1111-1111-111111111111")

        val afterShortA = BleStateReducer.reduce(
            BleUiState(),
            BleEvent.NotificationReceived(uuid, ":A=1329,5200,0,1760,\r\n".encodeToByteArray()),
            parser,
            nowMs = 30L,
        )

        assertEquals(30L, afterShortA.lastReadingTimestampMs)
    }

    @Test
    fun holdingFirstValuesWithholdsTheReadingTimeButStillTracksFrames() {
        val parser = KmfLineParser()
        val uuid = UUID.fromString("11111111-1111-1111-1111-111111111111")

        val afterShortA = BleStateReducer.reduce(
            BleUiState(),
            BleEvent.NotificationReceived(uuid, ":A=1329,5200,0,1760,\r\n".encodeToByteArray()),
            parser,
            nowMs = 30L,
            holdFirstValuesUntilCapacityKnown = true,
        )
        assertNull(afterShortA.lastReadingTimestampMs)
        assertTrue(afterShortA.hasAFrame)
        assertEquals(listOf(30L), afterShortA.inboundFrameTimesMs)
        assertEquals(13.29, afterShortA.latestReading.voltageV, 0.001)

        val afterFullA = BleStateReducer.reduce(
            afterShortA,
            BleEvent.NotificationReceived(uuid, ":A=1331,5200,1,2380,152340,1800,\r\n".encodeToByteArray()),
            parser,
            nowMs = 40L,
            holdFirstValuesUntilCapacityKnown = true,
        )
        assertEquals(40L, afterFullA.lastReadingTimestampMs)
        assertEquals(180.0, afterFullA.latestReading.capacityAh, 0.001)
    }

    @Test
    fun holdingFirstValuesChangesNothingOnceValuesExist() {
        val parser = KmfLineParser()
        val uuid = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val before = BleUiState(
            latestReading = KmfReading(voltageV = 13.5, remainingAh = 160.8, capacityAh = 180.0, socPercent = 89.3),
            lastReadingTimestampMs = 10L,
        )

        val afterShortA = BleStateReducer.reduce(
            before,
            BleEvent.NotificationReceived(uuid, ":A=1352,90,0,5000\n".encodeToByteArray()),
            parser,
            nowMs = 21L,
            holdFirstValuesUntilCapacityKnown = true,
        )

        assertEquals(21L, afterShortA.lastReadingTimestampMs)
        assertEquals(180.0, afterShortA.latestReading.capacityAh, 0.001)
    }
}
