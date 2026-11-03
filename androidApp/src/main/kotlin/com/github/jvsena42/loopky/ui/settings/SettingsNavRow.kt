package com.github.jvsena42.loopky.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/** A row that opens another Loopky screen — a chevron, where [SettingsLinkRow] has open-in-new. */
@Composable
internal fun SettingsNavRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    ListItem(
        headlineContent = {
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.W500,
                color = colors.foregroundPrimary,
            )
        },
        modifier = modifier.clickable(onClick = onClick),
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = colors.foregroundMuted,
            )
        },
        colors = settingsRowColors(),
    )
}
