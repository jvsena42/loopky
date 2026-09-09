package com.github.jvsena42.loopky.ui.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.presentation.backup.BackupPhraseEffect
import com.github.jvsena42.loopky.presentation.backup.BackupPhraseUiState
import com.github.jvsena42.loopky.presentation.backup.BackupPhraseViewModel
import com.github.jvsena42.loopky.ui.components.LoopkyPrimaryButton
import com.github.jvsena42.loopky.ui.layout.windowWidthClass
import com.github.jvsena42.loopky.ui.restore.SeedPhraseWarning
import com.github.jvsena42.loopky.ui.signup.SignupScaffold
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.LeaveEffect
import com.github.jvsena42.loopky.ui.util.PasswordManagerSheet
import com.github.jvsena42.loopky.ui.util.SecureScreen
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun BackupPhraseRoute(
    onBack: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupPhraseViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    SecureScreen()
    // Re-entrant on purpose: `onLeave` empties a ViewModel that outlives this screen, so
    // returning from the quiz has to refill it.
    LaunchedEffect(viewModel) { viewModel.onEnter() }
    LeaveEffect { viewModel.onLeave() }

    // The credential sheet needs an Activity, so the platform half of the save lives here and the
    // ViewModel only asks for it. `state.pubky` is not on this screen, so the account label is the
    // app's own name plus the phrase's owner, resolved by the sheet.
    val context = LocalContext.current
    val sheet = remember(context) { PasswordManagerSheet(context) }
    val account = stringResource(R.string.app_name)
    LaunchedEffect(viewModel, sheet) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is BackupPhraseEffect.SaveToPasswordManager ->
                    viewModel.onPasswordManagerSaveResult(sheet.save(account, effect.secret))
                BackupPhraseEffect.ReadBackFromPasswordManager ->
                    viewModel.onPasswordManagerReadBack(sheet.read(account))
            }
        }
    }

    BackupPhraseScreen(
        state = state,
        onReveal = viewModel::onRevealClick,
        onConfirm = onConfirm,
        onBack = onBack,
        onSaveToPasswordManager = viewModel::onSaveToPasswordManagerClick,
        modifier = modifier,
    )
}

@Composable
private fun BackupPhraseScreen(
    state: BackupPhraseUiState,
    onReveal: () -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    onSaveToPasswordManager: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    SignupScaffold(
        title = stringResource(R.string.backup_phrase_title),
        subtitle = stringResource(R.string.backup_phrase_subtitle),
        onBack = onBack,
        modifier = modifier,
    ) {
        // The warning inverted: this one prevents the mirror-image mistake of someone treating a
        // Pubky phrase as a wallet and sending sats to it.
        SeedPhraseWarning(text = stringResource(R.string.backup_phrase_warning))
        Spacer(Modifier.height(20.dp))

        WordGrid(words = state.words, revealed = state.revealed, onReveal = onReveal)

        Spacer(Modifier.height(24.dp))
        LoopkyPrimaryButton(
            label = stringResource(R.string.backup_phrase_continue),
            onClick = onConfirm,
            // Only after the words have actually been shown — "continue" on a blurred screen would
            // record having seen something nobody saw.
            enabled = state.revealed && state.words.isNotEmpty(),
            modifier = Modifier.testTag("backup_phrase_continue"),
        )

        // Secondary, and only once the words are visible: an offer to back up a secret the user
        // has not been shown asks them to trust a copy of something they never saw. Withheld
        // entirely on a platform that cannot do it, rather than shown and then explained away.
        if (state.showPasswordManagerSave) {
            Spacer(Modifier.height(10.dp))
            TextButton(
                onClick = onSaveToPasswordManager,
                enabled = !state.isSavingToPasswordManager && !state.savedToPasswordManager,
                modifier = Modifier.fillMaxWidth().testTag("backup_phrase_save_manager"),
            ) {
                Text(
                    text = stringResource(R.string.backup_phrase_save_to_manager),
                    color = colors.accentSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (state.savedToPasswordManager) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.backup_phrase_saved_to_manager),
                color = colors.foregroundMuted,
                fontSize = 12.sp,
                modifier = Modifier.testTag("backup_phrase_manager_saved"),
            )
        }
        if (state.passwordManagerFailed) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.backup_phrase_save_failed),
                color = colors.danger,
                fontSize = 12.sp,
                modifier = Modifier.testTag("backup_phrase_manager_failed"),
            )
        }
    }
}

@Composable
private fun WordGrid(
    words: List<String>,
    revealed: Boolean,
    onReveal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    // 2 / 3 / 4 columns by width, the same rule the deck grid uses — never chunked(2), which is
    // how a phone layout ends up stretched across a tablet.
    val columns = when {
        windowWidthClass().isExpanded -> COLUMNS_EXPANDED
        windowWidthClass().isAtLeastMedium -> COLUMNS_MEDIUM
        else -> COLUMNS_COMPACT
    }

    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (revealed) Modifier else Modifier.blur(BLUR_RADIUS))
                // The phrase is deliberately unreadable to accessibility services and to anything
                // that walks the semantics tree: a screen reader announcing twelve words aloud is
                // the same exposure as a screenshot, and this screen already blocks those.
                .clearAndSetSemantics { },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            words.chunked(columns).forEachIndexed { rowIndex, row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEachIndexed { colIndex, word ->
                        WordChip(
                            index = rowIndex * columns + colIndex + 1,
                            word = word,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        if (!revealed) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onReveal)
                    .testTag("backup_phrase_reveal"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.backup_phrase_reveal),
                    color = colors.foregroundPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun WordChip(index: Int, word: String, modifier: Modifier = Modifier) {
    val colors = LoopkyTheme.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceSecondary)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "$index", color = colors.foregroundMuted, fontSize = 11.sp)
        Spacer(Modifier.height(0.dp))
        Text(
            text = " $word",
            color = colors.foregroundPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private const val COLUMNS_COMPACT = 2
private const val COLUMNS_MEDIUM = 3
private const val COLUMNS_EXPANDED = 4
private val BLUR_RADIUS = 10.dp

@Preview
@Composable
private fun BackupPhrasePreview() {
    LoopkyTheme {
        BackupPhraseScreen(
            state = BackupPhraseUiState(
                isLoading = false,
                words = List(12) { "abandon" },
                revealed = true,
            ),
            onReveal = {}, onConfirm = {}, onBack = {}, onSaveToPasswordManager = {},
        )
    }
}
