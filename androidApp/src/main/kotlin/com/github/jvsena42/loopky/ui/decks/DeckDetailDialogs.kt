package com.github.jvsena42.loopky.ui.decks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.DeckLimits
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.presentation.decks.DeckDetailUiState
import com.github.jvsena42.loopky.ui.components.errorMessage
import com.github.jvsena42.loopky.ui.components.errorTitle
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.label

// Dialogs and small caption rows lifted out of DeckDetailScreen, which grew past 900 lines once
// Follow deck and Clone deck (#33) landed. The one piece of state here is the copy dialog's title
// field, which belongs to the field it draws; everything else takes what it renders and hands taps
// back to the caller.

/**
 * "12 following · 3 clones", from the indexer's distinct-tagger counts for the reserved labels.
 *
 * Renders nothing at zero. The counts are approximate by nature — indexer lag, and anyone can write
 * any label — so they are worth showing when present and never worth asserting when absent.
 */
@Composable
internal fun SocialCountsRow(followerCount: Int, clonedCount: Int) {
    if (followerCount <= 0 && clonedCount <= 0) return
    val colors = LoopkyTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (followerCount > 0) {
            Text(
                text = stringResource(R.string.deck_detail_followers, followerCount),
                color = colors.foregroundMuted,
                fontSize = 13.sp,
                modifier = Modifier.testTag("deck_follower_count"),
            )
        }
        if (clonedCount > 0) {
            Text(
                text = pluralStringResource(R.plurals.deck_detail_clones, clonedCount, clonedCount),
                color = colors.foregroundMuted,
                fontSize = 13.sp,
                modifier = Modifier.testTag("deck_clone_count"),
            )
        }
    }
}

/** "Cloned from @someone", so credit for a fork is visible rather than buried in the manifest. */
@Composable
internal fun ClonedFromRow(author: PubkyIdentity) {
    val colors = LoopkyTheme.colors
    Text(
        text = stringResource(R.string.deck_detail_cloned_from, author.label()),
        color = colors.foregroundMuted,
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.testTag("deck_cloned_from"),
    )
}

/**
 * "Copy this deck?" — raised by Edit on a deck you follow, which is the only route to a copy (#254).
 *
 * Two jobs, and both are why it is a dialog rather than a second pill. It states the two things
 * that make a copy different from the follow the reader already has — no author updates, progress
 * starts over — in one sentence, because a paragraph here is a paragraph nobody reads. And it takes
 * the copy's own name, which is **required** and may not be the source's: the copy lands in a
 * library that already holds the deck it forked, and two rows with one title are
 * indistinguishable. [sourceTitle] is the placeholder rather than the initial value — prefilled,
 * everyone would tap straight past it — and [isSourceName] is the shared rule
 * ([DeckDetailUiState.Content.isSourceName]) rather than a comparison reinvented here.
 */
@Composable
internal fun CloneDeckDialog(
    sourceTitle: String,
    cardCount: Int,
    isSourceName: (String) -> Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    var title by rememberSaveable { mutableStateOf("") }
    val trimmed = title.trim()
    // Reported as you type rather than on tapping Copy: the field is right there, and a rejection
    // that arrives after the tap reads as the button being broken.
    val clashes = trimmed.isNotEmpty() && isSourceName(trimmed)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceCard,
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        title = {
            Text(
                text = stringResource(R.string.deck_detail_clone_dialog_title),
                color = colors.foregroundPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Names the card count: a clone is one write per chunk plus the manifest, and the
                // user should know whether they are copying 20 cards or 20,000 before they wait.
                Text(
                    text = pluralStringResource(
                        R.plurals.deck_detail_clone_dialog_message,
                        cardCount,
                        cardCount,
                    ),
                    color = colors.foregroundSecondary,
                    fontSize = 14.sp,
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(DeckLimits.TITLE_MAX_LENGTH) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("deck_clone_title"),
                    label = { Text(stringResource(R.string.deck_detail_clone_name_label)) },
                    placeholder = { Text(text = sourceTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    isError = clashes,
                    supportingText = if (clashes) {
                        {
                            Text(
                                text = stringResource(R.string.deck_detail_clone_name_same),
                                color = colors.danger,
                                modifier = Modifier.testTag("deck_clone_name_error"),
                            )
                        }
                    } else {
                        null
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = textFieldColors(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmed) },
                enabled = trimmed.isNotEmpty() && !clashes,
                modifier = Modifier.testTag("deck_clone_confirm"),
            ) {
                Text(stringResource(R.string.deck_detail_clone_confirm), color = colors.accentPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.deck_detail_clone_cancel), color = colors.foregroundMuted)
            }
        },
    )
}

/**
 * A failed follow or clone. Reported over the loaded deck rather than replacing it with an Error
 * screen: the deck is fine, only the write failed, and throwing the page away would lose the user's
 * place for no reason.
 */
@Composable
internal fun RecoverableErrorDialog(reason: ErrorReason, onDismiss: () -> Unit) {
    val colors = LoopkyTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceCard,
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        title = {
            Text(
                text = errorTitle(reason),
                color = colors.foregroundPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        },
        text = {
            Text(text = errorMessage(reason), color = colors.foregroundSecondary, fontSize = 14.sp)
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("deck_error_dismiss")) {
                Text(stringResource(R.string.deck_detail_dismiss_error), color = colors.accentPrimary)
            }
        },
    )
}

@Composable
internal fun DeleteDeckDialog(deckTitle: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = LoopkyTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceCard,
        // AlertDialog renders in its own window, so the root's testTagsAsResourceId does not
        // reach it — journeys/05 could not find `deck_delete_confirm` without this.
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        title = {
            Text(
                text = stringResource(R.string.deck_detail_delete_dialog_title),
                color = colors.foregroundPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.deck_detail_delete_dialog_message, deckTitle),
                color = colors.foregroundSecondary,
                fontSize = 14.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("deck_delete_confirm")) {
                Text(stringResource(R.string.deck_detail_delete_confirm), color = colors.danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.deck_detail_delete_cancel), color = colors.foregroundMuted)
            }
        },
    )
}
