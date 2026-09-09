package com.github.jvsena42.loopky.ui.decks

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.VerticalDragHandle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.jvsena42.loopky.presentation.decks.DeckDetailUiState
import com.github.jvsena42.loopky.ui.components.AuthorRow
import com.github.jvsena42.loopky.ui.components.CardPreviewRow
import com.github.jvsena42.loopky.ui.components.StatsBar
import com.github.jvsena42.loopky.ui.components.TagChip
import com.github.jvsena42.loopky.ui.layout.PaneWidth
import com.github.jvsena42.loopky.ui.layout.contentPane

/**
 * Everything above the card list: cover, title, author, tags, stats and the keep/study actions.
 *
 * Extracted so the wide layout can stand it in a column of its own beside the cards instead of
 * stacking it above them. [showHeaderBar] is how the two layouts differ — stacked, the back and
 * share buttons belong to the scrolling header; side by side they belong to the screen, and are
 * drawn once across the top rather than trapped in the left pane.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DeckDetailHeader(
    state: DeckDetailUiState.Content,
    showHeaderBar: Boolean,
    onOpenTag: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onBackClick: () -> Unit,
    onShareClick: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onToggleFollow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        // Header: Back + Edit (owned or followed) + Delete (owner only) + Share
        if (showHeaderBar) {
            HeaderBar(
                isOwned = state.isOwned,
                canEdit = state.canEdit,
                onBackClick = onBackClick,
                onShareClick = onShareClick,
                onEditClick = onEditClick,
                onDeleteClick = onDeleteClick,
            )
        }

        // Cover
        CoverSection(
            coverEmoji = state.coverEmoji,
            coverImageUrl = state.coverImageUrl,
            coverImageBase64 = state.coverImageBase64,
            isOwned = state.isOwned,
        )

        // Owned badge
        if (state.isOwned) {
            OwnedBadgeRow()
            if (state.isIncomplete) IncompleteWarning()
        }

        // Title + Description
        TitleSection(title = state.title, description = state.description)

        // Credit for a clone, so attribution lives on the copy and not only in the
        // manifest's `source` block.
        state.clonedFrom?.let { ClonedFromRow(author = it) }

        // Hidden at zero rather than shown as "0 following": the indexer returns
        // nothing when it is unreachable or has not caught up, and a confident zero
        // would be a lie in both cases.
        SocialCountsRow(
            followerCount = state.followerCount,
            clonedCount = state.clonedCount,
        )

        // Author — tapping them opens their profile. This is where you actually meet
        // a stranger, so leaving it inert was the one dead end into their decks.
        // Your own name stays inert: there is nowhere to go but the Profile tab.
        AuthorRow(
            identity = state.author,
            isOwned = state.isOwned,
            onNameClick = if (state.isOwned) {
                null
            } else {
                { onOpenProfile(state.author.pubky) }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("deck_detail_author"),
        )

        // Tags
        if (state.tags.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.tags.forEach { tag ->
                    // Live since #26: global browse exists, so a chip leads to every
                    // deck on the network carrying it rather than to a dead end.
                    TagChip(tag = tag, onClick = { onOpenTag(tag) })
                }
            }
        }

        // Stats
        StatsBar(
            totalCards = state.totalCards,
            dueLabel = state.dueLabel,
            newCards = state.newCards,
            masteredPercent = state.masteredPercent,
            // Only Total means anything on a deck that is not yours yet: the action
            // below is Follow, and there is nothing to be due.
            showProgress = state.isOwned || state.isFollowing,
        )

        // The one way of keeping someone else's deck. Clone stood beside it as an
        // equal until #254, which asked a reader who had just found a deck to pick
        // between two words whose difference they had no reason to know — and made
        // the cheap, reversible action look like half a decision. Copying is now
        // reached from Edit, where the difference is the question. It sits under the
        // stats rather than in the bottom bar: crowded against Study it read as
        // competing primaries, and Study belongs to a deck you have already kept.
        if (!state.isOwned) {
            FollowDeckButton(
                isFollowing = state.isFollowing,
                isPending = state.isFollowPending,
                onClick = onToggleFollow,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Deck detail on a landscape tablet: what the deck *is* on the left, what is *in* it on the right.
 *
 * Stacked, this screen spends the whole first screenful on cover art and metadata and pushes the
 * cards — the thing you came to look at — below the fold, while every card row runs the full width
 * with its prompt at one edge and its answer at the other. Side by side, the metadata column is a
 * readable width and the card list is visible immediately, which is the actual point of the extra
 * room. The list stays a `LazyColumn` for the same reason it always was: a 442-card deck.
 */
