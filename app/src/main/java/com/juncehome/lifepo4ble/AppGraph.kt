package com.juncehome.lifepo4ble

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import com.juncehome.lifepo4ble.ble.AndroidBleScanner
import com.juncehome.lifepo4ble.ble.AndroidBleSession
import com.juncehome.lifepo4ble.ble.BleRepository
import com.juncehome.lifepo4ble.collection.AndroidCompanionAssociationGateway
import com.juncehome.lifepo4ble.collection.CompanionAssociationGateway
import com.juncehome.lifepo4ble.collection.CollectionController
import com.juncehome.lifepo4ble.collection.CollectionControllerImpl
import com.juncehome.lifepo4ble.collection.CollectionConfiguration
import com.juncehome.lifepo4ble.collection.CollectionStatusRelay
import com.juncehome.lifepo4ble.collection.AndroidCollectionServiceCommander
import com.juncehome.lifepo4ble.collection.AutonomousCollectionDisabler
import com.juncehome.lifepo4ble.data.DeviceStore
import com.juncehome.lifepo4ble.data.KmfDatabase
import com.juncehome.lifepo4ble.data.KmfBatteryHistoryExporter
import com.juncehome.lifepo4ble.data.KmfHistoryRepository
import com.juncehome.lifepo4ble.data.KmfTrendsRepository
import com.juncehome.lifepo4ble.ui.trends.TrendsViewModel
import com.juncehome.lifepo4ble.platform.BlePermissionPolicy
import com.juncehome.lifepo4ble.ui.BleViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import java.io.File

private val Context.deviceDataStore by preferencesDataStore(name = "device")

class AppGraph(
    private val application: Application,
) {
    val companionAssociationGateway: CompanionAssociationGateway by lazy {
        AndroidCompanionAssociationGateway(application)
    }

    /** Process-wide view of the service-owned controller state for the UI. */
    val collectionStatusRelay: CollectionStatusRelay by lazy { CollectionStatusRelay() }

    val collectionConfiguration: Flow<CollectionConfiguration?>
        get() = deviceStore.collectionConfiguration

    val autonomousCollectionDisabler: AutonomousCollectionDisabler by lazy {
        AutonomousCollectionDisabler(deviceStore, companionAssociationGateway)
    }

    private val bluetoothManager: BluetoothManager by lazy {
        requireNotNull(application.getSystemService(BluetoothManager::class.java))
    }

    private val deviceStore: DeviceStore by lazy {
        DeviceStore(application.deviceDataStore)
    }

    private val database: KmfDatabase by lazy {
        Room.databaseBuilder(
            application,
            KmfDatabase::class.java,
            DATABASE_NAME,
        ).build()
    }

    private val historyRepository by lazy {
        KmfHistoryRepository(
            frameEventDao = database.kmfFrameEventDao(),
            batterySampleDao = database.kmfBatterySampleDao(),
        )
    }

    private val batteryHistoryExporter by lazy {
        KmfBatteryHistoryExporter(
            batterySampleDao = database.kmfBatterySampleDao(),
            exportsDirectory = application.filesDir.resolve("exports"),
        )
    }

    private val scanner by lazy {
        AndroidBleScanner(
            context = application,
            hasScanPermission = ::hasRequiredBlePermissions,
        )
    }

    private val session by lazy {
        AndroidBleSession(
            context = application,
            bluetoothManager = bluetoothManager,
        )
    }

    private val repository by lazy {
        BleRepository(scanner, session)
    }

    fun createCollectionController(scope: CoroutineScope): CollectionController = CollectionControllerImpl(
        repository = repository,
        historyStore = historyRepository,
        scope = scope,
    )

    val bleViewModelFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(BleViewModel::class.java)) {
                return BleViewModel(
                    repository = repository,
                    deviceStore = deviceStore,
                    historyStore = historyRepository,
                    companionAssociationGateway = companionAssociationGateway,
                    collectionServiceCommander = AndroidCollectionServiceCommander(application),
                    autonomousCollectionDisabler = autonomousCollectionDisabler,
                    collectionStatus = collectionStatusRelay.state,
                ) as T
            }
            error("Unknown ViewModel class ${modelClass.name}")
        }
    }

    val trendsViewModelFactory: ViewModelProvider.Factory by lazy {
        TrendsViewModel.factory(
            store = KmfTrendsRepository(
                dao = database.kmfBatterySampleDao(),
                dbSizeBytes = {
                    val main = application.getDatabasePath(DATABASE_NAME)
                    listOf("", "-wal", "-shm").sumOf { File(main.path + it).length() }
                },
            ),
            exportStore = batteryHistoryExporter,
        )
    }

    private fun hasRequiredBlePermissions(): Boolean =
        BlePermissionPolicy.requiredRuntimePermissions(Build.VERSION.SDK_INT).all { permission ->
            application.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
}

private const val DATABASE_NAME = "kmf-history.db"
