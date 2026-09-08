package com.github.jvsena42.loopky.ui.search

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.data.pubky.PubkyLink
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.presentation.auth.SignInReason
import com.github.jvsena42.loopky.presentation.discover.DiscoverDeck
import com.github.jvsena42.loopky.presentation.discover.DiscoverPerson
import com.github.jvsena42.loopky.presentation.discover.SearchEffect
import com.github.jvsena42.loopky.presentation.discover.SearchUiState
import com.github.jvsena42.loopky.presentation.discover.SearchViewModel
import com.github.jvsena42.loopky.ui.components.AuthorRow
import com.github.jvsena42.loopky.ui.components.SignInPromptDialog
import com.github.jvsena42.loopky.ui.components.errorMessage
import com.github.jvsena42.loopky.ui.discover.DeckRow
import com.github.jvsena42.loopky.ui.discover.SectionHeader
import com.github.jvsena42.loopky.ui.discover.SectionHint
import com.github.jvsena42.loopky.ui.discover.SectionSpinner
import com.github.jvsena42.loopky.ui.discover.scanPubky
import com.github.jvsena42.loopky.ui.layout.PaneWidth
import com.github.jvsena42.loopky.ui.layout.contentPane
import com.github.jvsena42.loopky.ui.layout.deckGridColumns
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SearchRoute(
    onBack: () -> Unit = {},
    onOpenProfile: (String) -> Unit = {},
    onOpenDeck: (deckId: String, author: String?) -> Unit = { _, _ -> },
    /** Leave for the sign-in flow, when a signed-out visitor reaches for Follow. */
    onSignIn: () -> Unit = {},
) {
    val viewModel = koinViewModel<SearchViewModel>()

    val context = LocalContext.current
    val currentOpenProfile by rememberUpdatedState(onOpenProfile)
    val currentOpenDeck by rememberUpdatedState(onOpenDeck)
    var followError by remember { mutableStateOf<ErrorReason?>(null) }
    var signInPrompt by remember { mutableStateOf<SignInReason?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is SearchEffect.OpenProfile -> currentOpenProfile(effect.pubky)
                is SearchEffect.OpenDeck -> currentOpenDeck(effect.deckId, effect.authorPubky)
                is SearchEffect.ShowFollowError -> followError = effect.reason
                is SearchEffect.RequireSignIn -> signInPrompt = effect.reason
            }
        }
    }

    // Resolved here rather than in the effect collector: errorMessage is @Composable.
    followError?.let { reason ->
        val message = errorMessage(reason)
        LaunchedEffect(reason, message) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            followError = null
        }
    }

    signInPrompt?.let { reason ->
        SignInPromptDialog(
            reason = reason,
            onSignIn = {
                signInPrompt = null
                onSignIn()
            },
            onDismiss = { signInPrompt = null },
        )
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    SearchScreen(
        state = state,
        onBack = onBack,
        onQueryChange = viewModel::onQueryChange,
        onClearQuery = viewModel::onClearQuery,
        onSubmit = viewModel::onSubmit,
        // A scan is a paste by another route: it lands in the box, where it reads as the address
        // it is and can be corrected rather than acted on blindly.
        onScan = { scanPubky(context) { scanned -> viewModel.onQueryChange(scanned) } },
        onOpenLink = viewModel::onOpenLink,
        onOpenProfile = viewModel::onOpenProfile,
        onOpenDeck = viewModel::onOpenDeck,
        onFollowToggle = viewModel::onFollowToggle,
    )
}

/**
 * One box over people and decks. The results are two lists rather than one ranked feed: a person
 * and a deck are not alternatives, and merging them would bury whichever kind the user meant.
 */
@Suppress("LongParameterList")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScreen(
    state: SearchUiState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onSubmit: () -> Unit,
    onScan: () -> Unit,
    onOpenLink: (PubkyLink) -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenDeck: (String, String) -> Unit,
    onFollowToggle: (String) -> Unit,
) {
    val colors = LoopkyTheme.colors
    Scaffold(
        modifier = Modifier.testTag("search_screen"),
        containerColor = colors.surfacePrimary,
        topBar = {
            TopAppBar(
                title = {
                    SearchField(
                        query = state.query,
                        onQueryChange = onQueryChange,
                        onClearQuery = onClearQuery,
                        onSubmit = onSubmit,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("search_back")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.search_back),
                            tint = colors.foregroundPrimary,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onScan, modifier = Modifier.testTag("search_scan")) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = stringResource(R.string.search_scan_qr),
                            tint = colors.foregroundSecondary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.surfacePrimary),
            )
        },
    ) { padding ->
        val deckColumns = deckGridColumns()
        LazyColumn(
            // The keyboard stays up while searching, so without this the last results sit
            // under it with nothing left to scroll.
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .contentPane(PaneWidth.Wide)
                .imePadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // The address the text already names, above anything the indexer has to be asked for:
            // it is certain, it is instant, and it is the only result that reaches an account no
            // index has seen yet.
            state.directLink?.let { link ->
                item(key = "direct") { DirectHitRow(link = link, onClick = { onOpenLink(link) }) }
            }
            if (state.isSearching) {
                item(key = "searching") {
                    SectionSpinner(modifier = Modifier.testTag("search_loading"))
                }
            }
            peopleSection(state, onOpenProfile, onFollowToggle)
            decksSection(deckColumns, state, onOpenDeck, onOpenProfile)
            if (state.isEmpty) {
                item(key = "empty") { NoMatchesBlock(query = state.query) }
            }
            if (state.query.isBlank()) {
                item(key = "hint") {
                    SectionHint(
                        text = stringResource(R.string.search_hint),
                        modifier = Modifier.testTag("search_hint"),
                    )
                }
            }
        }
    }
}

