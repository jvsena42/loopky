package com.github.jvsena42.loopky.ui.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.presentation.profile.FollowSource
import com.github.jvsena42.loopky.presentation.profile.ProfileEffect
import com.github.jvsena42.loopky.presentation.profile.ProfileUiState
import com.github.jvsena42.loopky.presentation.profile.ProfileViewModel
import com.github.jvsena42.loopky.ui.components.LoopkyLoadingScreen
import com.github.jvsena42.loopky.ui.components.LoopkyPrimaryButton
import com.github.jvsena42.loopky.ui.components.ProfileHero
import com.github.jvsena42.loopky.ui.components.ProfileStat
import com.github.jvsena42.loopky.ui.components.ProfileStatsCard
import com.github.jvsena42.loopky.ui.components.PubkyAppProfileCta
import com.github.jvsena42.loopky.ui.layout.PaneWidth
import com.github.jvsena42.loopky.ui.layout.contentPane
import com.github.jvsena42.loopky.ui.layout.windowWidthClass
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.label
import com.github.jvsena42.loopky.ui.util.openUrl
import com.github.jvsena42.loopky.ui.util.shareText
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ProfileRoute(
    onSignedOut: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenFollows: (pubky: String, source: FollowSource) -> Unit = { _, _ -> },
    /** Opens the backup menu, from the card that sits above sign-out. */
    onBackUpNow: () -> Unit = {},
) {
    val viewModel = koinViewModel<ProfileViewModel>()

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val currentSignedOut by rememberUpdatedState(onSignedOut)
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                ProfileEffect.NavigateToOnboarding -> currentSignedOut()
                is ProfileEffect.ShareProfile -> context.shareText(
                    // Named, not a bare key: a recipient sees who it is before tapping.
                    text = context.getString(
                        R.string.share_profile_body,
                        effect.identity.label(context),
                        effect.uri,
                    ),
                    chooserTitle = context.getString(R.string.share_profile_chooser_title),
                )
                is ProfileEffect.CopyToClipboard -> clipboard.setText(AnnotatedString(effect.text))
                is ProfileEffect.OpenUrl -> context.openUrl(effect.url)
                is ProfileEffect.ShowError -> { errorMessage = effect.message }
            }
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOpenFollows by rememberUpdatedState(onOpenFollows)
    ProfileScreen(
        state = state,
        errorMessage = errorMessage,
        onOpenSettings = onOpenSettings,
        onOpenFollows = { source ->
            state.identity?.let { currentOpenFollows(it.pubky, source) }
        },
        onEditProfileClick = viewModel::onEditProfileClick,
        onDismissNameNudge = viewModel::onDismissNameNudge,
        onDismissAvatarNudge = viewModel::onDismissAvatarNudge,
        onShareClick = viewModel::onShareClick,
        onOpenOnPubkyApp = viewModel::onOpenOnPubkyApp,
        onCopyPubky = viewModel::onCopyPubky,
        onBackUpNow = onBackUpNow,
        onSignOutClick = viewModel::onSignOutClick,
        onDismissEditSheet = viewModel::onDismissEditSheet,
        onEditNameChanged = viewModel::onEditNameChanged,
        onEditBioChanged = viewModel::onEditBioChanged,
        onSaveClick = viewModel::onSaveClick,
        onDismissError = { errorMessage = null },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileScreen(
    state: ProfileUiState,
    errorMessage: String?,
    onOpenSettings: () -> Unit,
    onOpenFollows: (FollowSource) -> Unit,
    onEditProfileClick: () -> Unit,
    onDismissNameNudge: () -> Unit,
    onDismissAvatarNudge: () -> Unit,
    onShareClick: () -> Unit,
    onOpenOnPubkyApp: () -> Unit,
    onCopyPubky: () -> Unit,
    onBackUpNow: () -> Unit,
    onSignOutClick: () -> Unit,
    onDismissEditSheet: () -> Unit,
    onEditNameChanged: (String) -> Unit,
    onEditBioChanged: (String) -> Unit,
    onSaveClick: () -> Unit,
    onDismissError: () -> Unit,
) {
    // Settings confirms sign-out and reassures that decks stay on the homeserver; this button
    // used to sign out immediately, which is a surprising outcome for one stray tap.
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    // Loopky cannot set a photo: the profile write it owns is name and bio, and the picture is a
    // file record pubky.app uploads. So the camera badge explains where it is done rather than
    // opening a picker that would have nothing to save into.
    var explainAvatar by rememberSaveable { mutableStateOf(false) }

    if (explainAvatar) {
        AlertDialog(
            onDismissRequest = { explainAvatar = false },
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            title = { Text(stringResource(R.string.profile_avatar_dialog_title)) },
            text = { Text(stringResource(R.string.profile_avatar_hint)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        explainAvatar = false
                        onOpenOnPubkyApp()
                    },
                    modifier = Modifier.testTag("profile_avatar_open_pubky_app"),
                ) {
                    Text(stringResource(R.string.profile_avatar_open_pubky_app))
                }
            },
            dismissButton = {
                TextButton(onClick = { explainAvatar = false }) {
                    Text(stringResource(R.string.profile_avatar_not_now))
                }
            },
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            modifier = Modifier.semantics { testTagsAsResourceId = true },
            title = { Text(stringResource(R.string.settings_sign_out_dialog_title)) },
            text = { Text(stringResource(R.string.settings_sign_out_dialog_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSignOut = false
                        onSignOutClick()
                    },
                    modifier = Modifier.testTag("profile_signout_confirm"),
                ) {
                    Text(stringResource(R.string.settings_sign_out))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
    val colors = LoopkyTheme.colors

    if (state.isLoading) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.surfacePrimary),
        ) {
            LoopkyLoadingScreen(message = stringResource(R.string.profile_loading))
        }
        return
    }

    val wide = windowWidthClass().isExpanded
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surfacePrimary)
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            // After the background and the scroll, so the surface still reaches both edges and
            // only the content inside is bounded. A stat band stretched to a landscape tablet put
            // "Decks" and "Followers" a hand's width apart with nothing in between.
            .contentPane(if (wide) PaneWidth.Wide else PaneWidth.Reading)
            .padding(PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // --- Nav title + settings entry point ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.profile_title),
                fontSize = 28.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.ExtraBold,
                color = colors.foregroundPrimary,
            )
            OutlinedIconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .testTag("profile_settings")
                    .size(40.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.outlinedIconButtonColors(
                    containerColor = colors.surfaceCard,
                    contentColor = colors.foregroundSecondary,
                ),
                border = BorderStroke(1.dp, colors.borderSubtle),
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.profile_settings_content_description),
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        if (wide) {
            // Who you are on the left, what that adds up to on the right. Stacked, this screen is
            // a column of full-width bands: at tablet width a three-number stat card puts "Decks"
            // and "Due" a hand's width apart with a strip of empty card between them.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                ProfileIdentityPane(
                    state = state,
                    onCopyPubky = onCopyPubky,
                    onEditProfileClick = onEditProfileClick,
                    onDismissNameNudge = onDismissNameNudge,
                    onDismissAvatarNudge = onDismissAvatarNudge,
                    onEditAvatarClick = { explainAvatar = true },
                    onOpenOnPubkyApp = onOpenOnPubkyApp,
                    onShareClick = onShareClick,
                    modifier = Modifier.width(PROFILE_PANE_WIDTH),
                )
                ProfileDetailsPane(
                    state = state,
                    onOpenFollows = onOpenFollows,
                    onOpenOnPubkyApp = onOpenOnPubkyApp,
                    onBackUpNow = onBackUpNow,
                    onSignOutRequest = { confirmSignOut = true },
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            ProfileIdentityPane(
                state = state,
                onCopyPubky = onCopyPubky,
                onEditProfileClick = onEditProfileClick,
                onDismissNameNudge = onDismissNameNudge,
                onDismissAvatarNudge = onDismissAvatarNudge,
                onEditAvatarClick = { explainAvatar = true },
                onOpenOnPubkyApp = onOpenOnPubkyApp,
                onShareClick = onShareClick,
            )
            ProfileDetailsPane(
                state = state,
                onOpenFollows = onOpenFollows,
                onOpenOnPubkyApp = onOpenOnPubkyApp,
                onBackUpNow = onBackUpNow,
                onSignOutRequest = { confirmSignOut = true },
            )
        }
    }

    // --- Error snackbar ---
    if (errorMessage != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Snackbar(
                containerColor = colors.dangerSoft,
                contentColor = colors.srsAgain,
                action = {
                    TextButton(
                        onClick = onDismissError,
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.srsAgain),
                    ) {
                        Text(text = stringResource(R.string.profile_dismiss), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                },
            ) {
                Text(text = errorMessage, fontSize = 13.sp)
            }
        }
    }

    // --- Edit Profile Sheet ---
    if (state.showEditSheet) {
        EditProfileSheet(
            editName = state.editName,
            editBio = state.editBio,
            isSaving = state.isSaving,
            onDismiss = onDismissEditSheet,
            onNameChanged = onEditNameChanged,
            onBioChanged = onEditBioChanged,
            onSaveClick = onSaveClick,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditProfileSheet(
    editName: String,
    editBio: String,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onNameChanged: (String) -> Unit,
    onBioChanged: (String) -> Unit,
    onSaveClick: () -> Unit,
) {
    val colors = LoopkyTheme.colors

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceCard,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Sheet title
            Text(
                text = stringResource(R.string.profile_edit_profile_sheet_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = colors.foregroundPrimary,
            )

            // Name field
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.profile_edit_name_label),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = colors.foregroundMuted,
                )
                OutlinedTextField(
                    value = editName,
                    onValueChange = onNameChanged,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.foregroundPrimary,
                    ),
                    placeholder = {
                        Text(
                            stringResource(R.string.profile_edit_name_placeholder),
                            fontSize = 15.sp,
                            color = colors.foregroundMuted,
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accentPrimary,
                        unfocusedBorderColor = colors.borderSubtle,
                        cursorColor = colors.accentPrimary,
                    ),
                )
            }

            // Bio field
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.profile_edit_bio_label),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = colors.foregroundMuted,
                )
                OutlinedTextField(
                    value = editBio,
                    onValueChange = onBioChanged,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp),
                    textStyle = TextStyle(
                        fontSize = 15.sp,
                        color = colors.foregroundPrimary,
                        lineHeight = 22.sp,
                    ),
                    placeholder = {
                        Text(stringResource(R.string.profile_edit_bio_placeholder), fontSize = 15.sp, color = colors.foregroundMuted)
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accentPrimary,
                        unfocusedBorderColor = colors.borderSubtle,
                        cursorColor = colors.accentPrimary,
                    ),
                )
            }

            // Save button
            LoopkyPrimaryButton(
                label = stringResource(R.string.profile_save),
                onClick = onSaveClick,
                loading = isSaving,
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_menu_save),
                        contentDescription = null,
                        tint = colors.foregroundOnAccent,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
