package com.github.jvsena42.loopky.ui.discover

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.presentation.auth.SignInReason
import com.github.jvsena42.loopky.presentation.discover.DiscoverDeck
import com.github.jvsena42.loopky.presentation.discover.DiscoverEffect
import com.github.jvsena42.loopky.presentation.discover.DiscoverUiState
import com.github.jvsena42.loopky.presentation.discover.DiscoverViewModel
import com.github.jvsena42.loopky.presentation.discover.SectionState
import com.github.jvsena42.loopky.ui.components.GuestSignInBanner
import com.github.jvsena42.loopky.ui.components.LoopkyErrorBlock
import com.github.jvsena42.loopky.ui.components.SignInPromptDialog
import com.github.jvsena42.loopky.ui.components.errorMessage
import com.github.jvsena42.loopky.ui.layout.PaneWidth
import com.github.jvsena42.loopky.ui.layout.contentPane
import com.github.jvsena42.loopky.ui.layout.deckGridColumns
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun DiscoverRoute(
    onOpenProfile: (String) -> Unit = {},
    onOpenDeck: (deckId: String, author: String?) -> Unit = { _, _ -> },
    onOpenSearch: () -> Unit = {},
    /**
     * Discover is the whole app for a signed-out visitor, so it carries the standing offer to
     * sign in. Passed down rather than read off the state because the *shell* decides it: only
     * the guest shell has no tab bar to hold that offer somewhere else.
     */
    isGuest: Boolean = false,
    onSignIn: () -> Unit = {},
) {
    val viewModel = koinViewModel<DiscoverViewModel>()

    val context = LocalContext.current
    var signInPrompt by remember { mutableStateOf<SignInReason?>(null) }
    val currentOpenProfile by rememberUpdatedState(onOpenProfile)
    val currentOpenDeck by rememberUpdatedState(onOpenDeck)
    val currentOpenSearch by rememberUpdatedState(onOpenSearch)
    var followError by remember { mutableStateOf<ErrorReason?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                DiscoverEffect.OpenSearch -> currentOpenSearch()
                is DiscoverEffect.OpenProfile -> currentOpenProfile(effect.pubky)
                is DiscoverEffect.OpenDeck -> currentOpenDeck(effect.deckId, effect.authorPubky)
                is DiscoverEffect.ShowFollowError -> followError = effect.reason
                is DiscoverEffect.RequireSignIn -> signInPrompt = effect.reason
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
    DiscoverScreen(
        state = state,
        isGuest = isGuest,
        onSignIn = onSignIn,
        onTagSelected = viewModel::onTagSelected,
        onSearch = viewModel::onSearch,
        onOpenAuthor = viewModel::onOpenAuthor,
        onOpenDeck = viewModel::onOpenDeck,
        onFollowToggle = viewModel::onFollowToggle,
        onRefresh = viewModel::onRefresh,
        onRetryFollowing = viewModel::onRetryFollowing,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoverScreen(
    state: DiscoverUiState,
    isGuest: Boolean,
    onSignIn: () -> Unit,
    onTagSelected: (Tag?) -> Unit,
    onSearch: () -> Unit,
    onOpenAuthor: (String) -> Unit,
    onOpenDeck: (String, String) -> Unit,
    onFollowToggle: (String) -> Unit,
    onRefresh: () -> Unit,
    onRetryFollowing: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("discover_screen")
            .background(LoopkyTheme.colors.surfacePrimary)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            // A LazyColumn rather than a scrolling Column: strips settle at different times, and
            // item keys keep one landing from recomposing the others.
            // Computed out here: the section builders below are LazyListScope extensions, not
            // composables, so they cannot read the window themselves and take the count instead.
            val deckColumns = deckGridColumns()
            val tileActions = DeckTileActions(onOpenDeck = onOpenDeck, onOpenAuthor = onOpenAuthor)
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .contentPane(PaneWidth.Wide),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item(key = "header") { DiscoverHeader(onSearch = onSearch) }

                // Above the topics, below the search bar: first thing on the page, and it scrolls
                // away with everything else rather than pinning itself over the content a visitor
                // came to look at.
                if (isGuest) {
                    item(key = "guest_banner") { GuestSignInBanner(onSignIn = onSignIn) }
                }

                topicsSection(state, onTagSelected)
                // Picking a topic is an explicit question, so its answer leads. Unfiltered, browse
                // is the fallback firehose and sits under the people and decks you chose — which
                // costs a new account nothing, because the followed strip hides itself when empty.
                if (state.selectedTag != null) {
                    browseSection(state, deckColumns, onTagSelected, tileActions, onSearch)
                }
                peopleSection(state, onOpenAuthor, onFollowToggle)
                followingSection(state, deckColumns, tileActions, onRetryFollowing)
                if (state.selectedTag == null) {
                    browseSection(state, deckColumns, onTagSelected, tileActions, onSearch)
                }
            }
        }
    }
}

