package com.github.jvsena42.loopky.ui.signup

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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.presentation.signup.DialingCountry
import com.github.jvsena42.loopky.presentation.signup.PhoneVerificationEffect
import com.github.jvsena42.loopky.presentation.signup.PhoneVerificationPhase
import com.github.jvsena42.loopky.presentation.signup.PhoneVerificationUiState
import com.github.jvsena42.loopky.presentation.signup.PhoneVerificationViewModel
import com.github.jvsena42.loopky.ui.components.LoopkyPrimaryButton
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun PhoneVerificationRoute(onBack: () -> Unit, onDone: () -> Unit) {
    val viewModel: PhoneVerificationViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnDone by rememberUpdatedState(onDone)

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                PhoneVerificationEffect.NavigateToHandoff -> currentOnDone()
            }
        }
    }

    PhoneVerificationScreen(
        state = state,
        // Back from the code field returns to the number, keeping what was typed — the reason
        // both phases share one ViewModel.
        onBack = { if (state.phase == PhoneVerificationPhase.CodeEntry) viewModel.onBackToNumber() else onBack() },
        onPhoneChange = viewModel::onPhoneNumberChange,
        onCountrySelected = viewModel::onCountrySelected,
        onCodeChange = viewModel::onCodeChange,
        onSendCode = viewModel::onSendCodeClick,
        onVerify = viewModel::onVerifyClick,
    )
}

@Composable
private fun PhoneVerificationScreen(
    state: PhoneVerificationUiState,
    onBack: () -> Unit,
    onPhoneChange: (String) -> Unit,
    onCountrySelected: (DialingCountry) -> Unit,
    onCodeChange: (String) -> Unit,
    onSendCode: () -> Unit,
    onVerify: () -> Unit,
) {
    val isCodePhase = state.phase == PhoneVerificationPhase.CodeEntry
    SignupScaffold(
        title = stringResource(if (isCodePhase) R.string.signup_code_title else R.string.signup_phone_title),
        subtitle = if (isCodePhase) {
            stringResource(R.string.signup_code_subtitle, state.displayNumber)
        } else {
            stringResource(R.string.signup_phone_subtitle)
        },
        onBack = onBack,
        error = state.error,
    ) {
        // Split by phase rather than branching field-by-field: the two pages share a frame, not a
        // body, and threading `isCodePhase` through every property is how they become unreadable.
        if (isCodePhase) {
            CodeEntry(state = state, onCodeChange = onCodeChange, onVerify = onVerify, onResend = onSendCode)
        } else {
            NumberEntry(
                state = state,
                onPhoneChange = onPhoneChange,
                onCountrySelected = onCountrySelected,
                onSendCode = onSendCode,
            )
        }
    }
}

@Composable
private fun NumberEntry(
    state: PhoneVerificationUiState,
    onPhoneChange: (String) -> Unit,
    onCountrySelected: (DialingCountry) -> Unit,
    onSendCode: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    var isPickingCountry by rememberSaveable { mutableStateOf(false) }
    FieldLabel(stringResource(R.string.signup_phone_label))
    SignupTextField(
        value = state.phoneNumber,
        onValueChange = onPhoneChange,
        isError = state.error != null,
        keyboardType = KeyboardType.Phone,
        testTag = "signup_phone_input",
        leading = {
            CountryCodeButton(
                country = state.country,
                enabled = !state.isWorking,
                onClick = { isPickingCountry = true },
                modifier = Modifier.testTag("signup_phone_country"),
            )
        },
    )
    Spacer(Modifier.height(8.dp))
    // Once the number is complete, read it back in full: the trunk `0` it may have dropped and the
    // calling code it added are both invisible in the field, and an SMS attempt is scarce.
    Text(
        text = if (state.canSendCode) {
            stringResource(R.string.signup_phone_sending_to, state.displayNumber)
        } else {
            stringResource(R.string.signup_phone_hint)
        },
        color = colors.foregroundMuted,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        modifier = Modifier.testTag("signup_phone_hint"),
    )
    if (isPickingCountry) {
        CountryPickerSheet(
            selected = state.country,
            onSelect = {
                onCountrySelected(it)
                isPickingCountry = false
            },
            onDismiss = { isPickingCountry = false },
        )
    }
    Spacer(Modifier.height(24.dp))
    // Withheld on a terminal limit: offering "send" would invite the user to spend attempts they
    // no longer have.
    if (!state.isTerminal) {
        LoopkyPrimaryButton(
            label = stringResource(R.string.signup_phone_send),
            onClick = onSendCode,
            enabled = state.canSendCode,
            loading = state.isWorking,
            modifier = Modifier.testTag("signup_phone_send"),
        )
    }
}

@Composable
private fun CodeEntry(
    state: PhoneVerificationUiState,
    onCodeChange: (String) -> Unit,
    onVerify: () -> Unit,
    onResend: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    FieldLabel(stringResource(R.string.signup_code_label))
    SignupTextField(
        value = state.code,
        onValueChange = onCodeChange,
        isError = state.error != null,
        keyboardType = KeyboardType.NumberPassword,
        testTag = "signup_code_input",
    )
    Spacer(Modifier.height(24.dp))
    if (!state.isTerminal) {
        LoopkyPrimaryButton(
            label = stringResource(R.string.signup_code_verify),
            onClick = onVerify,
            enabled = state.canVerify,
            loading = state.isWorking,
            modifier = Modifier.testTag("signup_code_verify"),
        )
        Spacer(Modifier.height(10.dp))
        TextButton(
            onClick = onResend,
            enabled = state.canResend,
            modifier = Modifier.testTag("signup_code_resend"),
        ) {
            Text(
                text = if (state.canResend) {
                    stringResource(R.string.signup_code_resend)
                } else {
                    stringResource(R.string.signup_code_resend_in, state.resendCooldownSeconds)
                },
                color = if (state.canResend) colors.accentSecondary else colors.foregroundMuted,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text = text, color = LoopkyTheme.colors.foregroundMuted, fontSize = 11.sp)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SignupTextField(
    value: String,
    onValueChange: (String) -> Unit,
    isError: Boolean,
    keyboardType: KeyboardType,
    testTag: String,
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = LoopkyTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().testTag(testTag),
        leadingIcon = leading,
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.accentPrimary,
            unfocusedBorderColor = colors.borderSubtle,
            cursorColor = colors.accentPrimary,
            errorBorderColor = colors.danger,
        ),
    )
}