@Composable
internal fun WideDeckDetail(
    state: DeckDetailUiState.Content,
    onOpenTag: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onBackClick: () -> Unit,
    onShareClick: () -> Unit,
    onCardClick: (String) -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onToggleFollow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .contentPane(PaneWidth.Wide)
            .padding(horizontal = 20.dp)
            .testTag("deck_detail_content"),
    ) {
        // Across the top rather than inside the left pane: back and share act on the screen, not
        // on the metadata column, and a back button that scrolls away is a back button people
        // cannot find.
        HeaderBar(
            isOwned = state.isOwned,
            canEdit = state.canEdit,
            onBackClick = onBackClick,
            onShareClick = onShareClick,
            onEditClick = onEditClick,
            onDeleteClick = onDeleteClick,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val maxPaneWidth = (maxWidth - MIN_CARDS_PANE_WIDTH - PANE_GAP).coerceAtLeast(MIN_DETAIL_PANE_WIDTH)
            var paneWidth by rememberSaveable(stateSaver = DpSaver) {
                mutableStateOf(DETAIL_PANE_WIDTH)
            }
            val density = LocalDensity.current
            // In RTL the Row lays its first child out on the right, so a drag towards the trailing
            // edge is a drag towards the left. Without the flip the handle widens the pane the finger
            // is moving away from.
            val towardsTrailing = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
            val handleInteractionSource = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(PANE_GAP / 2),
            ) {
                DeckDetailHeader(
                    state = state,
                    showHeaderBar = false,
                    onOpenTag = onOpenTag,
                    onOpenProfile = onOpenProfile,
                    onBackClick = onBackClick,
                    onShareClick = onShareClick,
                    onEditClick = onEditClick,
                    onDeleteClick = onDeleteClick,
                    onToggleFollow = onToggleFollow,
                    modifier = Modifier
                        .width(paneWidth.coerceIn(MIN_DETAIL_PANE_WIDTH, maxPaneWidth))
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 20.dp),
                )
                // The split is the reader's to set: a deck whose description runs long wants a wider
                // left column, and one being scanned for a card wants none of it. Not announced to
                // TalkBack — a drag is not operable there, and both panes stay usable at the default,
                // so a control that could only report itself would be one more dead stop.
                VerticalDragHandle(
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .draggable(
                            orientation = Orientation.Horizontal,
                            interactionSource = handleInteractionSource,
                            state = rememberDraggableState { delta ->
                                val step = with(density) { (delta * towardsTrailing).toDp() }
                                paneWidth = (paneWidth + step)
                                    .coerceIn(MIN_DETAIL_PANE_WIDTH, maxPaneWidth)
                            },
                        ),
                    interactionSource = handleInteractionSource,
                )
                LazyColumn(
                    modifier = Modifier.weight(1f).testTag("deck_detail_cards"),
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {
                    item(key = "cards_heading") {
                        CardsHeading(count = state.cardPreviews.size, modifier = Modifier.padding(bottom = 12.dp))
                    }
                    if (state.cardPreviews.isEmpty()) {
                        item(key = "cards_empty") { CardsEmptyState(isOwned = state.isOwned) }
                    } else {
                        items(state.cardPreviews, key = { it.id }) { card ->
                            CardPreviewRow(
                                frontText = card.frontText,
                                backText = card.backText,
                                frontImageRef = card.frontImageRef,
                                deckId = state.deckId,
                                authorPubky = state.author.pubky,
                                onClick = if (state.isOwned) {
                                    { onCardClick(card.id) }
                                } else {
                                    null
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                                    .testTag("deck_card_row"),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The metadata column's starting width — a readable measure for the title and description. */
private val DETAIL_PANE_WIDTH = 360.dp

/** Narrow enough to still read a title, and the floor the handle cannot drag past. */
private val MIN_DETAIL_PANE_WIDTH = 260.dp

/** What the card list keeps whatever the handle does — a prompt and its answer on one row. */
private val MIN_CARDS_PANE_WIDTH = 320.dp

/** The gutter between the two panes, half of it either side of the handle. */
private val PANE_GAP = 28.dp

/** [Dp] is not one of the types [rememberSaveable] can bundle on its own. */
private val DpSaver = Saver<Dp, Float>(save = { it.value }, restore = { it.dp })
