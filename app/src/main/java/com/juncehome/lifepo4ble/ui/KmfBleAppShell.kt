package com.juncehome.lifepo4ble.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.juncehome.lifepo4ble.AppLog
import com.juncehome.lifepo4ble.BleApplication
import com.juncehome.lifepo4ble.ui.components.ConnectionPill
import com.juncehome.lifepo4ble.ui.components.KmfIcons
import com.juncehome.lifepo4ble.ui.components.KmfNavigationBar
import com.juncehome.lifepo4ble.ui.components.KmfNavigationBarItem
import com.juncehome.lifepo4ble.ui.components.rememberConnectionPill
import com.juncehome.lifepo4ble.ui.device.DeviceCallbacks
import com.juncehome.lifepo4ble.ui.device.DeviceRoute
import com.juncehome.lifepo4ble.ui.device.PermissionAction
import com.juncehome.lifepo4ble.ui.firstrun.FirstRunCallbacks
import com.juncehome.lifepo4ble.ui.firstrun.FirstRunPlatformActions
import com.juncehome.lifepo4ble.ui.firstrun.FirstRunResolver
import com.juncehome.lifepo4ble.ui.firstrun.FirstRunRoute
import com.juncehome.lifepo4ble.ui.firstrun.ShellContent
import com.juncehome.lifepo4ble.ui.now.NowBannerAction
import com.juncehome.lifepo4ble.ui.now.NowRoute
import com.juncehome.lifepo4ble.ui.theme.KmfTheme
import com.juncehome.lifepo4ble.ui.trends.TrendsMetric
import com.juncehome.lifepo4ble.ui.trends.TrendsRange
import com.juncehome.lifepo4ble.ui.trends.TrendsRoute
import com.juncehome.lifepo4ble.ui.trends.TrendsViewModel

/**
 * Top-level gate: first run replaces the tabs until setup is complete (docs/design/README.md
 * "Navigation"). The session latch keeps first run on screen through steps 4 and Done after setup
 * becomes complete mid-flow, and the Now tab "Set up" action reopens it.
 */
@Composable
fun KmfBleAppShell(
    state: BleUiState,
    viewModel: BleViewModel,
    firstRunActions: FirstRunPlatformActions = FirstRunPlatformActions.None,
    startDestination: TopLevelDestination = TopLevelDestination.startDestination,
) {
    var firstRunRequested by rememberSaveable { mutableStateOf(false) }
    val setupComplete = FirstRunResolver.setupComplete(state)
    val setupLoaded = state.setupStateLoaded
    LaunchedEffect(setupLoaded, setupComplete) {
        if (setupLoaded && !setupComplete && !firstRunRequested) {
            AppLog.d("FirstRun: setup incomplete; latching first run for this session")
            firstRunRequested = true
        }
    }
    val content = FirstRunResolver.shellContent(
        setupStateLoaded = setupLoaded,
        setupComplete = setupComplete,
        firstRunRequested = firstRunRequested || (setupLoaded && !setupComplete),
    )
    LaunchedEffect(content) {
        AppLog.d(
            "Shell: content=$content setupLoaded=$setupLoaded setupComplete=$setupComplete " +
                "meterSaved=${state.savedMeterAddress != null} associated=${state.companionAssociated} " +
                "configured=${state.autonomousCollectionConfigured} firstRunCompleted=${state.firstRunCompleted}",
        )
    }
    when (content) {
        ShellContent.Loading -> Box(
            modifier = Modifier
                .fillMaxSize()
                .background(KmfTheme.colors.background),
        )
        ShellContent.FirstRun -> {
            val notice by viewModel.firstRunNotice.collectAsStateWithLifecycle()
            FirstRunRoute(
                state = state,
                notice = notice,
                callbacks = FirstRunCallbacks(
                    onRequestBluetoothPermissions = firstRunActions::requestBluetoothPermissions,
                    onRequestNotificationPermission = firstRunActions::requestNotificationPermission,
                    onRequestEnableBluetooth = firstRunActions::requestEnableBluetooth,
                    onOpenAppSettings = firstRunActions::openAppSettings,
                    onStartScan = viewModel::startScan,
                    onStopScan = viewModel::stopScan,
                    onSelectMeter = viewModel::selectMeterForSetup,
                    onContinueApproval = viewModel::requestAutonomousCollection,
                    onChooseAnotherMeter = viewModel::chooseAnotherMeter,
                    onOpenDashboard = {
                        viewModel.finishFirstRun()
                        firstRunRequested = false
                    },
                    onClearNotice = viewModel::clearFirstRunNotice,
                ),
            )
        }
        ShellContent.Tabs -> KmfTabsShell(
            state = state,
            viewModel = viewModel,
            firstRunActions = firstRunActions,
            onOpenFirstRun = { source ->
                AppLog.d("FirstRun: reopened from $source")
                firstRunRequested = true
            },
            startDestination = startDestination,
        )
    }
}

/**
 * Three-tab shell (Now / Trends / Device): Now -> NowRoute, Trends -> TrendsRoute, Device ->
 * DeviceRoute. [TrendsViewModel] is created lazily on first visit to the Trends tab from
 * `AppGraph.trendsViewModelFactory` (reached through [BleApplication]).
 */
