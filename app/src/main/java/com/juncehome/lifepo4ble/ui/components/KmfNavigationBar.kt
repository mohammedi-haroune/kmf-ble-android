package com.juncehome.lifepo4ble.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.juncehome.lifepo4ble.ui.theme.KmfBleTheme
import com.juncehome.lifepo4ble.ui.theme.KmfDimens
import com.juncehome.lifepo4ble.ui.theme.KmfTheme

/**
 * Bottom navigation from the design boards: 72 dp tall, hairline border on top, no indicator
 * pill. Selected item uses primary text color, unselected tertiary.
 */
@Composable
fun KmfNavigationBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = KmfTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(KmfDimens.Hairline)
                .background(colors.hairline),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(KmfDimens.NavBarHeight - KmfDimens.Hairline)
                .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 12.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
fun RowScope.KmfNavigationBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = KmfTheme.colors
    val tint = if (selected) colors.textPrimary else colors.textTertiary
    Column(
        modifier = modifier
            .weight(1f)
            .fillMaxHeight()
            .selectable(selected = selected, onClick = onClick, role = Role.Tab),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        // The label is visible text, so the icon itself is decorative for accessibility.
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = tint,
            maxLines = 1,
        )
    }
}

@Preview(name = "KmfNavigationBar", backgroundColor = 0xFF0D0F12, showBackground = true, widthDp = 390)
@Composable
private fun KmfNavigationBarPreview() {
    KmfBleTheme {
        KmfNavigationBar {
            KmfNavigationBarItem(selected = true, onClick = {}, icon = KmfIcons.Battery, label = "Now")
            KmfNavigationBarItem(selected = false, onClick = {}, icon = KmfIcons.Trend, label = "Trends")
            KmfNavigationBarItem(selected = false, onClick = {}, icon = KmfIcons.Bluetooth, label = "Device")
        }
    }
}
