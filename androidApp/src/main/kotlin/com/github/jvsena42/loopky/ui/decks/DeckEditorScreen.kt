package com.github.jvsena42.loopky.ui.decks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.DeckLimits
import com.github.jvsena42.loopky.domain.model.SpeechLanguages
import com.github.jvsena42.loopky.platform.Speaker
import com.github.jvsena42.loopky.presentation.decks.DeckEditorEffect
import com.github.jvsena42.loopky.presentation.decks.DeckEditorError
import com.github.jvsena42.loopky.presentation.decks.DeckEditorUiState
import com.github.jvsena42.loopky.presentation.decks.DeckEditorViewModel
import com.github.jvsena42.loopky.presentation.decks.EditableCardModel
import com.github.jvsena42.loopky.ui.components.AddTagSheet
import com.github.jvsena42.loopky.ui.components.CharacterCounter
import com.github.jvsena42.loopky.ui.components.DeckStudyOptions
import com.github.jvsena42.loopky.ui.components.ImagePickerSheet
import com.github.jvsena42.loopky.ui.components.ImageSelection
import com.github.jvsena42.loopky.ui.components.ReorderableListState
import com.github.jvsena42.loopky.ui.components.SharePromptDialog
import com.github.jvsena42.loopky.ui.components.TagChip
import com.github.jvsena42.loopky.ui.components.deckEditorErrorMessage
import com.github.jvsena42.loopky.ui.components.formErrorMessage
import com.github.jvsena42.loopky.ui.components.rememberReorderableListState
import com.github.jvsena42.loopky.ui.components.reorderableHandle
import com.github.jvsena42.loopky.ui.components.speechLanguageOptions
import com.github.jvsena42.loopky.ui.layout.PaneWidth
import com.github.jvsena42.loopky.ui.layout.contentPane
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import com.github.jvsena42.loopky.ui.util.toast
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** How close to the end of the list a scroll gets before the next chunk is requested. */
private const val PAGE_PREFETCH_ROWS = 8

/** Enough for any deck size the layout supports; keeps a paste into the field from overflowing. */
private const val MAX_POSITION_DIGITS = 7

