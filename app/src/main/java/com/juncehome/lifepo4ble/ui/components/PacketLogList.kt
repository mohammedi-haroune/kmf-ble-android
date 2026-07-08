package com.juncehome.lifepo4ble.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.juncehome.lifepo4ble.protocol.FrameLogEntry

@Composable
fun PacketLogList(packetLog: List<FrameLogEntry>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Packet Log",
            style = MaterialTheme.typography.titleMedium,
        )
        if (packetLog.isEmpty()) {
            Text(text = "No packets yet")
        } else {
            packetLog.takeLast(60).asReversed().forEach { entry ->
                Text(
                    text = "${entry.timestampMs} ${entry.direction.name} ${entry.length}b ${entry.hex} ${entry.ascii}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
