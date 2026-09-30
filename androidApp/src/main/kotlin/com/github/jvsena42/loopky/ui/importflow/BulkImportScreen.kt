package com.github.jvsena42.loopky.ui.importflow

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.jvsena42.loopky.R
import com.github.jvsena42.loopky.data.anki.ApkgFieldMapping
import com.github.jvsena42.loopky.domain.model.Separator
import com.github.jvsena42.loopky.presentation.importflow.BulkImportEffect
import com.github.jvsena42.loopky.presentation.importflow.BulkImportError
import com.github.jvsena42.loopky.presentation.importflow.BulkImportUiState
import com.github.jvsena42.loopky.presentation.importflow.BulkImportViewModel
import com.github.jvsena42.loopky.presentation.importflow.SampleCard
import com.github.jvsena42.loopky.ui.components.LoopkyErrorBlock
import com.github.jvsena42.loopky.ui.components.LoopkyPrimaryButton
import com.github.jvsena42.loopky.ui.components.LoopkySecondaryButton
import com.github.jvsena42.loopky.ui.components.bulkImportErrorMessage
import com.github.jvsena42.loopky.ui.components.bulkImportErrorTitle
import com.github.jvsena42.loopky.ui.layout.PaneWidth
import com.github.jvsena42.loopky.ui.layout.contentPane
import com.github.jvsena42.loopky.ui.theme.LoopkyTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/**
 * Bulk file import. Deliberately a summary rather than the swipe queue paste uses: nobody
 * reviews a 20,000-card Anki export one card at a time (spec §5.4).
 */
@Composable
internal fun BulkImportRoute(
    onBack: () -> Unit,
    onContinue: () -> Unit,
    incoming: IncomingFile? = null,
    onIncomingHandled: () -> Unit = {},
) {
    val viewModel = koinViewModel<BulkImportViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resolver = context.contentResolver
    val cacheDir = context.cacheDir
    // Cancelled if the user navigates away mid-read; the ViewModel is cleared with the
    // destination too, so nothing outlives the screen.
    val scope = rememberCoroutineScope()

    // The effect collector outlives a recomposition that swaps these lambdas, so capture them
    // through rememberUpdatedState rather than closing over the originals.
    val currentBack by rememberUpdatedState(onBack)
    val currentContinue by rememberUpdatedState(onContinue)
    val currentIncomingHandled by rememberUpdatedState(onIncomingHandled)

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                BulkImportEffect.Continue -> currentContinue()
                BulkImportEffect.NavigateBack -> currentBack()
            }
        }
    }

    // An .apkg is read from its spool rather than from memory, so the file has to outlive the
    // picker callback — but only until the next pick or until the screen goes away, or a few
    // hundred MB of someone's cache is ours forever.
    val spool = remember { mutableStateOf<PickedFile?>(null) }
    DisposableEffect(Unit) {
        onDispose { spool.value?.delete() }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.onFileReadStarted()
        spool.value?.delete()
        spool.value = null
        // File access is a platform concern, and the shared ViewModel takes plain text so the
        // same summary works for any future source. The read itself is off the main thread —
        // it used to run right here in the callback, where a multi-MB .apkg froze the UI.
        scope.launch {
            spool.value = viewModel.acceptPickedFile(resolver.readPickedFile(uri, cacheDir))
        }
    }

    // A file the system handed to Loopky (*Open with*, or the share sheet). Already spooled by
    // MainActivity, because the uri's grant would not have survived the wait for onboarding —
    // so it joins the picker's path at [acceptPickedFile] rather than at the read.
    LaunchedEffect(incoming) {
        when (incoming) {
            null -> Unit
            IncomingFile.Reading -> viewModel.onFileReadStarted()
            is IncomingFile.Failed -> {
                viewModel.onFileReadFailed(incoming.reason)
                currentIncomingHandled()
            }
            is IncomingFile.Ready -> {
                spool.value?.delete()
                spool.value = viewModel.acceptPickedFile(Result.success(incoming.file))
                currentIncomingHandled()
            }
        }
    }

    // Anything is selectable because .apkg has no registered MIME type — SAF providers variously
    // report application/octet-stream, application/zip, or nothing, so a narrowed filter greys
    // out legitimate Anki decks. acceptPickedFile sniffs the bytes and does the type check.
    val pickFile = { picker.launch(arrayOf("*/*")) }

    val clipboard = LocalClipboardManager.current
    val cliPrompt = stringResource(R.string.bulk_cli_prompt)

    BulkImportScreen(
        state = state,
        onPickFile = { pickFile() },
        onCopyCliPrompt = { clipboard.setText(AnnotatedString(cliPrompt)) },
        onSeparatorOverride = viewModel::onSeparatorOverride,
        onFieldMappingChange = viewModel::onFieldMappingChanged,
        onConfirm = viewModel::onConfirm,
        onCancel = viewModel::onCancel,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BulkImportScreen(
    state: BulkImportUiState,
    onPickFile: () -> Unit,
    onCopyCliPrompt: () -> Unit,
    onSeparatorOverride: (Separator) -> Unit,
    onFieldMappingChange: (ApkgFieldMapping) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LoopkyTheme.colors
    var showSeparatorSheet by remember { mutableStateOf(false) }
    var showFieldSheet by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        containerColor = colors.surfacePrimary,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.bulk_title)) },
                navigationIcon = {
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.testTag("bulk_cancel"),
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.accentPrimary),
                    ) { Text(stringResource(R.string.bulk_cancel)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.surfacePrimary,
                    titleContentColor = colors.foregroundPrimary,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .contentPane(PaneWidth.Reading)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            when (state) {
                BulkImportUiState.Idle -> PickFilePrompt(onPickFile, onCopyCliPrompt)
                BulkImportUiState.Reading -> BusyIndicator(stringResource(R.string.bulk_reading))
                is BulkImportUiState.Parsing ->
                    BusyIndicator(stringResource(R.string.bulk_parsing, state.fileName))
                is BulkImportUiState.Ready -> Summary(
                    state = state,
                    onConfirm = onConfirm,
                    onPickFile = onPickFile,
                    onSeparatorClick = { showSeparatorSheet = true },
                    onFieldsClick = { showFieldSheet = true },
                )
                is BulkImportUiState.Error -> ErrorState(state.reason, onPickFile)
            }
        }
    }

    val fields = (state as? BulkImportUiState.Ready)?.fields
    if (showFieldSheet && fields != null) {
        FieldMappingSheet(
            fields = fields,
            onPick = {
                showFieldSheet = false
                onFieldMappingChange(it)
            },
            onDismiss = { showFieldSheet = false },
        )
    }

    if (showSeparatorSheet) {
        val current = (state as? BulkImportUiState.Ready)?.separator
        SeparatorOverrideSheet(
            current = current,
            onPick = {
                showSeparatorSheet = false
                onSeparatorOverride(it)
            },
            onDismiss = { showSeparatorSheet = false },
        )
    }
}

