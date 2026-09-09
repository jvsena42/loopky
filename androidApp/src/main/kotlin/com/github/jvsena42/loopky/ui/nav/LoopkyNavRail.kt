package com.github.jvsena42.loopky.ui.nav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalWideNavigationRail
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRailDefaults
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailItemDefaults
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import kotlinx.coroutines.launch

/**
 * Side navigation for windows wide enough to put the destinations beside the content instead of
 * under it — see [com.github.jvsena42.loopky.ui.layout.WindowWidthClass.Expanded].
 *
 * The Material 3 Expressive counterpart to [LoopkyTabBar], and deliberately its twin: same four
 * destinations in the same order, same brand tint, same `tab_*` test tags. The tags matter beyond
 * tests — a rail that renamed them would silently break every journey script the moment a device
 * was held in landscape, which is exactly the case nobody runs.
 *
 * Collapsed by default: four destinations with one-word labels do not need a 220dp drawer standing
 * open, and the room saved goes to the content, which is the point of the rail. It is a
 * [ModalWideNavigationRail] rather than a fixed one so the reader can still open it — the expanded
 * state draws *over* the content instead of squeezing it, so nothing reflows behind the menu, and
 * `hideOnCollapse = false` keeps the collapsed rail on screen the whole time.
 */
@Composable
fun LoopkyNavRail(
    selectedTab: LoopkyTab,
    onTabSelected: (LoopkyTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    val railState = rememberWideNavigationRailState()
    val scope = rememberCoroutineScope()
    val expanded = railState.targetValue == WideNavigationRailValue.Expanded
    ModalWideNavigationRail(
        modifier = modifier.testTag("nav_rail"),
        state = railState,
        hideOnCollapse = false,
        // The modal container is a *separate* token from the collapsed one, and left to its
        // default the rail turns pale lavender the moment it opens — the same four items on a
        // different-coloured panel, with the brand tint gone and grey labels on it.
        colors = WideNavigationRailDefaults.colors(
            containerColor = colors.navBarBackground,
            contentColor = colors.foregroundOnAccent,
            modalContainerColor = colors.navBarBackground,
            modalContentColor = colors.foregroundOnAccent,
        ),
        header = {
            IconButton(
                onClick = { scope.launch { railState.toggle() } },
                modifier = Modifier.testTag("nav_rail_toggle"),
            ) {
                Icon(
                    imageVector = if (expanded) Icons.AutoMirrored.Filled.MenuOpen else Icons.Default.Menu,
                    contentDescription = stringResource(
                        if (expanded) R.string.nav_rail_collapse else R.string.nav_rail_expand,
                    ),
                    tint = colors.foregroundOnAccent,
                )
            }
        },
        // Centred rather than top-aligned: with only four items, hugging the top of a 800dp-tall
        // panel leaves them stranded above a column of empty rail.
        arrangement = Arrangement.Center,
    ) {
        LoopkyTab.entries.forEach { tab ->
            val tabLabel = stringResource(tab.labelRes)
            WideNavigationRailItem(
                selected = tab == selectedTab,
                // Picking a destination closes the rail: expanded it covers the content it just
                // navigated to, and a menu that stays open over its own answer is a menu that
                // looks like it did nothing.
                onClick = {
                    scope.launch { railState.collapse() }
                    onTabSelected(tab)
                },
                icon = {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tabLabel,
                    )
                },
                label = { Text(tabLabel) },
                railExpanded = expanded,
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
