package com.github.jvsena42.loopky.ui.signup

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.presentation.signup.LocalSignupEffect
import com.github.jvsena42.loopky.presentation.signup.LocalSignupUiState
import com.github.jvsena42.loopky.presentation.signup.LocalSignupViewModel
import com.github.jvsena42.loopky.ui.components.LoopkyPrimaryButton
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Redeem the signup token in Loopky: mint a key here, register it, sign in.
 *
 * The terminal step of signup, reached from all three human checks.
 */
@Composable
fun LocalSignupRoute(
    registerHeldKey: Boolean,
    onBack: () -> Unit,
    onCreated: () -> Unit,
    onStartOver: () -> Unit,
    modifier: Modifier = Modifier,
    // Keyed on the intent so the two entry points cannot share a ViewModel that already decided
    // whether to mint.
    viewModel: LocalSignupViewModel = koinViewModel(key = "local-signup-$registerHeldKey") {
        parametersOf(registerHeldKey)
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnCreated by rememberUpdatedState(onCreated)
    val currentOnStartOver by rememberUpdatedState(onStartOver)

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                LocalSignupEffect.NavigateHome -> currentOnCreated()
                LocalSignupEffect.NavigateStartOver -> currentOnStartOver()
            }
        }
    }

    LocalSignupScreen(
        state = state,
        onRetry = viewModel::onRetryClick,
        onStartOver = viewModel::onStartOverClick,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
private fun LocalSignupScreen(
    state: LocalSignupUiState,
    onRetry: () -> Unit,
    onStartOver: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    SignupScaffold(
        title = stringResource(R.string.signup_local_title),
        subtitle = stringResource(R.string.signup_local_subtitle),
        onBack = onBack,
        error = state.error,
        modifier = modifier,
    ) {
        if (state.isWorking) {
            CircularProgressIndicator(
                modifier = Modifier.testTag("signup_local_progress"),
                color = colors.accentPrimary,
            )
        }
        if (state.canRetry) {
            Spacer(Modifier.height(16.dp))
            // Registers the key already minted rather than minting another — see onRetryClick.
            LoopkyPrimaryButton(
                label = stringResource(R.string.signup_local_retry),
                onClick = onRetry,
                modifier = Modifier.testTag("signup_local_retry"),
            )
        }
        if (state.canStartOver) {
            Spacer(Modifier.height(16.dp))
            // No "try again" here on purpose: the token is refused, so a retry is a loop.
            LoopkyPrimaryButton(
                label = stringResource(R.string.signup_start_over),
                onClick = onStartOver,
                modifier = Modifier.testTag("signup_local_start_over"),
            )
        }
        state.pubky?.let {
            Spacer(Modifier.height(12.dp))
            Text(text = it, color = colors.foregroundMuted, fontSize = 12.sp)
        }
    }
}

@Preview
@Composable
private fun LocalSignupPreview() {
    LoopkyTheme {
        LocalSignupScreen(state = LocalSignupUiState(), onRetry = {}, onStartOver = {}, onBack = {})
    }
}
