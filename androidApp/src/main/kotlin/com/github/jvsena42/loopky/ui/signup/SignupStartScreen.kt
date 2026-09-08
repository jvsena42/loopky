package com.github.jvsena42.loopky.ui.signup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.presentation.signup.SignupStartUiState
import com.github.jvsena42.loopky.presentation.signup.SignupStartViewModel
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SignupStartRoute(
    onBack: () -> Unit,
    onSms: () -> Unit,
    onLightning: () -> Unit,
    onInviteCode: () -> Unit,
) {
    val viewModel: SignupStartViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    SignupStartScreen(
        state = state,
        onBack = onBack,
        onSms = onSms,
        onLightning = onLightning,
        onInviteCode = onInviteCode,
    )
}

/**
 * The one and only human check. Loopky creates the account itself once a method here succeeds, so
 * there is nothing to install first and no second copy of this screen behind a custody choice —
 * moving the key to Pubky Ring is offered later, from the backup step.
 */
@Composable
private fun SignupStartScreen(
    state: SignupStartUiState,
    onBack: () -> Unit,
    onSms: () -> Unit,
    onLightning: () -> Unit,
    onInviteCode: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    SignupScaffold(
        title = stringResource(R.string.signup_start_title),
        subtitle = stringResource(R.string.signup_start_subtitle),
        onBack = onBack,
    ) {
        val unavailableLabel = stringResource(R.string.signup_card_unavailable)

        MethodCard(
            title = stringResource(R.string.signup_sms_card_title),
            price = stringResource(R.string.signup_sms_card_price),
            note = stringResource(R.string.signup_sms_card_note),
            enabled = state.isSmsEnabled,
            unavailableLabel = unavailableLabel,
            testTag = "signup_method_sms",
            onClick = onSms,
        )
        Spacer(Modifier.height(16.dp))
        MethodCard(
            title = stringResource(R.string.signup_lightning_card_title),
            // Two strings rather than concatenation: the parenthetical belongs elsewhere in
            // other languages. No quote — in flight, failed, or geoblocked — renders exactly the
            // sats-only string it always did.
            price = state.lightningPriceSat
                ?.let { sats ->
                    state.fiatPrice
                        ?.let { stringResource(R.string.signup_lightning_card_price_fiat, sats, it) }
                        ?: stringResource(R.string.signup_lightning_card_price, sats)
                }
                ?: stringResource(R.string.signup_lightning_card_price_unknown),
            note = stringResource(R.string.signup_lightning_card_note),
            enabled = state.isLightningEnabled,
            unavailableLabel = unavailableLabel,
            testTag = "signup_method_lightning",
            onClick = onLightning,
        )
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = onInviteCode, modifier = Modifier.testTag("signup_method_invite")) {
            Text(
                text = stringResource(R.string.signup_invite_link),
                color = colors.accentSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * A disabled card still says *why*, and is never hidden — "it isn't offered here" is answerable,
 * whereas a missing row leaves the user asking why they cannot see what a friend can.
 */
@Composable
private fun MethodCard(
    title: String,
    price: String,
    note: String,
    enabled: Boolean,
    unavailableLabel: String,
    testTag: String,
    onClick: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.borderSubtle, RoundedCornerShape(16.dp))
            .background(colors.surfaceCard, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(20.dp)
            .testTag(testTag),
    ) {
        Text(
            text = title,
            color = if (enabled) colors.foregroundPrimary else colors.foregroundMuted,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            // Tagged: this is the value that shows which environment the build is talking to —
            // staging and production quote different prices.
            text = if (enabled) price else unavailableLabel,
            modifier = Modifier.testTag(testTag + "_price"),
            color = if (enabled) colors.accentPrimary else colors.foregroundMuted,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        // Deliberately no storage figure. The quota is set by the signup token the homeserver
        // issues (`storage_quota_mb`), so it varies by token and by server — printing a number
        // here would be copy that silently goes stale.
        Text(text = note, color = colors.foregroundMuted, fontSize = 12.sp)
    }
}

@Preview
@Composable
private fun SignupStartPreview() {
    LoopkyTheme {
        SignupStartScreen(
            state = SignupStartUiState(isLoading = false),
            onBack = {},
            onSms = {},
            onLightning = {},
            onInviteCode = {},
        )
    }
}
