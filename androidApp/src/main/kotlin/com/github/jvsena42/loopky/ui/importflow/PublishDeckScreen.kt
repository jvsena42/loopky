package com.github.jvsena42.loopky.ui.importflow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.DeckLimits
import com.github.jvsena42.loopky.domain.model.SpeechLanguages
import com.github.jvsena42.loopky.platform.Speaker
import com.github.jvsena42.loopky.presentation.importflow.PublishDeckEffect
import com.github.jvsena42.loopky.presentation.importflow.PublishDeckUiState
import com.github.jvsena42.loopky.presentation.importflow.PublishDeckViewModel
import com.github.jvsena42.loopky.presentation.importflow.PublishError
import com.github.jvsena42.loopky.ui.components.AddTagSheet
import com.github.jvsena42.loopky.ui.components.CharacterCounter
import com.github.jvsena42.loopky.ui.components.DeckStudyOptions
import com.github.jvsena42.loopky.ui.components.ImagePickerSheet
import com.github.jvsena42.loopky.ui.components.ImageSelection
import com.github.jvsena42.loopky.ui.components.LoopkyOutlinedButton
import com.github.jvsena42.loopky.ui.components.LoopkyPrimaryButton
import com.github.jvsena42.loopky.ui.components.LoopkySecondaryButton
import com.github.jvsena42.loopky.ui.components.SharePromptBody
import com.github.jvsena42.loopky.ui.components.TagChip
import com.github.jvsena42.loopky.ui.components.formErrorMessage
import com.github.jvsena42.loopky.ui.components.publishErrorMessage
import com.github.jvsena42.loopky.ui.components.speechLanguageOptions
import com.github.jvsena42.loopky.ui.layout.PaneWidth
import com.github.jvsena42.loopky.ui.layout.contentPane
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.toast
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun PublishDeckRoute(
    onBack: () -> Unit = {},
    onPublished: (deckId: String) -> Unit = {},
    /** Opens Settings on the Unsplash key row, for when the image sheet reports a key problem. */
    onOpenSettings: () -> Unit = {},
    /** The session was ended from the error row's "Sign in again" — go collect a new one. */
    onSignedOut: () -> Unit = {},
) {
    val viewModel = koinViewModel<PublishDeckViewModel>()
    // Offer the languages this device can actually voice, so the author is not choosing
    // one their own Listen button cannot honour.
    val speaker = koinInject<Speaker>()

    val currentBack by rememberUpdatedState(onBack)
    val currentPublished by rememberUpdatedState(onPublished)
    val currentSignedOut by rememberUpdatedState(onSignedOut)
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                PublishDeckEffect.NavigateBack -> currentBack()
                PublishDeckEffect.NavigateToOnboarding -> currentSignedOut()
                is PublishDeckEffect.Published -> currentPublished(effect.deckId)
                PublishDeckEffect.Shared -> context.toast(R.string.share_prompt_posted)
                PublishDeckEffect.ShareFailed -> context.toast(R.string.share_prompt_failed)
            }
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    PublishDeckScreen(
        state = state,
        onTitleChanged = viewModel::onTitleChanged,
        onDescriptionChanged = viewModel::onDescriptionChanged,
        onAddTag = viewModel::onAddTag,
        onRemoveTag = viewModel::onRemoveTag,
        onToggleListen = viewModel::onToggleListen,
        onToggleSpeak = viewModel::onToggleSpeak,
        onToggleType = viewModel::onToggleType,
        onToggleReverse = viewModel::onToggleReverse,
        onFrontLangSelected = viewModel::onFrontLangSelected,
        onBackLangSelected = viewModel::onBackLangSelected,
        availableLanguages = speechLanguageOptions(speaker.availableLanguages()),
        onCoverWebSelected = viewModel::onCoverWebSelected,
        onCoverGallerySelected = viewModel::onCoverGallerySelected,
        onPublishClick = viewModel::onPublishClick,
        onCancelPublish = viewModel::onCancelPublish,
        onUndoPublish = viewModel::onUndoPublish,
        onDonePublish = viewModel::onDonePublish,
        onShareConfirm = viewModel::onShareConfirm,
        onShareDismiss = viewModel::onShareDismiss,
        onShareNeverAsk = viewModel::onShareNeverAsk,
        onBackClick = viewModel::onBackClick,
        onOpenSettings = onOpenSettings,
        onSignInAgain = viewModel::onSignInAgainClick,
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Suppress("CyclomaticComplexMethod", "LongMethod") // Single-screen form; sections read top-to-bottom.
@Composable
private fun PublishDeckScreen(
    state: PublishDeckUiState,
    onTitleChanged: (String) -> Unit,
    onDescriptionChanged: (String) -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    onToggleListen: () -> Unit,
    onToggleSpeak: () -> Unit,
    onToggleType: () -> Unit,
    onToggleReverse: () -> Unit,
    onFrontLangSelected: (String) -> Unit,
    onBackLangSelected: (String) -> Unit,
    availableLanguages: List<String>,
    onCoverWebSelected: (String) -> Unit,
    onCoverGallerySelected: (ByteArray, String) -> Unit,
    onPublishClick: () -> Unit,
    onCancelPublish: () -> Unit,
    onUndoPublish: () -> Unit,
    onDonePublish: () -> Unit,
    onShareConfirm: () -> Unit,
    onShareDismiss: () -> Unit,
    onShareNeverAsk: () -> Unit,
    onBackClick: () -> Unit,
    /** Opens Settings on the Unsplash key row, for when the image sheet reports a key problem. */
    onOpenSettings: () -> Unit = {},
    /** Ends the session and sends the user to sign in, from the error row (#165). */
    onSignInAgain: () -> Unit = {},
) {
    val colors = LoopkyTheme.colors
    var showTagSheet by remember { mutableStateOf(false) }
    var showCoverSheet by remember { mutableStateOf(false) }
    var showCancelDialog by remember { mutableStateOf(false) }

    val busy = state.isPublishing || state.isCancelling

    // Without this, back pops the destination, onCleared() cancels viewModelScope, and the
    // partially-written deck is orphaned with no coroutine left to sweep it.
    BackHandler(enabled = busy) { showCancelDialog = true }

    if (state.publishedDeckId != null) {
        PublishedContent(
            state = state,
            onUndoPublish = onUndoPublish,
            onDonePublish = onDonePublish,
            onShareConfirm = onShareConfirm,
            onShareDismiss = onShareDismiss,
            onShareNeverAsk = onShareNeverAsk,
            onSignInAgain = onSignInAgain,
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surfacePrimary)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                // Without this the scroll container keeps its full height behind the keyboard,
                // so the field being typed into stays hidden and there is nothing left to scroll.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .contentPane(PaneWidth.Reading)
                .padding(PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(colors.surfaceCard)
                        // Same guard as the system back: leaving mid-publish would orphan the
                        // partly-written deck along with the scope that could sweep it.
                        .clickable { if (busy) showCancelDialog = true else onBackClick() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        stringResource(R.string.publish_back),
                        tint = colors.foregroundPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.publish_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = colors.foregroundPrimary,
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.size(40.dp))
            }

            // Cards ready badge: peach panel, solid orange check, discarded subtitle.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.accentPrimarySoft)
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(colors.accentPrimary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Check,
                        null,
                        tint = colors.foregroundOnAccent,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        pluralStringResource(R.plurals.cards_ready, state.cardCount, state.cardCount),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.foregroundPrimary,
                    )
                    if (state.discardedCount > 0) {
                        Text(
                            pluralStringResource(R.plurals.cards_discarded, state.discardedCount, state.discardedCount),
                            fontSize = 12.sp,
                            color = colors.foregroundSecondary,
                        )
                    }
                }
            }

            // Cover
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.accentPrimarySoft),
                    contentAlignment = Alignment.Center,
                ) {
                    val coverModel: Any? = state.coverImageUrl ?: state.coverPendingBytes
                    if (coverModel != null) {
                        AsyncImage(
                            model = coverModel,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        // The deck's own initial, not a books emoji: `coverEmoji` is written as
                        // null when it is blank, and every list and detail screen falls back to
                        // the initial. Showing 📚 here promised a cover the deck never got.
                        Text(
                            text = state.coverEmoji.ifBlank {
                                state.title.firstOrNull()?.uppercaseChar()?.toString() ?: "•"
                            },
                            fontSize = 32.sp,
                            lineHeight = 38.sp,
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.publish_cover_label),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = colors.foregroundMuted,
                    )
                    Row(
                        modifier = Modifier
                            .testTag("publish_cover_change")
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, colors.borderSubtle, RoundedCornerShape(8.dp))
                            .clickable { showCoverSheet = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = null,
                            tint = colors.accentPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            stringResource(R.string.publish_cover_change),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.foregroundPrimary,
                        )
                    }
                }
            }

            // Title
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.publish_title_label),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    color = colors.foregroundMuted,
                )
                BasicTextField(
                    value = state.title,
                    onValueChange = onTitleChanged,
                    modifier = Modifier
                        .testTag("publish_title")
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceCard)
                        .padding(14.dp),
                    textStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colors.foregroundPrimary),
                    cursorBrush = SolidColor(colors.accentPrimary),
                    singleLine = true,
                    decorationBox = { inner ->
                        Box {
                            if (state.title.isEmpty()) {
                                Text(
                                    stringResource(R.string.publish_title_placeholder),
                                    fontSize = 16.sp,
                                    color = colors.foregroundMuted,
                                )
                            }
                            inner()
                        }
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    val error = state.titleError
                    if (error != null) {
                        Text(
                            text = formErrorMessage(error),
                            fontSize = 12.sp,
                            color = colors.danger,
                            modifier = Modifier
                                .testTag("publish_title_error")
                                .weight(1f),
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    CharacterCounter(
                        current = state.title.length,
                        max = DeckLimits.TITLE_MAX_LENGTH,
                    )
                }
            }

            // Description
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.publish_description_label),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    color = colors.foregroundMuted,
                )
                BasicTextField(
                    value = state.description,
                    onValueChange = onDescriptionChanged,
                    modifier = Modifier
                        .testTag("publish_description")
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceCard)
                        .padding(14.dp),
                    textStyle = TextStyle(fontSize = 14.sp, color = colors.foregroundSecondary),
                    cursorBrush = SolidColor(colors.accentPrimary),
                    decorationBox = { inner ->
                        Box {
                            if (state.description.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.publish_description_placeholder),
                                    fontSize = 14.sp,
                                    color = colors.foregroundMuted,
                                )
                            }
                            inner()
                        }
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    val error = state.descriptionError
                    if (error != null) {
                        Text(
                            formErrorMessage(error),
                            fontSize = 12.sp,
                            color = colors.danger,
                            modifier = Modifier
                                .testTag("publish_description_error")
                                .weight(1f),
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    CharacterCounter(
                        current = state.description.length,
                        max = DeckLimits.DESCRIPTION_MAX_LENGTH,
                    )
                }
            }

            // Tags
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.publish_tags_label),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    color = colors.foregroundMuted,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.tags.forEach { tag ->
                        TagChip(tag = tag, onRemove = { onRemoveTag(tag) })
                    }
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .border(1.5.dp, colors.borderSubtle, RoundedCornerShape(50))
                            .clickable { showTagSheet = true }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.publish_add_tag),
                            tint = colors.foregroundMuted,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text = stringResource(R.string.publish_add),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.foregroundMuted,
                        )
                    }
                }
            }

            // Card options (Listen / Speak / Type, and the languages the speech ones need)
            DeckStudyOptions(
                listenEnabled = state.listenEnabled,
                speakEnabled = state.speakEnabled,
                typeEnabled = state.typeEnabled,
                reverseEnabled = state.reverseEnabled,
                frontLang = state.frontLang,
                backLang = state.backLang,
                availableLanguages = availableLanguages,
                onToggleListen = onToggleListen,
                onToggleSpeak = onToggleSpeak,
                onToggleType = onToggleType,
                onToggleReverse = onToggleReverse,
                onFrontLangSelected = onFrontLangSelected,
                onBackLangSelected = onBackLangSelected,
            )
            state.languagesError?.let {
                Text(formErrorMessage(it), fontSize = 12.sp, color = colors.danger)
            }

            // Public on Pubky notice
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.accentSecondarySoft)
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🌐", fontSize = 18.sp)
                Column {
                    Text(
                        stringResource(R.string.publish_public_title),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.foregroundPrimary,
                    )
                    Text(stringResource(R.string.publish_public_subtitle), fontSize = 12.sp, color = colors.foregroundSecondary)
                }
            }
        }

        // Pinned so the primary action is never below the fold — on device the Publish
        // button sat at the end of the scroll and was invisible on a fresh screen.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.error?.let { error ->
                PublishErrorRow(error = error, onSignInAgain = onSignInAgain)
            }
            if (busy) {
                PublishProgress(
                    fraction = state.publishProgress,
                    publishedCardCount = state.publishedCardCount,
                    totalCardCount = state.cardCount,
                    isCancelling = state.isCancelling,
                    onCancelClick = { showCancelDialog = true },
                )
            } else {
                LoopkyPrimaryButton(
                    label = stringResource(R.string.publish_button),
                    onClick = onPublishClick,
                    // Enabled so validation can explain *why* it can't publish. Previously
                    // `enabled = state.canPublish` made `validateForPublish` dead code and the
                    // tap did nothing at all.
                    modifier = Modifier
                        .testTag("publish_button")
                        .fillMaxWidth(),
                )
            }
        }
    }

    if (showCancelDialog) {
        CancelPublishDialog(
            onConfirm = {
                showCancelDialog = false
                onCancelPublish()
            },
            onDismiss = { showCancelDialog = false },
        )
    }

    if (showTagSheet) {
        AddTagSheet(
            tags = state.tags,
            onAddTag = onAddTag,
            onRemoveTag = onRemoveTag,
            onDismiss = { showTagSheet = false },
        )
    }

    if (showCoverSheet) {
        ImagePickerSheet(
            onOpenSettings = onOpenSettings,
            title = stringResource(R.string.publish_cover_sheet_title),
            subtitle = null,
            onDismiss = { showCoverSheet = false },
            onSelected = { selection ->
                when (selection) {
                    is ImageSelection.Web -> onCoverWebSelected(selection.url)
                    is ImageSelection.Gallery -> onCoverGallerySelected(selection.bytes, selection.mime)
                }
                showCoverSheet = false
            },
        )
    }
}

