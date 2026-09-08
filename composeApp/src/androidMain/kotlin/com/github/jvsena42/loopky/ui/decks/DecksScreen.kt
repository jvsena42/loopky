package com.github.jvsena42.loopky.ui.decks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.presentation.decks.DeckRelation
import com.github.jvsena42.loopky.presentation.decks.DeckSort
import com.github.jvsena42.loopky.presentation.decks.DeckTileModel
import com.github.jvsena42.loopky.presentation.decks.DecksLibraryEffect
import com.github.jvsena42.loopky.presentation.decks.DecksLibraryUiState
import com.github.jvsena42.loopky.presentation.decks.DecksLibraryViewModel
import com.github.jvsena42.loopky.ui.components.DeckTile
import com.github.jvsena42.loopky.ui.components.LoopkyErrorBlock
import com.github.jvsena42.loopky.ui.components.LoopkyLoadingBlock
import com.github.jvsena42.loopky.ui.components.LoopkyPrimaryButton
import com.github.jvsena42.loopky.ui.layout.PaneWidth
import com.github.jvsena42.loopky.ui.layout.contentPane
import com.github.jvsena42.loopky.ui.layout.deckGridColumns
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.label
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun DecksRoute(
    onDeckClick: (String, String?) -> Unit = { _, _ -> },
    onImportClick: () -> Unit = {},
    onImportFileClick: () -> Unit = {},
    onCreateDeckClick: () -> Unit = {},
) {
    val viewModel = koinViewModel<DecksLibraryViewModel>()

    val currentDeckClick by rememberUpdatedState(onDeckClick)
    val currentImportClick by rememberUpdatedState(onImportClick)
    val currentCreateDeckClick by rememberUpdatedState(onCreateDeckClick)

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is DecksLibraryEffect.NavigateDeckDetail ->
                    currentDeckClick(effect.deckId, effect.authorPubky)
                DecksLibraryEffect.NavigateImport -> currentImportClick()
                DecksLibraryEffect.NavigateCreateDeck -> currentCreateDeckClick()
            }
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    DecksScreen(
        state = state,
        onDeckClick = viewModel::onDeckClick,
        onImportClick = viewModel::onImportClick,
        // Navigated directly rather than through the VM: picking a file is a platform concern
        // with no shared state to carry into it.
        onImportFileClick = onImportFileClick,
        onCreateDeckClick = viewModel::onCreateDeckClick,
        onRetry = viewModel::onRefresh,
        onQueryChanged = viewModel::onQueryChanged,
        onSortChanged = viewModel::onSortChanged,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DecksScreen(
    state: DecksLibraryUiState,
    onDeckClick: (String) -> Unit,
    onImportClick: () -> Unit,
    onImportFileClick: () -> Unit,
    onCreateDeckClick: () -> Unit,
    onRetry: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onSortChanged: (DeckSort) -> Unit,
) {
    val colors = LoopkyTheme.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surfacePrimary)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        // The loader sits where the grid will be, never over the whole screen: importing a deck
        // is the one thing you can do here that needs nothing loaded, and a first launch on a slow
        // homeserver hid its CTA behind a spinner for as long as the fetch took.
        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = onRetry,
            modifier = Modifier.fillMaxSize(),
        ) {
            DecksScreenContent(
                state = state,
                onDeckClick = onDeckClick,
                onImportClick = onImportClick,
                onImportFileClick = onImportFileClick,
                onCreateDeckClick = onCreateDeckClick,
                onRetry = onRetry,
                onQueryChanged = onQueryChanged,
                onSortChanged = onSortChanged,
            )
        }
    }
}

