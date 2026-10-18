package com.juncehome.lifepo4ble.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.juncehome.lifepo4ble.ui.theme.KmfBleTheme
import com.juncehome.lifepo4ble.ui.theme.KmfDimens
import com.juncehome.lifepo4ble.ui.theme.KmfTheme

/**
 * 16 dp radius surface card used by every section on the redesigned screens.
 * Pass [borderColor] for accented cards (e.g. `KmfTheme.colors.charge.borderSubtle`) and
 * [containerColor] for tinted fills (e.g. `charge.cardFill`).
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    containerColor: Color = KmfTheme.colors.surface,
    borderColor: Color? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    verticalSpacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(KmfDimens.CardRadius),
        color = containerColor,
        contentColor = KmfTheme.colors.textPrimary,
        border = borderColor?.let { BorderStroke(KmfDimens.Hairline, it) },
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(verticalSpacing),
            content = content,
        )
    }
}

@Preview(name = "SectionCard", backgroundColor = 0xFF0D0F12, showBackground = true, widthDp = 390)
@Composable
private fun SectionCardPreview() {
    KmfBleTheme {
        val colors = KmfTheme.colors
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard {
                Text("Saved history", style = MaterialTheme.typography.titleMedium)
                Text(
                    "12,480 samples since 2 Sep",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
            SectionCard(
                containerColor = colors.charge.cardFill,
                borderColor = colors.charge.borderSubtle,
            ) {
                Text("Charging", style = MaterialTheme.typography.titleMedium)
                ValueWithUnit(value = "+164.7", unit = "W")
            }
        }
    }
}
