package com.github.jvsena42.loopky.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.data.unsplash.UNSPLASH_DEVELOPER_URL
import com.github.jvsena42.loopky.data.unsplash.UnsplashError
import com.github.jvsena42.loopky.domain.model.AppTheme
import com.github.jvsena42.loopky.domain.model.SrsGrade
import com.github.jvsena42.loopky.presentation.settings.DeletionState
import com.github.jvsena42.loopky.presentation.settings.SettingsEffect
import com.github.jvsena42.loopky.presentation.settings.SettingsErrorMessage
import com.github.jvsena42.loopky.presentation.settings.SettingsUiState
import com.github.jvsena42.loopky.presentation.settings.SettingsViewModel
import com.github.jvsena42.loopky.presentation.settings.UnsplashKeyStatus
import com.github.jvsena42.loopky.ui.components.LoopkyLoadingScreen
import com.github.jvsena42.loopky.ui.layout.PaneWidth
import com.github.jvsena42.loopky.ui.layout.contentPane
import com.github.jvsena42.loopky.ui.nav.Routes
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.LICENSE_URL
import com.github.jvsena42.loopky.ui.util.PRIVACY_POLICY_URL
import com.github.jvsena42.loopky.ui.util.SecureScreen
import com.github.jvsena42.loopky.ui.util.openUrl
import com.github.jvsena42.loopky.ui.util.rememberAppVersion
import com.github.jvsena42.loopky.ui.util.truncatedPubky
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun SettingsRoute(
    onBack: () -> Unit = {},
    onSignedOut: () -> Unit = {},
    /** Opens the backup menu — from the nag row, and from the sign-out warning. */
    onBackUpNow: () -> Unit = {},
    /** Which row to open and focus on arrival — see [Routes.SETTINGS_FOCUS_UNSPLASH]. */
    focus: String? = null,
) {
    val context = LocalContext.current
    val appVersion = rememberAppVersion()
    val viewModel = koinViewModel<SettingsViewModel> { parametersOf(appVersion) }

    val currentSignedOut by rememberUpdatedState(onSignedOut)
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                SettingsEffect.SignedOut -> currentSignedOut()
                is SettingsEffect.CopyToClipboard -> clipboard.setText(AnnotatedString(effect.text))
                is SettingsEffect.ShowError -> Toast.makeText(
                    context,
                    when (effect.message) {
                        SettingsErrorMessage.StudySettingsNotSaved -> R.string.settings_study_save_failed
                        SettingsErrorMessage.AccountNotDeleted -> R.string.settings_delete_account_failed
                    },
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        focusUnsplashKey = focus == Routes.SETTINGS_FOCUS_UNSPLASH,
        onBack = onBack,
        onCopyPubkyClick = viewModel::onCopyPubkyClick,
        onCopyHomeserverClick = viewModel::onCopyHomeserverClick,
        onShareOnPubkyChange = viewModel::onShareOnPubkyChange,
        onThemeModeChange = viewModel::onThemeModeChange,
        onNewCardsGoalChange = viewModel::onNewCardsGoalChange,
        onIntervalChange = viewModel::onIntervalChange,
        onSaveUnsplashKey = viewModel::onSaveUnsplashKey,
        onRemoveUnsplashKey = viewModel::onRemoveUnsplashKey,
        onUnsplashKeyErrorDismissed = viewModel::onUnsplashKeyErrorDismissed,
        onGetUnsplashKeyClick = { context.openUrl(UNSPLASH_DEVELOPER_URL) },
        onPrivacyPolicyClick = { context.openUrl(PRIVACY_POLICY_URL) },
        onLicenseClick = { context.openUrl(LICENSE_URL) },
        onSignOutClick = viewModel::onSignOutClick,
        onConfirmSignOutWithoutBackup = viewModel::onConfirmSignOutWithoutBackup,
        onDismissSignOutWarning = viewModel::onDismissSignOutWarning,
        onBackUpNow = onBackUpNow,
        onDeleteAccountClick = viewModel::onDeleteAccountClick,
        onConfirmDeleteAccount = viewModel::onConfirmDeleteAccount,
        onDeleteAccountDismissed = viewModel::onDeleteAccountDismissed,
    )
}

