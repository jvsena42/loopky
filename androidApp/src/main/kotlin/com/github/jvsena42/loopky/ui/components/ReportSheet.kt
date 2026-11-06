package com.github.jvsena42.loopky.ui.components

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.ReportIssue
import com.github.jvsena42.loopky.presentation.report.ReportEffect
import com.github.jvsena42.loopky.presentation.report.ReportUiState
import com.github.jvsena42.loopky.presentation.report.ReportViewModel
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.toast
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The report sheet for a profile ([deck] null) or a deck. It says before sending that the report
 * also blocks the account, and hands [onSent] whether that block landed (#577).
 */
@Composable
fun ReportSheetHost(
    accountPubky: String,
    deck: Deck?,
    onDismiss: () -> Unit,
    onSent: (blocked: Boolean) -> Unit,
) {
    val viewModel = koinViewModel<ReportViewModel>(key = "report:$accountPubky:${deck?.id}") {
        parametersOf(accountPubky, deck)
    }
    val currentOnSent by rememberUpdatedState(onSent)
    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is ReportEffect.Sent -> currentOnSent(effect.blocked)
            }
        }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    ReportSheet(
        state = state,
        onIssueSelected = viewModel::onIssueSelected,
        onDetailsChange = viewModel::onDetailsChange,
        onSend = viewModel::onSend,
        onDismiss = {
            viewModel.onDismiss()
            onDismiss()
        },
    )
}

/**
 * After a report: one that also blocked closes the screen, as a block does; one whose block failed
 * says so and leaves the reader where they are.
 */
fun Context.announceReport(blocked: Boolean, leave: () -> Unit) {
    if (blocked) {
        toast(R.string.report_done)
        leave()
    } else {
        toast(R.string.report_done_block_failed)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun ReportSheet(
    state: ReportUiState,
    onIssueSelected: (ReportIssue) -> Unit,
    onDetailsChange: (String) -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    val isSending by rememberUpdatedState(state.isSending)
    ModalBottomSheet(
        onDismissRequest = { if (!state.isSending) onDismiss() },
        // The hide itself is refused mid-send: the sheet settles to Hidden before it calls
        // onDismissRequest, and a hidden sheet still composed keeps a window that eats the next tap.
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden || !isSending },
        ),
        containerColor = colors.surfaceCard,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = Modifier.semantics { testTagsAsResourceId = true },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp)
                .testTag("report_sheet"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(
                    if (state.isDeck) R.string.report_sheet_title_deck else R.string.report_sheet_title_profile,
                ),
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = colors.foregroundPrimary,
            )
            Text(
                text = stringResource(R.string.report_issue_label),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = colors.foregroundSecondary,
            )
            Column(modifier = Modifier.selectableGroup()) {
                state.issues.forEach { issue ->
                    IssueRow(
                        issue = issue,
                        selected = state.issue == issue,
                        enabled = !state.isSending,
                        onClick = { onIssueSelected(issue) },
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = state.details,
                    onValueChange = onDetailsChange,
                    enabled = !state.isSending,
                    label = { Text(stringResource(R.string.report_details_label)) },
                    placeholder = { Text(stringResource(R.string.report_details_placeholder)) },
                    minLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp)
                        .testTag("report_details"),
                )
                CharacterCounter(
                    current = state.details.length,
                    max = state.detailsMaxLength,
                    modifier = Modifier.align(Alignment.End),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.dangerSoft)
                    .padding(14.dp),
            ) {
                Text(
                    text = stringResource(
                        if (state.isDeck) R.string.report_blocks_deck else R.string.report_blocks_profile,
                    ),
                    fontSize = 13.sp,
                    color = colors.foregroundPrimary,
                    modifier = Modifier.testTag("report_blocks_notice"),
                )
            }
            Text(
                text = stringResource(R.string.report_public_note),
                fontSize = 12.sp,
                color = colors.foregroundMuted,
            )
            state.errorReason?.let { reason ->
                Text(
                    text = errorMessage(reason),
                    fontSize = 13.sp,
                    color = colors.danger,
                    modifier = Modifier.testTag("report_error"),
                )
            }
            LoopkyPrimaryButton(
                label = stringResource(R.string.report_send),
                onClick = onSend,
                enabled = state.canSend,
                loading = state.isSending,
                modifier = Modifier.testTag("report_send"),
            )
            TextButton(
                onClick = onDismiss,
                enabled = !state.isSending,
                modifier = Modifier.fillMaxWidth().testTag("report_cancel"),
            ) {
                Text(stringResource(R.string.report_cancel), color = colors.foregroundSecondary)
            }
        }
    }
}

@Composable
private fun IssueRow(issue: ReportIssue, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = LoopkyTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp)
            .testTag("report_issue_${issue.wire}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(selectedColor = colors.accentPrimary),
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = stringResource(issue.labelRes),
            fontSize = 15.sp,
            color = colors.foregroundPrimary,
        )
    }
}

@get:StringRes
private val ReportIssue.labelRes: Int
    get() = when (this) {
        ReportIssue.PersonalInfo -> R.string.report_issue_personal_info
        ReportIssue.HateSpeech -> R.string.report_issue_hate_speech
        ReportIssue.Harassment -> R.string.report_issue_harassment
        ReportIssue.ChildAbuse -> R.string.report_issue_child_abuse
        ReportIssue.Terrorism -> R.string.report_issue_terrorism
        ReportIssue.Violence -> R.string.report_issue_violence
        ReportIssue.IllegalSales -> R.string.report_issue_illegal_sales
        ReportIssue.SexualContent -> R.string.report_issue_sexual_content
        ReportIssue.Copyright -> R.string.report_issue_copyright
    }
