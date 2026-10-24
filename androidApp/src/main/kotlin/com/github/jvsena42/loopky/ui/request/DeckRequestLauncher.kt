package com.github.jvsena42.loopky.ui.request

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.DeckRequests
import com.github.jvsena42.loopky.domain.model.ErrorReason
import com.github.jvsena42.loopky.presentation.auth.SignInReason
import com.github.jvsena42.loopky.presentation.request.DeckRequestUiState
import com.github.jvsena42.loopky.presentation.request.DeckRequestViewModel
import com.github.jvsena42.loopky.ui.components.CharacterCounter
import com.github.jvsena42.loopky.ui.components.LoopkyPrimaryButton
import com.github.jvsena42.loopky.ui.components.SignInPromptDialog
import com.github.jvsena42.loopky.ui.components.TagChip
import com.github.jvsena42.loopky.ui.components.errorMessage
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import org.koin.compose.viewmodel.koinViewModel

/** Which of the two things a "Request a deck" tap raises, held by the screen that offers it. */
@Stable
class DeckRequestLauncher internal constructor() {
    internal var prefill by mutableStateOf<String?>(null)
    internal var signInPrompt by mutableStateOf(false)

    /** [prefill] is whatever came up empty — the query, the topic — or blank. */
    fun launch(prefill: String = "", isGuest: Boolean) {
        if (isGuest) signInPrompt = true else this.prefill = prefill
    }
}

@Composable
fun rememberDeckRequestLauncher(): DeckRequestLauncher = remember { DeckRequestLauncher() }

/**
 * Draws whatever [launcher] has raised: the sheet for a signed-in reader, the sign-in prompt for a
 * guest. Every screen that offers a request places one of these, so the gate and the sheet are the
 * same everywhere.
 */
