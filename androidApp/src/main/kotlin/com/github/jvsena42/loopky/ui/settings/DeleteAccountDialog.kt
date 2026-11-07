package com.github.jvsena42.loopky.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.PubkyAppFootprint
import com.github.jvsena42.loopky.presentation.settings.DeletionState
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * The delete-account warning, and then the sweep.
 *
 * Two things it does that an ordinary confirmation does not. The confirm button is dead for ten
 * seconds and says how many are left, because this is the one action in Loopky that destroys work
 * irrecoverably and a reflex tap must not be enough. And once the sweep starts the dialog stops
 * being dismissable at all: a back gesture that closed it would hide a deletion that is still
 * running and leave the user believing they had cancelled.
 */
@Suppress("LongParameterList")
@Composable
internal fun DeleteAccountDialog(
    deletion: DeletionState?,
    holdsOwnKey: Boolean,
    losesTheOnlyKey: Boolean,
    onDeletePubkyAppChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (deletion == null) return
    val colors = LoopkyTheme.colors
    val deleting = deletion as? DeletionState.Deleting
    val confirming = deletion as? DeletionState.Confirming

    AlertDialog(
        // No-op while deleting, which is what makes the dialog unclosable then. AlertDialog routes
        // both the scrim tap and the back gesture through here.
        onDismissRequest = { if (deleting == null) onDismiss() },
        // AlertDialog is a separate window: without this its tags are invisible to journeys.
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        containerColor = colors.surfaceCard,
        title = {
            Text(
                text = stringResource(
                    if (deleting != null) {
                        R.string.settings_delete_account_progress_title
                    } else {
                        R.string.settings_delete_account_dialog_title
                    },
                ),
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = colors.foregroundPrimary,
            )
        },
        text = {
            if (deleting != null) {
                DeleteAccountProgress(done = deleting.done, total = deleting.total)
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_delete_account_dialog_body),
                        fontSize = 14.sp,
                        color = colors.foregroundSecondary,
                        lineHeight = 20.sp,
                    )
                    Text(
                        text = stringResource(R.string.settings_delete_account_dialog_irreversible),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.srsAgain,
                        lineHeight = 20.sp,
                    )
                    confirming?.let {
                        DeletePubkyAppSection(confirming = it, onDeletePubkyAppChange = onDeletePubkyAppChange)
                    }
                    // The line that keeps the whole dialog honest: Loopky cannot delete the Pubky
                    // identity, and must not imply that it has.
                    Text(
                        text = stringResource(
                            if (holdsOwnKey) {
                                R.string.settings_delete_account_dialog_identity_device
                            } else {
                                R.string.settings_delete_account_dialog_identity
                            },
                        ),
                        fontSize = 13.sp,
                        color = colors.foregroundMuted,
                        lineHeight = 18.sp,
                    )
                    if (losesTheOnlyKey) {
                        Text(
                            text = stringResource(R.string.settings_delete_account_dialog_only_key),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.srsAgain,
                            lineHeight = 18.sp,
                            modifier = Modifier.testTag("settings_delete_account_only_key"),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (deleting != null) return@AlertDialog
            val seconds = confirming?.secondsRemaining ?: 0
            val ready = confirming?.isConfirmable == true
            TextButton(
                onClick = onConfirm,
                enabled = ready,
                modifier = Modifier.testTag("settings_delete_account_confirm"),
            ) {
                Text(
                    // Keyed on the countdown, not on `ready`: once it ends, a check still running
                    // leaves the button disabled, and "(0)" would read as a stuck timer.
                    text = if (seconds > 0) {
                        stringResource(R.string.settings_delete_account_confirm_countdown, seconds)
                    } else {
                        stringResource(R.string.settings_delete_account_confirm)
                    },
                    color = if (ready) colors.srsAgain else colors.foregroundMuted,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            if (deleting != null) return@AlertDialog
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("settings_delete_account_cancel"),
            ) {
                Text(text = stringResource(R.string.settings_cancel), color = colors.foregroundSecondary)
            }
        },
    )
}

/** What happens to the account's pubky.app records, once the dialog knows what they are. */
@Composable
private fun DeletePubkyAppSection(
    confirming: DeletionState.Confirming,
    onDeletePubkyAppChange: (Boolean) -> Unit,
) {
    val colors = LoopkyTheme.colors
    when (confirming.footprint) {
        null -> Row(
            modifier = Modifier.testTag("settings_delete_account_checking"),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.accentPrimary)
            Text(
                text = stringResource(R.string.settings_delete_account_checking),
                fontSize = 13.sp,
                color = colors.foregroundMuted,
                lineHeight = 18.sp,
            )
        }
        PubkyAppFootprint.LoopkyOnly -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = confirming.deletePubkyApp,
                    role = Role.Checkbox,
                    onValueChange = onDeletePubkyAppChange,
                )
                .testTag("settings_delete_account_pubky_app"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(
                checked = confirming.deletePubkyApp,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(checkedColor = colors.srsAgain),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.settings_delete_account_pubky_app_option),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.foregroundPrimary,
                    lineHeight = 20.sp,
                )
                Text(
                    text = stringResource(R.string.settings_delete_account_pubky_app_option_detail),
                    fontSize = 13.sp,
                    color = colors.foregroundMuted,
                    lineHeight = 18.sp,
                )
            }
        }
        PubkyAppFootprint.Direct, PubkyAppFootprint.Unknown -> Text(
            text = stringResource(
                if (confirming.footprint == PubkyAppFootprint.Direct) {
                    R.string.settings_delete_account_pubky_app_kept
                } else {
                    R.string.settings_delete_account_pubky_app_unknown
                },
            ),
            fontSize = 13.sp,
            color = colors.foregroundSecondary,
            lineHeight = 18.sp,
            modifier = Modifier.testTag("settings_delete_account_pubky_app_kept"),
        )
        PubkyAppFootprint.Empty -> Unit
    }
}

/**
 * A spinner plus a count, and no way to stop.
 *
 * [total] is what the sweep knew when it started, so a determinate bar would be a lie it might
 * have to walk back; the count says what has actually happened. Zero total means the first report
 * has not arrived yet.
 */
@Composable
private fun DeleteAccountProgress(done: Int, total: Int) {
    val colors = LoopkyTheme.colors
    Row(
        modifier = Modifier.testTag("settings_delete_account_progress"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = colors.accentPrimary)
        Text(
            text = if (total > 0) {
                stringResource(R.string.settings_delete_account_progress_count, done, total)
            } else {
                stringResource(R.string.settings_delete_account_progress_starting)
            },
            fontSize = 14.sp,
            color = colors.foregroundSecondary,
        )
    }
}
