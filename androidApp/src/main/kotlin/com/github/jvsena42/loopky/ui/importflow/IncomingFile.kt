package com.github.jvsena42.loopky.ui.importflow

import com.github.jvsena42.loopky.data.anki.ApkgReader
import com.github.jvsena42.loopky.presentation.importflow.BulkImportError
import com.github.jvsena42.loopky.presentation.importflow.BulkImportViewModel

/**
 * A deck file handed to Loopky from outside — *Open with*, or the share sheet — on its way to
 * the bulk import screen.
 *
 * It carries a spooled [PickedFile] rather than the `Uri` it arrived as. The grant on a `VIEW`
 * or `SEND` uri is one-shot and scoped to the activity: it is not persistable
 * (`takePersistableUriPermission` only applies to an `OpenDocument` result) and does not survive
 * process death. Since a cold start holds the file until the user is past onboarding — minutes,
 * on a signed-out device — reading it later would throw `SecurityException`. So it is copied to
 * our own cache the moment it arrives, and what travels is a path we own.
 */
internal sealed interface IncomingFile {
    /** The copy is under way. Held so the screen can show its own progress rather than nothing. */
    data object Reading : IncomingFile

    data class Ready(val file: PickedFile) : IncomingFile

    data class Failed(val reason: BulkImportError) : IncomingFile
}

/**
 * Hands a spooled file to [BulkImportViewModel], and returns the spool the caller must keep
 * alive — or null when the file is fully in hand and the spool has already been deleted.
 *
 * The one place that decides `.apkg` versus text, shared by the file picker and by a file the
 * system handed us. A second copy of this branch would drift from the first.
 */
internal fun BulkImportViewModel.acceptPickedFile(read: Result<PickedFile>): PickedFile? = read.fold(
    onSuccess = { file ->
        // Sniffed from the content, not the extension or the declared MIME: both are advisory,
        // and a picked .apkg often arrives with a content:// uri carrying no useful name at all.
        if (ApkgReader.canRead(file.header)) {
            onApkgLoaded(file.name, file.path)
            // Only the .apkg reader keeps reading from the spool, so only it needs the file kept.
            file
        } else {
            file.readAsText()
                .onSuccess { onFileLoaded(file.name, it) }
                .onFailure { err ->
                    onFileReadFailed((err as? FileReadException)?.reason ?: BulkImportError.NotText)
                }
            file.delete()
            null
        }
    },
    onFailure = { err ->
        onFileReadFailed((err as? FileReadException)?.reason ?: BulkImportError.Unreadable)
        null
    },
)