@Composable
fun DeckEditorRoute(
    deckId: String?,
    onBack: () -> Unit = {},
    onEditCard: (deckId: String, cardId: String) -> Unit = { _, _ -> },
    onNewCard: (deckId: String) -> Unit = {},
    onSaved: (deckId: String) -> Unit = {},
    /** Opens Settings on the Unsplash key row, for when the image sheet reports a key problem. */
    onOpenSettings: () -> Unit = {},
    /** The session was ended from the error row's "Sign in again" — go collect a new one. */
    onSignedOut: () -> Unit = {},
) {
    val viewModel = koinViewModel<DeckEditorViewModel> { parametersOf(deckId) }
    // Offer the languages this device can actually voice, so the author is not choosing
    // one their own Listen button cannot honour.
    val speaker = koinInject<Speaker>()

    val currentBack by rememberUpdatedState(onBack)
    val currentEditCard by rememberUpdatedState(onEditCard)
    val currentNewCard by rememberUpdatedState(onNewCard)
    val currentSaved by rememberUpdatedState(onSaved)
    val currentSignedOut by rememberUpdatedState(onSignedOut)
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                DeckEditorEffect.NavigateBack -> currentBack()
                DeckEditorEffect.NavigateToOnboarding -> currentSignedOut()
                is DeckEditorEffect.NavigateEditCard -> currentEditCard(effect.deckId, effect.cardId)
                is DeckEditorEffect.NavigateNewCard -> currentNewCard(effect.deckId)
                is DeckEditorEffect.SaveSuccess -> currentSaved(effect.deckId)
                DeckEditorEffect.Shared -> context.toast(R.string.share_prompt_posted)
                DeckEditorEffect.ShareFailed -> context.toast(R.string.share_prompt_failed)
            }
        }
    }

    // Coming back from the card editor, which wrote its card straight to the repository — pull
    // that in so the list is not showing, and would not save back, the pre-edit card.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }

    val state by viewModel.state.collectAsStateWithLifecycle()
    DeckEditorScreen(
        state = state,
        onCloseClick = viewModel::onCloseClick,
        onSaveClick = viewModel::onSaveClick,
        onTitleChanged = viewModel::onTitleChanged,
        onDescriptionChanged = viewModel::onDescriptionChanged,
        onRemoveTag = viewModel::onRemoveTag,
        onAddTag = viewModel::onAddTag,
        onToggleListen = viewModel::onToggleListen,
        onToggleSpeak = viewModel::onToggleSpeak,
        onToggleType = viewModel::onToggleType,
        onToggleReverse = viewModel::onToggleReverse,
        onFrontLangSelected = viewModel::onFrontLangSelected,
        onBackLangSelected = viewModel::onBackLangSelected,
        availableLanguages = speechLanguageOptions(speaker.availableLanguages()),
        onCardClick = viewModel::onCardClick,
        onAddCard = viewModel::onAddCard,
        onMoveCard = viewModel::onMoveCard,
        onLoadMoreCards = viewModel::onLoadMoreCards,
        onCoverWebSelected = viewModel::onCoverWebSelected,
        onCoverGallerySelected = viewModel::onCoverGallerySelected,
        onOpenSettings = onOpenSettings,
        onSignInAgain = viewModel::onSignInAgainClick,
    )

    // Shown over the editor rather than on the destination: saving a new deck leaves this screen,
    // so the offer has to be answered before the navigation happens (#39).
    state.sharePrompt?.let { prompt ->
        SharePromptDialog(
            prompt = prompt,
            onConfirm = viewModel::onShareConfirm,
            onDismiss = viewModel::onShareDismiss,
            onNeverAsk = viewModel::onShareNeverAsk,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeckEditorScreen(
    state: DeckEditorUiState,
    onCloseClick: () -> Unit,
    onSaveClick: () -> Unit,
    onTitleChanged: (String) -> Unit,
    onDescriptionChanged: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    onAddTag: (String) -> Unit,
    onToggleListen: () -> Unit,
    onToggleSpeak: () -> Unit,
    onToggleType: () -> Unit,
    onToggleReverse: () -> Unit,
    onFrontLangSelected: (String) -> Unit,
    onBackLangSelected: (String) -> Unit,
    availableLanguages: List<String>,
    onCardClick: (String) -> Unit,
    onAddCard: () -> Unit,
    onMoveCard: (Int, Int) -> Unit,
    onLoadMoreCards: () -> Unit,
    onCoverWebSelected: (String) -> Unit,
    onCoverGallerySelected: (ByteArray, String) -> Unit,
    /** Opens Settings on the Unsplash key row, for when the image sheet reports a key problem. */
    onOpenSettings: () -> Unit = {},
    /** Ends the session and sends the user to sign in, from the error row (#165). */
    onSignInAgain: () -> Unit = {},
) {
    val colors = LoopkyTheme.colors
    var showCoverSheet by rememberSaveable { mutableStateOf(false) }
    // The card whose "move to position…" dialog is open, by index in the loaded list.
    var moveTarget by rememberSaveable { mutableStateOf<Int?>(null) }

    if (showCoverSheet) {
        ImagePickerSheet(
            onOpenSettings = onOpenSettings,
            title = stringResource(R.string.deck_editor_cover),
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

    Scaffold(
        containerColor = colors.surfacePrimary,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = if (state.isNew) {
                            stringResource(R.string.deck_editor_title_new)
                        } else {
                            stringResource(R.string.deck_editor_title_edit)
                        },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.W800,
                    )
                },
                navigationIcon = {
                    FilledIconButton(
                        onClick = onCloseClick,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(40.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = colors.surfaceCard,
                            contentColor = colors.foregroundPrimary,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.deck_editor_close),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                actions = {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            color = colors.accentPrimary,
                            strokeWidth = 2.dp,
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .size(18.dp),
                        )
                    } else {
                        TextButton(
                            onClick = onSaveClick,
                            colors = ButtonDefaults.textButtonColors(contentColor = colors.accentPrimary),
                        ) {
                            Text(text = stringResource(R.string.deck_editor_save), fontSize = 14.sp, fontWeight = FontWeight.W700)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.surfacePrimary,
                    titleContentColor = colors.foregroundPrimary,
                ),
            )
        },
        // A FAB rather than a button after the last row: the list is the deck, so on an
        // 800-card deck the old trailing button was only reachable by paging the whole thing in.
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddCard,
                modifier = Modifier.testTag("deck_editor_add_card"),
                containerColor = colors.accentPrimary,
                contentColor = colors.surfacePrimary,
                icon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                text = {
                    Text(
                        text = stringResource(R.string.deck_editor_add_card),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.W700,
                    )
                },
            )
        },
    ) { innerPadding ->
        // A LazyColumn rather than a scrolling Column: it is what pages the deck in, and
        // drag-to-reorder needs its layout info to know which row the finger is over.
        val listState = rememberLazyListState()
        val cardKeys = remember(state.cards) { state.cards.mapTo(mutableSetOf<Any>()) { it.id } }
        val currentCards by rememberUpdatedState(state.cards)
        val reorderState = rememberReorderableListState(
            listState = listState,
            reorderableKeys = cardKeys,
            onMove = { fromKey, toKey ->
                val from = currentCards.indexOfFirst { it.id == fromKey }
                val to = currentCards.indexOfFirst { it.id == toKey }
                if (from >= 0 && to >= 0) onMoveCard(from, to)
            },
        )
        val haptics = LocalHapticFeedback.current

        // Pull the next chunk in a little before the list runs out, so scrolling a big deck does
        // not stall at every page boundary. onLoadMoreCards is idempotent while a page is in
        // flight, so firing on every recomposition of the condition is harmless.
        val shouldLoadMore by remember {
            derivedStateOf {
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
                last >= info.totalItemsCount - PAGE_PREFETCH_ROWS
            }
        }
        val currentLoadMore by rememberUpdatedState(onLoadMoreCards)
        LaunchedEffect(shouldLoadMore, state.hasMoreCards) {
            if (shouldLoadMore && state.hasMoreCards) currentLoadMore()
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                // Without this the scroll container keeps its full height behind the keyboard,
                // so the field being typed into stays hidden and there is nothing left to scroll.
                .imePadding()
                .padding(innerPadding)
                .contentPane(PaneWidth.Reading),
            // Extra bottom room so the FAB never covers the last card.
            contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 1. Metadata card
            item(key = "metadata") {
                DeckMetadataCard(
                    state = state,
                    onTitleChanged = onTitleChanged,
                    onDescriptionChanged = onDescriptionChanged,
                    onRemoveTag = onRemoveTag,
                    onAddTag = onAddTag,
                    onCoverClick = { showCoverSheet = true },
                )
            }

            // 2. Listen/Speak/Type and the languages the speech pair needs. Editable here and
            // not only at publish: a deck published before a field existed offers nothing it
            // controls until it is set.
            item(key = "study_options") {
                DeckStudySection(
                    state = state,
                    availableLanguages = availableLanguages,
                    onToggleListen = onToggleListen,
                    onToggleSpeak = onToggleSpeak,
                    onToggleType = onToggleType,
                    onToggleReverse = onToggleReverse,
                    onFrontLangSelected = onFrontLangSelected,
                    onBackLangSelected = onBackLangSelected,
                )
            }

            // 3. Cards section header — the deck's count, not the loaded page's.
            item(key = "cards_header") {
                Text(
                    text = stringResource(R.string.deck_editor_cards_count, state.totalCards),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.W800,
                    color = colors.foregroundPrimary,
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .testTag("deck_editor_cards_header"),
                )
            }

            // 3. Card list — the pages read in so far, not the whole deck.
            cardRows(
                state = state,
                reorderState = reorderState,
                haptics = haptics,
                onCardClick = onCardClick,
                onMoveCard = onMoveCard,
                onMoveToClick = { index -> moveTarget = index },
            )

            // 4. Paging spinner — the tail of the deck arriving.
            if (state.isLoadingCards) {
                item(key = "cards_loading") { CardsLoadingRow() }
            }

            // 5. Error row
            state.error?.let { error ->
                item(key = "error") {
                    DeckEditorErrorRow(error = error, onSignInAgain = onSignInAgain)
                }
            }
        }
    }

    moveTarget?.takeIf { it in state.cards.indices }?.let { index ->
        MoveCardDialog(
            currentPosition = index + 1,
            totalCards = maxOf(state.totalCards, state.cards.size),
            onConfirm = { position ->
                moveTarget = null
                onMoveCard(index, position - 1)
            },
            onDismiss = { moveTarget = null },
        )
    }
}

