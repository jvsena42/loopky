package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.data.repository.MediaRepository
import com.github.jvsena42.loopky.domain.model.MediaRef

/**
 * Take back the blobs an aborted publish already wrote.
 *
 * By hand rather than through `DeckRepository.delete`, which walks a manifest — and there is none
 * yet, because media goes up before `publish` writes one. Best-effort and never fatal: the import
 * quite plausibly aborted because storage ran out, and failing the failure replaces a useful error
 * with a useless one.
 */
internal suspend fun sweepUploadedMedia(
    media: MediaRepository,
    deckId: String,
    uploaded: List<MediaRef>,
    onNote: (String) -> Unit,
) {
    if (uploaded.isEmpty()) return
    var failed = 0
    uploaded.forEach { ref -> media.delete(deckId, ref).onFailure { failed++ } }
    if (failed > 0) {
        onNote("$failed of ${uploaded.size} pictures this run uploaded could not be removed again.")
    }
}
