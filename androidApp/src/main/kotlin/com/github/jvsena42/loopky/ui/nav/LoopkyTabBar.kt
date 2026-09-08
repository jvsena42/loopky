package com.github.jvsena42.loopky.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * Bottom navigation built on Material 3 Expressive's [ShortNavigationBar]. We keep the native
 * component (insets, ripple, indicator, expressive selection motion, a11y) and only tint it with
 * Loopky's brand tokens, rather than rebuilding the chrome from primitives.
 */
@Composable
fun LoopkyTabBar(
    selectedTab: LoopkyTab,
    onTabSelected: (LoopkyTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    ShortNavigationBar(
        modifier = modifier,
        containerColor = colors.navBarBackground,
        contentColor = colors.foregroundOnAccent,
    ) {
        LoopkyTab.entries.forEach { tab ->
            val tabLabel = stringResource(tab.labelRes)
            ShortNavigationBarItem(
                selected = tab == selectedTab,
                onClick = { onTabSelected(tab) },
                icon = {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tabLabel,
                    )
                },
                label = { Text(tabLabel) },
                colors = ShortNavigationBarItemDefaults.colors(
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

@Preview
@Composable
private fun LoopkyTabBarPreview() {
    LoopkyTheme {
        Box {
            LoopkyTabBar(
                selectedTab = LoopkyTab.DECKS,
                onTabSelected = {},
            )
        }
    }
}