/**
 * What failed, in the app's own words, plus the one action worth offering beside it.
 *
 * "Sign in again" appears only for the reasons [offersSignIn] admits, and it is deliberately an
 * offer rather than something the app does on its own: a `SessionUnreachable` write failed without
 * the homeserver ever answering, so ending a session that may still be good is the user's call.
 * Before it existed there was no route out of a wedged session short of restarting the app (#165).
 */
@Composable
private fun DeckEditorErrorRow(
    error: DeckEditorError,
    onSignInAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("deck_editor_error"),
    ) {
        Text(
            text = deckEditorErrorMessage(error),
            fontSize = 14.sp,
            color = colors.danger,
        )
        if (error.reason.offersSignIn) {
            TextButton(
                onClick = onSignInAgain,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.testTag("deck_editor_sign_in_again"),
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

/**
 * The loaded page of cards, one row each.
 *
 * A `LazyListScope` extension rather than inline in the screen so the list's own conditionals —
 * drag state, the ends of the window — stay out of the screen's branch count.
 */
@Suppress("LongParameterList")
private fun LazyListScope.cardRows(
    state: DeckEditorUiState,
    reorderState: ReorderableListState,
    haptics: HapticFeedback,
    onCardClick: (String) -> Unit,
    onMoveCard: (Int, Int) -> Unit,
    onMoveToClick: (Int) -> Unit,
) {
    itemsIndexed(state.cards, key = { _, card -> card.id }) { index, card ->
        val isDragging = reorderState.draggingKey == card.id
        CardRow(
            card = card,
            position = index + 1,
            canDrag = state.canDragReorder,
            canMoveUp = index > 0,
            canMoveDown = index < state.cards.lastIndex || state.hasMoreCards,
            isDragging = isDragging,
            onClick = { onCardClick(card.id) },
            onMoveUp = { onMoveCard(index, index - 1) },
            onMoveDown = { onMoveCard(index, index + 1) },
            onMoveToClick = { onMoveToClick(index) },
            modifier = if (isDragging) {
                Modifier
                    .zIndex(1f)
                    .graphicsLayer { translationY = reorderState.draggingOffset }
            } else {
                Modifier.animateItem()
            },
            dragHandleModifier = Modifier.reorderableHandle(
                state = reorderState,
                key = card.id,
                onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
            ),
        )
    }
}

/** The footer that says the next chunk record is on its way. */
@Composable
private fun CardsLoadingRow(modifier: Modifier = Modifier) {
    val colors = LoopkyTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .testTag("deck_editor_cards_loading"),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            color = colors.accentPrimary,
            strokeWidth = 2.dp,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.deck_editor_loading_cards),
            fontSize = 13.sp,
            color = colors.foregroundMuted,
        )
    }
}