private fun LazyListScope.peopleSection(
    state: SearchUiState,
    onOpenProfile: (String) -> Unit,
    onFollowToggle: (String) -> Unit,
) {
    if (state.people.isEmpty()) return
    item(key = "people_header") {
        SectionHeader(text = stringResource(R.string.search_people_title))
    }
    items(items = state.people, key = { it.identity.pubky }) { person ->
        AuthorRow(
            identity = person.identity,
            isFollowing = person.isFollowing,
            isFollowPending = person.isFollowPending,
            onFollowClick = { onFollowToggle(person.identity.pubky) },
            onNameClick = { onOpenProfile(person.identity.pubky) },
            modifier = Modifier.fillMaxWidth().testTag("search_person"),
        )
    }
}

private fun LazyListScope.decksSection(
    columns: Int,
    state: SearchUiState,
    onOpenDeck: (String, String) -> Unit,
    onOpenAuthor: (String) -> Unit,
) {
    if (state.decks.isEmpty()) return
    item(key = "decks_header") {
        SectionHeader(text = stringResource(R.string.search_decks_title))
    }
    val rows = state.decks.chunked(columns)
    items(
        items = rows,
        key = { row -> "decks:" + row.joinToString(",") { "${it.authorPubky}/${it.id}" } },
    ) { row ->
        DeckRow(
            decks = row,
            columns = columns,
            onOpenDeck = onOpenDeck,
            onOpenAuthor = onOpenAuthor,
            tileTestTag = "search_deck_tile",
        )
    }
}

/**
 * The box itself. Focused on arrival — the screen exists to be typed into, and landing on it with
 * the keyboard down costs a tap that has no other purpose.
 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onSubmit: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("search_input")
            .focusRequester(focusRequester),
        placeholder = {
            // One line, always: the title slot of a top bar is narrow, and a placeholder that
            // wraps pushes the field out of the bar.
            Text(
                text = stringResource(R.string.search_placeholder),
                fontSize = 15.sp,
                color = colors.foregroundMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClearQuery, modifier = Modifier.testTag("search_clear")) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = stringResource(R.string.search_clear),
                        tint = colors.foregroundMuted,
                    )
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = colors.accentPrimary,
            focusedTextColor = colors.foregroundPrimary,
            unfocusedTextColor = colors.foregroundPrimary,
        ),
    )
}

/** What a pasted address resolves to, offered as a row rather than opened from under the user. */
@Composable
private fun DirectHitRow(link: PubkyLink, onClick: () -> Unit) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("search_direct_hit")
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceCard)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(
                    when (link) {
                        is PubkyLink.Profile -> R.string.search_open_profile
                        is PubkyLink.Deck -> R.string.search_open_deck
                    },
                ),
                color = colors.foregroundPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = when (link) {
                is PubkyLink.Profile -> link.pubky
                is PubkyLink.Deck -> link.deckId
            },
            color = colors.foregroundMuted,
            fontSize = 12.sp,
        )
    }
}

/** A query that settled with nothing behind it. */
@Composable
private fun NoMatchesBlock(query: String) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("search_empty")
            .padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = stringResource(R.string.search_empty_emoji), fontSize = 36.sp)
        Text(
            text = stringResource(R.string.search_empty_title, query),
            color = colors.foregroundPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.search_empty_subtitle),
            color = colors.foregroundMuted,
            fontSize = 13.sp,
        )
        Spacer(modifier = Modifier.size(4.dp))
    }
}

@Preview
@Composable
private fun SearchScreenPreview() {
    LoopkyTheme {
        SearchScreen(
            state = SearchUiState(
                query = "spanish",
                hasSearched = true,
                people = listOf(
                    DiscoverPerson(PubkyIdentity("abc123def456ghi", "Ada", null, null)),
                    DiscoverPerson(PubkyIdentity("def456ghi789jkl", "Grace", null, null), isFollowing = true),
                ),
                decks = listOf(
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
            onQueryChange = {},
            onClearQuery = {},
            onSubmit = {},
            onScan = {},
            onOpenLink = {},
            onOpenProfile = {},
            onOpenDeck = { _, _ -> },
            onFollowToggle = {},
        )
    }
}

@Preview
@Composable
private fun SearchScreenEmptyPreview() {
    LoopkyTheme {
        SearchScreen(
            state = SearchUiState(query = "quantum", hasSearched = true),
            onBack = {},
            onQueryChange = {},
            onClearQuery = {},
            onSubmit = {},
            onScan = {},
            onOpenLink = {},
            onOpenProfile = {},
            onOpenDeck = { _, _ -> },
            onFollowToggle = {},
        )
    }
}
