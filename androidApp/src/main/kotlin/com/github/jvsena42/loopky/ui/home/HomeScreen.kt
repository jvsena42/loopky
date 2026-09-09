package com.github.jvsena42.loopky.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.presentation.home.DeckSummary
import com.github.jvsena42.loopky.presentation.home.HomeEffect
import com.github.jvsena42.loopky.presentation.home.HomeUiState
import com.github.jvsena42.loopky.presentation.home.HomeViewModel
import com.github.jvsena42.loopky.ui.components.LoopkyErrorBlock
import com.github.jvsena42.loopky.ui.components.LoopkyLoadingScreen
import com.github.jvsena42.loopky.ui.layout.PaneWidth
import com.github.jvsena42.loopky.ui.layout.contentPane
import com.github.jvsena42.loopky.ui.layout.windowWidthClass
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.labelOrFallback
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun HomeRoute(
    onCreateDeck: () -> Unit = {},
    onBrowseExamples: () -> Unit = {},
    onSeeAllDecks: () -> Unit = {},
    onStartStudy: () -> Unit = {},
    onOpenDeck: (String, String?) -> Unit = { _, _ -> },
    onSignedOut: () -> Unit = {},
) {
    val viewModel = koinViewModel<HomeViewModel>()

    val currentCreate by rememberUpdatedState(onCreateDeck)
    val currentBrowse by rememberUpdatedState(onBrowseExamples)
    val currentSeeAll by rememberUpdatedState(onSeeAllDecks)
    val currentStart by rememberUpdatedState(onStartStudy)
    val currentOpen by rememberUpdatedState(onOpenDeck)
    val currentSignedOut by rememberUpdatedState(onSignedOut)

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                HomeEffect.NavigateCreateDeck -> currentCreate()
                HomeEffect.NavigateBrowseExamples -> currentBrowse()
                HomeEffect.NavigateAllDecks -> currentSeeAll()
                HomeEffect.NavigateStartStudy -> currentStart()
                is HomeEffect.NavigateDeck -> currentOpen(effect.deckId, effect.authorPubky)
                HomeEffect.NavigateToOnboarding -> currentSignedOut()
            }
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    HomeScreen(
        state = state,
        onStartStudyClick = viewModel::onStartStudyClick,
        onCreateDeckClick = viewModel::onCreateDeckClick,
        onBrowseExamplesClick = viewModel::onBrowseExamplesClick,
        onSeeAllDecksClick = viewModel::onSeeAllDecksClick,
        onDeckClick = viewModel::onDeckClick,
        onRetry = viewModel::onRefresh,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onStartStudyClick: () -> Unit,
    onCreateDeckClick: () -> Unit,
    onBrowseExamplesClick: () -> Unit,
    onSeeAllDecksClick: () -> Unit,
    onDeckClick: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surfacePrimary)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        if (state is HomeUiState.Loading) {
            LoopkyLoadingScreen(message = stringResource(R.string.home_loading))
        } else {
            PullToRefreshBox(
                isRefreshing = false,
                onRefresh = onRetry,
                modifier = Modifier.fillMaxSize(),
            ) {
                HomeScreenContent(
                    state = state,
                    onStartStudyClick = onStartStudyClick,
                    onCreateDeckClick = onCreateDeckClick,
                    onBrowseExamplesClick = onBrowseExamplesClick,
                    onSeeAllDecksClick = onSeeAllDecksClick,
                    onDeckClick = onDeckClick,
                    onRetry = onRetry,
                )
            }
        }
    }
}

