package com.juncehome.lifepo4ble.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Line icons copied from the design boards (24x24 viewBox, stroke 2, round caps/joins).
 * Use with `Icon(imageVector = KmfIcons.Battery, contentDescription = ..., tint = ...)`;
 * the tint recolors the stroke.
 */
object KmfIcons {
    /** Now tab: battery with two cell bars. */
    val Battery: ImageVector by lazy {
        lineIcon(
            "Battery",
            "M5 7h12a2 2 0 0 1 2 2v6a2 2 0 0 1 -2 2H5a2 2 0 0 1 -2 -2V9a2 2 0 0 1 2 -2z",
            "M21 11v2",
            "M7 10v4",
            "M11 10v4",
        )
    }

    /** Trends tab: rising line over a baseline. */
    val Trend: ImageVector by lazy {
        lineIcon("Trend", "M3 17l5 -5 4 4 8 -8", "M3 21h18")
    }

    /** Device tab: Bluetooth rune. */
    val Bluetooth: ImageVector by lazy {
        lineIcon("Bluetooth", "M7 7l10 10 -5 5V2l5 5L7 17")
    }

    /** Bluetooth with a slash, used for offline banners. */
    val BluetoothOff: ImageVector by lazy {
        lineIcon("BluetoothOff", "M7 7l10 10 -5 5V2l5 5L7 17", "M3 3l18 18")
    }

    /** Small chevron, e.g. next to the meter name in the header. */
    val ChevronRight: ImageVector by lazy {
        lineIcon("ChevronRight", "M9 6l6 6 -6 6", strokeWidth = 2.4f)
    }

    /** Warning triangle, e.g. low battery alert row and error banners. */
    val Warning: ImageVector by lazy {
        lineIcon("Warning", "M12 3l10 18H2z", "M12 10v4", "M12 17.5v0.5", strokeWidth = 2.2f)
    }

    /** First run step 3: phone and meter linked (companion pairing). */
    val Link: ImageVector by lazy {
        lineIcon(
            "Link",
            "M4 7h5a2 2 0 0 1 2 2v6a2 2 0 0 1 -2 2H4a2 2 0 0 1 -2 -2V9a2 2 0 0 1 2 -2z",
            "M15 4h5a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-5a2 2 0 0 1 -2 -2V6a2 2 0 0 1 2 -2z",
            "M11 12h2",
            strokeWidth = 1.8f,
        )
    }

    /** First run step 4: notification bell. */
    val Bell: ImageVector by lazy {
        lineIcon("Bell", "M6 8a6 6 0 1 1 12 0c0 7 3 9 3 9H3s3 -2 3 -9", "M10 21h4", strokeWidth = 1.8f)
    }

    /** First run Done: check mark. */
    val Check: ImageVector by lazy {
        lineIcon("Check", "M5 12l5 5 9 -10")
    }

    /** Trends Export CSV button: tray with a down arrow. */
    val Download: ImageVector by lazy {
        lineIcon("Download", "M12 4v11", "M7 11l5 5 5 -5", "M4 20h16")
    }

    private fun lineIcon(
        name: String,
        vararg paths: String,
        strokeWidth: Float = 2f,
    ): ImageVector {
        val builder = ImageVector.Builder(
            name = "Kmf.$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        paths.forEach { data ->
            builder.addPath(
                pathData = addPathNodes(data),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = strokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }
}