/**
 * The empty state, which is where most of this screen's job gets done.
 *
 * It was a paragraph of prose and a button over two-thirds of blank screen, which said nothing
 * about what a "file" is here. Mirrors the paste screen's example cards instead: name the two
 * formats that work, say what each brings over, and — since the spec pitches Loopky at Anki
 * refugees (§1) — spell out the export that produces them.
 */
@Composable
private fun PickFilePrompt(onPickFile: () -> Unit, onCopyCliPrompt: () -> Unit) {
    val colors = LoopkyTheme.colors
    Spacer(Modifier.height(28.dp))
    Text(text = "📦", fontSize = 44.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.bulk_idle_title),
        fontSize = 22.sp,
        fontWeight = FontWeight.ExtraBold,
        color = colors.foregroundPrimary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = stringResource(R.string.bulk_idle_subtitle),
        fontSize = 14.sp,
        color = colors.foregroundMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(28.dp))
    Text(
        text = stringResource(R.string.bulk_formats_label),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        color = colors.foregroundMuted,
    )
    Spacer(Modifier.height(10.dp))
    FormatCard(
        extension = stringResource(R.string.bulk_format_apkg_ext),
        title = stringResource(R.string.bulk_format_apkg_title),
        detail = stringResource(R.string.bulk_format_apkg_detail),
    )
    Spacer(Modifier.height(10.dp))
    FormatCard(
        extension = stringResource(R.string.bulk_format_text_ext),
        title = stringResource(R.string.bulk_format_text_title),
        detail = stringResource(R.string.bulk_format_text_detail),
    )

    Spacer(Modifier.height(24.dp))
    LoopkyPrimaryButton(
        label = stringResource(R.string.bulk_pick_file),
        onClick = onPickFile,
        modifier = Modifier.testTag("bulk_pick_file"),
    )
    Spacer(Modifier.height(12.dp))
    Text(
        // Formatted from the reader's own ceiling so the copy cannot drift from the guard.
        text = stringResource(R.string.bulk_idle_limit, MAX_IMPORT_FILE_BYTES / BYTES_PER_MB),
        fontSize = 12.sp,
        color = colors.foregroundMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(28.dp))
    CliPromptCard(onCopyCliPrompt)
    Spacer(Modifier.height(24.dp))
}