/**
 * "Move to position…" — the reorder affordance for a deck too big to drag through.
 *
 * The destination may be a position the list has not paged in; the ViewModel resolves it against
 * the deck's own length rather than the loaded window (#52).
 */
@Composable
private fun MoveCardDialog(
    currentPosition: Int,
    totalCards: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    var text by rememberSaveable { mutableStateOf(currentPosition.toString()) }
    val position = text.toIntOrNull()
    val isValid = position != null && position in 1..totalCards

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.deck_editor_move_to_title), fontWeight = FontWeight.W800) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { entry -> text = entry.filter { it.isDigit() }.take(MAX_POSITION_DIGITS) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("deck_editor_move_to_input"),
                label = { Text(text = stringResource(R.string.deck_editor_move_to_label, totalCards)) },
                singleLine = true,
                isError = !isValid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(12.dp),
                colors = textFieldColors(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { position?.let(onConfirm) },
                enabled = isValid,
                modifier = Modifier.testTag("deck_editor_move_to_confirm"),
                colors = ButtonDefaults.textButtonColors(contentColor = colors.accentPrimary),
            ) {
                Text(text = stringResource(R.string.deck_editor_move_to_confirm), fontWeight = FontWeight.W700)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.deck_editor_move_to_cancel))
            }
        },
        containerColor = colors.surfaceCard,
    )
}