/** Avatar, name, pubky and the two things you can do with your own profile. */
@Composable
private fun ProfileIdentityPane(
    state: ProfileUiState,
    onCopyPubky: () -> Unit,
    onEditProfileClick: () -> Unit,
    onDismissNameNudge: () -> Unit,
    onDismissAvatarNudge: () -> Unit,
    onEditAvatarClick: () -> Unit,
    onOpenOnPubkyApp: () -> Unit,
    onShareClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // --- Profile section ---
        // The same hero someone else's profile draws, so your own picture shows up here too —
        // this screen used to hand-roll an initial-only circle and was the one avatar slot the
        // pubky.app file-URI fix could not reach. No "You" badge: that marks you inside someone
        // else's context, and everything on this tab is already yours.
        val identity = state.identity
        if (identity != null) {
            ProfileHero(
                identity = identity,
                onPubkyClick = onCopyPubky,
                onEditAvatar = onEditAvatarClick,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Directly under the hero, because that is where the missing name is showing: with none
        // published the hero falls back to the pubky, and this says what to do about it.
        if (state.showNameNudge) {
            NameNudgeCard(
                onAddName = onEditProfileClick,
                onDismiss = onDismissNameNudge,
            )
        }

        // Never beside the name card — see [ProfileUiState.showAvatarNudge]. Its action opens
        // pubky.app straight away rather than raising the badge's dialog: the card is already
        // saying what that dialog would.
        if (state.showAvatarNudge) {
            AvatarNudgeCard(
                onOpenPubkyApp = onOpenOnPubkyApp,
                onDismiss = onDismissAvatarNudge,
            )
        }

        // --- Action row ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LoopkyPrimaryButton(
                label = stringResource(R.string.profile_edit_profile),
                onClick = onEditProfileClick,
                modifier = Modifier.weight(1f),
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = android.R.drawable.ic_menu_edit),
                        contentDescription = null,
                        tint = colors.foregroundOnAccent,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )

            // Share button
            OutlinedIconButton(
                onClick = onShareClick,
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.outlinedIconButtonColors(
                    containerColor = colors.surfaceCard,
                    contentColor = colors.foregroundSecondary,
                ),
                border = BorderStroke(1.dp, colors.borderSubtle),
            ) {
                Icon(
                    painter = painterResource(id = android.R.drawable.ic_menu_share),
                    contentDescription = stringResource(R.string.profile_share_content_description),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** The counts, the pubky.app pointer and sign-out — everything your profile adds up to. */
@Composable
private fun ProfileDetailsPane(
    state: ProfileUiState,
    onOpenFollows: (FollowSource) -> Unit,
    onOpenOnPubkyApp: () -> Unit,
    onBackUpNow: () -> Unit,
    onSignOutRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // --- Stats card ---
        ProfileStatsCard(
            stats = listOf(
                ProfileStat(
                    value = state.deckCount.toString(),
                    label = stringResource(R.string.profile_stat_decks),
                    valueColor = colors.foregroundPrimary,
                ),
                ProfileStat(
                    value = state.cardCount.toString(),
                    label = stringResource(R.string.profile_stat_cards),
                    valueColor = colors.accentPrimary,
                ),
                ProfileStat(
                    value = state.dueCount.toString(),
                    label = stringResource(R.string.profile_stat_due),
                    valueColor = colors.srsGood,
                ),
            ),
        )

        // --- People card ---
        // A second strip rather than five columns in the first: on a phone that reduces every
        // label to unreadable, and these two answer a different question than your library does.
        val pending = stringResource(R.string.profile_stat_pending)
        ProfileStatsCard(
            stats = listOf(
                ProfileStat(
                    value = state.followingCount?.toString() ?: pending,
                    label = stringResource(R.string.profile_stat_following),
                    valueColor = colors.foregroundPrimary,
                    onClick = { onOpenFollows(FollowSource.FOLLOWING) },
                    testTag = "profile_stat_following",
                ),
                ProfileStat(
                    value = state.followerCount?.toString() ?: pending,
                    label = stringResource(R.string.profile_stat_followers),
                    valueColor = colors.accentPrimary,
                    onClick = { onOpenFollows(FollowSource.FOLLOWERS) },
                    testTag = "profile_stat_followers",
                ),
            ),
        )

        // --- pubky.app ---
        // Below the stats and above sign-out: it explains the button in the action row for the
        // person who does not already know what a Pubky account is, and it is the last thing on
        // the screen rather than the first, because it is context, not a task.
        PubkyAppProfileCta(onClick = onOpenOnPubkyApp)

        // --- Backup ---
        // Directly above sign-out, because that is the button that can destroy the key it warns
        // about: signing out of an un-backed-up local key ends the account. It disappears the
        // moment any one backup method is done, and never shows for a Ring-held key — there is
        // nothing on this device to lose. See [ProfileUiState.needsBackup] for why this is not a
        // bare custody check.
        if (state.needsBackup) {
            BackupNagCard(onBackUpNow = onBackUpNow)
        }

        // --- Sign out ---
        FilledTonalButton(
            onClick = onSignOutRequest,
            modifier = Modifier
                .testTag("profile_signout")
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
                text = stringResource(R.string.profile_sign_out),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * "What should we call you?" — the one-time invitation to name a nameless profile.
 *
 * Dismissible, unlike [BackupNagCard], and the dismissal is remembered on the device: a name is a
 * courtesy to other people, not a risk to the reader, so someone who would rather stay a pubky is
 * asked once and never again.
 */
@Composable
private fun NameNudgeCard(
    onAddName: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.accentPrimarySoft)
            .padding(16.dp)
            .testTag("profile_name_nudge"),
    ) {
        Text(
            text = stringResource(R.string.profile_name_nudge_title),
            color = colors.foregroundPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.profile_name_nudge_body),
            color = colors.foregroundSecondary,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onAddName,
                modifier = Modifier.testTag("profile_name_nudge_action"),
            ) {
                Text(
                    text = stringResource(R.string.profile_name_nudge_action),
                    color = colors.accentSecondary,
                    fontWeight = FontWeight.Bold,
                )
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("profile_name_nudge_dismiss"),
            ) {
                Text(
                    text = stringResource(R.string.profile_name_nudge_dismiss),
                    color = colors.foregroundMuted,
                )
            }
        }
    }
}

/**
 * "Add a profile photo" — the same one-time invitation as [NameNudgeCard], for the other half of
 * an anonymous profile.
 *
 * It leaves the app, because Loopky has nowhere to put a picture: the profile write it owns is
 * name and bio, and the photo is a file record uploaded by pubky.app under the same key.
 */
@Composable
private fun AvatarNudgeCard(
    onOpenPubkyApp: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.accentPrimarySoft)
            .padding(16.dp)
            .testTag("profile_avatar_nudge"),
    ) {
        Text(
            text = stringResource(R.string.profile_avatar_nudge_title),
            color = colors.foregroundPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.profile_avatar_nudge_body),
            color = colors.foregroundSecondary,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onOpenPubkyApp,
                modifier = Modifier.testTag("profile_avatar_nudge_action"),
            ) {
                Text(
                    text = stringResource(R.string.profile_avatar_open_pubky_app),
                    color = colors.accentSecondary,
                    fontWeight = FontWeight.Bold,
                )
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("profile_avatar_nudge_dismiss"),
            ) {
                Text(
                    text = stringResource(R.string.profile_avatar_not_now),
                    color = colors.foregroundMuted,
                )
            }
        }
    }
}

