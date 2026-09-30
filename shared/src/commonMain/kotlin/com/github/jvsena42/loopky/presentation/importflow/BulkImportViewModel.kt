package com.github.jvsena42.loopky.presentation.importflow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.jvsena42.loopky.data.anki.ApkgException
import com.github.jvsena42.loopky.data.anki.ApkgFailure
import com.github.jvsena42.loopky.data.anki.ApkgFieldMapping
import com.github.jvsena42.loopky.data.anki.ApkgImport
import com.github.jvsena42.loopky.data.anki.ApkgReader
import com.github.jvsena42.loopky.data.repository.ImportRepository
import com.github.jvsena42.loopky.domain.model.DraftCardImage
import com.github.jvsena42.loopky.domain.model.ImportDraft
import com.github.jvsena42.loopky.domain.model.Separator
import com.github.jvsena42.loopky.domain.model.frontBackOf
import com.github.jvsena42.loopky.platform.MediaProcessor
import com.github.jvsena42.loopky.util.Log
import com.github.jvsena42.loopky.util.runSuspendCatching
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Bulk file import: parse a whole exported deck and show a **summary**, not a swipe queue.
 *
 * spec §5.4's triage queue is card-at-a-time by design, which is right for a 40-line paste and
 * wrong for a 20,000-card Anki export. This screen reports what was parsed, shows a sample, and
 * takes one confirmation, then hands off to the same [PublishDeckViewModel] commit flow paste uses
 * — the spine every import source shares is parse → preview → commit, not the queue.
 */
