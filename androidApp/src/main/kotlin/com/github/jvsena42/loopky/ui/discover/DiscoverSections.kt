package com.github.jvsena42.loopky.ui.discover

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.presentation.discover.DiscoverDeck
import com.github.jvsena42.loopky.presentation.discover.DiscoverPerson
import com.github.jvsena42.loopky.ui.components.DeckTile
import com.github.jvsena42.loopky.ui.components.PubkyAvatar
import com.github.jvsena42.loopky.ui.components.TagChip
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.label
import com.github.jvsena42.loopky.ui.util.truncatedPubky

/**
 * The shared furniture of Discover's strips. Kept beside [DiscoverScreen] rather than in
 * `ui/components` because the section header, the strip spinner and the two-column grid row are
 * Discover's own layout language, not app-wide components.
 */

/** Small caps label above a strip. Matches the convention the rest of the app already uses. */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = LoopkyTheme.colors.foregroundSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.W700,
        )
        trailing?.invoke()
    }
}

/**
 * A strip's own spinner. Deliberately not `LoopkyLoadingScreen`, which fills the viewport and must
 * not sit inside a scrolling list — each strip spins in its own row while the others render.
 */
@Composable
fun SectionSpinner(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 20.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            color = LoopkyTheme.colors.accentPrimary,
            strokeWidth = 2.dp,
        )
    }
}

/** A one-line explanation where a strip settled with nothing in it. */
@Composable
fun SectionHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        color = LoopkyTheme.colors.foregroundMuted,
        fontSize = 13.sp,
    )
}

/**
 * Horizontally scrolling topic chips, faded at whichever edge has more behind it.
 *
 * Without the fade the row simply cut its trailing chip mid-word at the screen inset, which reads
 * as a clipping bug rather than as an invitation to scroll.
 */
@Composable
fun TopicRow(
    tags: List<Tag>,
    selectedTag: Tag?,
    onTagSelected: (Tag?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val surface = LoopkyTheme.colors.surfacePrimary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("discover_topic_row")
            .scrollEdgeFade(scrollState, surface)
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tags.forEach { tag ->
            TagChip(
                tag = tag.value,
                selected = tag == selectedTag,
                onClick = { onTagSelected(tag) },
                modifier = Modifier.testTag("discover_topic_chip"),
            )
        }
    }
}

/**
 * One row of the deck grid, [columns] tiles wide. The grid is built from chunked rows rather than
 * a `LazyVerticalGrid` because it lives inside the screen's own `LazyColumn`.
 *
 * [columns] is passed in rather than read here so it matches the count the caller chunked by — the
 * two going out of step would leave a row either overfull or short of its padding.
 */
@Composable
fun DeckRow(
    decks: List<DiscoverDeck>,
    columns: Int,
    onOpenDeck: (String, String) -> Unit,
    onOpenAuthor: (String) -> Unit,
    modifier: Modifier = Modifier,
    tileTestTag: String = "discover_deck_tile",
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        decks.forEach { deck ->
            DeckTile(
                deckId = deck.id,
                authorPubky = deck.authorPubky,
                title = deck.title,
                cardCount = deck.cardCount,
                coverEmoji = deck.coverEmoji,
                coverImage = deck.coverImage,
                authorLabel = deck.author.label(),
                onClick = { onOpenDeck(deck.authorPubky, deck.id) },
                onAuthorClick = { onOpenAuthor(deck.authorPubky) },
                modifier = Modifier.weight(1f).testTag(tileTestTag),
            )
        }
        // Keeps a short last row's tiles at their column width instead of stretching them across
        // everything a full row would have held.
        repeat(columns - decks.size) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

/**
 * One suggested account: avatar, name, truncated pubky, and a follow pill. Tapping the tile opens
 * the profile; tapping the pill follows without leaving Discover, which is the whole point for
 * someone who has nobody to follow yet.
 */
@Composable
fun PersonTile(
    person: DiscoverPerson,
    onOpenProfile: () -> Unit,
    onFollowToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    val pillShape = RoundedCornerShape(50)
    Column(
        modifier = modifier
            .testTag("discover_person")
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceCard)
            .clickable(onClick = onOpenProfile)
            .padding(horizontal = 14.dp, vertical = 14.dp)
            .width(120.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PubkyAvatar(identity = person.identity, size = 56.dp)
        Text(
            text = person.identity.label(),
            color = colors.foregroundPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.W700,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = truncatedPubky(person.identity.pubky),
            color = colors.foregroundMuted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(
                if (person.isFollowing) {
                    R.string.component_author_row_following
                } else {
                    R.string.component_author_row_follow
                },
            ),
            color = if (person.isFollowing) colors.accentSecondary else colors.foregroundOnAccent,
            fontSize = 12.sp,
            fontWeight = FontWeight.W700,
            modifier = Modifier
                .testTag("discover_follow")
                .clip(pillShape)
                .background(
                    if (person.isFollowing) colors.accentSecondarySoft else colors.accentSecondary,
                )
                .clickable(enabled = !person.isFollowPending, onClick = onFollowToggle)
                .alpha(if (person.isFollowPending) PENDING_ALPHA else 1f)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

/**
 * Shown when global browse comes back with nothing. Never a plain dead end: the index being young
 * is stated plainly, and there is still something to do.
 */
@Composable
fun BrowseEmptyBlock(
    selectedTag: Tag?,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("discover_browse_empty")
            .padding(top = 24.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(
                if (selectedTag == null) {
                    R.string.discover_browse_empty_emoji
                } else {
                    R.string.discover_empty_tag_emoji
                },
            ),
            fontSize = 36.sp,
            lineHeight = 43.sp,
        )
        Text(
            text = selectedTag
                ?.let { stringResource(R.string.discover_empty_tag_title, it.value) }
                ?: stringResource(R.string.discover_browse_empty_title),
            color = colors.foregroundPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = selectedTag
                ?.let { stringResource(R.string.discover_empty_tag_subtitle) }
                ?: stringResource(R.string.discover_browse_empty_subtitle),
            color = colors.foregroundMuted,
            fontSize = 13.sp,
        )
        if (selectedTag == null) {
            Spacer(modifier = Modifier.size(4.dp))
            Text(
                text = stringResource(R.string.discover_search_cta),
                color = colors.foregroundOnAccent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .testTag("discover_browse_empty_search")
                    .clip(RoundedCornerShape(50))
                    .background(colors.accentSecondary)
                    .clickable(onClick = onSearch)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

/** Dims the pill while a follow request is in flight, matching AuthorRow. */
private const val PENDING_ALPHA = 0.5f

/**
 * Fades the row out over whichever edge still has content behind it.
 *
 * Drawn rather than laid out, so it costs no space and never shifts the chips; and driven by the
 * live scroll position, so the leading fade only appears once there is something to scroll back to.
 */
private fun Modifier.scrollEdgeFade(scrollState: ScrollState, surface: Color): Modifier =
    this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val width = EDGE_FADE_WIDTH.toPx()
            if (scrollState.value > 0) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(surface, Color.Transparent),
                        endX = width,
                    ),
                    size = Size(width, size.height),
                )
            }
            if (scrollState.value < scrollState.maxValue) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, surface),
                        startX = size.width - width,
                        endX = size.width,
                    ),
                    topLeft = Offset(size.width - width, 0f),
                    size = Size(width, size.height),
                )
            }
        }

private val EDGE_FADE_WIDTH = 24.dp
