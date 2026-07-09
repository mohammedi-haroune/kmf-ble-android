package com.juncehome.lifepo4ble

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import com.juncehome.lifepo4ble.ui.BleUiState
import com.juncehome.lifepo4ble.ui.BleViewModel
import com.juncehome.lifepo4ble.ui.KmfBleAppShell
import com.juncehome.lifepo4ble.ui.theme.KmfBleTheme

@Composable
fun KmfBleApp(
    state: BleUiState,
    viewModel: BleViewModel,
    onRequestPermissions: () -> Unit,
) {
    KmfBleTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            KmfBleAppShell(
                state = state,
                viewModel = viewModel,
                onRequestPermissions = onRequestPermissions,
            )
        }
    }
}
