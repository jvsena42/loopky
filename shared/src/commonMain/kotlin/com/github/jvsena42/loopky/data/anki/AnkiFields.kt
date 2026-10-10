package com.github.jvsena42.loopky.data.anki

import com.github.jvsena42.loopky.domain.model.TagLabels

/**
 * Choosing the two fields to import, when nobody has said which.
 *
 * "The first two fields" is only right for the two-field note types. Real decks routinely put ids,
 * ranks or pictures first, and the importer offered them as cards without a word: 9000 Spanish
 * Sentences imported 9,213 cards reading `2528426` → `2760065`, because its first two fields are
 * `EnglishSentenceID` and `SpanishSentenceID` and the sentences are fields 2 and 3 (#96).
 *
 * So fields are scored on what is actually in them across a sample of notes, and the first two that
 * look like something a person would read win. The user can still override this — the heuristic
 * decides the default, not the outcome.
 */
internal fun chooseDefaultFields(notes: List<List<AnkiField>>, fieldCount: Int): ApkgFieldMapping {
    if (fieldCount <= 2) return ApkgFieldMapping(FIRST_FIELD, SECOND_FIELD)
    val sample = notes.take(SAMPLE_NOTES)
    if (sample.isEmpty()) return ApkgFieldMapping(FIRST_FIELD, SECOND_FIELD)

    val readable = (0 until fieldCount).filter { ord -> sample.isReadableProse(ord) }
    val usable = (0 until fieldCount).filter { ord -> sample.isPresent(ord) }

    // A picture field is a fine answer and a poor question, so an image-only field can be the back
    // but never the front. Spanish Top 5000's field 1 is `Picture`; its front must not be.
    val front = readable.firstOrNull() ?: usable.firstOrNull() ?: FIRST_FIELD
    val back = readable.firstOrNull { it != front }
        ?: usable.firstOrNull { it != front }
        ?: (if (front == FIRST_FIELD) SECOND_FIELD else FIRST_FIELD)
    return ApkgFieldMapping(frontOrd = front, backOrd = back)
}

/** Filled in on most notes — by text or by picture. An empty column is nobody's card side. */
private fun List<List<AnkiField>>.isPresent(ord: Int): Boolean =
    count { it.getOrNull(ord)?.isEmpty == false } >= size * MIN_FILLED_RATIO

/**
 * Filled in with **text a person would read**, rather than an identifier.
 *
 * The identifier test is deliberately narrow — digits, separators and nothing else. Plenty of real
 * card fronts are short ("Y qué?", "水"), and a length or word-count rule would throw those away
 * along with the ids.
 */
private fun List<List<AnkiField>>.isReadableProse(ord: Int): Boolean {
    val values = mapNotNull { it.getOrNull(ord)?.text?.takeIf { text -> text.isNotBlank() } }
    if (values.size < size * MIN_FILLED_RATIO) return false
    val identifiers = values.count { IDENTIFIER.matches(it) }
    return identifiers < values.size * MAX_IDENTIFIER_RATIO
}

/**
 * Deck tags suggested from the notes' own tags.
 *
 * Anki has no deck-level tags — tags there are per note, space-separated, with `::` hierarchy — so
 * this is a suggestion for the commit screen's chips rather than an import. The most-used labels
 * win, since a tag on three notes out of 1,400 describes those notes and not the deck.
 *
 * Labels that cannot survive [TagLabels.normalize] are dropped here rather than offered and then
 * rejected at publish.
 */
internal fun suggestDeckTags(noteTags: List<String>, limit: Int = MAX_SUGGESTED_TAGS): List<String> =
    noteTags
        .flatMap { it.split(' ', '\t') }
        .mapNotNull { it.toDeckTagLabel() }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(limit)
        .map { it.key }

private fun String.toDeckTagLabel(): String? =
    TagLabels.normalize(substringAfterLast(TAG_HIERARCHY))

private const val TAG_HIERARCHY = "::"

private const val MAX_SUGGESTED_TAGS = 5
private const val FIRST_FIELD = 0
private const val SECOND_FIELD = 1

/** Enough notes to tell an id column from a sentence column, cheap enough to scan up front. */
private const val SAMPLE_NOTES = 200
private const val MIN_FILLED_RATIO = 0.5
private const val MAX_IDENTIFIER_RATIO = 0.2

private val IDENTIFIER = Regex("[\\d.,\\s\\-_#]+")
