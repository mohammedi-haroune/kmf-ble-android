package com.juncehome.lifepo4ble.ble

import com.juncehome.lifepo4ble.data.DeviceSnapshot
import java.util.UUID

object GattProfileSelector {
    fun select(services: List<GattServiceInfo>, preferred: DeviceSnapshot?): GattProfile? {
        preferred?.toValidProfile(services)?.let { return it }

        return services
            .flatMap { service -> service.candidates() }
            .minWithOrNull(
                compareBy<ProfileCandidate> { it.rank }
                    .thenBy { it.characteristicCount }
                    .thenBy { it.profile.serviceUuid.toString() }
                    .thenBy { it.profile.notifyUuid.toString() }
                    .thenBy { it.profile.writeUuid.toString() }
            )
            ?.profile
    }

    private fun DeviceSnapshot.toValidProfile(services: List<GattServiceInfo>): GattProfile? {
        val serviceUuid = serviceUuid?.toUuidOrNull() ?: return null
        val notifyUuid = notifyUuid?.toUuidOrNull() ?: return null
        val writeUuid = writeUuid?.toUuidOrNull() ?: return null
        val service = services.firstOrNull { it.uuid == serviceUuid } ?: return null
        val notifyCharacteristic = service.characteristics.firstOrNull { it.uuid == notifyUuid }
            ?: return null
        val writeCharacteristic = service.characteristics.firstOrNull { it.uuid == writeUuid }
            ?: return null

        if (!notifyCharacteristic.canSubscribe || !writeCharacteristic.canWriteAny) {
            return null
        }

        return GattProfile(
            serviceUuid = serviceUuid,
            notifyUuid = notifyUuid,
            writeUuid = writeUuid,
            usesIndications = notifyCharacteristic.usesIndications(),
        )
    }

    private fun GattServiceInfo.candidates(): List<ProfileCandidate> {
        val subscribers = characteristics
            .filter { it.canSubscribe }
            .sortedWith(
                compareBy<GattCharacteristicInfo> { it.usesIndications() }
                    .thenBy { it.uuid.toString() }
            )
        val writers = characteristics
            .filter { it.canWriteAny }
            .sortedBy { it.uuid.toString() }

        val combined = subscribers
            .filter { it.canWriteAny }
            .map { characteristic ->
                ProfileCandidate(
                    profile = GattProfile(
                        serviceUuid = uuid,
                        notifyUuid = characteristic.uuid,
                        writeUuid = characteristic.uuid,
                        usesIndications = characteristic.usesIndications(),
                    ),
                    rank = 0,
                    characteristicCount = characteristics.size,
                )
            }

        val split = subscribers.flatMap { subscriber ->
            writers.map { writer ->
                ProfileCandidate(
                    profile = GattProfile(
                        serviceUuid = uuid,
                        notifyUuid = subscriber.uuid,
                        writeUuid = writer.uuid,
                        usesIndications = subscriber.usesIndications(),
                    ),
                    rank = 1,
                    characteristicCount = characteristics.size,
                )
            }
        }

        return combined + split
    }

    private fun GattCharacteristicInfo.usesIndications(): Boolean = !canNotify && canIndicate

    private fun String.toUuidOrNull(): UUID? = try {
        UUID.fromString(this)
    } catch (_: IllegalArgumentException) {
        null
    }

    private data class ProfileCandidate(
        val profile: GattProfile,
        val rank: Int,
        val characteristicCount: Int,
    )
}