private fun LazyListScope.topicsSection(
    state: DiscoverUiState,
    onTagSelected: (Tag?) -> Unit,
) {
    // No placeholder when there are no topics — an absent chip row reads as "nothing to filter by",
    // which is exactly right, and an empty-state block for it would be noise.
    if (state.topics.items.isEmpty()) return
    item(key = "topics") {
        TopicRow(
            tags = state.topics.items,
            selectedTag = state.selectedTag,
            onTagSelected = onTagSelected,
        )
    }
}

private fun LazyListScope.peopleSection(
    state: DiscoverUiState,
    onOpenAuthor: (String) -> Unit,
    onFollowToggle: (String) -> Unit,
) {
    // Hidden entirely once it settles empty: an empty people strip on a young network is not
    // information, and the browse strip below is the better thing to be looking at.
    if (state.people.isEmpty) return
    item(key = "people_header") {
        SectionHeader(text = stringResource(R.string.discover_people_title))
    }
    if (state.people.isLoading) {
        item(key = "people_loading") {
            SectionSpinner(modifier = Modifier.testTag("discover_people_loading"))
        }
        return
    }
    item(key = "people") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("discover_people_row")
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.people.items.forEach { person ->
                PersonTile(
                    person = person,
                    onOpenProfile = { onOpenAuthor(person.identity.pubky) },
                    onFollowToggle = { onFollowToggle(person.identity.pubky) },
                )
            }
        }
    }
}

private fun LazyListScope.browseSection(
    state: DiscoverUiState,
    columns: Int,
    onTagSelected: (Tag?) -> Unit,
    actions: DeckTileActions,
    onSearch: () -> Unit,
) {
    // Everything browse found is already in the follow strip below, so this section has nothing
    // left to show — and "No decks tagged X yet" is a claim about the world that is false while
    // the deck it denies sits right underneath it.
    //
    // With a tag selected the header stays regardless: it names the query and carries Clear, so
    // it is worth a section of its own even with no rows under it. Unfiltered it is a bare label
    // over nothing, so the section goes entirely.
    val coveredByFollowed = state.browseFullyCoveredByFollowed
    if (coveredByFollowed && state.selectedTag == null) return

    item(key = "browse_header") {
        SectionHeader(
            text = state.selectedTag
                ?.let { stringResource(R.string.discover_browse_tag_title, it.value) }
                ?: stringResource(R.string.discover_browse_title),
            trailing = state.selectedTag?.let { { ClearTagButton(onClick = { onTagSelected(null) }) } },
        )
    }
    val browse = state.browseExcludingFollowed
    if (browse.isLoading) {
        item(key = "browse_loading") { SectionSpinner(modifier = Modifier.testTag("discover_browse_loading")) }
    }
    if (browse.isEmpty && !coveredByFollowed) {
        item(key = "browse_empty") {
            BrowseEmptyBlock(selectedTag = state.selectedTag, onSearch = onSearch)
        }
    }
    deckRows(
        section = browse,
        columns = columns,
        keyPrefix = "browse",
        tileTestTag = "discover_deck_tile",
        actions = actions,
    )
}

private fun LazyListScope.followingSection(
    state: DiscoverUiState,
    columns: Int,
    actions: DeckTileActions,
    onRetryFollowing: () -> Unit,
) {
    // Silent while it is empty: someone who follows nobody should see browse, not a reminder that
    // they follow nobody. It only appears once it has decks, or something to report.
    if (state.following.items.isEmpty() && state.following.error == null) return

    item(key = "following_header") {
        SectionHeader(
            text = stringResource(R.string.discover_following_title),
            modifier = Modifier.testTag("discover_following_section"),
        )
    }
    state.following.error?.let { reason ->
        item(key = "following_error") {
            LoopkyErrorBlock(reason = reason, onRetry = onRetryFollowing)
        }
    }
    deckRows(
        section = state.following,
        columns = columns,
        keyPrefix = "following",
        tileTestTag = "discover_following_tile",
        actions = actions,
    )
}

