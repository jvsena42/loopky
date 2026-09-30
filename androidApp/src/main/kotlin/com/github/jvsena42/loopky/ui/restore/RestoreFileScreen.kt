package com.github.jvsena42.loopky.ui.restore

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.presentation.restore.RestoreEffect
import com.github.jvsena42.loopky.presentation.restore.RestoreFileUiState
import com.github.jvsena42.loopky.presentation.restore.RestoreFileViewModel
import com.github.jvsena42.loopky.presentation.restore.RestoreOutcome
import com.github.jvsena42.loopky.ui.components.LoopkyOutlinedButton
import com.github.jvsena42.loopky.ui.components.LoopkyPrimaryButton
import com.github.jvsena42.loopky.ui.components.PassphraseField
import com.github.jvsena42.loopky.ui.signup.SignupScaffold
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.LeaveEffect
import com.github.jvsena42.loopky.ui.util.SecureScreen
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun RestoreFileRoute(
    onBack: () -> Unit,
    onRestored: () -> Unit,
    onUnregistered: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RestoreFileViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnRestored by rememberUpdatedState(onRestored)
    val currentOnUnregistered by rememberUpdatedState(onUnregistered)
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()

    // A passphrase is on screen, so the same capture block the phrase screen uses applies.
    SecureScreen()

    LeaveEffect { viewModel.onLeaveUnlessCorrecting() }

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                RestoreEffect.NavigateHome -> currentOnRestored()
                is RestoreEffect.NavigateUnregistered -> currentOnUnregistered(effect.pubky)
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // The read is off the main thread and the bytes are Base64-encoded here, because the FFI
        // takes Base64 while the file on disk is raw — see readRecoveryFile.
        scope.launch {
            resolver.readRecoveryFile(uri)
                .onSuccess { viewModel.onFilePicked(it.name, it.base64) }
                .onFailure { viewModel.onFileUnreadable() }
        }
    }

    RestoreFileScreen(
        state = state,
        // Any type: a recovery file has no registered MIME type, and providers report it as
        // octet-stream, as a text file, or as nothing at all.
        onChooseFile = { picker.launch(arrayOf("*/*")) },
        onPassphraseChange = viewModel::onPassphraseChange,
        onSubmit = viewModel::onSubmit,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
private fun RestoreFileScreen(
    state: RestoreFileUiState,
    onChooseFile: () -> Unit,
    onPassphraseChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    SignupScaffold(
        title = stringResource(R.string.restore_file_title),
        subtitle = stringResource(R.string.restore_file_subtitle),
        onBack = onBack,
        modifier = modifier,
    ) {
        LoopkyOutlinedButton(
            label = state.fileName ?: stringResource(R.string.restore_file_choose),
            onClick = onChooseFile,
            modifier = Modifier.testTag("restore_file_choose"),
        )

        if (state.fileName != null) {
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.restore_file_passphrase_label),
                color = colors.foregroundMuted,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(8.dp))
            PassphraseField(
                value = state.passphrase,
                onValueChange = onPassphraseChange,
                // Locked while checking, for the same reason the phrase field is.
                enabled = !state.isChecking,
                isError = state.outcome is RestoreOutcome.WrongPassphrase,
                placeholder = stringResource(R.string.restore_file_passphrase_placeholder),
                testTag = "restore_file_passphrase",
            )
            Spacer(Modifier.height(24.dp))
            LoopkyPrimaryButton(
                label = stringResource(
                    if (state.isChecking) R.string.restore_phrase_checking else R.string.restore_file_submit,
                ),
                onClick = onSubmit,
                enabled = state.canSubmit,
                loading = state.isChecking,
                modifier = Modifier.testTag("restore_file_submit"),
            )
        }

        state.outcome?.let {
            Spacer(Modifier.height(20.dp))
            RestoreOutcomeBlock(outcome = it)
        }
    }
}

@Preview
@Composable
private fun RestoreFilePreview() {
    LoopkyTheme {
        RestoreFileScreen(
            state = RestoreFileUiState(fileName = "recovery.pkarr", passphrase = "hunter2hunter2"),
            onChooseFile = {},
            onPassphraseChange = {},
            onSubmit = {},
            onBack = {},
        )
    }
}
