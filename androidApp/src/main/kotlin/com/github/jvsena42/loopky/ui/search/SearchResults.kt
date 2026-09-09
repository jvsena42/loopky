package com.github.jvsena42.loopky.ui.search

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.data.pubky.PubkyLink
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.presentation.discover.DiscoverDeck
import com.github.jvsena42.loopky.presentation.discover.DiscoverPerson
import com.github.jvsena42.loopky.presentation.discover.SearchUiState
import com.github.jvsena42.loopky.ui.components.AuthorRow
import com.github.jvsena42.loopky.ui.discover.DeckRow
import com.github.jvsena42.loopky.ui.discover.SectionHeader
import com.github.jvsena42.loopky.ui.discover.SectionHint
import com.github.jvsena42.loopky.ui.discover.SectionSpinner
import com.github.jvsena42.loopky.ui.layout.PaneWidth
import com.github.jvsena42.loopky.ui.layout.contentPane
import com.github.jvsena42.loopky.ui.layout.deckGridColumns
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * The results, and only the results — the box that produces them is [DiscoverSearchBar].
 *
 * Two lists rather than one ranked feed: a person and a deck are not alternatives, and merging
 * them would bury whichever kind the user meant.
 */
@Suppress("LongParameterList")
@Composable
internal fun SearchResults(
    state: SearchUiState,
    onOpenLink: (PubkyLink) -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenDeck: (String, String) -> Unit,
    onFollowToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val deckColumns = deckGridColumns()
    LazyColumn(
        // The keyboard stays up while searching, so without this the last results sit
        // under it with nothing left to scroll.
        modifier = modifier
            .fillMaxSize()
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
private fun SearchResultsPreview() {
    LoopkyTheme {
        SearchResults(
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
            onOpenLink = {},
            onOpenProfile = {},
            onOpenDeck = { _, _ -> },
            onFollowToggle = {},
        )
    }
}

@Preview
@Composable
private fun SearchResultsEmptyPreview() {
    LoopkyTheme {
        SearchResults(
            state = SearchUiState(query = "quantum", hasSearched = true),
            onOpenLink = {},
            onOpenProfile = {},
            onOpenDeck = { _, _ -> },
            onFollowToggle = {},
        )
    }
}
