package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.SpeechLanguages

/**
 * Refuse `--listen` or `--speak` on a deck that has not declared both languages.
 *
 * `Deck.speechReady` gates both features on the pair, so such a deck published with exit 0 and
 * the two modes simply never appeared on a phone — the one case in this client where success was
 * reported for a deck that does not do what was asked. Both apps' editors already refuse it
 * through the same [SpeechLanguages.isPairMissing].
 */
internal fun requireSpeechPair(listen: Boolean, speak: Boolean, frontLang: String?, backLang: String?) {
    if (!SpeechLanguages.isPairMissing(listen, speak, frontLang, backLang)) return
    val modes = listOfNotNull("--listen".takeIf { listen }, "--speak".takeIf { speak }).joinToString(" and ")
    val missing = listOfNotNull("--front-lang".takeIf { frontLang == null }, "--back-lang".takeIf { backLang == null })
    throw CliError(
        ExitCode.Usage,
        "$modes cannot work without the language of both sides, and ${missing.joinToString(" and ")} is not set. " +
            "Without the pair the phone would read the cards in its own language's voice, so the modes " +
            "stay hidden. Pass BCP-47 tags such as --front-lang en-US --back-lang es-ES, or drop $modes.",
    )
}

/**
 * [requireSpeechPair] for a command that changes a deck that already exists, where only a change
 * **this invocation** makes is refused. A deck published before the pair existed carries the
 * opt-ins and no languages, and renaming it, or resuming an import into it, must still work.
 */
internal fun Args.requireSpeechPairAfter(before: Deck, listen: Boolean, speak: Boolean, frontLang: String?, backLang: String?) {
    val wasMissing = SpeechLanguages.isPairMissing(before.listenEnabled, before.speakEnabled, before.frontLang, before.backLang)
    if (has("listen") || has("speak") || !wasMissing) requireSpeechPair(listen, speak, frontLang, backLang)
}

/**
 * Notes about `--front-lang`/`--back-lang` values the speech engines are unlikely to honour.
 *
 * Advice, not a refusal: [SpeechLanguages.COMMON] is the pickers' fallback list rather than
 * everything an engine can voice, so an unlisted tag may be right.
 */
internal fun languageAdvice(frontLang: String?, backLang: String?): List<String> =
    listOfNotNull(
        frontLang?.let { adviceFor("--front-lang", it) },
        backLang?.let { adviceFor("--back-lang", it) },
    )

private fun adviceFor(flag: String, tag: String): String? {
    if (SpeechLanguages.COMMON.any { it.equals(tag, ignoreCase = true) }) return null
    val sameLanguage = SpeechLanguages.COMMON.filter { it.substringBefore('-').equals(tag.substringBefore('-'), ignoreCase = true) }
    return when {
        '-' !in tag && sameLanguage.isNotEmpty() ->
            "$flag $tag names no region, and a voice is chosen by one: use ${sameLanguage.joinToString(" or ")}."

        sameLanguage.isNotEmpty() ->
            "$flag $tag is not a locale Loopky's language pickers offer (${sameLanguage.joinToString(", ")} are). " +
                "It is stored as given; a phone without that voice falls back to another."

        else ->
            "$flag $tag is not a locale Loopky's language pickers offer. It is stored as given; check it is " +
                "a BCP-47 tag such as en-US."
    }
}

internal fun List<String>.reportLanguageAdvice(onNote: (String) -> Unit) = forEach { onNote("loopky: $it") }
