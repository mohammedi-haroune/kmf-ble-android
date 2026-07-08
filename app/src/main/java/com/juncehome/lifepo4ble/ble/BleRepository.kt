package com.juncehome.lifepo4ble.ble

import com.juncehome.lifepo4ble.data.DeviceSnapshot
import com.juncehome.lifepo4ble.AppLog
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
        AppLog.d("startScan()", "KMF-BLE")
        stopScan()
        scanJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            mutableEvents.emit(BleEvent.Scanning)
            scanner.scan()
                .catch { error ->
                    AppLog.e("scan failed", error, "KMF-BLE")
                    mutableEvents.emit(BleEvent.Error("BLE scan failed", error))
                }
                .collect { devices ->
                    AppLog.d("scan results count=${devices.size}", "KMF-BLE")
                    mutableEvents.emit(BleEvent.ScanResults(devices))
                }
        }
    }

    override fun stopScan() {
        AppLog.d("stopScan()", "KMF-BLE")
        scanJob?.cancel()
        scanJob = null
    }

    override fun connect(device: ScannedDevice, preferred: DeviceSnapshot?) {
        AppLog.d("connect(device=$device, preferred=${preferred?.serviceUuid ?: "none"})", "KMF-BLE")
        stopScan()
        connectionJob?.cancel()
        mutableEvents.tryEmit(BleEvent.Connecting(device))
        connectionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.connect(device, preferred)
                .catch { error ->
                    AppLog.e("connect flow failed", error, "KMF-BLE")
                    mutableEvents.emit(BleEvent.Error("BLE connection failed", error))
                }
                .collect { event ->
                    AppLog.d("session event=${event.javaClass.simpleName}", "KMF-BLE")
                    mutableEvents.emit(event)
                }
        }
    }

    override fun write(bytes: ByteArray): Boolean {
        AppLog.d("write(${bytes.size} bytes)", "KMF-BLE")
        return session.write(bytes)
    }

    override fun disconnect() {
        AppLog.d("disconnect()", "KMF-BLE")
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
