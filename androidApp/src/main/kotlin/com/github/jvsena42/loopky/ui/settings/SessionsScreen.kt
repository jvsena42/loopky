package com.github.jvsena42.loopky.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.AuthorizedSession
import com.github.jvsena42.loopky.domain.model.RequestedAccess
import com.github.jvsena42.loopky.domain.model.SignInRefusal
import com.github.jvsena42.loopky.presentation.settings.ApprovalPhase
import com.github.jvsena42.loopky.presentation.settings.ApprovalUiState
import com.github.jvsena42.loopky.presentation.settings.SessionsEffect
import com.github.jvsena42.loopky.presentation.settings.SessionsUiState
import com.github.jvsena42.loopky.presentation.settings.SessionsViewModel
import com.github.jvsena42.loopky.ui.components.LoopkyErrorBlock
import com.github.jvsena42.loopky.ui.components.LoopkyLoadingScreen
import com.github.jvsena42.loopky.ui.components.LoopkyPrimaryButton
import com.github.jvsena42.loopky.ui.components.LoopkySecondaryButton
import com.github.jvsena42.loopky.ui.discover.scanPubky
import com.github.jvsena42.loopky.ui.layout.PaneWidth
import com.github.jvsena42.loopky.ui.layout.ScreenBottomInsetSpacer
import com.github.jvsena42.loopky.ui.layout.contentPane
import com.github.jvsena42.loopky.ui.layout.screenSides
import com.github.jvsena42.loopky.ui.layout.screenTop
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.ClipboardContent
import com.github.jvsena42.loopky.ui.util.readClipboard
import com.github.jvsena42.loopky.ui.util.toast
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import java.text.DateFormat
import java.util.Date

@Composable
fun SessionsRoute(
    incomingLink: String? = null,
    onIncomingHandled: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    val viewModel = koinViewModel<SessionsViewModel>()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentIncomingHandled by rememberUpdatedState(onIncomingHandled)

    LaunchedEffect(incomingLink) {
        if (incomingLink == null) return@LaunchedEffect
        viewModel.onSignInLink(incomingLink)
        currentIncomingHandled()
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is SessionsEffect.RevokeFailed -> context.toast(R.string.sessions_revoke_failed)
            }
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    SessionsScreen(
        state = state,
        onBack = onBack,
        onRetry = viewModel::onRetry,
        onScan = { scanPubky(context, viewModel::onSignInLink) },
        onPaste = {
            scope.launch {
                when (val clip = context.readClipboard()) {
                    is ClipboardContent.Text -> viewModel.onSignInLink(clip.text)
                    else -> context.toast(R.string.sessions_paste_empty)
                }
            }
        },
        onRevoke = viewModel::onRevoke,
    )

    state.approval?.let { approval ->
        ApprovalSheet(
            approval = approval,
            onApprove = { viewModel.onApprove(approval.shownLink) },
            onDismiss = viewModel::onDismissApproval,
        )
    }
}