/**
 * A pitch for `loopky`, the headless client, on the screen whose whole premise is that the deck
 * already exists somewhere else. The prompt is copied rather than shown, because the reader's
 * agent lives on another machine and nobody retypes an install line from a phone.
 */
@Composable
private fun CliPromptCard(onCopyPrompt: () -> Unit) {
    val colors = LoopkyTheme.colors
    // Copying is silent below API 33, where the system shows no confirmation of its own.
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_LABEL_MILLIS)
            copied = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, colors.borderSubtle, RoundedCornerShape(14.dp))
            .background(colors.surfaceCard)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.bulk_cli_title),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = colors.foregroundPrimary,
        )
        Text(
            text = stringResource(R.string.bulk_cli_body),
            fontSize = 13.sp,
            color = colors.foregroundSecondary,
        )
        Spacer(Modifier.height(2.dp))
        LoopkySecondaryButton(
            text = stringResource(
                if (copied) R.string.bulk_cli_copied else R.string.bulk_cli_copy,
            ),
            onClick = {
                onCopyPrompt()
                copied = true
            },
            modifier = Modifier.testTag("bulk_cli_copy"),
        )
    }
}

/** How long the copy button stands in for the confirmation Android 12 and below never show. */
private const val COPIED_LABEL_MILLIS = 2_000L

/** One accepted file format: what it is, and what it does and doesn't bring over. */
@Composable
private fun FormatCard(extension: String, title: String, detail: String) {
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.foregroundPrimary)
            Text(
                text = extension,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.accentPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.accentPrimarySoft)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Text(detail, fontSize = 13.sp, color = colors.foregroundSecondary)
    }
}

private const val BYTES_PER_MB = 1024L * 1024

@Composable
private fun BusyIndicator(message: String) {
    val colors = LoopkyTheme.colors
    Spacer(Modifier.height(64.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = colors.accentPrimary)
    }
    Spacer(Modifier.height(16.dp))
    Text(
        text = message,
        fontSize = 14.sp,
        color = colors.foregroundSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .testTag("bulk_busy")
            .fillMaxWidth(),
    )
}