@Suppress("LongParameterList", "LongMethod") // One screen; the sections read top-to-bottom.
@Composable
private fun SettingsScreen(
    state: SettingsUiState,
    focusUnsplashKey: Boolean,
    onBack: () -> Unit,
    onCopyPubkyClick: () -> Unit,
    onCopyHomeserverClick: () -> Unit,
    onShareOnPubkyChange: (Boolean) -> Unit,
    onThemeModeChange: (AppTheme) -> Unit,
    onNewCardsGoalChange: (Int) -> Unit,
    onIntervalChange: (SrsGrade, Int) -> Unit,
    onSaveUnsplashKey: (String) -> Unit,
    onRemoveUnsplashKey: () -> Unit,
    onUnsplashKeyErrorDismissed: () -> Unit,
    onGetUnsplashKeyClick: () -> Unit,
    onPrivacyPolicyClick: () -> Unit,
    onLicenseClick: () -> Unit,
    onSignOutClick: () -> Unit,
    onConfirmSignOutWithoutBackup: () -> Unit,
    onDismissSignOutWarning: () -> Unit,
    onBackUpNow: () -> Unit,
    onDeleteAccountClick: () -> Unit,
    onConfirmDeleteAccount: () -> Unit,
    onDeleteAccountDismissed: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    var showSignOutDialog by remember { mutableStateOf(false) }

    // The access key is a credential, and this is the one screen that puts a field for it on
    // display. Release builds refuse screenshots and screen recording while it is open.
    SecureScreen()

    if (state.isLoading) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.surfacePrimary),
        ) {
            LoopkyLoadingScreen(message = stringResource(R.string.settings_loading))
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surfacePrimary)
            .windowInsetsPadding(WindowInsets.systemBars)
            // Without this the scroll container keeps its full height behind the keyboard, and
            // the Unsplash key editor's Save button is unreachable while the user is typing into
            // the field right above it.
            .imePadding()
            .verticalScroll(rememberScrollState())
            // A settings row is a label and its control; at full tablet width the two end up at
            // opposite edges and stop reading as one row. See ProfileScreen for why this sits
            // after the background rather than before it.
            .contentPane(PaneWidth.Reading)
            .padding(PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 100.dp)),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // --- Header: back + title ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(
                onClick = onBack,
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = colors.surfaceCard,
                    contentColor = colors.foregroundPrimary,
                ),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.settings_back_content_description),
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = stringResource(R.string.settings_title),
                fontSize = 28.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.ExtraBold,
                color = colors.foregroundPrimary,
            )
        }

        // --- Identity section ---
        SettingsSectionLabel(text = stringResource(R.string.settings_section_identity))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surfaceSecondary),
        ) {
            SettingsValueRow(
                label = stringResource(R.string.settings_pubky_label),
                value = if (state.pubky.isNotBlank()) {
                    truncatedPubky(state.pubky)
                } else {
                    stringResource(R.string.settings_not_signed_in)
                },
                trailing = {
                    if (state.pubky.isNotBlank()) {
                        FilledTonalButton(
                            onClick = onCopyPubkyClick,
                            modifier = Modifier.testTag("settings_copy_pubky"),
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = colors.surfaceCard,
                                contentColor = colors.foregroundMuted,
                            ),
                        ) {
                            Text(
                                text = stringResource(R.string.settings_copy),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                },
            )
            SettingsDivider()
            // Truncated and copyable, like the pubky directly above it. Shown whole it was a
            // 52-character key wrapped over two lines that nobody can read and nobody could copy.
            SettingsValueRow(
                label = stringResource(R.string.settings_homeserver_label),
                value = if (state.homeserver.isNotBlank()) {
                    truncatedPubky(state.homeserver)
                } else {
                    stringResource(R.string.settings_homeserver_unknown)
                },
                trailing = {
                    if (state.homeserver.isNotBlank()) {
                        FilledTonalButton(
                            onClick = onCopyHomeserverClick,
                            modifier = Modifier.testTag("settings_copy_homeserver"),
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = colors.surfaceCard,
                                contentColor = colors.foregroundMuted,
                            ),
                        ) {
                            Text(
                                text = stringResource(R.string.settings_copy),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                },
            )
            // A permanent way back into the backup flow, for as long as Loopky holds the key.
            //
            // The card on Profile is not enough on its own: it disappears the moment one method is
            // done, which made the whole flow unreachable afterwards — no second method, no
            // encrypted file to add later, and no route at all for a restored account, which is
            // marked backed-up the moment it signs in. Backup methods accumulate deliberately, so
            // the door has to stay open.
            //
            // [SettingsUiState.holdsOwnKey], not a bare custody check: the key in the vault is not
            // always the signed-in account's.
            if (state.holdsOwnKey) {
                SettingsDivider()
                SettingsLinkRow(
                    label = stringResource(R.string.settings_back_up_account),
                    onClick = onBackUpNow,
                    modifier = Modifier.testTag("settings_back_up_account"),
                )
            }
        }

        // --- Sharing section ---
        SettingsSectionLabel(text = stringResource(R.string.settings_section_sharing))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surfaceSecondary),
        ) {
            SettingsSwitchRow(
                label = stringResource(R.string.settings_share_on_pubky_label),
                description = stringResource(R.string.settings_share_on_pubky_description),
                checked = state.shareOnPubky,
                onCheckedChange = onShareOnPubkyChange,
                testTag = "settings_share_on_pubky",
            )
        }

        // --- Appearance section ---
        SettingsSectionLabel(text = stringResource(R.string.settings_section_appearance))
        AppearanceSection(selected = state.themeMode, onThemeChange = onThemeModeChange)

        // --- Language section ---
        SettingsSectionLabel(text = stringResource(R.string.settings_section_language))
        LanguageSection()

        // --- Studying section ---
        SettingsSectionLabel(text = stringResource(R.string.settings_section_studying))
        StudySettingsSection(
            settings = state.studySettings,
            enabled = state.canEditStudySettings,
            onGoalChange = onNewCardsGoalChange,
            onIntervalChange = onIntervalChange,
        )

        // --- Image search section ---
        SettingsSectionLabel(text = stringResource(R.string.settings_section_image_search))
        UnsplashKeySection(
            status = state.unsplashKeyStatus,
            isVerifying = state.isVerifyingUnsplashKey,
            error = state.unsplashKeyError,
            startExpanded = focusUnsplashKey,
            onSave = onSaveUnsplashKey,
            onRemove = onRemoveUnsplashKey,
            onErrorDismissed = onUnsplashKeyErrorDismissed,
            onGetKey = onGetUnsplashKeyClick,
        )

        // --- About section ---
        SettingsSectionLabel(text = stringResource(R.string.settings_section_about))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surfaceSecondary),
        ) {
            SettingsValueRow(
                label = stringResource(R.string.settings_app_version_label),
                value = state.appVersion.ifBlank { stringResource(R.string.settings_app_version_unknown) },
            )
            SettingsDivider()
            SettingsLinkRow(
                label = stringResource(R.string.settings_privacy_policy),
                onClick = onPrivacyPolicyClick,
                modifier = Modifier.testTag("settings_privacy_policy"),
            )
            SettingsDivider()
            SettingsLinkRow(
                label = stringResource(R.string.settings_license),
                onClick = onLicenseClick,
                modifier = Modifier.testTag("settings_license"),
            )
        }

        // --- Sign out ---
        FilledTonalButton(
            onClick = { showSignOutDialog = true },
            modifier = Modifier
                .testTag("settings_signout")
                .fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = colors.dangerSoft,
                contentColor = colors.srsAgain,
            ),
        ) {
            Icon(
                painter = painterResource(id = android.R.drawable.ic_menu_close_clear_cancel),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.settings_sign_out),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        // --- Delete account ---
        // A text button rather than a second filled one: two adjacent red blocks read as a pair
        // of equals, and only one of these destroys anything.
        TextButton(
            onClick = onDeleteAccountClick,
            modifier = Modifier
                .testTag("settings_delete_account")
                .fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.settings_delete_account),
                fontSize = 14.sp,
                fontWeight = FontWeight.W600,
                color = colors.srsAgain,
            )
        }
    }

    // --- Delete account confirmation ---
    DeleteAccountDialog(
        deletion = state.deletion,
        onConfirm = onConfirmDeleteAccount,
        onDismiss = onDeleteAccountDismissed,
    )

    // --- Sign out confirmation ---
    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            // AlertDialog is a separate window: without this its tags are invisible to journeys.
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            containerColor = colors.surfaceCard,
            title = {
                Text(
                    text = stringResource(R.string.settings_sign_out_dialog_title),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = colors.foregroundPrimary,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.settings_sign_out_dialog_message),
                    fontSize = 14.sp,
                    color = colors.foregroundSecondary,
                    lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSignOutDialog = false
                        onSignOutClick()
                    },
                    modifier = Modifier.testTag("settings_signout_confirm"),
                ) {
                    Text(
                        text = stringResource(R.string.settings_sign_out),
                        color = colors.srsAgain,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) {
                    Text(text = stringResource(R.string.settings_cancel), color = colors.foregroundSecondary)
                }
            },
        )
    }

    // --- Signing out would erase an un-backed-up key ---
    //
    // Raised by the repository *refusing* the sign-out, not by the screen checking a flag. That
    // way a future caller that forgets this dialog fails to sign out rather than silently
    // destroying the only copy of an identity.
    state.unbackedUpPubky?.let { pubky ->
        AlertDialog(
            onDismissRequest = onDismissSignOutWarning,
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            containerColor = colors.surfaceCard,
            title = {
                Text(
                    text = stringResource(R.string.settings_signout_unbacked_title),
                    modifier = Modifier.testTag("settings_signout_unbacked_title"),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = colors.foregroundPrimary,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.settings_signout_unbacked_body, pubky.take(PUBKY_PREVIEW_LEN)),
                    fontSize = 14.sp,
                    color = colors.foregroundSecondary,
                    lineHeight = 20.sp,
                )
            },
            // Backing up is the confirm button — it is what almost everyone here should do, and
            // the destructive option is the quiet one.
            confirmButton = {
                TextButton(
                    onClick = {
                        onDismissSignOutWarning()
                        onBackUpNow()
                    },
                    modifier = Modifier.testTag("settings_signout_unbacked_backup"),
                ) {
                    Text(
                        text = stringResource(R.string.settings_signout_unbacked_backup),
                        color = colors.accentSecondary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onConfirmSignOutWithoutBackup,
                    modifier = Modifier.testTag("settings_signout_unbacked_confirm"),
                ) {
                    Text(
                        text = stringResource(R.string.settings_signout_unbacked_confirm),
                        color = colors.srsAgain,
                    )
                }
            },
        )
    }
}

