package com.github.jvsena42.loopky.ui.study

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.platform.SpeechError
import com.github.jvsena42.loopky.presentation.study.SpeakPhase
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * Bottom sheets for Speak pronunciation practice. Rendered based
 * on the current [SpeakPhase]; [SpeakPhase.Idle] shows nothing.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun SpeakSheets(
    phase: SpeakPhase,
    onCancel: () -> Unit,
    onContinue: () -> Unit,
    onRetry: () -> Unit,
) {
    if (phase is SpeakPhase.Idle) return
    val colors = LoopkyTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = sheetState,
        // A sheet is a *raised* surface, so it cannot take the ground's colour: in dark mode that
        // left it exactly the tone of the screen behind it and the sheet had no edge at all. Light
        // mode gains a slightly warmer ground for the same reason it always should have.
        containerColor = colors.surfaceSecondary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { testTagsAsResourceId = true }
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (phase) {
                is SpeakPhase.Listening -> ListeningBody(phase.expected)
                is SpeakPhase.Correct -> CorrectBody(phase.heard, onContinue)
                is SpeakPhase.Wrong -> WrongBody(phase.heard, phase.expected, onRetry)
                is SpeakPhase.Failed -> FailedBody(phase, onRetry = onRetry, onDismiss = onCancel)
                SpeakPhase.Idle -> Unit
            }
        }
    }
}

@Composable
private fun ListeningBody(targetWord: String) {
    val colors = LoopkyTheme.colors
    Text(
        text = stringResource(R.string.speak_listening_prompt),
        fontSize = 22.sp,
        fontWeight = FontWeight.W800,
        color = colors.foregroundPrimary,
        textAlign = TextAlign.Center,
    )
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.accentSecondarySoft)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(text = targetWord, fontSize = 28.sp, fontWeight = FontWeight.W800, color = colors.accentSecondary)
    }
    // Solid purple mic in a soft halo ring.
    Box(
        modifier = Modifier
            .size(110.dp)
            .clip(CircleShape)
            .background(colors.accentSecondary.copy(alpha = 0.15f))
            .testTag("speak_mic"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(colors.accentSecondary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Mic, null, tint = colors.foregroundOnAccent, modifier = Modifier.size(36.dp))
        }
    }
    Text(
        text = stringResource(R.string.speak_listening_hint),
        fontSize = 13.sp,
        color = colors.foregroundMuted,
    )
}

@Composable
private fun CorrectBody(heard: String, onContinue: () -> Unit) {
    val colors = LoopkyTheme.colors
    Box(
        modifier = Modifier.size(56.dp).clip(CircleShape).background(colors.srsGood.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Check, null, tint = colors.srsGood, modifier = Modifier.size(28.dp))
    }
    Text(
        text = stringResource(R.string.speak_correct_title),
        fontSize = 20.sp,
        fontWeight = FontWeight.W800,
        color = colors.foregroundPrimary,
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.speak_you_said_label), fontSize = 10.sp, fontWeight = FontWeight.W700, color = colors.foregroundMuted)
        Text(
            text = heard,
            fontSize = 28.sp,
            fontWeight = FontWeight.W800,
            color = colors.srsGood,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("speak_correct_word"),
        )
    }
    SheetButton(
        label = stringResource(R.string.speak_continue),
        background = colors.srsGood,
        contentColor = colors.foregroundOnAccent,
        tag = "speak_continue",
        onClick = onContinue,
    )
}

@Composable
private fun WrongBody(heard: String, expected: String, onRetry: () -> Unit) {
    val colors = LoopkyTheme.colors
    Box(
        modifier = Modifier.size(72.dp).clip(CircleShape).background(colors.dangerSoft),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Close, null, tint = colors.srsAgain, modifier = Modifier.size(32.dp))
    }
    Text(
        text = stringResource(R.string.speak_wrong_title),
        fontSize = 22.sp,
        fontWeight = FontWeight.W700,
        color = colors.foregroundPrimary,
        textAlign = TextAlign.Center,
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.speak_you_said), fontSize = 11.sp, color = colors.foregroundMuted)
        Text(
            text = heard.ifBlank { "—" },
            fontSize = 28.sp,
            fontWeight = FontWeight.W700,
            color = colors.srsAgain,
            modifier = Modifier.testTag("speak_heard"),
        )
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.speak_correct_label), fontSize = 11.sp, color = colors.foregroundMuted)
        Text(text = expected, fontSize = 28.sp, fontWeight = FontWeight.W700, color = colors.srsGood)
    }
    SheetButton(
        label = stringResource(R.string.speak_try_again),
        background = colors.srsAgain,
        contentColor = colors.foregroundOnAccent,
        tag = "speak_retry",
        onClick = onRetry,
    )
}

/**
 * A listen that produced no attempt, said out loud rather than closed away — a sheet that vanishes
 * on ERROR_NO_MATCH or a busy engine is indistinguishable from the app having dropped the tap.
 */
@Composable
private fun FailedBody(phase: SpeakPhase.Failed, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val colors = LoopkyTheme.colors
    Box(
        modifier = Modifier.size(56.dp).clip(CircleShape).background(colors.dangerSoft),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.MicOff, null, tint = colors.srsAgain, modifier = Modifier.size(28.dp))
    }
    Text(
        text = stringResource(phase.reason.titleRes()),
        fontSize = 20.sp,
        fontWeight = FontWeight.W800,
        color = colors.foregroundPrimary,
        textAlign = TextAlign.Center,
        modifier = Modifier.testTag("speak_failed_title"),
    )
    Text(
        text = stringResource(phase.reason.messageRes()),
        fontSize = 13.sp,
        color = colors.foregroundMuted,
        textAlign = TextAlign.Center,
    )
    if (phase.retryable) {
        SheetButton(
            label = stringResource(R.string.speak_try_again),
            background = colors.accentSecondary,
            contentColor = colors.foregroundOnAccent,
            tag = "speak_retry",
            onClick = onRetry,
        )
    } else {
        SheetButton(
            label = stringResource(R.string.speak_close),
            background = colors.accentSecondary,
            contentColor = colors.foregroundOnAccent,
            tag = "speak_failed_close",
            onClick = onDismiss,
        )
    }
}

@StringRes
private fun SpeechError.titleRes(): Int = when (this) {
    SpeechError.NoMatch -> R.string.speak_failed_no_match_title
    SpeechError.Permission -> R.string.speak_failed_permission_title
    SpeechError.Network -> R.string.speak_failed_network_title
    SpeechError.Busy -> R.string.speak_failed_busy_title
    SpeechError.Unavailable -> R.string.speak_failed_unavailable_title
    SpeechError.LanguageUnavailable -> R.string.speak_failed_language_title
    SpeechError.Unknown -> R.string.speak_failed_unknown_title
}

@StringRes
private fun SpeechError.messageRes(): Int = when (this) {
    SpeechError.NoMatch -> R.string.speak_failed_no_match_message
    SpeechError.Permission -> R.string.speak_permission_denied
    SpeechError.Network -> R.string.speak_failed_network_message
    SpeechError.Busy -> R.string.speak_failed_busy_message
    SpeechError.Unavailable -> R.string.speak_unavailable
    SpeechError.LanguageUnavailable -> R.string.speak_failed_language_message
    SpeechError.Unknown -> R.string.speak_failed_unknown_message
}

@Composable
private fun SheetButton(
    label: String,
    background: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    tag: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .clickable(onClick = onClick)
            .testTag(tag)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, fontSize = 15.sp, fontWeight = FontWeight.W700, color = contentColor)
    }
}