@Composable
private fun Summary(
    state: BulkImportUiState.Ready,
    onConfirm: () -> Unit,
    onPickFile: () -> Unit,
    onSeparatorClick: () -> Unit,
    onFieldsClick: () -> Unit,
) {
    val colors = LoopkyTheme.colors
    Spacer(Modifier.height(24.dp))
    Text(
        text = stringResource(R.string.bulk_summary_heading),
        fontSize = 24.sp,
        lineHeight = 29.sp,
        fontWeight = FontWeight.W700,
        color = colors.foregroundPrimary,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = pluralStringResource(R.plurals.bulk_cards_parsed, state.cardCount, state.cardCount),
        fontSize = 16.sp,
        color = colors.foregroundPrimary,
    )

    Spacer(Modifier.height(12.dp))
    // What the reader decided, and a way to disagree with it. An .apkg knows its own field names,
    // so it offers those; a text file only ever had a separator to guess at.
    val fields = state.fields
    if (fields != null) {
        FieldsChip(fields = fields, onClick = onFieldsClick)
    } else {
        SeparatorChip(separator = state.separator, onClick = onSeparatorClick)
    }

    // Everything the import dropped, stated rather than silently swallowed.
    if (state.droppedNoteCount > 0) {
        Caption(pluralStringResource(R.plurals.bulk_dropped_notes, state.droppedNoteCount, state.droppedNoteCount))
    }
    if (state.skippedCount > 0) {
        Caption(stringResource(R.string.bulk_skipped, state.skippedCount))
    }
    if (state.duplicatesCollapsed > 0) {
        Caption(pluralStringResource(R.plurals.bulk_duplicates, state.duplicatesCollapsed, state.duplicatesCollapsed))
    }
    if (state.truncatedCount > 0) {
        Caption(pluralStringResource(R.plurals.bulk_truncated, state.truncatedCount, state.truncatedCount))
    }
    if (state.imagesSkippedCount > 0) {
        Caption(pluralStringResource(R.plurals.bulk_images_skipped, state.imagesSkippedCount, state.imagesSkippedCount))
    }

    Spacer(Modifier.height(24.dp))
    Text(
        text = stringResource(R.string.bulk_sample_label),
        fontSize = 12.sp,
        fontWeight = FontWeight.W700,
        letterSpacing = 1.5.sp,
        color = colors.foregroundSecondary,
    )
    Spacer(Modifier.height(8.dp))
    state.sample.forEach { SampleRow(it) }

    Spacer(Modifier.height(32.dp))
    LoopkyPrimaryButton(
        label = pluralStringResource(R.plurals.bulk_import_cards, state.cardCount, state.cardCount),
        onClick = onConfirm,
        enabled = state.canImport,
        modifier = Modifier.testTag("bulk_import"),
    )
    Spacer(Modifier.height(8.dp))
    // Changing your mind used to mean Cancel and start the whole flow over.
    LoopkySecondaryButton(
        text = stringResource(R.string.bulk_pick_another),
        onClick = onPickFile,
        modifier = Modifier
            .testTag("bulk_repick")
            .fillMaxWidth(),
    )
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun SeparatorChip(separator: Separator, onClick: () -> Unit) {
    val colors = LoopkyTheme.colors
    Text(
        text = stringResource(R.string.bulk_detected_separator, stringResource(separatorLabel(separator))),
        fontSize = 13.sp,
        fontWeight = FontWeight.W600,
        color = colors.accentPrimary,
        modifier = Modifier
            .testTag("bulk_separator_chip")
            .background(colors.accentPrimarySoft, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun Caption(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = LoopkyTheme.colors.foregroundSecondary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun ErrorState(reason: BulkImportError, onPickFile: () -> Unit) {
    Spacer(Modifier.height(48.dp))
    LoopkyErrorBlock(
        title = bulkImportErrorTitle(reason),
        message = bulkImportErrorMessage(reason),
        onRetry = onPickFile,
        retryLabel = stringResource(R.string.bulk_pick_file),
        modifier = Modifier.testTag("bulk_error"),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun BulkImportIdlePreview() {
    LoopkyTheme {
        BulkImportScreen(
            state = BulkImportUiState.Idle,
            onPickFile = {},
            onCopyCliPrompt = {},
            onSeparatorOverride = {},
            onFieldMappingChange = {},
            onConfirm = {},
            onCancel = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun BulkImportParsingPreview() {
    LoopkyTheme {
        BulkImportScreen(
            state = BulkImportUiState.Parsing("japanese_core.apkg"),
            onPickFile = {},
            onCopyCliPrompt = {},
            onSeparatorOverride = {},
            onFieldMappingChange = {},
            onConfirm = {},
            onCancel = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun BulkImportReadyPreview() {
    LoopkyTheme {
        BulkImportScreen(
            state = BulkImportUiState.Ready(
                fileName = "japanese_core.apkg",
                separator = Separator.Tab,
                cardCount = 1_842,
                skippedCount = 6,
                duplicatesCollapsed = 12,
                truncatedCount = 0,
                sample = listOf(
                    SampleCard(front = "日本", back = "Japan"),
                    SampleCard(front = "本", back = "book, origin"),
                    SampleCard(front = "人", back = "person"),
                ),
            ),
            onPickFile = {},
            onCopyCliPrompt = {},
            onSeparatorOverride = {},
            onFieldMappingChange = {},
            onConfirm = {},
            onCancel = {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun BulkImportErrorPreview() {
    LoopkyTheme {
        BulkImportScreen(
            state = BulkImportUiState.Error(BulkImportError.NotText),
            onPickFile = {},
            onCopyCliPrompt = {},
            onSeparatorOverride = {},
            onFieldMappingChange = {},
            onConfirm = {},
            onCancel = {},
        )
    }
}