private const val PUBKY_PREVIEW_LEN = 12

/**
 * The Unsplash access key row: a status line, Set/Replace/Remove, and — while editing — a masked
 * field plus instructions.
 *
 * No key material passes through here beyond what the user is typing. The built-in fallback is
 * never rendered at all (the status line only admits it exists), a saved key comes back as four
 * characters via [UnsplashKeyStatus.UserSet], and the draft lives in this composable's own state
 * rather than in `SettingsUiState` — a secret in a StateFlow is one state dump away from a log.
 * There is deliberately no reveal toggle.
 */
@Suppress("LongParameterList", "LongMethod") // One row with an inline editor; splitting it hides the flow.
@Composable
private fun UnsplashKeySection(
    status: UnsplashKeyStatus,
    isVerifying: Boolean,
    error: UnsplashError?,
    startExpanded: Boolean,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
    onErrorDismissed: () -> Unit,
    onGetKey: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    var isEditing by remember { mutableStateOf(startExpanded) }
    var draft by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    // Arriving from the image sheet's "Add key" means the field is the reason for the trip.
    LaunchedEffect(startExpanded) {
        if (startExpanded) focusRequester.requestFocus()
    }
    // A successful save is signalled by the status turning UserSet with no error pending.
    LaunchedEffect(status) {
        if (status is UnsplashKeyStatus.UserSet) {
            isEditing = false
            draft = ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceSecondary)
            .testTag("settings_unsplash_key"),
    ) {
        SettingsValueRow(
            label = stringResource(R.string.settings_unsplash_key_label),
            value = when (status) {
                UnsplashKeyStatus.NotSet -> stringResource(R.string.settings_unsplash_key_not_set)
                UnsplashKeyStatus.UsingBuiltIn -> stringResource(R.string.settings_unsplash_key_built_in)
                is UnsplashKeyStatus.UserSet ->
                    stringResource(R.string.settings_unsplash_key_user_set, status.suffix)
            },
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (status is UnsplashKeyStatus.UserSet) {
                        SettingsPillButton(
                            text = stringResource(R.string.settings_unsplash_key_remove),
                            onClick = onRemove,
                            modifier = Modifier.testTag("settings_unsplash_key_remove"),
                        )
                    }
                    if (!isEditing) {
                        SettingsPillButton(
                            text = if (status is UnsplashKeyStatus.UserSet) {
                                stringResource(R.string.settings_unsplash_key_replace)
                            } else {
                                stringResource(R.string.settings_unsplash_key_set)
                            },
                            onClick = { isEditing = true },
                            modifier = Modifier.testTag("settings_unsplash_key_edit"),
                        )
                    }
                }
            },
        )

        if (isEditing) {
            SettingsDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = {
                        draft = it
                        if (error != null) onErrorDismissed()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .testTag("settings_unsplash_key_input"),
                    placeholder = {
                        Text(
                            text = stringResource(R.string.settings_unsplash_key_placeholder),
                            color = colors.foregroundMuted,
                        )
                    },
                    singleLine = true,
                    isError = error != null,
                    // Masked with no reveal toggle: the point of the field is to accept a key,
                    // not to display one.
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accentPrimary,
                        unfocusedBorderColor = colors.borderSubtle,
                        cursorColor = colors.accentPrimary,
                        errorBorderColor = colors.danger,
                    ),
                )

                error?.let {
                    Text(
                        text = stringResource(it.settingsMessage()),
                        fontSize = 12.sp,
                        color = colors.danger,
                        modifier = Modifier.testTag("settings_unsplash_key_error"),
                    )
                }

                Text(
                    text = stringResource(R.string.settings_unsplash_key_hint),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = colors.foregroundMuted,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_unsplash_key_get),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.accentPrimary,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .clickable(onClick = onGetKey)
                            .testTag("settings_unsplash_key_get"),
                    )
                    Spacer(Modifier.weight(1f))
                    if (isVerifying) {
                        CircularProgressIndicator(
                            color = colors.accentPrimary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    SettingsPillButton(
                        text = stringResource(R.string.settings_unsplash_key_cancel),
                        onClick = {
                            isEditing = false
                            draft = ""
                            onErrorDismissed()
                        },
                    )
                    FilledTonalButton(
                        onClick = { onSave(draft) },
                        enabled = draft.isNotBlank() && !isVerifying,
                        modifier = Modifier.testTag("settings_unsplash_key_save"),
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = colors.accentPrimary,
                            contentColor = colors.foregroundOnAccent,
                        ),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_unsplash_key_save),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

/** Why Unsplash refused the key the user just typed. */
private fun UnsplashError.settingsMessage(): Int = when (this) {
    UnsplashError.MissingKey -> R.string.settings_unsplash_key_error_missing
    UnsplashError.InvalidKey -> R.string.settings_unsplash_key_error_invalid
    UnsplashError.RateLimited -> R.string.settings_unsplash_key_error_rate_limited
    UnsplashError.Unavailable -> R.string.settings_unsplash_key_error_unavailable
}

/** The small tonal pill used for row-level actions — same shape as the Copy button above. */
@Composable
private fun SettingsPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = colors.surfaceCard,
            contentColor = colors.foregroundMuted,
        ),
    ) {
        Text(text = text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SettingsSectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = LoopkyTheme.colors.foregroundMuted,
    )
}