@Composable
private fun DecksScreenContent(
    state: DecksLibraryUiState,
    onDeckClick: (String) -> Unit,
    onImportClick: () -> Unit,
    onImportFileClick: () -> Unit,
    onCreateDeckClick: () -> Unit,
    onRetry: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onSortChanged: (DeckSort) -> Unit,
) {
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Wide rather than Reading: this column is mostly a tile grid, which is the one thing
            // that genuinely improves with more room — it answers with more columns, not wider
            // tiles. The ceiling still stops the header and the paste banner from stretching to a
            // desktop window's full width.
            .contentPane(PaneWidth.Wide)
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        HeaderRow(
            searchOpen = searchOpen,
            onToggleSearch = {
                searchOpen = !searchOpen
                if (!searchOpen) onQueryChanged("")
            },
        )
        if (searchOpen) {
            SearchField(
                query = (state as? DecksLibraryUiState.Content)?.query.orEmpty(),
                onQueryChanged = onQueryChanged,
            )
        }
        // Grouped so the outer 20.dp spacing doesn't apply between them: the file CTA belongs to
        // the paste card, and the wide gap made it read as an unrelated stray control.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PasteCtaCard(onClick = onImportClick)
            // Secondary, because paste is the primary flow (spec §1). File import exists for decks
            // too big to paste — an Anki export, not a hand-typed list — and says so, since the
            // spec pitches Loopky at Anki refugees who won't find it under "a file".
            TextButton(
                onClick = onImportFileClick,
                modifier = Modifier.testTag("decks_import_file"),
            ) {
                Text(
                    text = stringResource(R.string.decks_import_file_cta),
                    color = LoopkyTheme.colors.accentPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.W600,
                )
            }
        }

        when (state) {
            DecksLibraryUiState.Loading -> LoopkyLoadingBlock(
                message = stringResource(R.string.decks_loading),
            )
            DecksLibraryUiState.Empty -> EmptyBlock(onCreateDeckClick = onCreateDeckClick)
            is DecksLibraryUiState.Content -> {
                SectionHeader(
                    deckCount = state.deckCount,
                    sort = state.sort,
                    onSortChanged = onSortChanged,
                )
                val visible = state.visibleDecks
                if (visible.isEmpty()) {
                    NoSearchResults(query = state.query)
                } else {
                    DeckGrid(decks = visible, onDeckClick = onDeckClick)
                }
            }
            is DecksLibraryUiState.Error -> LoopkyErrorBlock(
                reason = state.reason,
                onRetry = onRetry,
            )
        }
    }
}

@Composable
private fun HeaderRow(searchOpen: Boolean, onToggleSearch: () -> Unit) {
    val colors = LoopkyTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.decks_title),
            color = colors.foregroundPrimary,
            fontSize = 28.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        // Was a decorative Icon with no click handler at all.
        IconButton(onClick = onToggleSearch, modifier = Modifier.testTag("decks_search")) {
            Icon(
                imageVector = if (searchOpen) Icons.Default.Close else Icons.Default.Search,
                contentDescription = stringResource(R.string.decks_search),
                tint = colors.foregroundPrimary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun PasteCtaCard(onClick: () -> Unit) {
    val colors = LoopkyTheme.colors
    Box(
        modifier = Modifier
            .testTag("decks_paste_cta")
            .fillMaxWidth()
            .shadow(
                elevation = 32.dp,
                shape = RoundedCornerShape(28.dp),
                ambientColor = colors.shadowAccent,
                spotColor = colors.shadowAccent,
            )
            .clip(RoundedCornerShape(28.dp))
            .background(colors.accentPrimary)
            .clickable(onClick = onClick)
            .padding(22.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Icon box
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "\uD83D\uDCCB",
                    fontSize = 20.sp,
                )
            }

            // Text column
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.decks_paste_cta_title),
                    color = colors.foregroundOnAccent,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.W700,
                )
                Text(
                    text = stringResource(R.string.decks_paste_cta_subtitle),
                    color = colors.foregroundOnAccent.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                )
            }

            // Arrow icon
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.decks_paste_cta_go),
                tint = colors.foregroundOnAccent,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(
    deckCount: Int,
    sort: DeckSort,
    onSortChanged: (DeckSort) -> Unit,
) {
    val colors = LoopkyTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.decks_library_count, deckCount),
            color = colors.foregroundPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.W700,
        )
        Box {
            // Was a plain Text that looked like a sort control and did nothing.
            Text(
                text = stringResource(sort.labelRes()),
                color = colors.accentPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.W600,
                modifier = Modifier
                    .clickable { menuOpen = true }
                    .testTag("decks_sort"),
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DeckSort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(stringResource(option.labelRes())) },
                        onClick = {
                            onSortChanged(option)
                            menuOpen = false
                        },
                    )
                }
            }
        }
    }
}

