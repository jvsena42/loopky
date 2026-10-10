package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.domain.model.LanguageTags
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.domain.model.TagLabels
import kotlinx.serialization.Serializable

/**
 * The tags a deck carries once its declared languages have contributed theirs: what `--tag` asked
 * for, plus `"spanish"` and the `"language"` umbrella for a deck typed as English-to-Spanish.
 *
 * Deriving them is the whole reason a pair is worth declaring beyond the audio (#225). A tag
 * record is the only thing Loopky publishes that a network-wide index can answer questions about
 * (Architecture.md §7.7), so a deck that *says* it is Japanese in its manifest and carries no
 * label is invisible to tag browse, to `tag trending` and to anyone on Nexus looking for Japanese
 * decks — while the byte-identical deck published from a phone is not, because both ViewModels
 * route a language pick through [LanguageTags.retag]. Nothing reported the difference: the deck
 * published, `--json` said ok, and the only symptom was a search that came back empty somewhere
 * else entirely.
 *
 * **A deck that declares no pair gets nothing**, umbrella included — most decks are not language
 * decks, and `LanguageTags.forPair` is what keeps `"language"` off a deck of capital cities.
 *
 * The labels are ordinary author-removable tags rather than a reserved family, which is why
 * `deck edit --tag` is allowed to replace the set and drop them (see `editedTags`).
 *
 * [LanguageTags.retag] rather than `forPair` even here, where there is no previous pair to drop:
 * one function across create, import and edit is one dedupe and one ordering rule, and a
 * hand-typed `--tag language` beside a declared pair must not become two chips.
 */
internal fun deckTags(requested: List<String>, frontLang: String?, backLang: String?): List<Tag> =
    LanguageTags.retag(requested.normalizedTags(), null, null, frontLang, backLang).map(::Tag)

/**
 * `--tag` as it is stored: folded by [TagLabels] (#479), blanks dropped, first occurrence wins.
 *
 * A reserved or over-long label is refused rather than dropped the way the apps drop one: an agent
 * that asked for five tags and got four with exit 0 has no reason to look, and the indexer rejects
 * a label past [TagLabels.MAX_LENGTH] without telling anyone.
 */
internal fun List<String>.normalizedTags(): List<String> =
    mapNotNull { raw ->
        val label = TagLabels.fold(raw)
        when {
            label.isEmpty() -> null
            ReservedTags.isReserved(label) -> throw CliError(
                ExitCode.BadInput,
                "--tag $raw: the ${ReservedTags.PREFIX} prefix is reserved for Loopky's own index labels.",
            )
            label.length > TagLabels.MAX_LENGTH -> throw CliError(
                ExitCode.BadInput,
                "--tag $raw is ${label.length} characters once stored as \"$label\"; a tag holds at most " +
                    "${TagLabels.MAX_LENGTH}.",
            )
            else -> label
        }
    }.distinct()

/**
 * One label that was not stored the way it was written. `--json` is how an agent checks what it
 * published, so a `--tag café` that became `cafe` is said rather than left to a later `deck show`.
 */
@Serializable
data class TagFold(val from: String, val to: String)

internal fun List<String>.tagFolds(): List<TagFold> =
    map(String::trim)
        .mapNotNull { raw -> TagLabels.fold(raw).takeIf { it.isNotEmpty() && it != raw }?.let { TagFold(raw, it) } }
        .distinct()

/** `--tag` checked and reported in one step, for the commands that validate before they read a file. */
internal fun Args.requestedTagFolds(): List<TagFold> = options("tag").also { it.normalizedTags() }.tagFolds()

internal fun List<TagFold>.describe(): String =
    if (isEmpty()) "" else "\nTags stored as: " + joinToString(", ") { "${it.from} -> ${it.to}" }
