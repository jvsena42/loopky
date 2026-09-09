package com.github.jvsena42.loopky.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme

/**
 * Explains why a permission is needed *before* the system prompt appears.
 *
 * Speak used to fire the RECORD_AUDIO dialog cold, with no context for what Loopky wanted the
 * microphone for.
 */
@Composable
fun PermissionRationaleDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceCard,
        title = { Text(title, color = colors.foregroundPrimary) },
        text = { Text(message, color = colors.foregroundSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("permission_continue")) {
                Text(stringResource(R.string.permission_continue), color = colors.accentPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.permission_not_now), color = colors.foregroundMuted)
            }
        },
    )
}

/**
 * Shown once a permission is permanently denied — the system prompt will not appear again, so
 * without this the control is simply inert forever with no way back.
 */
@Composable
fun PermissionBlockedDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceCard,
        title = { Text(title, color = colors.foregroundPrimary) },
        text = { Text(message, color = colors.foregroundSecondary) },
        confirmButton = {
            TextButton(
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    )
                    context.startActivity(intent)
                    onDismiss()
                },
                modifier = Modifier.testTag("permission_open_settings"),
            ) {
                Text(stringResource(R.string.permission_open_settings), color = colors.accentPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.permission_not_now), color = colors.foregroundMuted)
            }
        },
    )
}