@Composable
private fun SettingsValueRow(
    label: String,
    value: String,
    trailing: @Composable () -> Unit = {},
) {
    val colors = LoopkyTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.W500,
                color = colors.foregroundMuted,
            )
            Text(
                text = value,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.foregroundPrimary,
            )
        }
        trailing()
    }
}

/**
 * A preference the user toggles. Distinct from [SettingsValueRow] in carrying a description: this
 * one changes what the app does, and "Share on Pubky" in particular has to say that it governs a
 * *post*, not who can see the deck — published decks are public either way (spec §11).
 */
@Composable
private fun SettingsSwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
) {
    val colors = LoopkyTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.foregroundPrimary,
            )
            Text(
                text = description,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = colors.foregroundMuted,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.surfacePrimary,
                checkedTrackColor = colors.accentPrimary,
            ),
        )
    }
}

/**
 * The delete-account warning, and then the sweep.
 *
 * Two things it does that an ordinary confirmation does not. The confirm button is dead for ten
 * seconds and says how many are left, because this is the one action in Loopky that destroys work
 * irrecoverably and a reflex tap must not be enough. And once the sweep starts the dialog stops
 * being dismissable at all: a back gesture that closed it would hide a deletion that is still
 * running and leave the user believing they had cancelled.
 */
@Composable
private fun DeleteAccountDialog(
    deletion: DeletionState?,
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
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    // The line that keeps the whole dialog honest: Loopky cannot delete the Pubky
                    // identity, and must not imply that it has.
                    Text(
                        text = stringResource(R.string.settings_delete_account_dialog_identity),
                        fontSize = 13.sp,
                        color = colors.foregroundMuted,
                        lineHeight = 18.sp,
                    )
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
                    text = if (ready) {
                        stringResource(R.string.settings_delete_account_confirm)
                    } else {
                        stringResource(R.string.settings_delete_account_confirm_countdown, seconds)
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

/**
 * A row that leaves the app. Distinct from [SettingsValueRow], which shows a fact: the trailing
 * open-in-new glyph is the only signal that a tap here hands the user to a browser.
 */
@Composable
private fun SettingsLinkRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            fontSize = 14.sp,
            fontWeight = FontWeight.W500,
            color = colors.foregroundPrimary,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = colors.foregroundMuted,
        )
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = LoopkyTheme.colors.borderSubtle,
    )
}

@Preview
@Composable
private fun SettingsScreenPreview() {
    LoopkyTheme {
        SettingsScreen(
            state = SettingsUiState(
                isLoading = false,
                pubky = "abcdef1234567890abcdef",
                displayName = "Ada Lovelace",
                homeserver = "homeserver.pubky.org",
                appVersion = "1.0.0",
            ),
            focusUnsplashKey = false,
            onBack = {},
            onCopyPubkyClick = {},
            onCopyHomeserverClick = {},
            onShareOnPubkyChange = {},
            onThemeModeChange = {},
            onNewCardsGoalChange = {},
            onIntervalChange = { _, _ -> },
            onSaveUnsplashKey = {},
            onRemoveUnsplashKey = {},
            onUnsplashKeyErrorDismissed = {},
            onGetUnsplashKeyClick = {},
            onPrivacyPolicyClick = {},
            onLicenseClick = {},
            onSignOutClick = {},
            onConfirmSignOutWithoutBackup = {},
            onDismissSignOutWarning = {},
            onBackUpNow = {},
            onDeleteAccountClick = {},
            onConfirmDeleteAccount = {},
            onDeleteAccountDismissed = {},
        )
    }
}