/**
 * Determinate publish progress.
 *
 * A 20k-card import is ~200 uploads; the repository has reported per-chunk progress since #49,
 * but nothing consumed it, so a 1,200-card publish showed an indeterminate spinner for ~35s and
 * read as hung rather than busy.
 */
@Composable
private fun PublishProgress(
    fraction: Float?,
    publishedCardCount: Int,
    totalCardCount: Int,
    isCancelling: Boolean,
    onCancelClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LinearProgressIndicator(
            progress = { fraction ?: 0f },
            modifier = Modifier
                .testTag("publish_progress")
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(50)),
            color = colors.accentPrimary,
            trackColor = colors.borderSubtle,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        Text(
            text = if (isCancelling) {
                stringResource(R.string.publish_cancelling)
            } else {
                pluralStringResource(
                    R.plurals.publish_progress_count,
                    totalCardCount,
                    publishedCardCount,
                    totalCardCount,
                )
            },
            fontSize = 13.sp,
            color = colors.foregroundSecondary,
            modifier = Modifier.testTag("publish_progress_label"),
        )
        if (!isCancelling) {
            LoopkySecondaryButton(
                text = stringResource(R.string.publish_cancel),
                onClick = onCancelClick,
                modifier = Modifier
                    .testTag("publish_cancel")
                    .fillMaxWidth(),
            )
        }
    }
}