@Composable
private fun HomeScreenContent(
    state: HomeUiState,
    onStartStudyClick: () -> Unit,
    onCreateDeckClick: () -> Unit,
    onBrowseExamplesClick: () -> Unit,
    onSeeAllDecksClick: () -> Unit,
    onDeckClick: (String) -> Unit,
    onRetry: () -> Unit,
) {
    // The empty state centers its card + actions in the space below the greeting,
    // so it gets a non-scrolling full-height layout instead of the shared scroll column.
    if (state is HomeUiState.Empty) {
        HomeEmptyScreen(
            greetingName = state.identity.labelOrFallback(),
            onCreateDeckClick = onCreateDeckClick,
            onBrowseExamplesClick = onBrowseExamplesClick,
        )
        return
    }

    val wide = windowWidthClass().isExpanded
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // Wide enough for two panes side by side; on narrower windows the due-today hero is a
            // number and its caption, and unbounded they drift to opposite ends of the card and
            // read as two unrelated things.
            .contentPane(if (wide) PaneWidth.Wide else PaneWidth.Reading)
            .padding(PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        when (state) {
            is HomeUiState.Content -> {
                GreetingHeader(name = state.identity.labelOrFallback())
                if (wide) {
                    WideHomeContent(
                        state = state,
                        onStartStudyClick = onStartStudyClick,
                        onSeeAllDecksClick = onSeeAllDecksClick,
                        onDeckClick = onDeckClick,
                    )
                } else {
                    HomeContent(
                        state = state,
                        onStartStudyClick = onStartStudyClick,
                        onSeeAllDecksClick = onSeeAllDecksClick,
                        onDeckClick = onDeckClick,
                    )
                }
            }
            is HomeUiState.Error -> {
                GreetingHeader(name = state.identity.labelOrFallback())
                LoopkyErrorBlock(
                    reason = state.reason,
                    onRetry = onRetry,
                    modifier = Modifier.padding(top = 48.dp),
                )
            }
            HomeUiState.Loading, is HomeUiState.Empty -> Unit
        }
    }
}

/**
 * Home on a landscape tablet: the day's headline beside the decks, rather than stacked above them.
 *
 * Two things are different from the compact layout, both about the same problem. The hero is
 * pinned to a column of its own instead of running the full width — a "2 / cards to review" card
 * stretched across 1100dp puts its number and its caption a hand's width apart. And the deck list
 * becomes a grid of covers, because the stacked pair used barely a third of the height and left
 * the rest of the screen empty; the tiles are the same decks, drawn at a size the space deserves.
 */
@Composable
private fun WideHomeContent(
    state: HomeUiState.Content,
    onStartStudyClick: () -> Unit,
    onSeeAllDecksClick: () -> Unit,
    onDeckClick: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        HomeHero(
            state = state,
            onStartStudyClick = onStartStudyClick,
            modifier = Modifier.width(HERO_PANE_WIDTH),
        )
        TodaysDecksGrid(
            decks = state.decks,
            countsKnown = state.countsKnown,
            // Two across inside the right pane. The window may be wide, but this pane is only
            // part of it, so the screen-wide count would give tiles too narrow to read.
            columns = WIDE_HOME_DECK_COLUMNS,
            onSeeAllClick = onSeeAllDecksClick,
            onDeckClick = onDeckClick,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HomeEmptyScreen(
    greetingName: String,
    onCreateDeckClick: () -> Unit,
    onBrowseExamplesClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        GreetingHeader(name = greetingName)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        ) {
            HomeEmptyContent(
                onCreateDeckClick = onCreateDeckClick,
                onBrowseExamplesClick = onBrowseExamplesClick,
            )
        }
    }
}

@Composable
private fun ErrorBlock(message: String, onRetry: () -> Unit) {
    val colors = LoopkyTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(top = 48.dp),
    ) {
        Text(
            text = stringResource(R.string.home_error_title),
            color = colors.foregroundPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        Text(
            text = message,
            color = colors.foregroundMuted,
            fontSize = 14.sp,
        )
        androidx.compose.material3.TextButton(onClick = onRetry) {
            Text(stringResource(R.string.home_retry), color = colors.accentPrimary)
        }
    }
}

@Preview
@Composable
private fun HomeScreenPreview() {
    LoopkyTheme {
        HomeScreen(
            state = HomeUiState.Content(
                identity = PubkyIdentity("alex1xqz9", "Alex", avatarUrl = null, bio = null),
                dueToday = 24,
                doneToday = 9,
                decks = listOf(
                    DeckSummary(
                        id = "1",
                        title = "Spanish Basics",
                        authorPubky = "alex1xqz9",
                        cardCount = 60,
                        dueCount = 12,
                        coverInitial = 'S',
                    ),
                    DeckSummary(
                        id = "2",
                        title = "Kanji N5",
                        authorPubky = "friend1xqz9",
                        cardCount = 103,
                        dueCount = 8,
                        coverInitial = 'K',
                    ),
                ),
            ),
            onStartStudyClick = {},
            onCreateDeckClick = {},
            onBrowseExamplesClick = {},
            onSeeAllDecksClick = {},
            onDeckClick = {},
            onRetry = {},
        )
    }
}

/** Wide enough for the hero's headline number and its caption to stay one phrase. */
private val HERO_PANE_WIDTH = 380.dp
private const val WIDE_HOME_DECK_COLUMNS = 2
