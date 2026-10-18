package com.github.jvsena42.loopky.domain.model

/**
 * The ordinary tag a declared deck language contributes — `"spanish"` for a side written in
 * `es-ES`.
 *
 * A deck that knows it is English-to-Spanish should be findable by someone learning Spanish, and
 * tag records are the only thing Loopky publishes that a network-wide index can answer questions
 * about (issue #40). These are **user tags, not a reserved family**: they live in [Deck.tags]
 * alongside the author's own labels, are written by the same tag sync, and can be removed by the
 * author like any other. Being ordinary is what lets them trend and appear in tag browse, which a
 * `loopky-` label cannot (Architecture.md §7.7 point 3).
 *
 * Two rules the labels follow:
 *
 * - **Base subtag only.** `es-ES` and `es-MX` are both `"spanish"`. The region picks a voice;
 *   splitting the label by it would halve a search for Spanish decks.
 * - **Named, not coded.** `"spanish"` rather than `es`, because the label is shown to people as a
 *   chip and searched for as a word. Codes the table does not know fall back to the subtag itself
 *   rather than going untagged; a code that is not a language subtag at all contributes nothing.
 * - **[UMBRELLA] rides along.** A language deck is also a `"language"` deck, so the pair earns the
 *   category label too — someone browsing for language decks at all should not have to guess which
 *   of seventy language labels to open first.
 */
object LanguageTags {

    /**
     * The umbrella label every deck that declares a language carries, on top of the languages
     * themselves — the shelf a browser lands on before knowing which language they want.
     */
    val UMBRELLA = Tag("language")

    /**
     * Labels for a deck declaring [frontLang]/[backLang] (BCP-47): [UMBRELLA] and then one per
     * language in declaration order, deduplicated — a Spanish-to-Spanish deck contributes one
     * language label, not two. Empty for a deck that has declared nothing, which is what keeps
     * [UMBRELLA] off a deck with no pair.
     */
    fun forPair(frontLang: String?, backLang: String?): List<Tag> {
        val languages = listOfNotNull(frontLang, backLang)
            .map(::baseSubtag)
            .filter { it.matches(BASE_SUBTAG) }
            .mapNotNull { TagLabels.normalize(nameOf(it)) }
            .distinct()
            .map(::Tag)
        return if (languages.isEmpty()) emptyList() else listOf(UMBRELLA) + languages
    }

    /**
     * [tags] with the labels the old pair contributed swapped for the new pair's, keeping the
     * order of everything else.
     *
     * The drop is the half that is easy to miss: retyping a deck from Spanish to French has to
     * take `"spanish"` off it, or the deck stays listed as Spanish forever. A label the author
     * happened to type by hand is indistinguishable from a derived one, so switching away from
     * Spanish does remove a hand-typed `"spanish"` — the author can type it back, whereas an
     * un-droppable label could never be corrected.
     */
    fun retag(
        tags: List<String>,
        previousFrontLang: String?,
        previousBackLang: String?,
        frontLang: String?,
        backLang: String?,
    ): List<String> {
        val previous = (forPair(previousFrontLang, previousBackLang).map { it.value } +
            legacyLabels(previousFrontLang, previousBackLang)).toSet()
        val current = forPair(frontLang, backLang).map { it.value }
        val kept = tags.filter { it !in previous || it in current }
        return kept + current.filterNot { it in kept }
    }

    /** English name of a base language subtag, or the subtag itself when it is not in [NAMES]. */
    fun nameOf(code: String): String {
        val subtag = baseSubtag(code)
        return NAMES[subtag] ?: subtag
    }

    /** `_` too: `pt_BR` is what Java and POSIX locales print, so agents pass it (#534). */
    private fun baseSubtag(code: String): String = code.trim().substringBefore('-').substringBefore('_').lowercase()

    /**
     * What [forPair] stored before #534 — the code up to its first `-`, unvalidated, folded on
     * the way to the homeserver — so a deck tagged `pt-br` by `pt_BR` sheds it on the next edit.
     */
    private fun legacyLabels(frontLang: String?, backLang: String?): List<String> =
        listOfNotNull(frontLang, backLang)
            .map { it.substringBefore('-').trim().lowercase() }
            .map { TagLabels.fold(NAMES[it] ?: it) }
            .filter { it.isNotEmpty() }

    /** A BCP-47 language subtag; anything else declares no language a tag could name. */
    private val BASE_SUBTAG = Regex("[a-z]{2,8}")

    /**
     * Base subtag → English name. Covers [SpeechLanguages.COMMON] plus the languages a TTS engine
     * is likely to add to it; anything else tags under its own subtag, which is worse to read but
     * still findable. Legacy ISO codes (`iw`, `in`, `ji`) are here because older Android locales
     * still hand them out.
     */
    private val NAMES: Map<String, String> = mapOf(
        "af" to "afrikaans", "ar" to "arabic", "bg" to "bulgarian", "bn" to "bengali",
        "bs" to "bosnian", "ca" to "catalan", "cs" to "czech", "cy" to "welsh", "da" to "danish",
        "de" to "german", "el" to "greek", "en" to "english", "es" to "spanish",
        "et" to "estonian", "eu" to "basque", "fa" to "persian", "fi" to "finnish",
        "fil" to "filipino", "fr" to "french", "ga" to "irish", "gl" to "galician",
        "gu" to "gujarati", "he" to "hebrew", "hi" to "hindi", "hr" to "croatian",
        "hu" to "hungarian", "hy" to "armenian", "id" to "indonesian", "in" to "indonesian",
        "is" to "icelandic", "it" to "italian", "iw" to "hebrew", "ja" to "japanese",
        "ji" to "yiddish", "jv" to "javanese", "ka" to "georgian", "kk" to "kazakh",
        "km" to "khmer", "kn" to "kannada", "ko" to "korean", "lo" to "lao", "lt" to "lithuanian",
        "lv" to "latvian", "mk" to "macedonian", "ml" to "malayalam", "mn" to "mongolian",
        "mr" to "marathi", "ms" to "malay", "my" to "burmese", "nb" to "norwegian",
        "ne" to "nepali", "nl" to "dutch", "nn" to "norwegian", "no" to "norwegian",
        "pa" to "punjabi", "pl" to "polish", "pt" to "portuguese", "ro" to "romanian",
        "ru" to "russian", "si" to "sinhala", "sk" to "slovak", "sl" to "slovenian",
        "sq" to "albanian", "sr" to "serbian", "su" to "sundanese", "sv" to "swedish",
        "sw" to "swahili", "ta" to "tamil", "te" to "telugu", "th" to "thai", "tl" to "filipino",
        "tr" to "turkish", "uk" to "ukrainian", "ur" to "urdu", "uz" to "uzbek",
        "vi" to "vietnamese", "yi" to "yiddish", "zh" to "chinese", "zu" to "zulu",
    )
}