/**
 * Confirms before throwing away an upload in progress.
 *
 * Worth a dialog: this control sits where Publish just was, and a mis-tap 30s into a 20k-card
 * upload discards all of it. `AlertDialog` renders in its own window, so the nav host's
 * `testTagsAsResourceId` does not reach it.
 */
@Composable
private fun CancelPublishDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = LoopkyTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceCard,
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        title = {
            Text(
                stringResource(R.string.publish_cancel_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = colors.foregroundPrimary,
            )
        },
        text = {
            Text(
                stringResource(R.string.publish_cancel_message),
                fontSize = 14.sp,
                color = colors.foregroundSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("publish_cancel_confirm")) {
                Text(stringResource(R.string.publish_cancel_confirm), color = colors.danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("publish_cancel_keep")) {
                Text(stringResource(R.string.publish_cancel_keep), color = colors.foregroundMuted)
            }
        },
    )
}

@Composable
private fun PublishedContent(
    state: PublishDeckUiState,
    onUndoPublish: () -> Unit,
    onDonePublish: () -> Unit,
    onShareConfirm: () -> Unit,
    onShareDismiss: () -> Unit,
    onShareNeverAsk: () -> Unit,
    onSignInAgain: () -> Unit,
) {
    val colors = LoopkyTheme.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surfacePrimary)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp)),
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Success indicator
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(colors.srsGood.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Check,
                stringResource(R.string.publish_published_icon_desc),
                tint = colors.srsGood,
                modifier = Modifier.size(36.dp),
            )
        }
        Text(
            stringResource(R.string.publish_published_title),
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            color = colors.foregroundPrimary,
        )
        Text(
            text = pluralStringResource(R.plurals.cards_published, state.cardCount, state.cardCount),
            fontSize = 14.sp,
            color = colors.foregroundSecondary,
        )

        Spacer(Modifier.height(10.dp))

        // The share prompt replaces Done/Undo rather than stacking a dialog over them: by the
        // time it appears the undo window is over, so those two controls are spent (#39).
        val prompt = state.sharePrompt
        if (prompt != null) {
            SharePromptBody(
                prompt = prompt,
                modifier = Modifier.fillMaxWidth(),
                onNeverAsk = onShareNeverAsk,
            )
            LoopkyPrimaryButton(
                label = stringResource(R.string.share_prompt_confirm),
                onClick = onShareConfirm,
                enabled = !prompt.isPosting,
                modifier = Modifier
                    .testTag("share_prompt_confirm")
                    .fillMaxWidth(),
            )
            LoopkyOutlinedButton(
                label = stringResource(R.string.share_prompt_dismiss),
                onClick = onShareDismiss,
                enabled = !prompt.isPosting,
                modifier = Modifier.testTag("share_prompt_dismiss"),
            )
        } else {
            // Done button
            LoopkyPrimaryButton(
                label = stringResource(R.string.publish_done),
                onClick = onDonePublish,
                modifier = Modifier
                    .testTag("publish_done")
                    .fillMaxWidth(),
            )

            // Undo button with countdown
            LoopkyOutlinedButton(
                label = stringResource(R.string.publish_undo, state.undoSecondsRemaining),
                onClick = onUndoPublish,
                modifier = Modifier.testTag("publish_undo"),
            )
        }

        // Error
        state.error?.let { error ->
            PublishErrorRow(error = error, onSignInAgain = onSignInAgain)
        }
    }
}