@Composable
private fun KmfTabsShell(
    state: BleUiState,
    viewModel: BleViewModel,
    firstRunActions: FirstRunPlatformActions,
    onOpenFirstRun: (source: String) -> Unit,
    startDestination: TopLevelDestination,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val connectionPill by rememberConnectionPill(state)
    val onPillAction: (NowBannerAction) -> Unit = { action ->
        AppLog.d("Shell: connection pill action=$action", "KMF-BLE")
        when (action) {
            NowBannerAction.RetryNow, NowBannerAction.Retry -> viewModel.retryCollectionNow()
            NowBannerAction.Start -> viewModel.startCollectionFromNow()
            NowBannerAction.SetUp -> onOpenFirstRun("connection pill Set up")
        }
    }
    val recentBatterySamples by viewModel.recentBatterySamples.collectAsStateWithLifecycle()
    // Set by the Now power chart tap; consumed by the Trends tab to show Live + Power.
    var trendsLiveRequested by rememberSaveable { mutableStateOf(false) }

    val currentRoute = currentDestination?.route
    LaunchedEffect(currentRoute) {
        if (currentRoute != null) {
            AppLog.d("Navigation: showing tab route=$currentRoute")
        }
    }

    Scaffold(
        containerColor = KmfTheme.colors.background,
        bottomBar = {
            KmfNavigationBar {
                TopLevelDestination.bottomBarItems.forEach { destination ->
                    val selected = currentDestination
                        ?.hierarchy
                        ?.any { it.route == destination.route } == true
                    KmfNavigationBarItem(
                        selected = selected,
                        icon = destination.icon,
                        label = destination.label,
                        onClick = {
                            AppLog.d("Navigation: tab tapped ${destination.route} (selected=$selected)")
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination.route,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(TopLevelDestination.Now.route) {
                NowRoute(
                    state = state,
                    samples = recentBatterySamples,
                    contentPadding = innerPadding,
                    onOpenDevice = {
                        navController.navigate(TopLevelDestination.Device.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onSetUp = { onOpenFirstRun("Now tab Set up") },
                    onRetryCollection = viewModel::retryCollectionNow,
                    onStartCollection = viewModel::startCollectionFromNow,
                    onOpenTrends = {
                        AppLog.d("Navigation: Now chart opening Trends live power")
                        trendsLiveRequested = true
                        navController.navigate(TopLevelDestination.Trends.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(TopLevelDestination.Trends.route) {
                val application = LocalContext.current.applicationContext as BleApplication
                val trendsViewModel: TrendsViewModel = viewModel(factory = application.graph.trendsViewModelFactory)
                LaunchedEffect(trendsLiveRequested) {
                    if (trendsLiveRequested) {
                        trendsViewModel.selectMetric(TrendsMetric.Power)
                        trendsViewModel.selectRange(TrendsRange.Live)
                        trendsLiveRequested = false
                    }
                }
                TrendsRoute(
                    viewModel = trendsViewModel,
                    contentPadding = innerPadding,
                    headerTrailing = {
                        ConnectionPill(pill = connectionPill, onAction = onPillAction, logSource = "Trends header")
                    },
                )
            }
            composable(TopLevelDestination.Device.route) {
                val notice by viewModel.firstRunNotice.collectAsStateWithLifecycle()
                DeviceRoute(
                    state = state,
                    notice = notice,
                    contentPadding = innerPadding,
                    headerTrailing = {
                        ConnectionPill(pill = connectionPill, onAction = onPillAction, logSource = "Device header")
                    },
                    callbacks = DeviceCallbacks(
                        onEnableCollection = viewModel::enableBackgroundCollection,
                        onDisableCollection = viewModel::disableBackgroundCollection,
                        onPairWithAndroid = viewModel::pairWithAndroid,
                        // No explicit first-run latch here: the gate switches to first run (Find
                        // meter) once the saved meter is cleared, which avoids flashing a stale
                        // "Done" step while the reset is still writing.
                        onChangeMeter = viewModel::changeMeter,
                        onForgetMeter = viewModel::forgetMeter,
                        onPermissionAction = { action ->
                            when (action) {
                                PermissionAction.EnableBluetooth -> firstRunActions.requestEnableBluetooth()
                                PermissionAction.RequestNearbyDevices -> firstRunActions.requestBluetoothPermissions()
                                PermissionAction.RequestNotifications -> firstRunActions.requestNotificationPermission()
                                PermissionAction.OpenAppSettings -> firstRunActions.openAppSettings()
                                PermissionAction.None -> Unit
                            }
                        },
                        onConnectOnce = viewModel::startTestConnection,
                        onClearLog = viewModel::clearLog,
                        onDisconnectTest = viewModel::stopTestConnection,
                    ),
                )
            }
        }
    }
}

/** Tab icon from the design boards. Kept out of the enum so routing stays pure Kotlin. */
val TopLevelDestination.icon: ImageVector
    get() = when (this) {
        TopLevelDestination.Now -> KmfIcons.Battery
        TopLevelDestination.Trends -> KmfIcons.Trend
        TopLevelDestination.Device -> KmfIcons.Bluetooth
    }
