package com.juncehome.lifepo4ble.data

import com.juncehome.lifepo4ble.AppLog
import com.juncehome.lifepo4ble.protocol.KmfFrame
import com.juncehome.lifepo4ble.protocol.KmfReading
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface KmfHistoryStore {
    suspend fun resetSession(deviceAddress: String)

    suspend fun recordNotification(observation: KmfNotificationObservation)

    suspend fun recordWrite(observation: KmfWriteObservation)

    fun observeLatestBatterySample(deviceAddress: String): Flow<KmfBatterySampleEntity?>

    fun observeRecentBatterySamples(deviceAddress: String, limit: Int): Flow<List<KmfBatterySampleEntity>>

    fun observeRecentFrameEvents(deviceAddress: String, limit: Int): Flow<List<KmfFrameEventEntity>>
}

data class KmfNotificationObservation(
    val timestampMs: Long,
    val deviceAddress: String,
    val deviceName: String?,
    val serviceUuid: String?,
    val notifyUuid: String?,
    val writeUuid: String?,
    val connectionState: String,
    val bytes: ByteArray,
    val frames: List<KmfFrame>,
    val mergedReading: KmfReading,
)

data class KmfWriteObservation(
    val timestampMs: Long,
    val deviceAddress: String,
    val deviceName: String?,
    val serviceUuid: String?,
    val notifyUuid: String?,
    val writeUuid: String?,
    val bytes: ByteArray,
    val writeSuccess: Boolean,
    val error: String? = null,
)

class KmfHistoryRepository(
    private val frameEventDao: KmfFrameEventDao,
    private val batterySampleDao: KmfBatterySampleDao,
) : KmfHistoryStore {
    private val sessionStateByDevice = mutableMapOf<String, SessionFrameState>()
    private val sessionMutex = Mutex()

    override suspend fun resetSession(deviceAddress: String) {
        sessionMutex.withLock {
            sessionStateByDevice.remove(deviceAddress)
        }
    }

    override suspend fun recordNotification(observation: KmfNotificationObservation) {
        sessionMutex.withLock {
            if (observation.frames.isEmpty()) {
                return
            }

            val sessionState = sessionStateByDevice.getOrPut(observation.deviceAddress) { SessionFrameState() }
            observation.frames.forEach { frame ->
                sessionState.update(frame)
            }

            // Each connection starts from an all-zero reading, so a sample is only trustworthy once
            // A (live values), B (richer state) and C (energy totals) were all seen this session.
            if (!sessionState.isComplete) {
                AppLog.d(
                    "battery sample skipped until session is complete: " +
                        "A=${sessionState.hasAFrame} B=${sessionState.hasBFrame} C=${sessionState.hasCFrame}",
                    TAG,
                )
                return
            }

            batterySampleDao.insert(
                KmfBatterySampleEntity(
                    timestampMs = observation.timestampMs,
                    deviceAddress = observation.deviceAddress,
                    deviceName = observation.deviceName,
                    voltageV = observation.mergedReading.voltageV,
                    currentA = observation.mergedReading.currentA,
                    powerW = observation.mergedReading.powerW,
                    charging = observation.mergedReading.charging,
                    minutesRemaining = observation.mergedReading.minutesRemaining,
                    remainingAh = observation.mergedReading.remainingAh,
                    capacityAh = observation.mergedReading.capacityAh,
                    socPercent = observation.mergedReading.socPercent,
                    chargeKwh = observation.mergedReading.chargeKwh,
                    dischargeKwh = observation.mergedReading.dischargeKwh,
                    hasAFrame = sessionState.hasAFrame,
                    hasBFrame = sessionState.hasBFrame,
                    hasCFrame = sessionState.hasCFrame,
                    rawAFieldsCsv = sessionState.rawAFieldsCsv,
                    rawBFieldsCsv = sessionState.rawBFieldsCsv,
                    rawCFieldsCsv = sessionState.rawCFieldsCsv,
                    connectionState = observation.connectionState,
                    serviceUuid = observation.serviceUuid,
                    notifyUuid = observation.notifyUuid,
                    writeUuid = observation.writeUuid,
                )
            )
        }
    }

    override suspend fun recordWrite(observation: KmfWriteObservation) {
        AppLog.d("battery-only history skipped outbound write", TAG)
    }

    override fun observeLatestBatterySample(deviceAddress: String): Flow<KmfBatterySampleEntity?> =
        batterySampleDao.observeLatest(deviceAddress)

    override fun observeRecentBatterySamples(
        deviceAddress: String,
        limit: Int,
    ): Flow<List<KmfBatterySampleEntity>> =
        batterySampleDao.observeRecent(deviceAddress, limit)

    override fun observeRecentFrameEvents(
        deviceAddress: String,
        limit: Int,
    ): Flow<List<KmfFrameEventEntity>> =
        frameEventDao.observeRecent(deviceAddress, limit)

    private data class SessionFrameState(
        var hasAFrame: Boolean = false,
        var hasBFrame: Boolean = false,
        var hasCFrame: Boolean = false,
        var rawAFieldsCsv: String? = null,
        var rawBFieldsCsv: String? = null,
        var rawCFieldsCsv: String? = null,
    ) {
        val isComplete: Boolean
            get() = hasAFrame && hasBFrame && hasCFrame

        fun update(frame: KmfFrame) {
            when (frame) {
                is KmfFrame.A -> {
                    hasAFrame = true
                    rawAFieldsCsv = frame.rawFields.toCsv()
                }
                is KmfFrame.B -> {
                    hasBFrame = true
                    rawBFieldsCsv = frame.fields.toCsv()
                }
                is KmfFrame.C -> {
                    hasCFrame = true
                    rawCFieldsCsv = frame.rawFields.toCsv()
                }
            }
        }
    }

    private companion object {
        const val TAG = "KMF-BLE"
    }
}

private fun List<Int>.toCsv(): String? =
    takeIf { it.isNotEmpty() }?.joinToString(separator = ",")
