package com.juncehome.lifepo4ble

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.juncehome.lifepo4ble.collection.CollectionVisibility
import com.juncehome.lifepo4ble.ui.TopLevelDestination
import com.juncehome.lifepo4ble.platform.BlePermissionPolicy
import com.juncehome.lifepo4ble.platform.BluetoothAdapterStateObserver
import com.juncehome.lifepo4ble.ui.firstrun.FirstRunNotice
import com.juncehome.lifepo4ble.ui.firstrun.FirstRunPlatformActions
import com.juncehome.lifepo4ble.ui.firstrun.FirstRunStep
import com.juncehome.lifepo4ble.ui.BleViewModel
import com.juncehome.lifepo4ble.ui.CompanionAssociationResultDiagnostics
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), FirstRunPlatformActions {
    private val viewModel: BleViewModel by lazy {
        ViewModelProvider(
            owner = this,
            factory = (application as BleApplication).graph.bleViewModelFactory,
        )[BleViewModel::class.java]
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        refreshReadiness()
    }

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        refreshReadiness()
        val denied = results.filterValues { granted -> !granted }.keys
        AppLog.d("FirstRun: Bluetooth permission result granted=${results.size - denied.size} denied=${denied.size}")
        if (denied.isEmpty()) {
            viewModel.clearFirstRunNotice()
            return@registerForActivityResult
        }
        val permanentlyDenied = denied.any { permission -> !shouldShowRequestPermissionRationale(permission) }
        viewModel.showFirstRunNotice(
            FirstRunNotice(
                step = FirstRunStep.NearbyDevices,
                message = if (permanentlyDenied) {
                    "Nearby devices access is blocked. Allow it in app settings."
                } else {
                    "Nearby devices access was not allowed."
                },
                offerAppSettings = permanentlyDenied,
            ),
        )
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        refreshReadiness()
        AppLog.d("FirstRun: notification permission result granted=$granted")
        if (granted) {
            viewModel.clearFirstRunNotice()
            return@registerForActivityResult
        }
        val permanentlyDenied = Build.VERSION.SDK_INT >= 33 &&
            !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        viewModel.showFirstRunNotice(
            FirstRunNotice(
                step = FirstRunStep.Notifications,
                message = if (permanentlyDenied) {
                    "Notifications are blocked. Allow them in app settings."
                } else {
                    "Notifications were not allowed."
                },
                offerAppSettings = permanentlyDenied,
            ),
        )
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        AppLog.d("FirstRun: enable Bluetooth result code=${result.resultCode}")
        refreshReadiness()
        if (result.resultCode != Activity.RESULT_OK && !viewModel.uiState.value.bluetoothEnabled) {
            viewModel.showFirstRunNotice(
                FirstRunNotice(step = FirstRunStep.BluetoothOff, message = "Bluetooth is still off."),
            )
        }
    }

    private val bluetoothStateObserver by lazy {
        BluetoothAdapterStateObserver(this) { refreshReadiness() }
    }

    private val associationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        AppLog.d(
            "CDM activity result: code=${result.resultCode}, hasData=${result.data != null}",
        )
        if (result.resultCode == Activity.RESULT_OK) {
            val gateway = (application as BleApplication).graph.companionAssociationGateway
            viewModel.onAssociationCompleted(gateway.associationIdFromResult(result.data ?: Intent()))
        } else {
            viewModel.onAssociationCanceled(
                CompanionAssociationResultDiagnostics.describe(result.resultCode, result.data != null),
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as BleApplication).filesDir.resolve("kmf-ble.log").appendText("I/KMF-BLE: MainActivity.onCreate\n")
        AppLog.d("MainActivity.onCreate", "KMF-BLE")
        refreshReadiness()
        lifecycleScope.launch {
            viewModel.associationIntentSenders.collect { intentSender ->
                AppLog.d("CDM activity launch: launching chooser IntentSender")
                associationLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
        }
        lifecycleScope.launch {
            viewModel.autonomousPermissionRequests.collect {
                requestBlePermissions()
            }
        }
        val startDestination = TopLevelDestination.fromRoute(
            intent?.getStringExtra(LaunchIntentExtras.EXTRA_START_DESTINATION),
        )
        setContent {
            val state = viewModel.uiState.collectAsStateWithLifecycle().value
            KmfBleApp(
                state = state,
                viewModel = viewModel,
                firstRunActions = this,
                startDestination = startDestination,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshReadiness()
        viewModel.onAppVisibilityChanged(CollectionVisibility.FOREGROUND)
    }

    override fun onStart() {
        super.onStart()
        bluetoothStateObserver.register()
        refreshReadiness()
        viewModel.onAppVisibilityChanged(CollectionVisibility.FOREGROUND)
    }

    override fun onStop() {
        bluetoothStateObserver.unregister()
        viewModel.onAppVisibilityChanged(CollectionVisibility.BACKGROUND)
        super.onStop()
    }

    private fun requestBlePermissions() {
        val permissions = BlePermissionPolicy.requiredRuntimePermissions(Build.VERSION.SDK_INT)
        permissionLauncher.launch(permissions.toTypedArray())
    }

    override fun requestBluetoothPermissions() {
        val permissions = BlePermissionPolicy.bluetoothPermissions(Build.VERSION.SDK_INT)
        AppLog.d("FirstRun: requesting Bluetooth permissions count=${permissions.size}")
        viewModel.clearFirstRunNotice()
        bluetoothPermissionLauncher.launch(permissions.toTypedArray())
    }

    override fun requestNotificationPermission() {
        val permission = BlePermissionPolicy.notificationPermission(Build.VERSION.SDK_INT)
        if (permission == null) {
            AppLog.d("FirstRun: notification permission not required on api=${Build.VERSION.SDK_INT}")
            refreshReadiness()
            return
        }
        AppLog.d("FirstRun: requesting notification permission")
        viewModel.clearFirstRunNotice()
        notificationPermissionLauncher.launch(permission)
    }

    override fun requestEnableBluetooth() {
        viewModel.clearFirstRunNotice()
        // ACTION_REQUEST_ENABLE needs BLUETOOTH_CONNECT on API 31+; before step 1 is granted we
        // fall back to the system Bluetooth settings screen.
        val canRequest = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        AppLog.d("FirstRun: requesting Bluetooth enable directRequest=$canRequest")
        try {
            if (canRequest) {
                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } else {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
        } catch (error: SecurityException) {
            AppLog.e("FirstRun: Bluetooth enable request rejected; opening settings", error)
            openBluetoothSettingsOrNotice()
        } catch (error: ActivityNotFoundException) {
            AppLog.e("FirstRun: Bluetooth enable activity missing; opening settings", error)
            openBluetoothSettingsOrNotice()
        }
    }

    override fun openAppSettings() {
        AppLog.d("FirstRun: opening app settings")
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
            )
        } catch (error: ActivityNotFoundException) {
            AppLog.e("FirstRun: app settings activity missing", error)
        }
    }

    private fun openBluetoothSettingsOrNotice() {
        try {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (error: ActivityNotFoundException) {
            AppLog.e("FirstRun: Bluetooth settings activity missing", error)
            viewModel.showFirstRunNotice(
                FirstRunNotice(
                    step = FirstRunStep.BluetoothOff,
                    message = "Open Android settings and turn on Bluetooth.",
                ),
            )
        }
    }

    private fun refreshReadiness() {
        val requiredPermissions = BlePermissionPolicy.requiredRuntimePermissions(Build.VERSION.SDK_INT)
        val grantedPermissions = requiredPermissions.filterTo(mutableSetOf()) { permission ->
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
        val permissionsGranted = grantedPermissions.containsAll(requiredPermissions)
        val hasBleFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter

        viewModel.updateReadiness(
            requiredPermissions = requiredPermissions,
            grantedPermissions = grantedPermissions,
            hasBleFeature = hasBleFeature,
            hasBluetoothAdapter = adapter != null,
            bluetoothEnabled = adapter.isEnabledSafely(permissionsGranted),
            bluetoothPermissions = BlePermissionPolicy.bluetoothPermissions(Build.VERSION.SDK_INT),
            notificationPermission = BlePermissionPolicy.notificationPermission(Build.VERSION.SDK_INT),
        )
    }

    /**
     * BluetoothAdapter.isEnabled needs no runtime permission, so it is read even before step 1 is
     * granted; the first-run "Bluetooth is off" screen depends on it. A SecurityException on an
     * unusual build falls back to "enabled" when permissions are missing so step 1 stays reachable.
     */
    private fun android.bluetooth.BluetoothAdapter?.isEnabledSafely(
        permissionsGranted: Boolean,
    ): Boolean {
        if (this == null) {
            return false
        }
        return try {
            isEnabled
        } catch (error: SecurityException) {
            AppLog.w("BluetoothAdapter.isEnabled threw SecurityException: ${error.message}")
            !permissionsGranted
        }
    }
}
