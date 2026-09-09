package com.github.jvsena42.loopky.ui.tagbrowse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.presentation.discover.DiscoverDeck
import com.github.jvsena42.loopky.presentation.discover.TagBrowseEffect
import com.github.jvsena42.loopky.presentation.discover.TagBrowseUiState
import com.github.jvsena42.loopky.presentation.discover.TagBrowseViewModel
import com.github.jvsena42.loopky.ui.components.LoopkyLoadingScreen
import com.github.jvsena42.loopky.ui.discover.DeckRow
import com.github.jvsena42.loopky.ui.layout.PaneWidth
import com.github.jvsena42.loopky.ui.layout.contentPane
import com.github.jvsena42.loopky.ui.layout.deckGridColumns
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun TagBrowseRoute(
    tag: String,
    onBack: () -> Unit = {},
    onOpenProfile: (String) -> Unit = {},
    onOpenDeck: (deckId: String, author: String?) -> Unit = { _, _ -> },
) {
    val viewModel = koinViewModel<TagBrowseViewModel> { parametersOf(tag) }

    val currentOpenProfile by rememberUpdatedState(onOpenProfile)
    val currentOpenDeck by rememberUpdatedState(onOpenDeck)

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is TagBrowseEffect.OpenDeck -> currentOpenDeck(effect.deckId, effect.authorPubky)
                is TagBrowseEffect.OpenProfile -> currentOpenProfile(effect.pubky)
            }
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    TagBrowseScreen(
        label = viewModel.label,
        state = state,
        onBack = onBack,
        onOpenDeck = viewModel::onOpenDeck,
        onOpenAuthor = viewModel::onOpenAuthor,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagBrowseScreen(
    label: String,
    state: TagBrowseUiState,
    onBack: () -> Unit,
    onOpenDeck: (String, String) -> Unit,
    onOpenAuthor: (String) -> Unit,
) {
    val colors = LoopkyTheme.colors
    Scaffold(
        modifier = Modifier.testTag("tag_browse_screen"),
        containerColor = colors.surfacePrimary,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.tag_browse_title, label),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = colors.foregroundPrimary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("tag_browse_back")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.tag_browse_back),
                            tint = colors.foregroundPrimary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surfacePrimary),
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (state) {
                TagBrowseUiState.Loading -> LoopkyLoadingScreen()
                TagBrowseUiState.Empty -> EmptyBlock(label = label)
                is TagBrowseUiState.Content -> DeckGrid(
                    decks = state.decks,
                    onOpenDeck = onOpenDeck,
                    onOpenAuthor = onOpenAuthor,
                )
            }
        }
    }
}

@Composable
private fun DeckGrid(
    decks: List<DiscoverDeck>,
    onOpenDeck: (String, String) -> Unit,
    onOpenAuthor: (String) -> Unit,
) {
    val columns = deckGridColumns()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .contentPane(PaneWidth.Wide)
            .testTag("tag_browse_grid"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(
            items = decks.chunked(columns),
            key = { row -> row.joinToString(",") { "${it.authorPubky}/${it.id}" } },
        ) { row ->
            DeckRow(
                decks = row,
                columns = columns,
                onOpenDeck = onOpenDeck,
                onOpenAuthor = onOpenAuthor,
                tileTestTag = "tag_browse_tile",
            )
        }
    }
}

@Composable
private fun EmptyBlock(label: String) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tag_browse_empty")
            .padding(top = 64.dp, start = 20.dp, end = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = stringResource(R.string.discover_empty_tag_emoji), fontSize = 36.sp)
        Text(
            text = stringResource(R.string.tag_browse_empty_title, label),
            color = colors.foregroundPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.tag_browse_empty_subtitle),
            color = colors.foregroundMuted,
            fontSize = 13.sp,
        )
    }
}

@Preview
@Composable
private fun TagBrowseScreenPreview() {
    LoopkyTheme {
        TagBrowseScreen(
            label = "spanish",
            state = TagBrowseUiState.Content(
                listOf(
                    DiscoverDeck(
                        id = "1",
                        authorPubky = "abc123def456ghi",
                        title = "Spanish basics",
                        cardCount = 24,
                        coverEmoji = "📚",
                        author = PubkyIdentity("abc123def456ghi", "Ada", null, null),
                        tags = listOf("spanish"),
                    ),
                ),
            ),
            onBack = {},
            onOpenDeck = { _, _ -> },
            onOpenAuthor = {},
        )
    }
}