@Suppress("TooManyFunctions")
class BulkImportViewModel(
    private val importRepository: ImportRepository,
    private val mediaProcessor: MediaProcessor,
) : ViewModel() {
    private val _state = MutableStateFlow<BulkImportUiState>(BulkImportUiState.Idle)
    val state: StateFlow<BulkImportUiState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<BulkImportEffect>(extraBufferCapacity = 4)
    val effects: SharedFlow<BulkImportEffect> = _effects.asSharedFlow()

    private var parseJob: Job? = null

    /**
     * The spooled `.apkg`, kept so a change of field mapping can re-read it. Re-reading is the
     * whole design of the field picker: the archive is on disk and the read takes a second, which
     * beats holding every note of a 9,000-card deck in the ViewModel on the chance the user
     * disagrees with the default.
     */
    private var apkgPath: String? = null

    /** [fileName] is shown as the default deck name; [text] is the file's contents. */
    fun onFileLoaded(fileName: String, text: String) {
        startParse(fileName) { Result.success(LoadedFile(text)) }
    }

    /**
     * An Anki `.apkg`: a zip around a SQLite collection. Unpacked to the same tab-separated shape
     * a "Notes in Plain Text" export produces, so it feeds the same parser rather than a second
     * one.
     */
    fun onApkgLoaded(fileName: String, path: String) {
        apkgPath = path
        readApkg(fileName, path, mapping = null)
    }

    /**
     * Import a different pair of fields. "The first two fields" is right for a two-field note type
     * and wrong for most real decks, so the summary names the two it chose and lets the user
     * disagree — the role the separator chip plays for a paste. Spec §5.3 rules out a
     * column-mapping UI for *pasted text*, where there are no field names to show; an `.apkg` knows
     * its own.
     */
    fun onFieldMappingChanged(mapping: ApkgFieldMapping) {
        val path = apkgPath ?: return
        val fileName = (_state.value as? BulkImportUiState.Ready)?.fileName ?: return
        readApkg(fileName, path, mapping)
    }

    private fun readApkg(fileName: String, path: String, mapping: ApkgFieldMapping?) {
        parseJob?.cancel()
        parseJob = viewModelScope.launch {
            _state.update { BulkImportUiState.Parsing(fileName) }
            runSuspendCatching {
                val apkg = ApkgReader.readNotes(path, mapping, ::compressForCard)
                    .getOrElse { throw FailedToLoad(readErrorFor(it), it) }
                val draft = importRepository.parseBulkNotes(
                    notes = apkg.notes,
                    suggestedTitle = suggestedTitleFor(apkg.deckName, fileName),
                    suggestedDescription = suggestedDescriptionFor(apkg.deckDescription),
                    suggestedTags = apkg.suggestedTags,
                    suggestsReverse = apkg.reversible,
                ).getOrThrow()
                apkg to draft
            }
                .onSuccess { (apkg, draft) -> emitReady(fileName, draft, apkg) }
                .onFailure { reportParseFailure(it) }
        }
    }

    /**
     * Anki's blobs are full-resolution photographs; a card shows one at 96 dp. Reuses the gallery
     * picker's compression, so an imported picture and a chosen one cost the same against the quota.
     */
    private suspend fun compressForCard(bytes: ByteArray, mime: String): DraftCardImage {
        val processed = mediaProcessor.compressImage(bytes)
        return DraftCardImage(bytes = processed.bytes, mime = processed.mime.ifBlank { mime })
    }

    private fun startParse(
        fileName: String,
        load: suspend () -> Result<LoadedFile>,
    ) {
        parseJob?.cancel()
        parseJob = viewModelScope.launch {
            _state.update { BulkImportUiState.Parsing(fileName) }
            // One runSuspendCatching rather than a recoverCatching/mapCatching chain: both of those
            // are inline, so their lambdas inherit this suspend context and would re-swallow the
            // cancellation the repository now rethrows. getOrElse does not catch, so the load's
            // own failure still gets tagged while a cancel passes straight through.
            runSuspendCatching {
                val loaded = load().getOrElse { throw FailedToLoad(readErrorFor(it), it) }
                importRepository.parseBulk(
                    rawText = loaded.text,
                    suggestedTitle = suggestedTitleFor(loaded.deckName, fileName),
                ).getOrThrow()
            }
                .onSuccess { emitReady(fileName, it) }
                .onFailure { reportParseFailure(it) }
        }
    }

    /**
     * A re-pick cancels a parse that may run for seconds; that kills the coroutine outright, so
     * only real failures reach here. Anything past the load is the parser's: it read fine, there
     * was just nothing card-shaped in it.
     */
    private fun reportParseFailure(err: Throwable) {
        val reason = (err as? FailedToLoad)?.reason ?: BulkImportError.NoCardsFound
        Log.e(TAG, "bulk parse: FAILED — $reason — ${err.message}", err)
        _state.update { BulkImportUiState.Error(reason) }
    }

    private fun emitReady(fileName: String, draft: ImportDraft, apkg: ApkgImport? = null) {
        // Both counts come off keptRows() so the summary and what actually publishes agree by
        // construction. Computing "skipped" independently is how they came to disagree: the screen
        // reported rows as dropped that publish still saw.
        val kept = importRepository.keptRows()
        val skipped = draft.rows.size - kept.size
        Log.d(
            TAG,
            "bulk parse: ${draft.rows.size} rows, $skipped skipped, " +
                "${apkg?.dropped?.total ?: 0} notes dropped by the reader",
        )
        _state.update {
            BulkImportUiState.Ready(
                fileName = fileName,
                separator = draft.separator,
                cardCount = kept.size,
                skippedCount = skipped,
                duplicatesCollapsed = draft.duplicatesCollapsed,
                truncatedCount = draft.truncated,
                // Notes the reader never turned into rows. Computed downstream of `draft.rows`,
                // they were invisible in every count: 1,458 notes in, 1,338 cards out, and a
                // summary that explained the gap as "1 duplicates merged" (#96).
                droppedNoteCount = apkg?.dropped?.total ?: 0,
                imagesSkippedCount = apkg?.imagesSkipped ?: 0,
                fields = apkg?.let { ApkgFields(it.fieldNames, it.mapping, it.fieldSamples) },
                // Sampled from the kept rows, not all of them: showing a card that is about to be
                // skipped is the one sample guaranteed to mislead.
                sample = kept.take(SAMPLE_SIZE).map { row ->
                    val (front, back) = draft.frontBackOf(row)
                    SampleCard(
                        front = front,
                        back = back,
                        // An Anki `Basic` note routinely puts nothing but an `<img>` in a field.
                        // Without this the sample drew a wordless side as an empty box, so the
                        // one thing the preview is for — did my deck come across? — went unanswered
                        // precisely on the decks where the answer was least obvious.
                        hasFrontImage = importRepository.rowImage(row.index, isFront = true) != null,
                        hasBackImage = importRepository.rowImage(row.index, isFront = false) != null,
                    )
                },
            )
        }
    }

    /**
     * What a failed *read* should tell the user. This used to be one constant per call site, so
     * every `.apkg` failure — a zstd collection, a corrupt zip, an export holding only Anki's
     * compatibility stub — surfaced as "Loopky can't open this .apkg" and sent the user looking for
     * a format problem they might not have. The reader names its own reason now.
     */
    private fun readErrorFor(err: Throwable): BulkImportError = when ((err as? ApkgException)?.reason) {
        ApkgFailure.UnsupportedFormat -> BulkImportError.UnsupportedApkg
        ApkgFailure.LegacyStubOnly -> BulkImportError.LegacyStubOnly
        ApkgFailure.Unreadable, null -> BulkImportError.Unreadable
    }

    /**
     * The deck title to prefill the commit screen with. The `.apkg`'s own deck name wins over the
     * file name: "Japanese Core 2000" is what the user calls this deck,
     * "japanese_core_2000_step_01.apkg" is what their file manager calls it. Capped at
     * [PublishDeckViewModel.TITLE_MAX_LENGTH] so the commit screen never opens already invalid.
     */
    internal fun suggestedTitleFor(deckName: String?, fileName: String): String? =
        deckName?.trim()?.takeIf { it.isNotBlank() }?.take(PublishDeckViewModel.TITLE_MAX_LENGTH)
            ?: fileName.substringBeforeLast('.')
                .replace('_', ' ')
                .replace('-', ' ')
                .split(' ')
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .takeIf { it.isNotBlank() }
                ?.take(PublishDeckViewModel.TITLE_MAX_LENGTH)

    /**
     * The deck description to prefill the commit screen with, or null.
     *
     * Every deck exported from AnkiWeb carries the same "Please see the shared deck page for more
     * info." — true of the AnkiWeb page and meaningless once the deck is a Loopky deck. Importing
     * it gives every deck the same description and buries the fact that the field is the user's to
     * write. Matched on the normalised text, since the field arrives as HTML and different Anki
     * versions punctuate it differently.
     */
    internal fun suggestedDescriptionFor(description: String?): String? =
        description?.takeIf { raw ->
            val normalised = raw.trim().lowercase().trimEnd('.', '!', ' ')
            normalised.isNotEmpty() && ANKI_BOILERPLATE_DESCRIPTIONS.none { it == normalised }
        }

    /** The platform layer could not turn the picked uri into text. */
    fun onFileReadFailed(reason: BulkImportError) {
        parseJob?.cancel()
        Log.e(TAG, "file read: FAILED — $reason")
        _state.update { BulkImportUiState.Error(reason) }
    }

    /** Reading the picked file's bytes, before its contents — or even its name — are known. */
    fun onFileReadStarted() {
        parseJob?.cancel()
        _state.update { BulkImportUiState.Reading }
    }

    /** Back to the picker without leaving the screen, so changing your mind isn't a restart. */
    fun onPickAnother() {
        parseJob?.cancel()
        apkgPath = null
        _state.update { BulkImportUiState.Idle }
    }

    /**
     * Re-parse the file the user already picked with an explicit separator. The summary showed what
     * the parser decided and gave no way to disagree, so a misdetected file could only be fixed by
     * editing it outside the app. Re-passes the suggested title, or the prefill would be lost.
     */
    fun onSeparatorOverride(separator: Separator) {
        val draft = importRepository.currentDraft() ?: return
        val fileName = (_state.value as? BulkImportUiState.Ready)?.fileName ?: return
        parseJob?.cancel()
        parseJob = viewModelScope.launch {
            _state.update { BulkImportUiState.Parsing(fileName) }
            importRepository
                .parseBulk(draft.rawText, separator, draft.suggestedTitle)
                .onSuccess { emitReady(fileName, it) }
                .onFailure { err ->
                    Log.e(TAG, "separator override: FAILED — ${err.message}", err)
                    _state.update { BulkImportUiState.Error(BulkImportError.NoCardsFound) }
                }
        }
    }

    /** One confirmation for the whole file — the point of the summary. */
    fun onConfirm() {
        _state.value as? BulkImportUiState.Ready ?: return
        viewModelScope.launch { _effects.emit(BulkImportEffect.Continue) }
    }

    fun onCancel() {
        importRepository.clear()
        viewModelScope.launch { _effects.emit(BulkImportEffect.NavigateBack) }
    }

    companion object {
        private const val TAG = "Loopky/BulkImportVM"

        /** Enough to see the parse worked without pretending the user reviews them all. */
        private const val SAMPLE_SIZE = 3

        /**
         * Descriptions Anki writes for you, normalised (lowercased, trailing punctuation
         * stripped). These describe the AnkiWeb listing, not the deck, so they import as nothing.
         */
        private val ANKI_BOILERPLATE_DESCRIPTIONS = setOf(
            "please see the shared deck page for more info",
            "please see the shared deck page for more information",
            "see the shared deck page for more info",
        )
    }
}

