package com.juncehome.lifepo4ble.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

@Composable
fun KmfBleAppShell(
    state: BleUiState,
    viewModel: BleViewModel,
    onRequestPermissions: () -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                TopLevelDestination.bottomBarItems.forEach { destination ->
                    NavigationBarItem(
                        selected = currentDestination
                            ?.hierarchy
                            ?.any { it.route == destination.route } == true,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Text(
                                text = destination.iconText,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        },
                        label = {
                            Text(text = destination.label)
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.startDestination.route,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(TopLevelDestination.Dashboard.route) {
                DashboardScreen(
                    state = state,
                    contentPadding = innerPadding,
                )
            }
            composable(TopLevelDestination.Live.route) {
                PlaceholderScreen(
                    title = "Live",
                    description = "Realtime Vico charts land in the next task.",
                    contentPadding = innerPadding,
                )
            }
            composable(TopLevelDestination.History.route) {
                PlaceholderScreen(
                    title = "History",
                    description = "Historical analytics and grouped queries are still pending.",
                    contentPadding = innerPadding,
                )
            }
            composable(TopLevelDestination.Energy.route) {
                PlaceholderScreen(
                    title = "Energy",
                    description = "Charge and discharge totals will move here after the dashboard slice.",
                    contentPadding = innerPadding,
                )
            }
            composable(TopLevelDestination.Diagnostics.route) {
                BleScreen(
                    state = state,
                    modifier = Modifier.padding(innerPadding),
                    onRequestPermissions = onRequestPermissions,
                    onStartScan = viewModel::startScan,
                    onStopScan = viewModel::stopScan,
                    onConnect = viewModel::connect,
                    onDisconnect = viewModel::disconnect,
                    onClearLog = viewModel::clearLog,
                )
            }
        }
    }
}

@Composable
private fun DashboardScreen(
    state: BleUiState,
    contentPadding: PaddingValues,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Dashboard",
            style = MaterialTheme.typography.headlineMedium,
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Current battery state",
                    style = MaterialTheme.typography.titleMedium,
                )
                DashboardMetric("Connection", state.connectionState.name)
                DashboardMetric("SoC", formatPercent(state.latestReading.socPercent))
                DashboardMetric("Voltage", formatDecimal(state.latestReading.voltageV, "V"))
                DashboardMetric("Current", formatDecimal(state.latestReading.currentA, "A"))
                DashboardMetric("Power", formatDecimal(state.latestReading.powerW, "W"))
            }
        }
        Text(
            text = "Use Diagnostics for scan, connect, packet logs, and UUID details.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DashboardMetric(
    label: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun PlaceholderScreen(
    title: String,
    description: String,
    contentPadding: PaddingValues,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Start,
        )
    }
}

private fun formatPercent(value: Double): String = String.format("%.0f%%", value)

private fun formatDecimal(
    value: Double,
    unit: String,
): String = String.format("%.2f %s", value, unit)