@Suppress("LongMethod")
@Composable
private fun SessionsScreen(
    state: SessionsUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onScan: () -> Unit,
    onPaste: () -> Unit,
    onRevoke: (String) -> Unit,
) {
    val colors = LoopkyTheme.colors
    val errorReason = state.errorReason
    var confirming by remember { mutableStateOf<AuthorizedSession?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surfacePrimary)
            .windowInsetsPadding(WindowInsets.screenTop.union(WindowInsets.screenSides))
            .padding(PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp)),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(colors.surfaceCard)
                .clickable(onClick = onBack)
                .testTag("sessions_back"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.settings_back_content_description),
                tint = colors.foregroundPrimary,
                modifier = Modifier.size(20.dp),
            )
        }

        Text(
            text = stringResource(R.string.settings_sessions),
            fontSize = 28.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.ExtraBold,
            color = colors.foregroundPrimary,
        )

        if (!state.canApprove) {
            Text(
                text = stringResource(R.string.sessions_ring_holds_key),
                fontSize = 14.sp,
                color = colors.foregroundMuted,
                modifier = Modifier.testTag("sessions_ring_holds_key"),
            )
            return@Column
        }

        Text(
            text = stringResource(R.string.sessions_caption),
            fontSize = 13.sp,
            color = colors.foregroundMuted,
        )
        Row(
            modifier = Modifier.contentPane(PaneWidth.Reading),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LoopkySecondaryButton(
                text = stringResource(R.string.sessions_scan),
                onClick = onScan,
                icon = Icons.Filled.QrCodeScanner,
                modifier = Modifier
                    .weight(1f)
                    .testTag("sessions_scan"),
            )
            LoopkySecondaryButton(
                text = stringResource(R.string.sessions_paste),
                onClick = onPaste,
                icon = Icons.Filled.ContentPaste,
                modifier = Modifier
                    .weight(1f)
                    .testTag("sessions_paste"),
            )
        }

        when {
            state.isLoading -> Box(modifier = Modifier.fillMaxSize()) {
                LoopkyLoadingScreen(message = stringResource(R.string.sessions_loading))
            }

            errorReason != null -> LoopkyErrorBlock(
                reason = errorReason,
                onRetry = onRetry,
                modifier = Modifier.fillMaxWidth(),
            )

            state.isEmpty -> Text(
                text = stringResource(R.string.sessions_empty),
                fontSize = 14.sp,
                color = colors.foregroundMuted,
                modifier = Modifier.testTag("sessions_empty"),
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .contentPane(PaneWidth.Reading),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                itemsIndexed(state.sessions, key = { _, session -> session.grantId }) { index, session ->
                    SessionRow(
                        session = session,
                        revoking = session.grantId in state.revoking,
                        onRevoke = { confirming = session },
                        modifier = Modifier.testTag("session_row_$index"),
                        revokeTag = "session_revoke_$index",
                    )
                }
                item(key = "bottom_inset") { ScreenBottomInsetSpacer() }
            }
        }
    }

    confirming?.let { session ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            title = { Text(stringResource(R.string.sessions_revoke_dialog_title, appName(session.clientId))) },
            text = { Text(stringResource(R.string.sessions_revoke_dialog_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = null
                        onRevoke(session.grantId)
                    },
                    modifier = Modifier.testTag("sessions_revoke_confirm"),
                ) { Text(stringResource(R.string.sessions_revoke)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.sessions_revoke_cancel)) }
            },
        )
    }
}

