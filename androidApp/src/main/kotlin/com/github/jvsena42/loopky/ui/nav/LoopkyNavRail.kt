package com.github.jvsena42.loopky.ui.nav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailDefaults
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * Side navigation for windows wide enough to put the destinations beside the content instead of
 * under it — see [com.github.jvsena42.loopky.ui.layout.WindowWidthClass.Expanded].
 *
 * The Material 3 Expressive counterpart to [LoopkyTabBar], and deliberately its twin: same four
 * destinations in the same order, same brand tint, same `tab_*` test tags. The tags matter beyond
 * tests — a rail that renamed them would silently break every journey script the moment a device
 * was held in landscape, which is exactly the case nobody runs.
 *
 * Collapsed (icon over label) rather than expanded: four destinations with one-word labels do not
 * need a 220dp drawer, and the room saved goes to the content, which is the point of the rail.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoopkyNavRail(
    selectedTab: LoopkyTab,
    onTabSelected: (LoopkyTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    WideNavigationRail(
        modifier = modifier.testTag("nav_rail"),
        colors = WideNavigationRailDefaults.colors(
            containerColor = colors.navBarBackground,
            contentColor = colors.foregroundOnAccent,
        ),
        // Centred rather than top-aligned: with only four items, hugging the top of a 800dp-tall
        // panel leaves them stranded above a column of empty rail.
        arrangement = Arrangement.Center,
    ) {
        LoopkyTab.entries.forEach { tab ->
            val tabLabel = stringResource(tab.labelRes)
            WideNavigationRailItem(
                selected = tab == selectedTab,
                onClick = { onTabSelected(tab) },
                icon = {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tabLabel,
                    )
                },
                label = { Text(tabLabel) },
                railExpanded = false,
                colors = WideNavigationRailItemDefaults.colors(
                    selectedIconColor = colors.foregroundOnAccent,
                    selectedTextColor = colors.foregroundOnAccent,
                    selectedIndicatorColor = colors.accentPrimary,
                    unselectedIconColor = colors.navBarInactive,
                    unselectedTextColor = colors.navBarInactive,
                ),
                modifier = Modifier.testTag("tab_${tab.name.lowercase()}"),
            )
        }
    }
}

@Preview(widthDp = 900, heightDp = 600)
@Composable
private fun LoopkyNavRailPreview() {
    LoopkyTheme {
        Row {
            LoopkyNavRail(
                selectedTab = LoopkyTab.DECKS,
                onTabSelected = {},
            )
        }
    }
}
