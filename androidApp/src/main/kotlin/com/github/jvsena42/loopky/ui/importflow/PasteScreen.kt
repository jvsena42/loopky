package com.github.jvsena42.loopky.ui.importflow

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
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
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.domain.model.Separator
import com.github.jvsena42.loopky.presentation.importflow.PasteImportEffect
import com.github.jvsena42.loopky.presentation.importflow.PasteImportUiState
import com.github.jvsena42.loopky.presentation.importflow.PasteImportViewModel
import com.github.jvsena42.loopky.presentation.importflow.PasteValidation
import com.github.jvsena42.loopky.presentation.importflow.PreviewCard
import com.github.jvsena42.loopky.ui.components.LoopkyPrimaryButton
import com.github.jvsena42.loopky.ui.layout.PaneWidth
import com.github.jvsena42.loopky.ui.layout.contentPane
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun PasteRoute(
    onCancel: () -> Unit = {},
    onNext: () -> Unit = {},
) {
    val viewModel = koinViewModel<PasteImportViewModel>()

    val currentCancel by rememberUpdatedState(onCancel)
    val currentNext by rememberUpdatedState(onNext)

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                PasteImportEffect.NavigateBack -> currentCancel()
                PasteImportEffect.NavigatePublish -> currentNext()
            }
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    PasteScreen(
        state = state,
        onTextChanged = viewModel::onTextChanged,
        onSeparatorOverride = viewModel::onSeparatorOverride,
        onNextClick = viewModel::onNextClick,
        onCancelClick = viewModel::onCancelClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasteScreen(
    state: PasteImportUiState,
    onTextChanged: (String) -> Unit,
    onSeparatorOverride: (Separator) -> Unit,
    onNextClick: () -> Unit,
    onCancelClick: () -> Unit,
) {
    val colors = LoopkyTheme.colors

    Scaffold(
        containerColor = colors.surfacePrimary,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.paste_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                },
                navigationIcon = {
                    TextButton(
                        onClick = onCancelClick,
                        modifier = Modifier.testTag("paste_cancel"),
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.accentPrimary),
                    ) {
                        Text(text = stringResource(R.string.paste_cancel), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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
                .imePadding()
                // A paste box and its worked examples are prose-shaped; the format chip on each
                // example row was ending up a full screen away from the title it labels.
                .contentPane(PaneWidth.Reading),
        ) {
            // Top fixed region: input + detected-separator summary.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                OutlinedTextField(
                    value = state.rawText,
                    onValueChange = onTextChanged,
                    modifier = Modifier
                        .testTag("paste_input")
                        .fillMaxWidth()
                        // Fixed, not heightIn(min = …): the field used to grow with its content,
                        // so a paste of any real size pushed Next off the bottom of the screen and
                        // under the keyboard. It scrolls inside these bounds instead.
                        .height(PASTE_INPUT_HEIGHT),
                    textStyle = TextStyle(fontSize = 14.sp, color = colors.foregroundPrimary),
                    placeholder = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(R.string.paste_input_placeholder_title),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.foregroundMuted,
                            )
                            Text(
                                stringResource(R.string.paste_input_placeholder_subtitle),
                                fontSize = 13.sp,
                                color = colors.foregroundMuted.copy(alpha = 0.6f),
                            )
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accentPrimary,
                        unfocusedBorderColor = colors.borderSubtle,
                        cursorColor = colors.accentPrimary,
                        focusedContainerColor = colors.surfaceCard,
                        unfocusedContainerColor = colors.surfaceCard,
                    ),
                )

                if (state.isParsed && state.detectedSeparator != null) {
                    ParseSummaryRow(state = state, onSeparatorOverride = onSeparatorOverride)
                }
            }

            // Middle filling region: preview cards (fill height) or example cards.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    state.isParsed && state.hasPreviewableCard -> {
                        Text(
                            stringResource(R.string.paste_preview_label),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            color = colors.foregroundMuted,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                        // contentPadding gives the 8dp card shadow room to render instead of being
                        // clipped at the LazyRow bounds, and keeps the 20dp screen margin.
                        LazyRow(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                        ) {
                            itemsIndexed(state.previewCards) { index, card ->
                                PreviewCardItem(
                                    index = index + 1,
                                    total = state.cardCount,
                                    front = card.front,
                                    back = card.back,
                                    modifier = Modifier.fillMaxHeight(),
                                )
                            }
                        }
                    }

                    else -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                stringResource(R.string.paste_try_pasting_label),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp,
                                color = colors.foregroundMuted,
                            )
                            ExampleCard(
                                title = "Vocab list",
                                separator = stringResource(R.string.paste_separator_dash),
                                lines = listOf("hola — hello", "gracias — thank you"),
                            )
                            ExampleCard(
                                title = "Glossary",
                                separator = stringResource(R.string.paste_separator_colon),
                                lines = listOf("mitosis: cell division", "osmosis: water moves across a membrane"),
                            )
                            ExampleCard(
                                title = "Notion table",
                                separator = stringResource(R.string.paste_separator_markdown),
                                lines = listOf("| capital | France |", "| currency | euro |"),
                            )
                        }
                    }
                }
            }

            // Bottom fixed region: public notice, error, and the single Next CTA.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Hide the public notice while the keyboard is open so only the Next
                // button floats above the IME instead of the notice riding up with it.
                val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                if (!imeVisible) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("🔗", fontSize = 14.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.paste_public_notice),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.accentSecondary,
                        )
                    }
                }

                state.error?.let { errorText ->
                    Text(errorText, fontSize = 14.sp, color = colors.danger, modifier = Modifier.fillMaxWidth())
                }

                state.validation?.let { validation ->
                    Text(
                        text = stringResource(
                            when (validation) {
                                PasteValidation.EmptyInput -> R.string.paste_validation_empty
                                PasteValidation.NoCardsParsed -> R.string.paste_validation_no_cards
                            },
                        ),
                        fontSize = 13.sp,
                        color = colors.danger,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("paste_validation"),
                    )
                }

                LoopkyPrimaryButton(
                    label = stringResource(R.string.paste_next),
                    onClick = onNextClick,
                    // Enabled so the validation message can explain the block.
                    enabled = true,
                    modifier = Modifier
                        .testTag("paste_next")
                        .fillMaxWidth(),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParseSummaryRow(
    state: PasteImportUiState,
    onSeparatorOverride: (Separator) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    val chipError = when {
        state.noPatternDetected -> stringResource(R.string.paste_chip_no_pattern)
        state.hasIncompleteCards -> pluralStringResource(R.plurals.cards_incomplete, state.incompleteCardCount, state.incompleteCardCount)
        else -> null
    }
    var showSeparatorSheet by rememberSaveable { mutableStateOf(false) }
    if (showSeparatorSheet) {
        SeparatorOverrideSheet(
            current = state.separatorOverride ?: state.detectedSeparator,
            onPick = {
                onSeparatorOverride(it)
                showSeparatorSheet = false
            },
            onDismiss = { showSeparatorSheet = false },
        )
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(
            onClick = { showSeparatorSheet = true },
            modifier = Modifier.testTag("paste_separator_chip"),
            label = {
                Text(
                    text = chipError ?: stringResource(
                        R.string.paste_detected_separator,
                        stringResource(separatorLabel(state.detectedSeparator)),
                    ),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = if (chipError != null) Icons.Default.Warning else Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            },
            shape = RoundedCornerShape(50),
            colors = AssistChipDefaults.assistChipColors(
                containerColor = if (chipError != null) colors.dangerSoft else colors.accentSecondarySoft,
                labelColor = if (chipError != null) colors.danger else colors.accentSecondary,
                leadingIconContentColor = if (chipError != null) colors.danger else colors.accentSecondary,
            ),
            border = null,
        )
        Text(
            pluralStringResource(R.plurals.card_count, state.cardCount, state.cardCount),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = colors.foregroundMuted,
        )
    }
}

@Composable
private fun PreviewCardItem(
    index: Int,
    total: Int,
    front: String,
    back: String,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = modifier
            .width(160.dp)
            .shadow(8.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceCard)
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.paste_card_index, index, total), fontSize = 11.sp, color = colors.foregroundMuted)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                front.ifBlank { stringResource(R.string.paste_blank_placeholder) },
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = colors.foregroundPrimary,
            )
            Spacer(Modifier.height(12.dp))
            Box(Modifier.width(32.dp).height(2.dp).background(colors.accentPrimary))
            Spacer(Modifier.height(12.dp))
            Text(back.ifBlank { stringResource(R.string.paste_blank_placeholder) }, fontSize = 14.sp, color = colors.foregroundMuted)
        }
    }
}