private fun LazyListScope.deckRows(
    section: SectionState<DiscoverDeck>,
    columns: Int,
    keyPrefix: String,
    tileTestTag: String,
    actions: DeckTileActions,
) {
    val rows = section.items.chunked(columns)
    items(
        items = rows,
        key = { row -> keyPrefix + ":" + row.joinToString(",") { "${it.authorPubky}/${it.id}" } },
    ) { row ->
        DeckRow(
            decks = row,
            columns = columns,
            onOpenDeck = actions.onOpenDeck,
            onOpenAuthor = actions.onOpenAuthor,
            tileTestTag = tileTestTag,
        )
    }
}

/** Drops the topic filter and goes back to browsing everything. */
@Composable
private fun ClearTagButton(onClick: () -> Unit) {
    val pillShape = RoundedCornerShape(50)
    Text(
        text = stringResource(R.string.discover_clear_tag),
        color = LoopkyTheme.colors.accentPrimary,
        fontSize = 13.sp,
        fontWeight = FontWeight.W700,
        modifier = Modifier
            .testTag("discover_clear_tag")
            .clip(pillShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun DiscoverHeader(onSearch: () -> Unit) {
    val colors = LoopkyTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.discover_title),
            color = colors.foregroundPrimary,
            fontSize = 28.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        // A magnifier alone, and the platform's own button: what the icon means needs no label,
        // and search reaches everything the old "Add friend" pill did — pasting a pubky is one of
        // the things it accepts, rather than the only thing. Styled like the Decks header's search
        // button so the two tab headers read as the same furniture.
        IconButton(onClick = onSearch, modifier = Modifier.testTag("discover_search")) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = stringResource(R.string.discover_search),
                tint = colors.foregroundPrimary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

private fun previewDeck(id: String, title: String, emoji: String, author: String, name: String) =
    DiscoverDeck(
        id = id,
        authorPubky = author,
        title = title,
        cardCount = 24,
        coverEmoji = emoji,
        author = PubkyIdentity(author, name, null, null),
        tags = listOf("spanish"),
    )

@Preview
@Composable
private fun DiscoverScreenPreview() {
    LoopkyTheme {
        DiscoverScreen(
            state = DiscoverUiState(
                topics = SectionState(items = listOf(Tag("spanish"), Tag("biology"))),
                browse = SectionState(
                    items = listOf(
                        previewDeck("1", "Spanish basics", "📚", "abc123def456ghi", "Ada"),
                        previewDeck("2", "Biology 101", "🧬", "def456ghi789jkl", "Grace"),
                    ),
                ),
                following = SectionState(
                    items = listOf(previewDeck("3", "Chess openings", "♟️", "ghi789jkl012mno", "Alan")),
                ),
            ),
            isGuest = false,
            onSignIn = {},
            onTagSelected = {},
            onSearch = {},
            onOpenAuthor = {},
            onOpenDeck = { _, _ -> },
            onFollowToggle = {},
            onRefresh = {},
            onRetryFollowing = {},
        )
    }
}

/** The state a brand-new account lands on before anything has been published network-wide. */
@Preview
@Composable
private fun DiscoverScreenEmptyBrowsePreview() {
    LoopkyTheme {
        DiscoverScreen(
            state = DiscoverUiState(),
            isGuest = false,
            onSignIn = {},
            onTagSelected = {},
            onSearch = {},
            onOpenAuthor = {},
            onOpenDeck = { _, _ -> },
            onFollowToggle = {},
            onRefresh = {},
            onRetryFollowing = {},
        )
    }
}

/** Discover as a signed-out visitor sees it: the same content, plus the way in. */
@Preview
@Composable
private fun DiscoverScreenGuestPreview() {
    LoopkyTheme {
        DiscoverScreen(
            state = DiscoverUiState(
                isSignedIn = false,
                topics = SectionState(items = listOf(Tag("spanish"), Tag("biology"))),
                browse = SectionState(
                    items = listOf(previewDeck("1", "Spanish basics", "📚", "abc123def456ghi", "Ada")),
                ),
            ),
            isGuest = true,
            onSignIn = {},
            onTagSelected = {},
            onSearch = {},
            onOpenAuthor = {},
            onOpenDeck = { _, _ -> },
            onFollowToggle = {},
            onRefresh = {},
            onRetryFollowing = {},
        )
    }
}

/**
 * The two ways out of a deck tile — into the deck, or into whoever wrote it.
 *
 * Carried together because they always are: every section that renders tiles forwards both,
 * unchanged, to the row beneath it.
 */
private data class DeckTileActions(
    val onOpenDeck: (String, String) -> Unit,
    val onOpenAuthor: (String) -> Unit,
)