/**
 * The failed step, plus the one action worth offering beside it.
 *
 * "Check your connection and try again" was the whole of what this screen said when the session
 * round trip stopped going through, and it pointed at the one thing that was fine (#165). The
 * reason now carries the truth; this adds the way out, for the reasons [offersSignIn] documents.
 */
@Composable
private fun PublishErrorRow(
    error: PublishError,
    onSignInAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("publish_error"),
    ) {
        Text(
            publishErrorMessage(error),
            fontSize = 14.sp,
            color = colors.danger,
        )
        if (error.reason?.offersSignIn == true) {
            TextButton(
                onClick = onSignInAgain,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.testTag("publish_sign_in_again"),
            ) {
                Text(
                    text = stringResource(R.string.error_sign_in_again),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.W700,
                    color = colors.accentPrimary,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun PublishDeckScreenPreview() {
    LoopkyTheme {
        PublishDeckScreen(
            state = PublishDeckUiState(
                title = "Spanish basics",
                description = "Everyday travel phrases",
                coverEmoji = "📚",
                tags = listOf("language", "beginner"),
                cardCount = 24,
            ),
            onTitleChanged = {},
            onDescriptionChanged = {},
            onAddTag = {},
            onRemoveTag = {},
            onToggleListen = {},
            onToggleSpeak = {},
            onToggleType = {},
            onCoverWebSelected = {},
            onCoverGallerySelected = { _, _ -> },
            onPublishClick = {},
            onCancelPublish = {},
            onUndoPublish = {},
            onDonePublish = {},
            onShareConfirm = {},
            onShareDismiss = {},
            onShareNeverAsk = {},
            onBackClick = {},
            onToggleReverse = {},
            onFrontLangSelected = {},
            onBackLangSelected = {},
            availableLanguages = SpeechLanguages.COMMON,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun PublishDeckPublishingPreview() {
    LoopkyTheme {
        PublishDeckScreen(
            state = PublishDeckUiState(
                title = "Japanese Core 2000",
                cardCount = 1_842,
                isPublishing = true,
                publishProgress = 0.42f,
                publishedCardCount = 774,
            ),
            onTitleChanged = {},
            onDescriptionChanged = {},
            onAddTag = {},
            onRemoveTag = {},
            onToggleListen = {},
            onToggleSpeak = {},
            onToggleType = {},
            onCoverWebSelected = {},
            onCoverGallerySelected = { _, _ -> },
            onPublishClick = {},
            onCancelPublish = {},
            onUndoPublish = {},
            onDonePublish = {},
            onShareConfirm = {},
            onShareDismiss = {},
            onShareNeverAsk = {},
            onBackClick = {},
            onToggleReverse = {},
            onFrontLangSelected = {},
            onBackLangSelected = {},
            availableLanguages = SpeechLanguages.COMMON,
        )
    }
}
