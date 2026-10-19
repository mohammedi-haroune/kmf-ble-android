package com.juncehome.lifepo4ble.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import com.juncehome.lifepo4ble.ui.theme.KmfDimens

/** The header reports this much less height, so the gap under it is 8 dp instead of the section gap. */
private val HeaderGapTrim = 8.dp

/**
 * Shared geometry for the Now, Trends and Device header rows: full width, 44 dp tall, drawn at
 * the top of the screen padding, with a tighter gap below. Every tab uses it so the title and
 * connection pill sit at exactly the same position when switching tabs.
 *
 * The trim must wrap the fixed height (not sit inside it), or the row is centered in the
 * 44 dp slot and drawn lower than on the other tabs.
 */
fun Modifier.screenHeader(): Modifier = this
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val trim = HeaderGapTrim.roundToPx().coerceAtMost(placeable.height)
        layout(placeable.width, placeable.height - trim) { placeable.place(0, 0) }
    }
    .fillMaxWidth()
    .height(KmfDimens.MinTouchTarget)
