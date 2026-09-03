package com.juncehome.lifepo4ble

import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import com.juncehome.lifepo4ble.ui.BleViewModel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
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

    private val associationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val gateway = (application as BleApplication).graph.companionAssociationGateway
            viewModel.onAssociationCompleted(gateway.associationIdFromResult(result.data ?: Intent()))
        } else {
            viewModel.onAssociationCanceled()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as BleApplication).filesDir.resolve("kmf-ble.log").appendText("I/KMF-BLE: MainActivity.onCreate\n")
        AppLog.d("MainActivity.onCreate", "KMF-BLE")
        refreshReadiness()
        lifecycleScope.launch {
            viewModel.associationIntentSenders.collect { intentSender ->
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
                onRequestPermissions = ::requestBlePermissions,
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
        viewModel.onAppVisibilityChanged(CollectionVisibility.FOREGROUND)
    }

    override fun onStop() {
        viewModel.onAppVisibilityChanged(CollectionVisibility.BACKGROUND)
        super.onStop()
    }

    private fun requestBlePermissions() {
        val permissions = BlePermissionPolicy.requiredRuntimePermissions(Build.VERSION.SDK_INT)
        permissionLauncher.launch(permissions.toTypedArray())
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
            bluetoothEnabled = adapter.isEnabledWhenAllowed(permissionsGranted),
        )
    }

    private fun android.bluetooth.BluetoothAdapter?.isEnabledWhenAllowed(
        permissionsGranted: Boolean,
    ): Boolean {
        if (this == null || !permissionsGranted) {
            return false
        }
        return try {
            isEnabled
        } catch (_: SecurityException) {
            false
        }
    }
}
