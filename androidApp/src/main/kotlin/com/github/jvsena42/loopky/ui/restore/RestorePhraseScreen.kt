package com.github.jvsena42.loopky.ui.restore

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.presentation.restore.RestoreEffect
import com.github.jvsena42.loopky.presentation.restore.RestoreOutcome
import com.github.jvsena42.loopky.presentation.restore.RestorePhraseUiState
import com.github.jvsena42.loopky.presentation.restore.RestorePhraseViewModel
import com.github.jvsena42.loopky.ui.components.LoopkyPrimaryButton
import com.github.jvsena42.loopky.ui.components.errorMessage
import com.github.jvsena42.loopky.ui.components.errorTitle
import com.github.jvsena42.loopky.ui.signup.SignupScaffold
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.LeaveEffect
import com.github.jvsena42.loopky.ui.util.PasswordManagerSheet
import com.github.jvsena42.loopky.ui.util.SecureScreen
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun RestorePhraseRoute(
    onBack: () -> Unit,
    onRestored: () -> Unit,
    onUnregistered: (String) -> Unit,
    modifier: Modifier = Modifier,
    startFromPasswordManager: Boolean = false,
    viewModel: RestorePhraseViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnRestored by rememberUpdatedState(onRestored)
    val currentOnUnregistered by rememberUpdatedState(onUnregistered)

    // Blocks screenshots and screen recording while a recovery phrase is on screen. Release builds
    // only, so android-cli journeys can still capture this screen.
    SecureScreen()

    // The words must not outlive the screen: a StateFlow lives as long as the ViewModel, so
    // without this they stay in memory — and in any heap dump — after the user has navigated away.
    LeaveEffect { viewModel.onLeaveUnlessCorrecting() }

    // The credential picker needs an Activity, so the read lives here and the ViewModel judges it.
    val context = LocalContext.current
    val sheet = remember(context) { PasswordManagerSheet(context) }

    LaunchedEffect(viewModel, sheet) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                RestoreEffect.NavigateHome -> currentOnRestored()
                is RestoreEffect.NavigateUnregistered -> currentOnUnregistered(effect.pubky)
                RestoreEffect.ReadFromPasswordManager -> viewModel.answerFrom(sheet)
            }
        }
    }

    // Arriving from the "Password manager" card raises the picker without a second tap. Saveable,
    // so a rotation or a return from the unregistered-key screen does not raise it again. Called
    // directly rather than through the effect, which would be emitted before the collector above
    // has subscribed.
    var pickerRaised by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(startFromPasswordManager, sheet) {
        if (startFromPasswordManager && !pickerRaised) {
            pickerRaised = true
            if (viewModel.beginPasswordManagerRead()) viewModel.answerFrom(sheet)
        }
    }

    RestorePhraseScreen(
        state = state,
        onPhraseChange = viewModel::onPhraseChange,
        onSubmit = viewModel::onSubmit,
        onUsePasswordManager = viewModel::onUsePasswordManagerClick,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * A rotation cancels the coroutine waiting on the picker while the ViewModel outlives it. Without
 * the `finally`, the field stays locked on an answer that can no longer arrive.
 */
private suspend fun RestorePhraseViewModel.answerFrom(sheet: PasswordManagerSheet) {
    var answered = false
    try {
        val secret = sheet.pick()
        answered = true
        onPasswordManagerResult(secret)
    } finally {
        if (!answered) onPasswordManagerResult(null)
    }
}

@Composable
private fun RestorePhraseScreen(
    state: RestorePhraseUiState,
    onPhraseChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onUsePasswordManager: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    SignupScaffold(
        title = stringResource(R.string.restore_phrase_title),
        subtitle = stringResource(R.string.restore_phrase_subtitle),
        onBack = onBack,
        modifier = modifier,
    ) {
        SeedPhraseWarning(text = stringResource(R.string.restore_seed_warning))
        Spacer(Modifier.height(20.dp))

        Text(
            text = stringResource(R.string.restore_phrase_label),
            color = colors.foregroundMuted,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.phrase,
            onValueChange = onPhraseChange,
            modifier = Modifier.fillMaxWidth().testTag("restore_phrase_input"),
            // Locked while the DHT lookup and sign-in are in flight. Editable, the field let a
            // user keep fixing a typo during the round trip, so the words that got checked and the
            // words on screen could differ — and everything downstream keys off the submitted ones.
            enabled = !state.isChecking && !state.isReadingPasswordManager,
            placeholder = {
                Text(text = stringResource(R.string.restore_phrase_placeholder), color = colors.foregroundMuted)
            },
            minLines = PHRASE_FIELD_MIN_LINES,
            isError = state.outcome is RestoreOutcome.InvalidPhrase,
            // Autocorrect off and no capitalisation: BIP-39 words are lowercase, and an IME that
            // "helpfully" corrects one turns a good phrase into a checksum-valid stranger.
            // `KeyboardType.Password` is what actually suppresses IME personalized learning on
            // Android — autocorrect off does not. Without it the twelve words land in the
            // keyboard's learned-word store, which is one of the exposures SeedPhraseWarning
            // itself names.
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
            ),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accentPrimary,
                unfocusedBorderColor = colors.borderSubtle,
                cursorColor = colors.accentPrimary,
                errorBorderColor = colors.danger,
            ),
        )
        Spacer(Modifier.height(24.dp))
        LoopkyPrimaryButton(
            label = stringResource(
                if (state.isChecking) R.string.restore_phrase_checking else R.string.restore_phrase_submit,
            ),
            onClick = onSubmit,
            enabled = state.canSubmit,
            loading = state.isChecking,
            modifier = Modifier.testTag("restore_phrase_submit"),
        )

        if (state.canUsePasswordManager) {
            Spacer(Modifier.height(10.dp))
            TextButton(
                onClick = onUsePasswordManager,
                enabled = !state.isChecking && !state.isReadingPasswordManager,
                modifier = Modifier.fillMaxWidth().testTag("restore_phrase_from_manager"),
            ) {
                Text(
                    text = stringResource(R.string.restore_phrase_from_manager),
                    color = colors.accentSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (state.passwordManagerEmpty) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.restore_phrase_manager_empty),
                color = colors.foregroundSecondary,
                fontSize = 12.sp,
                modifier = Modifier.testTag("restore_phrase_manager_empty"),
            )
        }

        state.outcome?.let {
            Spacer(Modifier.height(20.dp))
            RestoreOutcomeBlock(outcome = it)
        }
    }
}

