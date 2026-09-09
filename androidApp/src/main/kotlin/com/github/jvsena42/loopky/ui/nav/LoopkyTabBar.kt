package com.github.jvsena42.loopky.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationItemIconPosition
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarArrangement
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.github.jvsena42.loopky.ui.layout.LocalWindowWidthClass
import com.github.jvsena42.loopky.ui.layout.WindowWidthClass
import com.github.jvsena42.loopky.ui.layout.windowWidthClass
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * Bottom navigation built on Material 3 Expressive's [ShortNavigationBar]. We keep the native
 * component (insets, ripple, indicator, expressive selection motion, a11y) and only tint it with
 * Loopky's brand tokens, rather than rebuilding the chrome from primitives.
 *
 * The bar reaches [WindowWidthClass.Medium] windows — a tablet held in portrait, half a split
 * screen — because the rail only takes over at [WindowWidthClass.Expanded]. Stretched over 800dp
 * the four equal-weight items sit a hand's width apart with the icon marooned above the label, so
 * at that width they gather in the middle with the icon beside the label instead: the Material 3
 * Expressive arrangement for exactly this case, and the reason the component takes the knob.
 */
@Composable
fun LoopkyTabBar(
    selectedTab: LoopkyTab,
    onTabSelected: (LoopkyTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    val roomy = windowWidthClass().isAtLeastMedium
    ShortNavigationBar(
        modifier = modifier,
        containerColor = colors.navBarBackground,
        contentColor = colors.foregroundOnAccent,
        arrangement = if (roomy) {
            ShortNavigationBarArrangement.Centered
        } else {
            ShortNavigationBarArrangement.EqualWeight
        },
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
                iconPosition = if (roomy) {
                    NavigationItemIconPosition.Start
                } else {
                    NavigationItemIconPosition.Top
                },
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

@Preview(widthDp = 700)
@Composable
private fun LoopkyTabBarMediumPreview() {
    LoopkyTheme {
        CompositionLocalProvider(LocalWindowWidthClass provides WindowWidthClass.Medium) {
            Box {
                LoopkyTabBar(
                    selectedTab = LoopkyTab.DECKS,
                    onTabSelected = {},
                )
            }
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
