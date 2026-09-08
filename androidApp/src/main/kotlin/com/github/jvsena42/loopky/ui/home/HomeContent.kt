package com.github.jvsena42.loopky.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.presentation.home.DeckSummary
import com.github.jvsena42.loopky.presentation.home.HomeUiState
import com.github.jvsena42.loopky.ui.components.DeckCover
import com.github.jvsena42.loopky.ui.components.DeckTile
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.relativeFromNow

/**
 * Daily study state: hero card + deck list.
 */
@Composable
fun HomeContent(
    state: HomeUiState.Content,
    onStartStudyClick: () -> Unit,
    onSeeAllDecksClick: () -> Unit,
    onDeckClick: (String) -> Unit,
) {
    HomeHero(state = state, onStartStudyClick = onStartStudyClick)
    TodaysDecksSection(
        decks = state.decks,
        countsKnown = state.countsKnown,
        onSeeAllClick = onSeeAllDecksClick,
        onDeckClick = onDeckClick,
    )
}

/**
 * The day's headline: what is due and the button that starts it, or the caught-up note.
 *
 * Split out from [HomeContent] so the wide layout can put it beside the deck list rather than
 * above it — stacked on a landscape tablet, the two together fill about half the height and leave
 * the rest of the screen empty.
 */
@Composable
fun HomeHero(
    state: HomeUiState.Content,
    onStartStudyClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (state.isCaughtUp) {
            CaughtUpHeroCard(nextDueAtMillis = state.nextDueAtMillis)
        } else {
            DueTodayHeroCard(
                studyTarget = state.studyTarget,
                doneToday = state.doneToday,
                newCardsToday = state.newCardsToday,
                newCardsGoal = state.newCardsGoal,
                countsKnown = state.countsKnown,
                onStartStudyClick = onStartStudyClick,
            )
        }
    }
}

/**
 * Today's decks as a wall of cover tiles, for windows with room for them.
 *
 * Same list as the compact [TodaysDecksSection] — `decks` is every studiable deck, not only the
 * ones with something due — drawn as covers rather than 60dp rows. The swap is the point: a
 * landscape tablet showing six decks as thin rows uses a fifth of the height it has and leaves the
 * rest empty, while the same six as tiles fill it and put the cover art to work.
 */
@Composable
fun TodaysDecksGrid(
    decks: List<DeckSummary>,
    columns: Int,
    countsKnown: Boolean,
    onSeeAllClick: () -> Unit,
    onDeckClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.home_todays_decks),
                color = colors.foregroundPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            TextButton(onClick = onSeeAllClick, modifier = Modifier.testTag("home_see_all_decks")) {
                Text(
                    text = stringResource(R.string.home_see_all),
                    color = colors.accentSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        decks.chunked(columns).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                row.forEach { deck ->
                    DeckTile(
                        deckId = deck.id,
                        authorPubky = deck.authorPubky,
                        title = deck.title,
                        cardCount = deck.cardCount,
                        coverEmoji = deck.coverInitial.toString(),
                        coverImage = deck.coverImage,
                        authorLabel = dueCaption(deck, countsKnown),
                        onClick = { onDeckClick(deck.id) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("home_deck_tile_${deck.id}"),
                    )
                }
                repeat(columns - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * The caption under a tile: what this deck is asking of you today, falling back to its size.
 *
 * The tile's own caption slot is normally the author's name, which on this screen is always you or
 * someone you already chose to follow — so it is spent on the number that decides whether to tap.
 *
 * [countsKnown] false is the cached first paint: the deck is real, its badge is not yet.
 */
@Composable
private fun dueCaption(deck: DeckSummary, countsKnown: Boolean): String = when {
    // Blank rather than the card count, which the tile is already showing on its own.
    !countsKnown -> ""
    deck.dueCount > 0 -> stringResource(R.string.home_deck_due, deck.dueCount)
    deck.newCount > 0 -> stringResource(R.string.home_deck_new, deck.newCount)
    else -> stringResource(R.string.home_deck_caught_up)
}

/**
 * Shown when the user owns decks but has nothing due. Previously this fell through to the
 * zero-decks empty state, which told someone who had just finished a session to "create or
 * import a deck to start your first study session".
 */
@Composable
private fun CaughtUpHeroCard(nextDueAtMillis: Long?) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(colors.accentPrimarySoft)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = "\uD83C\uDF89", fontSize = 40.sp)
        Text(
            text = stringResource(R.string.home_caught_up_title),
            color = colors.foregroundPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = nextDueAtMillis
                ?.let { stringResource(R.string.home_caught_up_next_due, relativeFromNow(it)) }
                ?: stringResource(R.string.home_caught_up_no_next_due),
            color = colors.foregroundMuted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * [studyTarget] is today's intent — everything overdue plus whatever room the new-cards goal has
 * left — not the size of the backlog. A 1669-card import headlines 20, and studying past it still
 * works, because nothing caps the queue behind this number (#101 §7).
 */
@Composable
private fun DueTodayHeroCard(
    studyTarget: Int,
    doneToday: Int,
    newCardsToday: Int,
    newCardsGoal: Int,
    countsKnown: Boolean,
    onStartStudyClick: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    // Against work done *plus* work left, not against what remains: dividing by the remaining
    // count alone climbed past 1 as the session went on and rendered "9 of 3 done".
    val plannedTotal = doneToday + studyTarget
    val progress = if (plannedTotal == 0) 0f else (doneToday.toFloat() / plannedTotal).coerceIn(0f, 1f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 32.dp,
                shape = RoundedCornerShape(28.dp),
                ambientColor = colors.shadowAccent,
                spotColor = colors.shadowAccent,
            )
            .clip(RoundedCornerShape(28.dp))
            .background(colors.accentPrimary)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.home_due_today),
            color = colors.foregroundOnAccentMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                // A dash, not a zero, until the counts land: this is the same card either way, so
                // nothing moves when the real number arrives — but "0" would read as a claim.
                text = if (countsKnown) studyTarget.toString() else "—",
                color = colors.foregroundOnAccent,
                fontSize = 72.sp,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = 72.sp,
            )
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(bottom = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.home_cards),
                    color = colors.foregroundOnAccent,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(R.string.home_to_review),
                    color = colors.foregroundOnAccentMuted,
                    fontSize = 13.sp,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ProgressBar(progress = if (countsKnown) progress else null)
            Text(
                text = if (countsKnown) {
                    stringResource(R.string.home_progress_done, doneToday, plannedTotal)
                } else {
                    stringResource(R.string.home_checking_due)
                },
                color = colors.foregroundOnAccentMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = if (newCardsToday >= newCardsGoal) {
                    stringResource(R.string.home_new_cards_goal_reached, newCardsGoal)
                } else {
                    stringResource(R.string.home_new_cards_goal, newCardsToday, newCardsGoal)
                },
                color = colors.foregroundOnAccentMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Button(
            onClick = onStartStudyClick,
            modifier = Modifier
                .testTag("home_start_study")
                .fillMaxWidth(),
            shape = RoundedCornerShape(50),
            contentPadding = PaddingValues(vertical = 16.dp),
            // The pill sits *on the accent*, so it takes on-accent colours, not the app's surface
            // family — Material's own inverse-of-primary pairing. `surfaceCard` happens to be
            // white in light mode, which is why the two were indistinguishable until dark mode
            // turned this pill into a hole punched in the middle of the orange card.
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.foregroundOnAccent,
                contentColor = colors.accentPrimary,
            ),
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.home_start_studying),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** A null [progress] is "not known yet", which the indeterminate bar says without a number. */
@Composable
private fun ProgressBar(progress: Float?) {
    val colors = LoopkyTheme.colors
    if (progress == null) {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(50)),
            color = colors.foregroundOnAccent,
            trackColor = Color(0x40FFFFFF),
            gapSize = 0.dp,
        )
        return
    }
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(50)),
        color = colors.foregroundOnAccent,
        trackColor = Color(0x40FFFFFF),
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

@Composable
fun TodaysDecksSection(
    decks: List<DeckSummary>,
    countsKnown: Boolean,
    onSeeAllClick: () -> Unit,
    onDeckClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.home_todays_decks),
                color = colors.foregroundPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.home_see_all),
                color = colors.accentSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onSeeAllClick)
                    .testTag("home_see_all")
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        decks.forEach { deck ->
            DeckRow(
                deck = deck,
                countsKnown = countsKnown,
                onClick = { onDeckClick(deck.id) },
            )
        }
    }
}

