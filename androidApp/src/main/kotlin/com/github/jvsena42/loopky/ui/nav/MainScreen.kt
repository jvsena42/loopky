package com.github.jvsena42.loopky.ui.nav

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.github.jvsena42.loopky.presentation.profile.FollowSource
import com.github.jvsena42.loopky.ui.decks.DecksRoute
import com.github.jvsena42.loopky.ui.discover.DiscoverRoute
import com.github.jvsena42.loopky.ui.home.HomeRoute
import com.github.jvsena42.loopky.ui.layout.windowWidthClass
import com.github.jvsena42.loopky.ui.profile.ProfileRoute
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    /**
     * Nobody is signed in — this is where a launch with no session lands, ahead of onboarding.
     *
     * Discover is the whole shell in that case, with no tab bar at all. Today, Decks and Profile
     * are each a view onto a library, a review queue and an identity that do not exist yet, so a
     * guest tab set of four would be one working destination and three apologies. What replaces
     * them is the banner at the top of Discover, which is a way *in* rather than a wall.
     */
    isGuest: Boolean = false,
    /** Leave the guest shell for the sign-in flow. */
    onSignIn: () -> Unit = {},
    onNavigateDeckDetail: (deckId: String, author: String?) -> Unit = { _, _ -> },
    onNavigateCreateDeck: () -> Unit = {},
    onNavigateImport: () -> Unit = {},
    onNavigateImportFile: () -> Unit = {},
    onNavigateStudy: (String?) -> Unit = {},
    onNavigateProfile: (String) -> Unit = {},
    onNavigateSettings: () -> Unit = {},
    onNavigateSearch: () -> Unit = {},
    onNavigateFollows: (pubky: String, source: FollowSource) -> Unit = { _, _ -> },
    /** Opens the backup menu from the card Profile raises above sign-out. */
    onBackUpNow: () -> Unit = {},
    onSignOut: () -> Unit = {},
) {
    if (isGuest) {
        DiscoverRoute(
            isGuest = true,
            onSignIn = onSignIn,
            onOpenProfile = onNavigateProfile,
            onOpenDeck = onNavigateDeckDetail,
            onOpenSearch = onNavigateSearch,
        )
        return
    }

    val pagerState = rememberPagerState(pageCount = { LoopkyTab.entries.size })
    val scope = rememberCoroutineScope()
    val selectedTab = LoopkyTab.entries[pagerState.currentPage]
    val onTabSelected: (LoopkyTab) -> Unit = { tab ->
        scope.launch { pagerState.animateScrollToPage(tab.ordinal) }
    }

    // Wide windows put the destinations down the leading edge instead of across the bottom: four
    // tabs stretched over a landscape tablet leave the content squeezed under a bar it never
    // needed, and the thumb that a bottom bar is designed for isn't where the hand is on a tablet.
    // The rail and the bar are otherwise interchangeable — same tabs, same tags, same callback —
    // so the pager below is written once and neither branch owns it.
    val useRail = windowWidthClass().isExpanded

    Row(modifier = Modifier.fillMaxSize()) {
        if (useRail) {
            LoopkyNavRail(
                selectedTab = selectedTab,
                onTabSelected = onTabSelected,
                // Held below the status bar rather than painted behind it. The rail's container is
                // dark and the app runs edge-to-edge with dark status-bar icons, so a full-bleed
                // rail puts a black clock on a black strip; the tab screens beside it already
                // inset themselves the same way, so the cream reads as one continuous top edge.
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
            )
        }
        Scaffold(
            // The tab screens each apply their own status-bar inset, and the bottom bar consumes
            // the navigation-bar inset natively. Zero out the Scaffold's content insets so the
            // status-bar height isn't added twice above each page title.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (!useRail) {
                    LoopkyTabBar(
                        selectedTab = selectedTab,
                        onTabSelected = onTabSelected,
                    )
                }
            },
        ) { innerPadding ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) { page ->
                when (LoopkyTab.entries[page]) {
                    LoopkyTab.STUDY -> HomeRoute(
                        // The author travels with the id so a followed deck resolves on a cold cache.
                        onOpenDeck = onNavigateDeckDetail,
                        onCreateDeck = onNavigateCreateDeck,
                        onBrowseExamples = {
                            scope.launch { pagerState.animateScrollToPage(LoopkyTab.DISCOVER.ordinal) }
                        },
                        onSeeAllDecks = {
                            scope.launch { pagerState.animateScrollToPage(LoopkyTab.DECKS.ordinal) }
                        },
                        onStartStudy = { onNavigateStudy(null) },
                        onSignedOut = onSignOut,
                    )
                    LoopkyTab.DECKS -> DecksRoute(
                        onDeckClick = onNavigateDeckDetail,
                        onImportClick = onNavigateImport,
                        onImportFileClick = onNavigateImportFile,
                        onCreateDeckClick = onNavigateCreateDeck,
                    )
                    LoopkyTab.DISCOVER -> DiscoverRoute(
                        onOpenProfile = onNavigateProfile,
                        onOpenDeck = onNavigateDeckDetail,
                        onOpenSearch = onNavigateSearch,
                    )
                    LoopkyTab.PROFILE -> ProfileRoute(
                        onSignedOut = onSignOut,
                        onOpenSettings = onNavigateSettings,
                        onOpenFollows = onNavigateFollows,
                        onBackUpNow = onBackUpNow,
                    )
                }
            }
        }
    }
}
