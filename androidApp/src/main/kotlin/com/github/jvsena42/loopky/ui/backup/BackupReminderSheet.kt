package com.github.jvsena42.loopky.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.presentation.backup.BackupReminderViewModel
import com.github.jvsena42.loopky.ui.components.LoopkyPrimaryButton
import com.github.jvsena42.loopky.ui.layout.PaneWidth
import com.github.jvsena42.loopky.ui.layout.contentPane
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import org.koin.compose.viewmodel.koinViewModel

/** The once-a-day backup reminder — see [BackupReminderViewModel]. Draws nothing when not due. */
@Composable
fun BackupReminderRoute(onBackUpNow: () -> Unit) {
    val viewModel = koinViewModel<BackupReminderViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onAppForeground()
        onPauseOrDispose {}
    }
    if (state.isVisible) {
        BackupReminderSheet(
            onBackUpNow = {
                viewModel.onDismiss()
                onBackUpNow()
            },
            onDismiss = viewModel::onDismiss,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupReminderSheet(
    onBackUpNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceSecondary,
    ) {
        Column(
            modifier = Modifier
                .contentPane(PaneWidth.Focused)
                .semantics { testTagsAsResourceId = true }
                .testTag("backup_reminder_sheet")
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.backup_reminder_title),
                color = colors.foregroundPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.backup_reminder_body),
                color = colors.foregroundSecondary,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                textAlign = TextAlign.Center,
            )
            LoopkyPrimaryButton(
                label = stringResource(R.string.backup_nag_action),
                onClick = onBackUpNow,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .testTag("backup_reminder_action"),
            )
        }
    }
}