@Composable
private fun DeckRow(deck: DeckSummary, countsKnown: Boolean, onClick: () -> Unit) {
    val colors = LoopkyTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 18.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = colors.shadowElevationMedium,
                spotColor = colors.shadowElevationMedium,
            )
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceCard)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DeckCover(
            coverImage = deck.coverImage,
            deckId = deck.id,
            authorPubky = deck.authorPubky,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.size(56.dp),
        ) {
            Text(
                text = deck.coverInitial.toString(),
                color = colors.accentPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }
        Spacer(Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = deck.title,
                color = colors.foregroundPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                // Clamped so today's rows are the same height whatever the deck is called; an
                // imported Anki title can run to a sentence.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (!countsKnown) {
                    // The cached first paint knows the deck and not its badge.
                    pluralStringResource(R.plurals.card_count, deck.cardCount, deck.cardCount)
                } else if (deck.dueCount == 0 && deck.newCount > 0) {
                    // A freshly imported deck has nothing due and everything unseen. Saying
                    // "0 due" there described it as finished.
                    pluralStringResource(
                        R.plurals.home_deck_new_cards,
                        deck.cardCount,
                        deck.newCount,
                        deck.cardCount,
                    )
                } else {
                    pluralStringResource(
                        R.plurals.home_deck_due_cards,
                        deck.cardCount,
                        deck.dueCount,
                        deck.cardCount,
                    )
                },
                color = colors.foregroundMuted,
                fontSize = 13.sp,
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(colors.accentPrimary)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = if (!countsKnown) {
                    "—"
                } else {
                    (if (deck.dueCount == 0) deck.newCount else deck.dueCount).toString()
                },
                color = colors.foregroundOnAccent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Preview
@Composable
private fun HomeContentPreview() {
    LoopkyTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(LoopkyTheme.colors.surfacePrimary)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            HomeContent(
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
                            newCount = 0,
                            coverInitial = 'S',
                        ),
                        DeckSummary(
                            id = "2",
                            title = "Kanji N5",
                            authorPubky = "friend1xqz9",
                            cardCount = 103,
                            dueCount = 0,
                            newCount = 40,
                            coverInitial = 'K',
                        ),
                    ),
                ),
                onStartStudyClick = {},
                onSeeAllDecksClick = {},
                onDeckClick = {},
            )
        }
    }
}