@Composable
fun DeckRequestHost(launcher: DeckRequestLauncher, onSignIn: () -> Unit) {
    if (launcher.signInPrompt) {
        SignInPromptDialog(
            reason = SignInReason.RequestDeck,
            onSignIn = {
                launcher.signInPrompt = false
                onSignIn()
            },
            onDismiss = { launcher.signInPrompt = false },
        )
    }
    launcher.prefill?.let { prefill ->
        DeckRequestSheet(prefill = prefill, onDismiss = { launcher.prefill = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeckRequestSheet(prefill: String, onDismiss: () -> Unit) {
    val viewModel = koinViewModel<DeckRequestViewModel>()
    LaunchedEffect(prefill) { viewModel.start(prefill) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lead = stringResource(R.string.deck_request_post_lead)
    // The field owns its text while typing; the ViewModel is told, never asked back.
    var text by rememberSaveable(prefill) { mutableStateOf(prefill.trim().take(DeckRequests.MAX_LENGTH)) }
    val colors = LoopkyTheme.colors

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceCard,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        // A sheet is its own window, so the activity's `testTagsAsResourceId` does not reach it.
        modifier = Modifier.semantics { testTagsAsResourceId = true },
    ) {
        DeckRequestSheetContent(
            state = state,
            text = text,
            lead = lead,
            onTextChange = {
                text = it
                viewModel.onTextChange(it)
            },
            onSubmit = { viewModel.onSubmit(lead) },
            onDone = onDismiss,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeckRequestSheetContent(
    state: DeckRequestUiState,
    text: String,
    lead: String,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDone: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    if (state.isSent) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("deck_request_sent")
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp, top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "📬", fontSize = 48.sp, lineHeight = 56.sp)
            Text(
                text = stringResource(R.string.deck_request_sent_title),
                color = colors.foregroundPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.deck_request_sent_body),
                color = colors.foregroundMuted,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                textAlign = TextAlign.Center,
            )
            LoopkyPrimaryButton(
                label = stringResource(R.string.deck_request_done),
                onClick = onDone,
                modifier = Modifier.padding(top = 8.dp).testTag("deck_request_done"),
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("deck_request_sheet")
            .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.deck_request_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = colors.foregroundPrimary,
            )
            Text(
                text = stringResource(R.string.deck_request_body),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = colors.foregroundMuted,
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                enabled = !state.isPosting,
                placeholder = { Text(stringResource(R.string.deck_request_placeholder)) },
                minLines = 2,
                maxLines = 5,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accentPrimary,
                    unfocusedBorderColor = colors.borderSubtle,
                    cursorColor = colors.accentPrimary,
                    focusedTextColor = colors.foregroundPrimary,
                    unfocusedTextColor = colors.foregroundPrimary,
                    focusedPlaceholderColor = colors.foregroundMuted,
                    unfocusedPlaceholderColor = colors.foregroundMuted,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("deck_request_input"),
            )
            CharacterCounter(
                current = text.trim().length,
                max = DeckRequests.MAX_LENGTH,
                modifier = Modifier.padding(top = 4.dp, end = 4.dp),
            )
        }

        // The post exactly as it will be written, tags included: it is public, so nothing about it
        // should be a surprise afterwards.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.deck_request_preview_label),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                color = colors.foregroundMuted,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surfacePrimary)
                    .padding(14.dp),
            ) {
                Text(
                    text = DeckRequests.compose(lead, text.ifBlank { "…" }),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = colors.foregroundSecondary,
                    modifier = Modifier.testTag("deck_request_preview"),
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DeckRequests.TAGS.forEach { TagChip(tag = it.value) }
            }
        }

        state.error?.let { reason ->
            Text(
                text = errorMessage(reason),
                color = colors.danger,
                fontSize = 13.sp,
                modifier = Modifier.testTag("deck_request_error"),
            )
        }

        LoopkyPrimaryButton(
            label = stringResource(R.string.deck_request_submit),
            onClick = onSubmit,
            enabled = DeckRequests.isValid(text),
            loading = state.isPosting,
            modifier = Modifier.testTag("deck_request_submit"),
        )
    }
}

/**
 * The standing offer where a reader has run out of decks to look at: the foot of Discover, a search
 * with nothing behind it.
 */
@Composable
fun DeckRequestCallout(onRequest: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceCard)
            .border(1.dp, colors.borderSubtle, RoundedCornerShape(20.dp))
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.deck_request_cta_title),
            color = colors.foregroundPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.deck_request_cta_body),
            color = colors.foregroundSecondary,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.deck_request_cta_button),
            color = colors.foregroundOnAccent,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(top = 6.dp)
                .testTag("deck_request_cta")
                .clip(RoundedCornerShape(50))
                .background(colors.accentPrimary)
                .clickable(role = Role.Button, onClick = onRequest)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}

@Preview
@Composable
private fun DeckRequestSheetPreview() {
    LoopkyTheme {
        Box(Modifier.background(LoopkyTheme.colors.surfaceCard)) {
            DeckRequestSheetContent(
                state = DeckRequestUiState(text = "Japanese hiragana for beginners"),
                text = "Japanese hiragana for beginners",
                lead = "I’d love a Loopky deck about:",
                onTextChange = {},
                onSubmit = {},
                onDone = {},
            )
        }
    }
}

@Preview
@Composable
private fun DeckRequestSheetErrorPreview() {
    LoopkyTheme {
        Box(Modifier.background(LoopkyTheme.colors.surfaceCard)) {
            DeckRequestSheetContent(
                state = DeckRequestUiState(text = "Organic chemistry", error = ErrorReason.Offline),
                text = "Organic chemistry",
                lead = "I’d love a Loopky deck about:",
                onTextChange = {},
                onSubmit = {},
                onDone = {},
            )
        }
    }
}

@Preview
@Composable
private fun DeckRequestSentPreview() {
    LoopkyTheme {
        Box(Modifier.background(LoopkyTheme.colors.surfaceCard)) {
            DeckRequestSheetContent(
                state = DeckRequestUiState(isSent = true),
                text = "",
                lead = "",
                onTextChange = {},
                onSubmit = {},
                onDone = {},
            )
        }
    }
}

@Preview
@Composable
private fun DeckRequestCalloutPreview() {
    LoopkyTheme {
        Box(Modifier.background(LoopkyTheme.colors.surfacePrimary).padding(20.dp).size(width = 360.dp, height = 220.dp)) {
            DeckRequestCallout(onRequest = {})
        }
    }
}