/**
 * The four ways this stops, each with its own copy.
 *
 * They are separated because collapsing them is the bug: "we could not reach the network" rendered
 * as "that phrase is wrong" is a verdict on something we never checked, told to someone who is
 * already worried they mistyped.
 */
@Composable
internal fun RestoreOutcomeBlock(outcome: RestoreOutcome, modifier: Modifier = Modifier) {
    val colors = LoopkyTheme.colors
    Column(modifier = modifier.fillMaxWidth().testTag("restore_outcome")) {
        val title: String
        val message: String
        when (outcome) {
            RestoreOutcome.InvalidPhrase -> {
                title = stringResource(R.string.restore_error_invalid_title)
                message = stringResource(R.string.restore_error_invalid_message)
            }
            is RestoreOutcome.NoAccount -> {
                title = stringResource(R.string.restore_error_no_account_title)
                message = stringResource(R.string.restore_error_no_account_message)
            }
            is RestoreOutcome.CouldNotCheck -> {
                title = errorTitle(outcome.reason)
                message = errorMessage(outcome.reason)
            }
            is RestoreOutcome.SignInFailed -> {
                title = errorTitle(outcome.reason)
                message = errorMessage(outcome.reason)
            }
            RestoreOutcome.WrongPassphrase -> {
                title = stringResource(R.string.restore_error_passphrase_title)
                message = stringResource(R.string.restore_error_passphrase_message)
            }
            RestoreOutcome.FileUnreadable -> {
                title = stringResource(R.string.restore_error_unreadable_title)
                message = stringResource(R.string.restore_error_unreadable_message)
            }
        }

        Text(
            text = title,
            modifier = Modifier.testTag("restore_outcome_title"),
            color = colors.danger,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = message,
            modifier = Modifier.testTag("restore_outcome_message"),
            color = colors.foregroundSecondary,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )

        // The derived pubky, for the "valid phrase, wrong account" case only. A user with more than
        // one key can often tell at a glance that this is not theirs, which is the fastest
        // diagnosis available and the only one they can make rather than us.
        if (outcome is RestoreOutcome.NoAccount) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.restore_error_no_account_pubky),
                color = colors.foregroundMuted,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = outcome.pubky,
                modifier = Modifier.testTag("restore_outcome_pubky"),
                color = colors.foregroundPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

private const val PHRASE_FIELD_MIN_LINES = 3

@Preview
@Composable
private fun RestorePhraseNoAccountPreview() {
    LoopkyTheme {
        RestorePhraseScreen(
            state = RestorePhraseUiState(
                phrase = "abandon abandon abandon",
                outcome = RestoreOutcome.NoAccount("pk1234567890abcdef"),
            ),
            onPhraseChange = {},
            onSubmit = {},
            onUsePasswordManager = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun RestorePhraseCouldNotCheckPreview() {
    LoopkyTheme {
        RestorePhraseScreen(
            state = RestorePhraseUiState(
                phrase = "abandon abandon abandon",
                outcome = RestoreOutcome.CouldNotCheck(ErrorReason.HomeserverLookupFailed),
            ),
            onPhraseChange = {},
            onSubmit = {},
            onUsePasswordManager = {},
            onBack = {},
        )
    }
}
