package com.github.jvsena42.loopky.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.PassphraseStrength
import com.github.jvsena42.loopky.presentation.backup.BackupEffect
import com.github.jvsena42.loopky.presentation.backup.BackupFileUiState
import com.github.jvsena42.loopky.presentation.backup.BackupFileViewModel
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
fun BackupFileRoute(
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupFileViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnDone by rememberUpdatedState(onDone)
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    var pendingBlob by remember { mutableStateOf<String?>(null) }

    SecureScreen()
    LeaveEffect { viewModel.onLeave() }

    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(RECOVERY_MIME),
    ) { uri ->
        val blob = pendingBlob
        pendingBlob = null
        if (uri == null || blob == null) return@rememberLauncherForActivityResult
        scope.launch {
            resolver.writeRecoveryFile(uri, blob)
                // Only a file that was actually written counts as a backup.
                .onSuccess { viewModel.onFileSaved() }
                // A silent failure here is the worst outcome on this screen: the user picks a
                // location, comes back to no error and nothing ticked, and reasonably concludes
                // the backup exists.
                .onFailure { viewModel.onFileSaveFailed() }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is BackupEffect.SaveFile -> {
                    pendingBlob = effect.base64
                    saver.launch(effect.fileName)
                }
                BackupEffect.Done -> currentOnDone()
                else -> Unit
            }
        }
    }

    BackupFileScreen(
        state = state,
        onPassphraseChange = viewModel::onPassphraseChange,
        onCreate = viewModel::onCreateClick,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
private fun BackupFileScreen(
    state: BackupFileUiState,
    onPassphraseChange: (String) -> Unit,
    onCreate: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    SignupScaffold(
        title = stringResource(R.string.backup_file_title),
        subtitle = stringResource(R.string.backup_file_subtitle),
        onBack = onBack,
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.backup_file_passphrase),
            color = colors.foregroundMuted,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(8.dp))
        // Revealable: this is the one and only time this passphrase is typed, and every later use
        // is a comparison against it. A typo made here cannot be detected here — only much later,
        // by a file that will not open.
        PassphraseField(
            value = state.passphrase,
            onValueChange = onPassphraseChange,
            testTag = "backup_file_passphrase",
        )
        Spacer(Modifier.height(8.dp))
        // A nudge, never a gate: locking someone out of exporting their own key over a strength
        // meter is a worse outcome than a mediocre passphrase. It matters more than usual here
        // because the file's KDF salt is a fixed constant, so length is the only defence.
        Text(
            text = stringResource(
                when (state.strength) {
                    PassphraseStrength.TooShort -> R.string.backup_strength_too_short
                    PassphraseStrength.Weak -> R.string.backup_strength_weak
                    PassphraseStrength.Fair -> R.string.backup_strength_fair
                    PassphraseStrength.Strong -> R.string.backup_strength_strong
                },
            ),
            modifier = Modifier.testTag("backup_file_strength"),
            color = if (state.strength == PassphraseStrength.Strong) colors.accentSecondary else colors.foregroundMuted,
            fontSize = 12.sp,
        )

        if (state.failed) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.backup_file_failed),
                color = colors.danger,
                fontSize = 13.sp,
            )
        }

        Spacer(Modifier.height(24.dp))
        LoopkyPrimaryButton(
            label = stringResource(R.string.backup_file_create),
            onClick = onCreate,
            enabled = state.canCreate,
            loading = state.isCreating,
            modifier = Modifier.testTag("backup_file_create"),
        )
    }
}

private const val RECOVERY_MIME = "application/octet-stream"

@Preview
@Composable
private fun BackupFilePreview() {
    LoopkyTheme {
        BackupFileScreen(
            state = BackupFileUiState(passphrase = "correct horse", strength = PassphraseStrength.Fair),
            onPassphraseChange = {}, onCreate = {}, onBack = {},
        )
    }
}
