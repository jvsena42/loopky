package com.github.jvsena42.loopky.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.presentation.auth.SignInReason
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * What to say when a signed-out visitor reaches for something that writes.
 *
 * One dialog for every gate in the app, worded by [SignInReason] rather than by a generic "sign in
 * required": someone who just tapped Follow is being asked for an account by *that* tap, and the
 * answer to "why?" is the sentence most likely to get it. Every reason ends on the same
 * reassurance — one key, no email — because the fear the account is up against is a signup form.
 *
 * Dismissing changes nothing and writes nothing: the visitor stays exactly where they were, with
 * everything they could read still readable.
 */
@Composable
fun SignInPromptDialog(
    reason: SignInReason,
    onSignIn: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceCard,
        modifier = Modifier
            .semantics { testTagsAsResourceId = true }
            .testTag("sign_in_prompt"),
        title = {
            Text(
                text = stringResource(reason.titleRes),
                color = colors.foregroundPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        },
        text = {
            Text(
                text = stringResource(reason.bodyRes),
                color = colors.foregroundSecondary,
                fontSize = 14.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = onSignIn, modifier = Modifier.testTag("sign_in_prompt_cta")) {
                Text(
                    text = stringResource(R.string.sign_in_prompt_cta),
                    color = colors.accentPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("sign_in_prompt_dismiss")) {
                Text(stringResource(R.string.sign_in_prompt_dismiss), color = colors.foregroundMuted)
            }
        },
    )
}

private val SignInReason.titleRes: Int
    get() = when (this) {
        SignInReason.FollowDeck -> R.string.sign_in_prompt_follow_deck_title
        SignInReason.CloneDeck -> R.string.sign_in_prompt_clone_deck_title
        SignInReason.FollowPerson -> R.string.sign_in_prompt_follow_person_title
    }

private val SignInReason.bodyRes: Int
    get() = when (this) {
        SignInReason.FollowDeck -> R.string.sign_in_prompt_follow_deck_body
        SignInReason.CloneDeck -> R.string.sign_in_prompt_clone_deck_body
        SignInReason.FollowPerson -> R.string.sign_in_prompt_follow_person_body
    }

/**
 * The standing offer at the top of Discover while nobody is signed in.
 *
 * A guest's whole shell is Discover — there is no tab bar to hold a "Sign in" destination — so
 * this is the one always-visible way in, and it has to earn its place rather than nag: it says
 * what an account *does*, scrolls away with the content, and is never modal.
 *
 * The copy is deliberately a heading plus one short line. It used to spend three lines on the
 * full pitch — keeping decks, tracking progress, following people, no email needed — which made
 * a permanent banner the tallest thing above the decks the visitor came to browse. The pitch
 * belongs on the screen behind "Get started", which states it in full; this only has to name the
 * state and give one reason to leave it. Nothing sets `maxLines`, so a longer translation wraps
 * rather than truncating.
 *
 * The text sits beside the CTA rather than above it, so the button is a trailing element in a
 * [Row] rather than a third line under the text.
 */
@Composable
fun GuestSignInBanner(
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.accentSecondarySoft)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag("guest_banner"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.guest_banner_title),
                color = colors.foregroundPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.W800,
            )
            Text(
                text = stringResource(R.string.guest_banner_body),
                color = colors.foregroundSecondary,
                fontSize = 13.sp,
            )
        }
        // A bare Button rather than LoopkyPrimaryButton: that one fills its width by contract,
        // which is right for a bottom bar and wrong beside a weighted column — it would measure
        // first, take the whole row, and leave the text nothing.
        Button(
            onClick = onSignIn,
            shape = CircleShape,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accentPrimary,
                contentColor = colors.foregroundOnAccent,
            ),
            modifier = Modifier.testTag("guest_banner_cta"),
        ) {
            Text(
                text = stringResource(R.string.guest_banner_cta),
                fontSize = 14.sp,
                fontWeight = FontWeight.W700,
            )
        }
    }
}

@Preview
@Composable
private fun GuestSignInBannerPreview() {
    LoopkyTheme {
        GuestSignInBanner(onSignIn = {}, modifier = Modifier.padding(20.dp))
    }
}

@Preview
@Composable
private fun SignInPromptDialogPreview() {
    LoopkyTheme {
        SignInPromptDialog(reason = SignInReason.FollowDeck, onSignIn = {}, onDismiss = {})
    }
}
