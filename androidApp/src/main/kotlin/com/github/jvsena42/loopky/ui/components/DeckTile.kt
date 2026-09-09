package com.github.jvsena42.loopky.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.presentation.decks.DeckRelation
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

@Composable
fun DeckTile(
    deckId: String,
    /** The *deck's* author — a followed deck's cover blob lives on their homeserver, not yours. */
    authorPubky: String,
    title: String,
    cardCount: Int,
    coverEmoji: String,
    /** Caption after the card count — the author's name, or a tag on a profile grid. */
    authorLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** The deck's cover art. Drawn over [coverEmoji]; null leaves the emoji showing. */
    coverImage: MediaRef.Image? = null,
    coverColor: Color = LoopkyTheme.colors.accentPrimarySoft,
    /**
     * How the signed-in user relates to this deck. Only a fork is badged: the author's name already
     * says whose a deck is, so "You" and "FOLLOWING" were repeating it. A clone has no such tell —
     * it carries *your* name while being someone else's deck — so it keeps its badge.
     */
    relation: DeckRelation = DeckRelation.None,
    /** The author has published changes since this followed deck was last opened. */
    hasUpdate: Boolean = false,
    onAuthorClick: (() -> Unit)? = null,
) {
    val colors = LoopkyTheme.colors
    val shape = RoundedCornerShape(20.dp)

    // A native [Card] for the container, ripple and click handling — but the shadow stays a
    // `Modifier.shadow`, because `CardDefaults.cardElevation` has no ambient or spot colour and
    // Loopky tints both from `shadowElevationXHigh`. The card's own elevation is therefore zero:
    // two shadows under one tile is one shadow too many.
    Card(
        onClick = onClick,
        modifier = modifier.shadow(
            elevation = 24.dp,
            shape = shape,
            ambientColor = colors.shadowElevationXHigh,
            spotColor = colors.shadowElevationXHigh,
        ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = colors.surfaceCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        // Cover area
        DeckCover(
            coverImage = coverImage,
            deckId = deckId,
            authorPubky = authorPubky,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            background = coverColor,
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
        ) {
            Text(
                text = coverEmoji,
                fontSize = 48.sp,
                lineHeight = 58.sp,
            )
        }

        // Body
        Column(
            modifier = Modifier.padding(14.dp),
        ) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.W700,
                color = colors.foregroundPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Meta row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = pluralStringResource(R.plurals.card_count, cardCount, cardCount),
                    fontSize = 12.sp,
                    color = colors.foregroundMuted,
                )
                // A blank label is "nothing to say here" — Home passes one while the cached
                // library is on screen and the due count has not answered. Without the guard the
                // row ends on a dangling separator.
                if (authorLabel.isNotBlank()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "\u00B7",
                        fontSize = 12.sp,
                        color = colors.foregroundMuted,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = authorLabel,
                        fontSize = 12.sp,
                        color = colors.accentSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = (
                            if (onAuthorClick != null) {
                                Modifier.clickable(onClick = onAuthorClick)
                            } else {
                                Modifier
                            }
                            ).weight(1f, fill = false),
                    )
                }
                if (hasUpdate) {
                    Spacer(modifier = Modifier.width(6.dp))
                    UpdateDot()
                }
                relationBadge(relation)?.let { label ->
                    Spacer(modifier = Modifier.width(6.dp))
                    RelationBadge(label = label)
                }
            }
        }
    }
}

/**
 * The badge text for a relation, or null when there is nothing to say. Owning or following a deck
 * says nothing the author's name doesn't already — only a clone, which reads as yours while being
 * someone else's work, needs the label.
 */
@Composable
private fun relationBadge(relation: DeckRelation): String? = when (relation) {
    DeckRelation.Cloned -> stringResource(R.string.deck_tile_cloned_badge)
    DeckRelation.Owned, DeckRelation.Followed, DeckRelation.None -> null
}

/**
 * The author of a followed deck has published changes since you last opened it — enough to notice,
 * not enough to nag, and it clears itself when the deck is opened. Stands on its own now that
 * "FOLLOWING" is gone, rather than riding inside that pill.
 */
@Composable
private fun UpdateDot() {
    Box(
        modifier = Modifier
            .size(6.dp)
            .clip(RoundedCornerShape(50))
            .background(LoopkyTheme.colors.accentPrimary),
    )
}

/** Pill beside the author name. */
@Composable
private fun RelationBadge(label: String) {
    val colors = LoopkyTheme.colors
    Text(
        text = label,
        fontSize = 10.sp,
        fontWeight = FontWeight.W700,
        color = colors.accentSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(colors.accentSecondarySoft)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Preview
@Composable
private fun DeckTilePreview() {
    LoopkyTheme {
        Box(
            modifier = Modifier
                .background(LoopkyTheme.colors.surfacePrimary)
                .padding(16.dp),
        ) {
            DeckTile(
                deckId = "deck-1",
                authorPubky = "ada1xqz9uvwxyz",
                title = "Spanish Basics",
                cardCount = 42,
                coverEmoji = "🇪🇸",
                authorLabel = "Ada Lovelace",
                onClick = {},
                relation = DeckRelation.Followed,
                hasUpdate = true,
                onAuthorClick = {},
                modifier = Modifier.width(180.dp),
            )
        }
    }
}
