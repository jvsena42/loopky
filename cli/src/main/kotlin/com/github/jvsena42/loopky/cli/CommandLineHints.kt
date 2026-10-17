package com.github.jvsena42.loopky.cli

/**
 * What a terminal says about the command table: the near-miss hint for a mistyped verb, and the
 * groupings the completion generators walk. The table itself is `:cli-core`'s `CommandSurface.kt`;
 * this is the half that only means anything to somebody typing.
 */

/** `dispatch`'s refusal of a verb the table does not have: still one line, now naming the near miss. */
internal fun Args.unknownCommandMessage(): String =
    listOfNotNull("Unknown command '$verb'.", nearMissHint(), "Try `loopky --help`.").joinToString(" ")

/** How many edits a mistyped command may be from a real one and still be named. */
private const val NEAR_MISS_DISTANCE = 2

/**
 * What to say about a verb the table does not have: the one command it is a near miss of, or
 * failing that the verbs of the group it names, or null (#293).
 *
 * **Only an unambiguous guess is offered.** Something acting on the text runs what it is told, so a
 * wrong guess costs more than none: `card ed` is as close to `card add` as to `card edit`, and gets
 * the group's verbs rather than either. The group fallback is also what catches a synonym —
 * `card delete` is nowhere near `card rm` by edits, and guessing `deck delete` would be the worst
 * answer available. Matched against full paths, and against the first two words as well as the
 * verb, so `dek list` reaches `deck list` although its verb parses as `dek`.
 */
internal fun Args.nearMissHint(): String? {
    val probes = listOfNotNull(verb, words.take(2).takeIf { it.size == 2 }?.joinToString(" "))
        .map { it.lowercase() }
    val near = cliCommands().map { it.path }
        .filter { path -> probes.any { editDistance(it, path) <= NEAR_MISS_DISTANCE } }
    near.singleOrNull()?.let { return "Did you mean `$it`?" }

    val noun = words.firstOrNull()?.lowercase() ?: return null
    val group = commandGroups()[noun] ?: return null
    return "`$noun` takes one of: ${group.joinToString(", ") { it.path.substringAfter(' ') }}."
}

private fun editDistance(a: String, b: String): Int {
    var previous = IntArray(b.length + 1) { it }
    for (i in a.indices) {
        val current = IntArray(b.length + 1)
        current[0] = i + 1
        for (j in b.indices) {
            val substitution = previous[j] + if (a[i] == b[j]) 0 else 1
            current[j + 1] = minOf(substitution, previous[j + 1] + 1, current[j] + 1)
        }
        previous = current
    }
    return previous[b.length]
}

/** The group nouns — `deck`, `card`, `tag` — mapped to the verbs that follow them. */
internal fun commandGroups(): Map<String, List<CliCommand>> = cliCommands()
    .filter { " " in it.path }
    .groupBy { it.path.substringBefore(' ') }

/** What a group noun on its own means, since it has no command of its own to describe it. */
private val GROUP_SUMMARIES = mapOf(
    "deck" to "create, read and edit decks",
    "card" to "add, edit and remove cards",
    "tag" to "read the public tag index",
)

/** The first word of every command, in the order they are listed: group nouns and one-word verbs. */
internal fun topLevelWords(): List<Pair<String, String>> {
    val groups = commandGroups()
    return cliCommands()
        .map { it.path.substringBefore(' ') }
        .distinct()
        .map { head ->
            head to (
                if (head in groups) {
                    GROUP_SUMMARIES.getValue(head)
                } else {
                    cliCommands().first { it.path == head }.summary
                }
                )
        }
}

/** Every option that consumes the token after it, which is what a completion has to know. */
internal fun valueTakingOptions(): List<String> =
    (cliCommands().flatMap { it.options } + GLOBAL_OPTIONS)
        .filter { it.value != OptionValue.Switch }
        .map { it.name }
        .distinct()
        .sorted()
