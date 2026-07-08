package com.juncehome.lifepo4ble.ble

import com.juncehome.lifepo4ble.data.DeviceSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

interface BleRepositoryContract {
    val events: SharedFlow<BleEvent>

    fun startScan()

    fun stopScan()

    fun connect(device: ScannedDevice, preferred: DeviceSnapshot?)

    fun write(bytes: ByteArray): Boolean

    fun disconnect()
}

class BleRepository(
    private val scanner: BleScanner,
    private val session: BleSession,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : BleRepositoryContract {
    private val mutableEvents = MutableSharedFlow<BleEvent>(
        replay = 0,
        extraBufferCapacity = 64,
    )

    override val events: SharedFlow<BleEvent> = mutableEvents.asSharedFlow()

    private var scanJob: Job? = null
    private var connectionJob: Job? = null

    override fun startScan() {
        stopScan()
        scanJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            mutableEvents.emit(BleEvent.Scanning)
            scanner.scan()
                .catch { error ->
                    mutableEvents.emit(BleEvent.Error("BLE scan failed", error))
                }
                .collect { devices ->
                    mutableEvents.emit(BleEvent.ScanResults(devices))
                }
        }
    }

    override fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }

    override fun connect(device: ScannedDevice, preferred: DeviceSnapshot?) {
        stopScan()
        connectionJob?.cancel()
        mutableEvents.tryEmit(BleEvent.Connecting(device))
        connectionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.connect(device, preferred)
                .catch { error ->
                    mutableEvents.emit(BleEvent.Error("BLE connection failed", error))
                }
                .collect { event ->
                    mutableEvents.emit(event)
                }
        }
    }

    override fun write(bytes: ByteArray): Boolean = session.write(bytes)

    override fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        session.disconnect()
        mutableEvents.tryEmit(BleEvent.Disconnected())
    }

    fun close() {
        disconnect()
        stopScan()
        scope.cancel()
    }
}
