package com.github.jvsena42.loopky.ui.signup

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.presentation.signup.InviteCodeEffect
import com.github.jvsena42.loopky.presentation.signup.InviteCodeUiState
import com.github.jvsena42.loopky.presentation.signup.InviteCodeViewModel
import com.github.jvsena42.loopky.ui.components.LoopkyPrimaryButton
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun InviteCodeRoute(onBack: () -> Unit, onDone: () -> Unit) {
    val viewModel: InviteCodeViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnDone by rememberUpdatedState(onDone)

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                InviteCodeEffect.NavigateToHandoff -> currentOnDone()
            }
        }
    }

    InviteCodeScreen(
        state = state,
        onBack = onBack,
        onCodeChange = viewModel::onCodeChange,
        onSubmit = viewModel::onSubmit,
    )
}

@Composable
private fun InviteCodeScreen(
    state: InviteCodeUiState,
    onBack: () -> Unit,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    SignupScaffold(
        title = stringResource(R.string.signup_invite_title),
        subtitle = stringResource(R.string.signup_invite_subtitle),
        onBack = onBack,
        error = state.error,
    ) {
        Text(
            text = stringResource(R.string.signup_invite_label),
            color = colors.foregroundMuted,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.code,
            onValueChange = onCodeChange,
            modifier = Modifier.fillMaxWidth().testTag("signup_invite_input"),
            placeholder = {
                Text(text = stringResource(R.string.signup_invite_placeholder), color = colors.foregroundMuted)
            },
            singleLine = true,
            isError = state.error != null,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
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
            label = stringResource(R.string.signup_invite_submit),
            onClick = onSubmit,
            enabled = state.canSubmit,
            loading = state.isSubmitting,
            modifier = Modifier.testTag("signup_invite_submit"),
        )
    }
}
