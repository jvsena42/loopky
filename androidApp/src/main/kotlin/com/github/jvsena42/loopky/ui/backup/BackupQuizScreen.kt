package com.github.jvsena42.loopky.ui.backup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.presentation.backup.BackupEffect
import com.github.jvsena42.loopky.presentation.backup.BackupQuizUiState
import com.github.jvsena42.loopky.presentation.backup.BackupQuizViewModel
import com.github.jvsena42.loopky.presentation.backup.ConfirmMode
import com.github.jvsena42.loopky.ui.components.LoopkyPrimaryButton
import com.github.jvsena42.loopky.ui.signup.SignupScaffold
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.PasswordManagerSheet
import com.github.jvsena42.loopky.ui.util.SecureScreen
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel

/**
 * The confirm quiz. Passing it is what records the phrase as backed up — seeing the words is not.
 */
@Composable
fun BackupQuizRoute(
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupQuizViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnDone by rememberUpdatedState(onDone)

    SecureScreen()

    // Same split as the phrase screen: the credential sheet needs an Activity, so the read lives
    // here and the ViewModel only asks for it and judges the answer.
    val context = LocalContext.current
    val sheet = remember(context) { PasswordManagerSheet(context) }
    val account = stringResource(R.string.app_name)

    LaunchedEffect(viewModel, sheet) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                BackupEffect.Done -> currentOnDone()
                BackupEffect.ReadBackFromPasswordManager ->
                    viewModel.onPasswordManagerReadBack(sheet.read(account))
                else -> Unit
            }
        }
    }

    BackupQuizScreen(
        state = state,
        onAnswer = viewModel::onAnswer,
        onSubmit = viewModel::onSubmit,
        onCheckSaved = viewModel::onCheckSavedClick,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
private fun BackupQuizScreen(
    state: BackupQuizUiState,
    onAnswer: (Int, String) -> Unit,
    onSubmit: () -> Unit,
    onCheckSaved: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    // Two different claims need two different checks. Recall tests that twelve words were copied
    // down; someone who saved the phrase to a password manager copied nothing, so that question
    // tests nothing they did and fails for a reason that is not a problem. What is worth checking
    // on that path is that the credential is still there and still says the right words.
    val savedMode = state.mode == ConfirmMode.PasswordManager
    SignupScaffold(
        title = stringResource(
            if (savedMode) R.string.backup_quiz_saved_title else R.string.backup_quiz_title,
        ),
        subtitle = stringResource(
            if (savedMode) R.string.backup_quiz_saved_subtitle else R.string.backup_quiz_subtitle,
        ),
        onBack = onBack,
        modifier = modifier,
    ) {
        if (savedMode) {
            if (state.wrong) {
                Text(
                    text = stringResource(R.string.backup_quiz_saved_wrong),
                    modifier = Modifier.testTag("backup_quiz_saved_wrong"),
                    color = colors.danger,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(16.dp))
            }
            LoopkyPrimaryButton(
                label = stringResource(R.string.backup_quiz_saved_check),
                onClick = onCheckSaved,
                enabled = state.canCheckSaved,
                loading = state.isChecking,
                modifier = Modifier.testTag("backup_quiz_saved_check"),
            )
            return@SignupScaffold
        }

        state.positions.forEachIndexed { questionIndex, position ->
            Text(
                text = stringResource(R.string.backup_quiz_position, position),
                color = colors.foregroundMuted,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.options.getOrNull(questionIndex).orEmpty().forEach { option ->
                    OptionChip(
                        label = option,
                        selected = state.answers[questionIndex] == option,
                        onClick = { onAnswer(questionIndex, option) },
                        modifier = Modifier.testTag("backup_quiz_${questionIndex}_$option"),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        if (state.wrong) {
            // Clears the answers rather than locking: this checks that the words were written
            // down, and someone holding their own paper copy should be able to try again.
            Text(
                text = stringResource(R.string.backup_quiz_wrong),
                modifier = Modifier.testTag("backup_quiz_wrong"),
                color = colors.danger,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(16.dp))
        }

        LoopkyPrimaryButton(
            label = stringResource(R.string.backup_quiz_submit),
            onClick = onSubmit,
            enabled = state.canSubmit,
            modifier = Modifier.testTag("backup_quiz_submit"),
        )
    }
}

@Composable
private fun OptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            // Filled when chosen, matching iOS, rather than a tinted fill behind an accent
            // outline. A 1dp warm border on a rounded field is the app's *error* treatment — it is
            // what an invalid phone number wears — and #FF5C00 against #D92C2C is a hue apart, so
            // the chosen answer read as a rejected one. That lands worst here of anywhere: this is
            // a quiz, being wrong is a live outcome, and the real failure message renders in
            // `danger` a few dp below.
            .background(if (selected) colors.accentPrimary else colors.surfaceSecondary)
            .border(
                BorderStroke(1.dp, if (selected) colors.accentPrimary else colors.borderSubtle),
                RoundedCornerShape(12.dp),
            )
            // `selectable`, not `clickable`: these are one-of-N per position, and the selection was
            // previously carried by colour and font weight alone — invisible to TalkBack, which
            // announced four identical rows, and to the journeys, whose semantics tree said
            // nothing about which word was picked.
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (selected) colors.foregroundOnAccent else colors.foregroundPrimary,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = colors.foregroundOnAccent,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Preview
@Composable
private fun BackupQuizPreview() {
    LoopkyTheme {
        BackupQuizScreen(
            state = BackupQuizUiState(
                isLoading = false,
                positions = listOf(3, 6, 9),
                options = List(3) { listOf("abandon", "ability", "about", "above") },
            ),
            onAnswer = { _, _ -> }, onSubmit = {}, onCheckSaved = {}, onBack = {},
        )
    }
}