private fun DeckSort.labelRes(): Int = when (this) {
    DeckSort.Recent -> R.string.decks_sort_recent
    DeckSort.Alphabetical -> R.string.decks_sort_alphabetical
    DeckSort.CardCount -> R.string.decks_sort_cards
}

@Composable
private fun SearchField(query: String, onQueryChanged: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChanged,
        placeholder = { Text(stringResource(R.string.decks_search_placeholder)) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("decks_search_input"),
    )
}

@Composable
private fun NoSearchResults(query: String) {
    val colors = LoopkyTheme.colors
    Text(
        text = stringResource(R.string.decks_search_no_results, query),
        color = colors.foregroundMuted,
        fontSize = 14.sp,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DeckGrid(decks: List<DeckTileModel>, onDeckClick: (String) -> Unit) {
    // Chunked rows rather than a LazyVerticalGrid because this lives inside the screen's own
    // scrolling Column; the column count is the window's, so a landscape tablet gets four tiles
    // across instead of two 600dp-wide ones.
    val columns = deckGridColumns()
    val rows = decks.chunked(columns)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                row.forEach { deck ->
                    DeckTile(
                        deckId = deck.id,
                        authorPubky = deck.author.pubky,
                        title = deck.title,
                        cardCount = deck.cardCount,
                        coverEmoji = deck.coverEmoji,
                        coverImage = deck.coverImage,
                        authorLabel = deck.author.label(),
                        onClick = { onDeckClick(deck.id) },
                        relation = deck.relation,
                        hasUpdate = deck.hasUpdate,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("deck_tile_${deck.id}"),
                    )
                }
                // Pad the last row so a lone tile keeps its column width instead of stretching
                // across everything the full row would have held.
                repeat(columns - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun EmptyBlock(onCreateDeckClick: () -> Unit) {
    val colors = LoopkyTheme.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
    ) {
        Text(
            text = "\uD83D\uDCDA",
            fontSize = 48.sp,
            lineHeight = 58.sp,
        )
        Text(
            text = stringResource(R.string.decks_empty_title),
            color = colors.foregroundPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        Text(
            text = stringResource(R.string.decks_empty_subtitle),
            color = colors.foregroundMuted,
            fontSize = 14.sp,
        )
        LoopkyPrimaryButton(
            label = stringResource(R.string.decks_empty_create),
            onClick = onCreateDeckClick,
            modifier = Modifier
                .testTag("decks_create")
                .padding(top = 8.dp),
        )
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
            text = stringResource(R.string.decks_error_title),
            color = colors.foregroundPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        Text(
            text = message,
            color = colors.foregroundMuted,
            fontSize = 14.sp,
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.decks_retry), color = colors.accentPrimary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun DecksScreenPreview() {
    LoopkyTheme {
        DecksScreen(
            state = DecksLibraryUiState.Content(
                deckCount = 3,
                decks = listOf(
                    DeckTileModel(
                        id = "d1",
                        title = "Spanish Essentials",
                        cardCount = 42,
                        coverEmoji = "🇪🇸",
                        author = PubkyIdentity("you9xqz1ghijkl", "Cosmic-Crystal-Panda", null, null),
                        relation = DeckRelation.Owned,
                        updatedAt = 0L,
                    ),
                    DeckTileModel(
                        id = "d2",
                        title = "Capital Cities",
                        cardCount = 50,
                        coverEmoji = "🌍",
                        author = PubkyIdentity("alex1xqz9uvwxyz", "Alex", null, null),
                        relation = DeckRelation.Followed,
                        hasUpdate = true,
                        updatedAt = 0L,
                    ),
                    DeckTileModel(
                        id = "d3",
                        title = "Chemistry Basics",
                        cardCount = 30,
                        coverEmoji = "⚗️",
                        author = PubkyIdentity("you9xqz1ghijkl", "Cosmic-Crystal-Panda", null, null),
                        relation = DeckRelation.Owned,
                        updatedAt = 0L,
                    ),
                ),
            ),
            onDeckClick = {},
            onImportClick = {},
            onImportFileClick = {},
            onCreateDeckClick = {},
            onRetry = {},
            onQueryChanged = {},
            onSortChanged = {},
        )
    }
}
