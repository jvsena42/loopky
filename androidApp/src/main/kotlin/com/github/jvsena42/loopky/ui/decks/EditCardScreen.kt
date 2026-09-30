package com.github.jvsena42.loopky.ui.decks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.presentation.decks.EditCardEffect
import com.github.jvsena42.loopky.presentation.decks.EditCardUiState
import com.github.jvsena42.loopky.presentation.decks.EditCardViewModel
import com.github.jvsena42.loopky.ui.components.CardSideEditor
import com.github.jvsena42.loopky.ui.components.ImagePickerSheet
import com.github.jvsena42.loopky.ui.components.ImageSelection
import com.github.jvsena42.loopky.ui.components.formErrorMessage
import com.github.jvsena42.loopky.ui.layout.PaneWidth
import com.github.jvsena42.loopky.ui.layout.contentPane
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun EditCardRoute(
    deckId: String,
    cardId: String,
    onBack: () -> Unit = {},
    /** Opens Settings on the Unsplash key row, for when the image sheet reports a key problem. */
    onOpenSettings: () -> Unit = {},
) {
    val viewModel = koinViewModel<EditCardViewModel> { parametersOf(deckId, cardId) }

    val currentBack by rememberUpdatedState(onBack)

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                EditCardEffect.NavigateBack -> currentBack()
                EditCardEffect.SaveSuccess -> currentBack()
                EditCardEffect.Deleted -> currentBack()
            }
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    EditCardScreen(
        state = state,
        onCancelClick = viewModel::onCancelClick,
        onSaveClick = viewModel::onSaveClick,
        onFrontTextChanged = viewModel::onFrontTextChanged,
        onBackTextChanged = viewModel::onBackTextChanged,
        onFrontImageWebSelected = viewModel::onFrontImageWebSelected,
        onFrontImageGallerySelected = viewModel::onFrontImageGallerySelected,
        onRemoveFrontImage = viewModel::onRemoveFrontImage,
        onBackImageWebSelected = viewModel::onBackImageWebSelected,
        onBackImageGallerySelected = viewModel::onBackImageGallerySelected,
        onRemoveBackImage = viewModel::onRemoveBackImage,
        onDeleteCard = viewModel::onDeleteCard,
        onOpenSettings = onOpenSettings,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditCardScreen(
    state: EditCardUiState,
    onCancelClick: () -> Unit,
    onSaveClick: () -> Unit,
    onFrontTextChanged: (String) -> Unit,
    onBackTextChanged: (String) -> Unit,
    /** Opens Settings on the Unsplash key row, for when the image sheet reports a key problem. */
    onOpenSettings: () -> Unit,
    onFrontImageWebSelected: (String) -> Unit = {},
    onFrontImageGallerySelected: (ByteArray, String) -> Unit = { _, _ -> },
    onRemoveFrontImage: () -> Unit = {},
    onBackImageWebSelected: (String) -> Unit = {},
    onBackImageGallerySelected: (ByteArray, String) -> Unit = { _, _ -> },
    onRemoveBackImage: () -> Unit = {},
    onDeleteCard: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    // Which side's image picker is open (true = front, false = back, null = none).
    var imagePickerSide by remember { mutableStateOf<Boolean?>(null) }

    Scaffold(
        containerColor = colors.surfacePrimary,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = if (state.isNewCard) {
                            stringResource(R.string.edit_card_title_new)
                        } else {
                            stringResource(R.string.edit_card_title)
                        },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.W800,
                    )
                },
                navigationIcon = {
                    TextButton(
                        onClick = onCancelClick,
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.accentPrimary),
                    ) {
                        Text(text = stringResource(R.string.edit_card_cancel), fontSize = 16.sp, fontWeight = FontWeight.W600)
                    }
                },
                actions = {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            color = colors.accentPrimary,
                            strokeWidth = 2.dp,
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .size(20.dp),
                        )
                    } else {
                        Button(
                            onClick = onSaveClick,
                            modifier = Modifier.padding(end = 12.dp),
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.accentPrimary,
                                contentColor = colors.foregroundOnAccent,
                            ),
                        ) {
                            Text(text = stringResource(R.string.edit_card_save), fontSize = 15.sp, fontWeight = FontWeight.W700)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.surfacePrimary,
                    titleContentColor = colors.foregroundPrimary,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .contentPane(PaneWidth.Reading)
                // Without this the scroll container keeps its full height behind the keyboard,
                // so the field being typed into stays hidden and there is nothing left to scroll.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // 1. Context chip
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        text = stringResource(
                            R.string.edit_card_context,
                            state.cardIndex,
                            state.totalCards,
                            state.deckTitle,
                        ),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.W600,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                },
                shape = RoundedCornerShape(50),
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = colors.accentSecondarySoft,
                    labelColor = colors.accentSecondary,
                    leadingIconContentColor = colors.accentSecondary,
                ),
                border = null,
            )

            // 2. Front section
            CardSideEditor(
                label = stringResource(R.string.edit_card_label_front),
                value = state.frontText,
                onValueChange = onFrontTextChanged,
                placeholder = stringResource(R.string.edit_card_front_placeholder),
                textStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.W700),
                imageModel = state.frontPendingBytes,
                imageRef = state.frontImageRef,
                deckId = state.deckId,
                authorPubky = state.authorPubky,
                onPickImage = { imagePickerSide = true },
                onRemoveImage = onRemoveFrontImage,
                imageTag = "editcard_front_image",
                fieldTag = "editcard_front",
                error = state.frontError?.let { formErrorMessage(it) },
            )

            // 3. Back section
            CardSideEditor(
                label = stringResource(R.string.edit_card_label_back),
                value = state.backText,
                onValueChange = onBackTextChanged,
                placeholder = stringResource(R.string.edit_card_back_placeholder),
                textStyle = TextStyle(fontSize = 16.sp),
                imageModel = state.backPendingBytes,
                imageRef = state.backImageRef,
                deckId = state.deckId,
                authorPubky = state.authorPubky,
                onPickImage = { imagePickerSide = false },
                onRemoveImage = onRemoveBackImage,
                imageTag = "editcard_back_image",
                fieldTag = "editcard_back",
                error = state.backError?.let { formErrorMessage(it) },
            )

            // Audio recording is not built yet (no AudioRecorder expect/actual, nothing calls
            // MediaRepository.putAudio). The button used to render here and do nothing when
            // tapped; existing audio on a card still round-trips and shows as an indicator.

            // Tags are a deck-level concept (spec §8) — cards carry none, so there is no
            // tag section here. Deck tags are edited on the deck editor / publish screen.

            // 5. Delete button — nothing to delete on a card that has not been written yet.
            if (!state.isNewCard) {
                FilledTonalButton(
                    onClick = onDeleteCard,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = colors.dangerSoft,
                        contentColor = colors.srsAgain,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.edit_card_delete),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.edit_card_delete), fontSize = 15.sp, fontWeight = FontWeight.W700)
                }
            }

            state.error?.let { errorText ->
                Text(
                    text = errorText,
                    fontSize = 14.sp,
                    color = colors.danger,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    imagePickerSide?.let { isFront ->
        ImagePickerSheet(
            onOpenSettings = onOpenSettings,
            title = stringResource(if (isFront) R.string.image_sheet_front_title else R.string.image_sheet_back_title),
            subtitle = stringResource(if (isFront) R.string.image_sheet_front_subtitle else R.string.image_sheet_back_subtitle),
            onDismiss = { imagePickerSide = null },
            onSelected = { selection ->
                when (selection) {
                    is ImageSelection.Web ->
                        if (isFront) onFrontImageWebSelected(selection.url) else onBackImageWebSelected(selection.url)
                    is ImageSelection.Gallery ->
                        if (isFront) {
                            onFrontImageGallerySelected(selection.bytes, selection.mime)
                        } else {
                            onBackImageGallerySelected(selection.bytes, selection.mime)
                        }
                }
                imagePickerSide = null
            },
        )
    }
}

@Preview
@Composable
private fun EditCardScreenPreview() {
    LoopkyTheme {
        EditCardScreen(
            state = EditCardUiState(
                deckTitle = "Spanish Essentials",
                cardIndex = 3,
                totalCards = 42,
                frontText = "Hola",
                backText = "Hello",
                hasImage = false,
                hasAudio = true,
                isSaving = false,
            ),
            onCancelClick = {},
            onSaveClick = {},
            onFrontTextChanged = {},
            onBackTextChanged = {},
            onOpenSettings = {},
            onDeleteCard = {},
        )
    }
}