sealed interface BulkImportUiState {
    data object Idle : BulkImportUiState

    /**
     * Pulling the bytes off the provider. Separate from [Parsing] because the file's real name
     * comes from a `DISPLAY_NAME` query and so isn't known until the read is under way.
     */
    data object Reading : BulkImportUiState

    data class Parsing(val fileName: String) : BulkImportUiState

    data class Ready(
        val fileName: String,
        val separator: Separator,
        val cardCount: Int,
        /** Rows missing a front or a back; they are dropped rather than queued for editing. */
        val skippedCount: Int,
        val duplicatesCollapsed: Int,
        /** Rows past the parser's cap. Reported rather than dropped silently. */
        val truncatedCount: Int,
        /** Notes the `.apkg` reader never turned into rows, with nowhere else to be reported. */
        val droppedNoteCount: Int = 0,
        /** Pictures left behind at the importer's per-deck ceiling. */
        val imagesSkippedCount: Int = 0,
        /** The `.apkg`'s field names and current mapping; null for a text file, which has none. */
        val fields: ApkgFields? = null,
        val sample: List<SampleCard>,
    ) : BulkImportUiState {
        val canImport: Boolean get() = cardCount > 0
    }

    data class Error(val reason: BulkImportError) : BulkImportUiState
}

/**
 * Why an import failed, in terms the user can act on.
 *
 * A single message string collapsed "you picked a photo", "the file wouldn't open" and "the parser
 * found no cards" into one line — usually a parser message shown to someone who picked the wrong
 * file. Deliberately not `ErrorReason`: every member of that is network/session/auth, and extending
 * it would force edits to exhaustive `when`s eight unrelated screens depend on.
 */
