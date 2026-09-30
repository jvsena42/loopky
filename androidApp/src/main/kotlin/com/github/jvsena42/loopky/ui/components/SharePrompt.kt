package com.github.jvsena42.loopky.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.DeckAnnouncement
import com.github.jvsena42.loopky.presentation.share.DeckSharePrompt
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * "Share this on Pubky?" (#39), shown after a deck is created, followed or cloned.
 *
 * The body says *post*, never *visibility*: publishing a deck already makes it public, so copy
 * that reads as a privacy control would describe a setting Loopky does not have (spec §11).
 */
@Composable
fun SharePromptDialog(
    prompt: DeckSharePrompt,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onNeverAsk: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    AlertDialog(
        // Declining is the safe default, so a tap outside is a decline rather than a post.
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceCard,
        // AlertDialog renders in its own window, so the root's testTagsAsResourceId does not reach
        // it — without this a journey cannot find the buttons.
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        title = {
            Text(
                text = stringResource(prompt.kind.titleRes()),
                color = colors.foregroundPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        },
        text = { SharePromptBody(prompt, onNeverAsk = onNeverAsk) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !prompt.isPosting,
                modifier = Modifier.testTag("share_prompt_confirm"),
            ) {
                Text(
                    text = stringResource(R.string.share_prompt_confirm),
                    color = colors.accentPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !prompt.isPosting,
                modifier = Modifier.testTag("share_prompt_dismiss"),
            ) {
                Text(stringResource(R.string.share_prompt_dismiss), color = colors.foregroundMuted)
            }
        },
    )
}

/** The explanation, the exact post that would be written, and the opt-out. */
@Composable
fun SharePromptBody(
    prompt: DeckSharePrompt,
    modifier: Modifier = Modifier,
    onNeverAsk: (() -> Unit)? = null,
) {
    val colors = LoopkyTheme.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.share_prompt_body),
            color = colors.foregroundSecondary,
            fontSize = 14.sp,
            lineHeight = 19.sp,
        )
        // The post verbatim, not a paraphrase of it: DeckSharePrompt.preview is the same string
        // announceDeck writes.
        Text(
            text = prompt.preview,
            color = colors.foregroundPrimary,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier
                .testTag("share_prompt_preview")
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surfaceSecondary)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
        if (onNeverAsk != null) {
            // Underlined and in the accent, not flat muted text: this sets a preference that
            // stops Loopky ever asking again, and drawn as grey body copy it read as a disabled
            // label rather than as something to press.
            TextButton(
                onClick = onNeverAsk,
                enabled = !prompt.isPosting,
                modifier = Modifier.testTag("share_prompt_never"),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.share_prompt_never),
                    color = colors.accentPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.W600,
                    textDecoration = TextDecoration.Underline,
                )
            }
        }
    }
}

@StringRes
private fun DeckAnnouncement.Kind.titleRes(): Int = when (this) {
    DeckAnnouncement.Kind.Created -> R.string.share_prompt_title_created
    DeckAnnouncement.Kind.Followed -> R.string.share_prompt_title_followed
    DeckAnnouncement.Kind.Cloned -> R.string.share_prompt_title_cloned
}