/**
 * "Back up your account", shown until at least one method is done.
 *
 * Deliberately persistent rather than dismissible: the risk it describes does not go away by being
 * acknowledged, and a "don't show again" here would silence the only warning standing between a
 * lost phone and a lost identity.
 */
@Composable
private fun BackupNagCard(onBackUpNow: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.accentPrimarySoft)
            .padding(16.dp)
            .testTag("profile_backup_nag"),
    ) {
        Text(
            text = stringResource(R.string.backup_nag_title),
            color = colors.foregroundPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.backup_nag_body),
            color = colors.foregroundSecondary,
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onBackUpNow, modifier = Modifier.testTag("profile_backup_nag_action")) {
            Text(
                text = stringResource(R.string.backup_nag_action),
                color = colors.accentSecondary,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Wide enough for a display name and a truncated pubky to sit on one line each. */
private val PROFILE_PANE_WIDTH = 340.dp

@Preview
@Composable
private fun ProfileScreenPreview() {
    LoopkyTheme {
        ProfileScreen(
            state = ProfileUiState(
                isLoading = false,
                identity = PubkyIdentity(
                    pubky = "abcdef1234567890abcdef",
                    displayName = "Ada Lovelace",
                    avatarUrl = null,
                    bio = "Building decks about computing history.",
                ),
                deckCount = 8,
                cardCount = 240,
                dueCount = 12,
            ),
            errorMessage = null,
            onOpenSettings = {},
            onOpenFollows = {},
            onEditProfileClick = {},
            onDismissNameNudge = {},
            onDismissAvatarNudge = {},
            onShareClick = {},
            onOpenOnPubkyApp = {},
            onCopyPubky = {},
            onBackUpNow = {},
            onSignOutClick = {},
            onDismissEditSheet = {},
            onEditNameChanged = {},
            onEditBioChanged = {},
            onSaveClick = {},
            onDismissError = {},
        )
    }
}