enum class BulkImportError {
    /** The provider wouldn't open it, or the read failed part-way. */
    Unreadable,

    /** Past [the picker's memory backstop]; the user should export without media. */
    TooLarge,

    /** Not UTF-8 text — a photo or a PDF, most often. */
    NotText,

    /** A real `.apkg`, but one this build can't unpack (zstd `collection.anki21b`). */
    UnsupportedApkg,

    /**
     * The `.apkg` holds only the legacy compatibility stub a modern Anki ships alongside the real
     * collection. Distinct from [NoCardsFound]: the file is fine, the deck is just not in the part
     * of it this build can read, and "no cards" would send the user hunting through their deck.
     */
    LegacyStubOnly,

    /** Read and parsed fine; there was simply nothing card-shaped in it. */
    NoCardsFound,

    Unknown,
}

data class SampleCard(
    val front: String,
    val back: String,
    val hasFrontImage: Boolean = false,
    val hasBackImage: Boolean = false,
)

/** What the field picker needs: the note type's field names, and which two are in use. */
data class ApkgFields(
    val names: List<String>,
    val mapping: ApkgFieldMapping,
    /** One real value per field, parallel to [names]. Empty when the source had none to give. */
    val samples: List<String> = emptyList(),
) {
    val frontName: String get() = names.getOrNull(mapping.frontOrd).orEmpty()
    val backName: String get() = names.getOrNull(mapping.backOrd).orEmpty()

    /** What field [ord] actually holds, for the picker to show under its name. */
    fun sampleAt(ord: Int): String? = samples.getOrNull(ord)?.takeIf { it.isNotBlank() }

    /** Worth offering a choice only when there is more than one pair to choose between. */
    val canChoose: Boolean get() = names.size > 2
}

/** What a picked file yielded: its text, plus the deck name if the source knew one. */
private data class LoadedFile(val text: String, val deckName: String? = null)

/** Marks a failure as coming from reading the file rather than from parsing its contents. */
private class FailedToLoad(val reason: BulkImportError, cause: Throwable) : Exception(cause)

sealed interface BulkImportEffect {
    /**
     * Proceed to the shared commit screen. Carries no payload: the suggested title travels on the
     * draft, alongside every other part of this handoff.
     */
    data object Continue : BulkImportEffect

    data object NavigateBack : BulkImportEffect
}
