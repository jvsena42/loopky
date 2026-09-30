package com.github.jvsena42.loopky.ui.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.BackupMethod
import com.github.jvsena42.loopky.presentation.backup.BackupStartUiState
import com.github.jvsena42.loopky.presentation.backup.BackupStartViewModel
import com.github.jvsena42.loopky.ui.layout.windowWidthClass
import com.github.jvsena42.loopky.ui.signup.SignupScaffold
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import org.koin.compose.viewmodel.koinViewModel

/**
 * The backup menu, reached straight after a locally-created account and from the Settings nag.
 *
 * Skippable, deliberately. Someone who has just made an account is the least equipped person in
 * the app to weigh four key-custody options, and blocking them here is how a flashcards app loses
 * a user before they ever see a card. The nag is the follow-up, and it does not go away.
 */
@Composable
fun BackupStartRoute(
    onBack: () -> Unit,
    onDone: () -> Unit,
    onPhrase: () -> Unit,
    onFile: () -> Unit,
    onRing: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupStartViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackupStartScreen(
        state = state,
        onBack = onBack,
        onDone = onDone,
        onPhrase = onPhrase,
        onFile = onFile,
        onRing = onRing,
        modifier = modifier,
    )
}

@Composable
private fun BackupStartScreen(
    state: BackupStartUiState,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onPhrase: () -> Unit,
    onFile: () -> Unit,
    onRing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    SignupScaffold(
        title = stringResource(R.string.backup_start_title),
        // A restored key is already backed up, so the default copy — "your key lives only on this
        // device" — would be describing a risk the user does not have. What is still worth
        // offering them is Ring — a live second copy in an app they can sign other Pubky apps in
        // with, which a phrase on paper is not. It does not move the key: Loopky keeps its own.
        subtitle = stringResource(
            if (state.isBackedUp) {
                R.string.backup_start_subtitle_backed_up
            } else {
                R.string.backup_start_subtitle
            },
        ),
        onBack = onBack,
        modifier = modifier,
    ) {
        // Two columns above compact: four cards stacked on a tablet is a thin ribbon down the
        // middle of a very wide screen.
        val twoUp = windowWidthClass().isAtLeastMedium
        val cards = buildList {
            // A key restored from a recovery file has no words, so offering a phrase screen would
            // open one with nothing on it.
            if (state.hasPhrase) {
                add(
                    BackupCard(
                        method = BackupMethod.RecoveryPhrase,
                        label = stringResource(R.string.backup_method_phrase),
                        detail = stringResource(R.string.backup_method_phrase_detail),
                        onClick = onPhrase,
                        tag = "backup_method_phrase",
                    ),
                )
            }
            add(
                BackupCard(
                    method = BackupMethod.EncryptedFile,
                    label = stringResource(R.string.backup_method_file),
                    detail = stringResource(R.string.backup_method_file_detail),
                    onClick = onFile,
                    tag = "backup_method_file",
                ),
            )
            // Opens the phrase screen, because that is where the save lives — the words have to
            // be on screen to be handed over. The card exists anyway: without one there is no
            // row for the tick to appear on, so a user who had saved to a password manager came
            // back to a menu that showed the method undone.
            if (state.showPasswordManager) {
                add(
                    BackupCard(
                        method = BackupMethod.PasswordManager,
                        label = stringResource(R.string.backup_method_password_manager),
                        detail = stringResource(R.string.backup_method_password_manager_detail),
                        onClick = onPhrase,
                        tag = "backup_method_password_manager",
                    ),
                )
            }
            add(
                BackupCard(
                    method = BackupMethod.PubkyRing,
                    label = stringResource(R.string.backup_method_ring),
                    // Still offered when Ring is absent — the screen behind it installs — but it
                    // says so, rather than looking identical and then explaining itself one tap
                    // later. This is what `ringInstalled` was plumbed here for; nothing read it.
                    detail = if (state.ringInstalled) {
                        stringResource(R.string.backup_method_ring_detail)
                    } else {
                        stringResource(R.string.backup_method_ring_missing)
                    },
                    onClick = onRing,
                    tag = "backup_method_ring",
                ),
            )
        }

        if (twoUp) {
            cards.chunkedPairs().forEach { (first, second) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BackupMethodCard(first, state.done, Modifier.weight(1f))
                    if (second != null) {
                        BackupMethodCard(second, state.done, Modifier.weight(1f))
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        } else {
            cards.forEach {
                BackupMethodCard(it, state.done, Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onDone, modifier = Modifier.testTag("backup_later")) {
            Text(
                text = stringResource(R.string.backup_later),
                color = colors.foregroundSecondary,
                fontSize = 14.sp,
            )
        }
    }
}

private data class BackupCard(
    val method: BackupMethod,
    val label: String,
    val detail: String,
    val onClick: () -> Unit,
    val tag: String,
)

/** Pairs for the two-up grid, keeping a trailing odd card. */
private fun List<BackupCard>.chunkedPairs(): List<Pair<BackupCard, BackupCard?>> =
    chunked(2).map { it.first() to it.getOrNull(1) }

@Composable
private fun BackupMethodCard(
    card: BackupCard,
    done: Set<BackupMethod>,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    val isDone = card.method in done
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceSecondary)
            .clickable(onClick = card.onClick)
            .padding(16.dp)
            .testTag(card.tag),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = card.label,
                color = colors.foregroundPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (isDone) {
                Spacer(Modifier.width(8.dp))
                // Methods accumulate rather than replacing each other, so each says so separately.
                Text(
                    text = "✓ " + stringResource(R.string.backup_done_label),
                    modifier = Modifier.testTag("${card.tag}_done"),
                    color = colors.accentSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(text = card.detail, color = colors.foregroundSecondary, fontSize = 13.sp)
    }
}

@Preview
@Composable
private fun BackupStartPreview() {
    LoopkyTheme {
        BackupStartScreen(
            state = BackupStartUiState(
                isLoading = false,
                pubky = "pk123",
                done = setOf(BackupMethod.RecoveryPhrase),
                hasPhrase = true,
                ringInstalled = true,
            ),
            onBack = {}, onDone = {}, onPhrase = {}, onFile = {}, onRing = {},
        )
    }
}