@Composable
private fun DeckStudySection(
    state: DeckEditorUiState,
    availableLanguages: List<String>,
    onToggleListen: () -> Unit,
    onToggleSpeak: () -> Unit,
    onToggleType: () -> Unit,
    onToggleReverse: () -> Unit,
    onFrontLangSelected: (String) -> Unit,
    onBackLangSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
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
            Text(formErrorMessage(it), fontSize = 12.sp, color = LoopkyTheme.colors.danger)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalEncodingApi::class)
@Composable
private fun DeckMetadataCard(
    state: DeckEditorUiState,
    onTitleChanged: (String) -> Unit,
    onDescriptionChanged: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    onAddTag: (String) -> Unit,
    onCoverClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    var showTagSheet by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 14.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = colors.shadowElevationLow,
                spotColor = colors.shadowElevationLow,
            )
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceCard)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Top row: cover + title, matching iOS. The column beside the tile is label + field and
        // nothing else — the counter and error live below the whole row — so centring pairs the
        // cover with both, rather than parking it against the 10sp label or the counter.
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Cover box — tappable so a deck's cover can be changed after publishing,
            // which was previously impossible: the picker only existed on the publish step.
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.accentPrimarySoft)
                    .clickable(onClick = onCoverClick)
                    .testTag("deck_editor_cover"),
                contentAlignment = Alignment.Center,
            ) {
                // Cover in the same priority order deck detail uses: bytes picked this session →
                // remote URL → homeserver blob (Base64, loaded by the ViewModel) → the emoji box.
                // Coil renders a URL string and a decoded ByteArray alike.
                val pendingBytes = state.coverPendingBytes
                val coverUrl = state.coverImageUrl
                val coverBase64 = state.coverImageBase64
                val coverModel: Any? = remember(pendingBytes, coverUrl, coverBase64) {
                    when {
                        pendingBytes != null -> pendingBytes
                        !coverUrl.isNullOrEmpty() -> coverUrl
                        !coverBase64.isNullOrEmpty() -> runCatching { Base64.decode(coverBase64) }.getOrNull()
                        else -> null
                    }
                }
                if (coverModel != null) {
                    AsyncImage(
                        model = coverModel,
                        contentDescription = stringResource(R.string.deck_editor_cover),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (state.coverEmoji.isNotEmpty()) {
                    Text(text = state.coverEmoji, fontSize = 32.sp)
                } else {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = stringResource(R.string.deck_editor_cover),
                        tint = colors.foregroundMuted,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            // Title + description column
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.deck_editor_label_title),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.W700,
                    letterSpacing = 0.8.sp,
                    color = colors.foregroundMuted,
                )

                OutlinedTextField(
                    value = state.title,
                    onValueChange = onTitleChanged,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.W700,
                        color = colors.foregroundPrimary,
                    ),
                    placeholder = {
                        Text(
                            text = stringResource(R.string.deck_editor_title_placeholder),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.W700,
                            color = colors.foregroundMuted,
                        )
                    },
                    isError = state.titleError != null,
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = textFieldColors(),
                )
            }
        }

        // Below the row, not inside the column beside the cover: keeping them there made that
        // column label / field / counter, and a tile centred on all three hangs level with the
        // field while the label floats above it.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            val fieldError = state.titleError
            if (fieldError != null) {
                Text(
                    text = formErrorMessage(fieldError),
                    fontSize = 12.sp,
                    color = colors.danger,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            CharacterCounter(
                current = state.title.length,
                max = DeckLimits.TITLE_MAX_LENGTH,
            )
        }

        // Description section
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.deck_editor_label_description),
                fontSize = 10.sp,
                fontWeight = FontWeight.W700,
                letterSpacing = 0.8.sp,
                color = colors.foregroundMuted,
            )

            OutlinedTextField(
                value = state.description,
                onValueChange = onDescriptionChanged,
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(fontSize = 14.sp, color = colors.foregroundSecondary),
                placeholder = {
                    Text(
                        text = stringResource(R.string.deck_editor_description_placeholder),
                        fontSize = 14.sp,
                        color = colors.foregroundMuted,
                    )
                },
                isError = state.descriptionError != null,
                shape = RoundedCornerShape(12.dp),
                colors = textFieldColors(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                val fieldError = state.descriptionError
                if (fieldError != null) {
                    Text(
                        text = formErrorMessage(fieldError),
                        fontSize = 12.sp,
                        color = colors.danger,
                        modifier = Modifier.weight(1f),
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

        // Tags section
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.deck_editor_label_tags),
                fontSize = 10.sp,
                fontWeight = FontWeight.W700,
                letterSpacing = 0.8.sp,
                color = colors.foregroundMuted,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.tags.forEach { tag ->
                    TagChip(
                        tag = tag,
                        onRemove = { onRemoveTag(tag) },
                    )
                }

                AssistChip(
                    onClick = { showTagSheet = true },
                    label = {
                        Text(text = stringResource(R.string.deck_editor_add_tag), fontSize = 13.sp, fontWeight = FontWeight.W600)
                    },
                    modifier = Modifier.testTag("deck_editor_add_tag"),
                    shape = RoundedCornerShape(50),
                    colors = AssistChipDefaults.assistChipColors(labelColor = colors.accentSecondary),
                    border = BorderStroke(1.dp, colors.accentSecondary),
                )
            }
        }
    }

    if (showTagSheet) {
        AddTagSheet(
            tags = state.tags,
            onAddTag = onAddTag,
            onRemoveTag = onRemoveTag,
            onDismiss = { showTagSheet = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun textFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = LoopkyTheme.colors.accentPrimary,
    unfocusedBorderColor = LoopkyTheme.colors.borderSubtle,
    cursorColor = LoopkyTheme.colors.accentPrimary,
    errorBorderColor = LoopkyTheme.colors.danger,
)

@Suppress("LongParameterList")
@Composable
private fun CardRow(
    card: EditableCardModel,
    position: Int,
    canDrag: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    isDragging: Boolean,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveToClick: () -> Unit,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (isDragging) 14.dp else 4.dp,
                shape = RoundedCornerShape(14.dp),
                ambientColor = colors.shadowElevationLow,
                spotColor = colors.shadowElevationLow,
            )
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceCard)
            .clickable(onClick = onClick)
            .padding(start = 6.dp, top = 14.dp, end = 14.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Long-press-and-drag handle. No content description:
        // dragging is not operable with TalkBack, and announcing it would just be another
        // dead control — the move buttons next to it are the accessible path to the same move.
        //
        // Dropped entirely once the deck is bigger than a page: dragging one row across thousands
        // is not a usable gesture, and most of the rows it would cross are not even loaded (#52).
        // The position badge below is the affordance at that size.
        if (canDrag) {
            Icon(
                imageVector = Icons.Default.DragIndicator,
                contentDescription = null,
                tint = if (isDragging) colors.accentPrimary else colors.foregroundMuted,
                modifier = dragHandleModifier
                    .size(24.dp)
                    .testTag("card_drag_handle"),
            )
        }

        // The move buttons stay alongside the handle: dragging is unreachable with TalkBack,
        // and these are what the journey tests drive. The number between them is the card's
        // study position, and tapping it opens "move to position…".
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(
                onClick = onMoveUp,
                enabled = canMoveUp,
                modifier = Modifier.size(24.dp).testTag("card_move_up"),
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.deck_editor_move_up),
                    tint = if (canMoveUp) colors.foregroundMuted else colors.borderSubtle,
                    modifier = Modifier.size(18.dp),
                )
            }
            // A chip, not bare text: this opens "move to position", which is the only practical
            // way to move a card any distance in a 442-card imported deck — and drawn as a plain
            // number between two arrows it read as a label, so nobody would find it.
            Text(
                text = position.toString(),
                fontSize = 11.sp,
                fontWeight = FontWeight.W700,
                color = colors.accentSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.accentSecondarySoft)
                    .clickable(
                        onClick = onMoveToClick,
                        onClickLabel = stringResource(R.string.deck_editor_move_to),
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                    .testTag("card_position"),
            )
            IconButton(
                onClick = onMoveDown,
                enabled = canMoveDown,
                modifier = Modifier.size(24.dp).testTag("card_move_down"),
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.deck_editor_move_down),
                    tint = if (canMoveDown) colors.foregroundMuted else colors.borderSubtle,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // Text column
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = card.frontText.ifEmpty { stringResource(R.string.deck_editor_card_front_placeholder) },
                fontSize = 15.sp,
                fontWeight = FontWeight.W700,
                color = if (card.frontText.isEmpty()) colors.foregroundMuted else colors.foregroundPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = card.backText.ifEmpty { stringResource(R.string.deck_editor_card_back_placeholder) },
                fontSize = 13.sp,
                color = colors.foregroundMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Media indicators
        if (card.hasImage || card.hasAudio) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (card.hasImage) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = stringResource(R.string.deck_editor_has_image),
                        tint = colors.accentSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                if (card.hasAudio) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = stringResource(R.string.deck_editor_has_audio),
                        tint = colors.accentSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun DeckEditorScreenPreview() {
    LoopkyTheme {
        DeckEditorScreen(
            state = DeckEditorUiState(
                isNew = false,
                coverEmoji = "🇪🇸",
                title = "Spanish Essentials",
                description = "Core vocabulary for everyday conversations.",
                tags = listOf("language", "spanish"),
                cards = listOf(
                    EditableCardModel(
                        id = "c1",
                        frontText = "Hola",
                        backText = "Hello",
                        hasImage = false,
                        hasAudio = true,
                    ),
                    EditableCardModel(
                        id = "c2",
                        frontText = "Gracias",
                        backText = "Thank you",
                        hasImage = true,
                        hasAudio = false,
                    ),
                ),
                totalCards = 2,
            ),
            onCloseClick = {},
            onSaveClick = {},
            onTitleChanged = {},
            onDescriptionChanged = {},
            onRemoveTag = {},
            onAddTag = {},
            onToggleListen = {},
            onToggleSpeak = {},
            onToggleType = {},
            onToggleReverse = {},
            onFrontLangSelected = {},
            onBackLangSelected = {},
            availableLanguages = SpeechLanguages.COMMON,
            onCardClick = {},
            onMoveCard = { _, _ -> },
            onLoadMoreCards = {},
            onCoverWebSelected = {},
            onCoverGallerySelected = { _, _ -> },
            onAddCard = {},
        )
    }
}