@Composable
private fun ExampleCard(title: String, separator: String, lines: List<String>) {
    val colors = LoopkyTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, colors.borderSubtle, RoundedCornerShape(14.dp))
            .background(colors.surfaceCard)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.foregroundPrimary)
            Text(
                separator, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.accentPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.accentPrimarySoft)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        lines.forEach { line -> Text(line, fontSize = 13.sp, color = colors.foregroundSecondary) }
    }
}

/**
 * Spec §5.2 "tap to change": the detected-separator chip is an override control, not a label.
 * `ModalBottomSheet` renders in its own window, so it needs its own `testTagsAsResourceId`.
 *
 * Internal so the bulk-import summary reuses this sheet rather than growing a second one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SeparatorOverrideSheet(
    current: Separator?,
    onPick: (Separator) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    val options = listOf(
        Separator.Auto,
        Separator.Tab,
        Separator.Comma,
        Separator.Semicolon,
        Separator.Pipe,
        Separator.Colon,
        Separator.EmDash,
        Separator.MarkdownTable,
        Separator.BlankLine,
    )
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surfaceSecondary) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .semantics { testTagsAsResourceId = true },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.paste_separator_sheet_title),
                color = colors.foregroundPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            options.forEach { option ->
                val selected = option == current || (option == Separator.Auto && current == null)
                Text(
                    text = stringResource(separatorLabel(option)),
                    color = if (selected) colors.accentPrimary else colors.foregroundPrimary,
                    fontSize = 15.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(option) }
                        .testTag("separator_option")
                        .padding(vertical = 12.dp),
                )
            }
        }
    }
}

@StringRes
internal fun separatorLabel(sep: Separator?): Int = when (sep) {
    Separator.Tab -> R.string.paste_separator_tab
    Separator.Semicolon -> R.string.paste_separator_semicolon
    Separator.Pipe -> R.string.paste_separator_pipe
    // Buckets em-dash, en-dash and a spaced hyphen, so "dash" not stringResource(R.string.paste_separator_dash).
    Separator.EmDash -> R.string.paste_separator_dash
    Separator.Colon -> R.string.paste_separator_colon
    Separator.Comma -> R.string.paste_separator_comma
    Separator.BlankLine -> R.string.paste_separator_blank_lines
    Separator.MarkdownTable -> R.string.paste_separator_markdown
    Separator.SingleColumn -> R.string.paste_separator_single_column
    else -> R.string.paste_separator_auto
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun PasteScreenPreview() {
    LoopkyTheme {
        PasteScreen(
            state = PasteImportUiState(
                rawText = "hola — hello\ngracias — thank you",
                detectedSeparator = Separator.EmDash,
                cardCount = 2,
                previewCards = listOf(
                    PreviewCard(front = "hola", back = "hello"),
                    PreviewCard(front = "gracias", back = "thank you"),
                ),
                isParsed = true,
            ),
            onTextChanged = {},
            onSeparatorOverride = {},
            onNextClick = {},
            onCancelClick = {},
        )
    }
}

/** Paste field height. Fixed so the Next button below it keeps its place whatever is pasted. */
private val PASTE_INPUT_HEIGHT = 200.dp
