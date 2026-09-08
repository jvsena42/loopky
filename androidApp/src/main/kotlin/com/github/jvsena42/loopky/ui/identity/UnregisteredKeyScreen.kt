package com.github.jvsena42.loopky.ui.identity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.KeyCustody
import com.github.jvsena42.loopky.presentation.identity.UnregisteredKeyEffect
import com.github.jvsena42.loopky.presentation.identity.UnregisteredKeyUiState
import com.github.jvsena42.loopky.presentation.identity.UnregisteredKeyViewModel
import com.github.jvsena42.loopky.ui.components.LoopkyOutlinedButton
import com.github.jvsena42.loopky.ui.components.LoopkyPrimaryButton
import com.github.jvsena42.loopky.ui.signup.SignupScaffold
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * A valid key with no account anywhere.
 *
 * The action ranking is the design: **checking the phrase again is primary**, because a
 * checksum-passing typo is the likeliest reason to be here, and registering is the branch that
 * costs money and publishes an identity.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun UnregisteredKeyRoute(
    pubky: String,
    custody: KeyCustody,
    onBack: () -> Unit,
    onNeedsVerification: () -> Unit,
    onRegistered: () -> Unit,
    onRestoreWithPhrase: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: UnregisteredKeyViewModel = koinViewModel { parametersOf(pubky, custody) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnBack by rememberUpdatedState(onBack)
    val currentOnVerify by rememberUpdatedState(onNeedsVerification)
    val currentOnRegistered by rememberUpdatedState(onRegistered)
    var confirming by remember { mutableStateOf(false) }

    // Backing out of the verification flow lands here again; the key is then held on spec once
    // more, so the "it has a future" flag must not still be set from the earlier tap.
    val onReturned = viewModel::onReturned
    LifecycleResumeEffect(onReturned) {
        onReturned()
        onPauseOrDispose { }
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                UnregisteredKeyEffect.NavigateBack -> currentOnBack()
                UnregisteredKeyEffect.NavigateSignup -> currentOnVerify()
                UnregisteredKeyEffect.NavigateHome -> currentOnRegistered()
            }
        }
    }

    UnregisteredKeyScreen(
        state = state,
        onCheckPhrase = viewModel::onCheckPhraseAgainClick,
        onRegisterClick = { confirming = true },
        onRestoreWithPhrase = onRestoreWithPhrase,
        onBack = onBack,
        modifier = modifier,
    )

    if (confirming) {
        val colors = LoopkyTheme.colors
        AlertDialog(
            onDismissRequest = { confirming = false },
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            containerColor = colors.surfaceCard,
            title = {
                Text(
                    text = stringResource(R.string.unregistered_register_confirm_title),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = colors.foregroundPrimary,
                )
            },
            text = {
                // The Bitcoin warning restated in its *publish* form. The input screen warned about
                // typing a wallet phrase in; this is the moment it would become a public identity
                // in the DHT, and a whole flow has happened in between.
                Text(
                    text = stringResource(
                        R.string.unregistered_register_confirm_body,
                        state.pubky.take(PUBKY_PREVIEW_LEN),
                    ),
                    fontSize = 14.sp,
                    color = colors.foregroundSecondary,
                    lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        viewModel.onRegisterConfirmed()
                    },
                    modifier = Modifier.testTag("unregistered_register_confirm"),
                ) {
                    Text(
                        text = stringResource(R.string.unregistered_register_confirm_yes),
                        color = colors.accentSecondary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(
                        text = stringResource(R.string.unregistered_register_confirm_cancel),
                        color = colors.foregroundSecondary,
                    )
                }
            },
        )
    }
}

@Composable
private fun UnregisteredKeyScreen(
    state: UnregisteredKeyUiState,
    onCheckPhrase: () -> Unit,
    onRegisterClick: () -> Unit,
    onRestoreWithPhrase: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    SignupScaffold(
        title = stringResource(R.string.unregistered_title),
        subtitle = stringResource(R.string.unregistered_subtitle),
        onBack = onBack,
        error = state.error,
        modifier = modifier,
    ) {
        // Always shown: a user with several keys can often tell at a glance that this is not the
        // one they meant, which is the fastest diagnosis available and the only one they can make.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surfaceSecondary)
                .padding(14.dp),
        ) {
            Text(
                text = stringResource(R.string.unregistered_pubky_label),
                color = colors.foregroundMuted,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = state.pubky,
                modifier = Modifier.testTag("unregistered_pubky"),
                color = colors.foregroundPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(24.dp))

        // Primary: by likelihood, not by ease of implementation.
        LoopkyPrimaryButton(
            label = stringResource(R.string.unregistered_check_phrase),
            onClick = onCheckPhrase,
            modifier = Modifier.testTag("unregistered_check_phrase"),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.unregistered_check_hint),
            color = colors.foregroundSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.height(24.dp))

        if (state.loopkyHoldsKey) {
            LoopkyOutlinedButton(
                label = stringResource(R.string.unregistered_register),
                onClick = onRegisterClick,
                enabled = !state.isRegistering,
                modifier = Modifier.testTag("unregistered_register"),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.unregistered_register_hint),
                color = colors.foregroundMuted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        } else {
            // Ring holds the key. There is deliberately no "create an account" button here: that
            // path mints a *different* pubky and leaves this one account-less forever.
            RingHeldBlock(onRestoreWithPhrase = onRestoreWithPhrase)
        }
    }
}

@Composable
private fun RingHeldBlock(onRestoreWithPhrase: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceSecondary)
            .padding(16.dp)
            .testTag("unregistered_ring_block"),
    ) {
        Text(
            text = stringResource(R.string.unregistered_ring_title),
            color = colors.foregroundPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.unregistered_ring_body),
            color = colors.foregroundSecondary,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(12.dp))
        // Offered as a considered tradeoff, not the default: it moves the key into Loopky.
        LoopkyOutlinedButton(
            label = stringResource(R.string.unregistered_ring_import),
            onClick = onRestoreWithPhrase,
            modifier = Modifier.testTag("unregistered_ring_import"),
        )
    }
}

private const val PUBKY_PREVIEW_LEN = 12

@Preview
@Composable
private fun UnregisteredKeyLoopkyPreview() {
    LoopkyTheme {
        UnregisteredKeyScreen(
            state = UnregisteredKeyUiState(pubky = "pk1234567890abcdef", loopkyHoldsKey = true),
            onCheckPhrase = {}, onRegisterClick = {}, onRestoreWithPhrase = {}, onBack = {},
        )
    }
}

@Preview
@Composable
private fun UnregisteredKeyRingPreview() {
    LoopkyTheme {
        UnregisteredKeyScreen(
            state = UnregisteredKeyUiState(pubky = "pk1234567890abcdef", loopkyHoldsKey = false),
            onCheckPhrase = {}, onRegisterClick = {}, onRestoreWithPhrase = {}, onBack = {},
        )
    }
}