@Composable
private fun SessionRow(
    session: AuthorizedSession,
    revoking: Boolean,
    onRevoke: () -> Unit,
    revokeTag: String,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceSecondary)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = appName(session.clientId),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.foregroundPrimary,
            )
            session.access.forEach { access ->
                Text(text = accessLabel(access), fontSize = 13.sp, color = colors.foregroundMuted)
            }
            Text(
                text = stringResource(R.string.sessions_signed_in_on, formatDate(session.issuedAt)),
                fontSize = 12.sp,
                color = colors.foregroundMuted,
            )
        }
        if (session.isThisDevice) {
            Text(
                text = stringResource(R.string.sessions_this_device),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.foregroundMuted,
            )
        } else {
            TextButton(
                onClick = onRevoke,
                enabled = !revoking,
                modifier = Modifier.testTag(revokeTag),
            ) {
                Text(
                    text = stringResource(R.string.sessions_revoke),
                    color = colors.accentPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApprovalSheet(
    approval: ApprovalUiState,
    onApprove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    val approving = approval.phase == ApprovalPhase.Approving
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Not swipeable mid-approval: the sheet would hide while the ViewModel keeps it open.
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { !approving },
        ),
        containerColor = colors.surfaceCard,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        // A sheet is its own window, so the activity root's flag does not reach these tags.
        Column(
            modifier = Modifier
                .semantics { testTagsAsResourceId = true }
                .fillMaxWidth()
                .contentPane(PaneWidth.Focused)
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
                .testTag("approval_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (approval.phase) {
                ApprovalPhase.Refused -> RefusedContent(approval.refusal, onDismiss)
                ApprovalPhase.Approved -> ApprovedContent(approval.clientId, onDismiss)
                else -> ReviewContent(approval, onApprove, onDismiss)
            }
        }
    }
}

@Composable
private fun ReviewContent(approval: ApprovalUiState, onApprove: () -> Unit, onDismiss: () -> Unit) {
    val colors = LoopkyTheme.colors
    val approving = approval.phase == ApprovalPhase.Approving
    SheetTitle(stringResource(R.string.approval_title))
    Text(
        text = approval.clientId?.let { stringResource(R.string.approval_body, it) }
            ?: stringResource(R.string.approval_body_unknown),
        fontSize = 15.sp,
        color = colors.foregroundPrimary,
        modifier = Modifier.testTag("approval_client"),
    )
    Text(
        text = stringResource(R.string.approval_access_label),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = colors.foregroundMuted,
    )
    if (approval.access.isEmpty()) {
        Text(stringResource(R.string.approval_access_none), fontSize = 14.sp, color = colors.foregroundPrimary)
    }
    approval.access.forEach { access ->
        Text(text = "• ${accessLabel(access)}", fontSize = 14.sp, color = colors.foregroundPrimary)
    }
    Text(
        text = stringResource(
            if (approval.reachesBeyondLoopky) R.string.approval_beyond_loopky else R.string.approval_only_if_you_started,
        ),
        fontSize = 13.sp,
        color = if (approval.reachesBeyondLoopky) colors.accentPrimary else colors.foregroundMuted,
        modifier = Modifier.testTag("approval_warning"),
    )
    if (approval.phase == ApprovalPhase.Failed) {
        Text(
            text = stringResource(R.string.approval_failed),
            fontSize = 13.sp,
            color = colors.accentPrimary,
            modifier = Modifier.testTag("approval_failed"),
        )
    }
    LoopkyPrimaryButton(
        label = stringResource(
            if (approval.phase == ApprovalPhase.Failed) R.string.approval_retry else R.string.approval_approve,
        ),
        onClick = onApprove,
        loading = approving,
        modifier = Modifier.testTag("approval_approve"),
    )
    TextButton(
        onClick = onDismiss,
        enabled = !approving,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("approval_deny"),
    ) { Text(stringResource(R.string.approval_deny), color = colors.foregroundPrimary) }
}

@Composable
private fun RefusedContent(refusal: SignInRefusal?, onDismiss: () -> Unit) {
    SheetTitle(stringResource(R.string.approval_refused_title))
    Text(
        text = stringResource(
            when (refusal) {
                SignInRefusal.CreatesAccount -> R.string.refusal_creates_account
                SignInRefusal.WholeAccount -> R.string.refusal_whole_account
                SignInRefusal.NoKeyHere -> R.string.refusal_no_key
                SignInRefusal.OldFormat -> R.string.refusal_old_format
                SignInRefusal.NotASignInLink, null -> R.string.refusal_not_a_link
            },
        ),
        fontSize = 15.sp,
        color = LoopkyTheme.colors.foregroundPrimary,
        modifier = Modifier.testTag("approval_refusal"),
    )
    LoopkyPrimaryButton(
        label = stringResource(R.string.approval_close),
        onClick = onDismiss,
        modifier = Modifier.testTag("approval_close"),
    )
}

@Composable
private fun ApprovedContent(clientId: String?, onDismiss: () -> Unit) {
    SheetTitle(stringResource(R.string.approval_done_title))
    Text(
        text = clientId?.let { stringResource(R.string.approval_done_body, it) }
            ?: stringResource(R.string.approval_done_body_unknown),
        fontSize = 15.sp,
        color = LoopkyTheme.colors.foregroundPrimary,
        modifier = Modifier.testTag("approval_done_body"),
    )
    LoopkyPrimaryButton(
        label = stringResource(R.string.approval_done),
        onClick = onDismiss,
        modifier = Modifier.testTag("approval_done"),
    )
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        fontSize = 22.sp,
        fontWeight = FontWeight.ExtraBold,
        color = LoopkyTheme.colors.foregroundPrimary,
    )
}

@Composable
private fun appName(clientId: String): String =
    clientId.ifBlank { stringResource(R.string.sessions_unknown_app) }

@Composable
private fun accessLabel(access: RequestedAccess): String = when {
    access.isLoopkyOnly && access.canWrite -> stringResource(R.string.sessions_access_loopky)
    access.isLoopkyOnly -> stringResource(R.string.sessions_access_loopky_read)
    access.canWrite -> stringResource(R.string.sessions_access_write, access.path)
    else -> stringResource(R.string.sessions_access_read, access.path)
}

private fun formatDate(epochSeconds: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochSeconds * MILLIS_PER_SECOND))

private const val MILLIS_PER_SECOND = 1000L

@Preview
@Composable
private fun SessionsScreenPreview() {
    LoopkyTheme {
        SessionsScreen(
            state = SessionsUiState(
                isLoading = false,
                sessions = listOf(
                    AuthorizedSession(
                        grantId = "a",
                        clientId = "mcp.loopky.app",
                        access = listOf(RequestedAccess("/pub/loopky/", canWrite = true)),
                        issuedAt = 1_762_000_000,
                        expiresAt = 1_800_000_000,
                        isThisDevice = false,
                    ),
                    AuthorizedSession(
                        grantId = "b",
                        clientId = "loopky.app",
                        access = listOf(RequestedAccess("/", canWrite = true)),
                        issuedAt = 1_761_000_000,
                        expiresAt = 1_800_000_000,
                        isThisDevice = true,
                    ),
                ),
            ),
            onBack = {},
            onRetry = {},
            onScan = {},
            onPaste = {},
            onRevoke = {},
        )
    }
}
