package com.github.jvsena42.loopky.cli

/**
 * Refuse an operand that cannot become a homeserver path segment, before it becomes one.
 *
 * The failure this exists for: `loopky card list ""` put the empty string into
 * `pubky://…/decks//manifest.json` and came back as **`internal` / exit 1** carrying the URI
 * parser's complaint — "path contains empty segment ('//')". That is the worst answer available.
 * Exit 1 reads as transient, so an agent retries an input that can never succeed, forever; and a
 * message about a URI points the diagnosis at the network or the server. `deck show doesnotexist1`
 * already answers `not_found` cleanly, so nothing was missing but the check.
 *
 * Same shape as [SupportedHost]: refuse *before* the failure can be misclassified, rather than
 * teaching a classifier to recognise it afterwards.
 *
 * **Deliberately narrow.** Ids here are [com.github.jvsena42.loopky.util.generateId]'s twelve
 * lowercase alphanumerics, so a charset allowlist would fit — and would refuse an id shape a
 * future release mints, from a binary that cannot be upgraded in the sandbox it is running in.
 * What is rejected is only what genuinely cannot survive a path segment: nothing, whitespace,
 * a separator, a URI delimiter, and the two relative segments.
 */
internal fun requireUsableOperand(value: String, name: String): String {
    val complaint = when {
        value.isBlank() ->
            "is empty. That can never address a record, so it is refused here rather than sent"

        value == "." || value == ".." -> "cannot be '$value' — that is a relative path, not an id"

        else -> UNUSABLE_IN_SEGMENT.find(value)?.let {
            "'$value' contains ${describe(it.value)}, which cannot appear in a record path"
        }
    } ?: return value
    throw CliError(
        ExitCode.BadInput,
        "<$name> $complaint. The ids to use are the ones `loopky deck list --json` and " +
            "`loopky card list --json` report.",
    )
}

/** Whitespace, control characters, a path separator, and the delimiters that end a URI path. */
private val UNUSABLE_IN_SEGMENT = Regex("""[\p{Cntrl}\s/\\?#]""")

private fun describe(char: String): String = when {
    char.isBlank() -> "whitespace"
    char.length == 1 && char[0].isISOControl() -> "a control character"
    else -> "'$char'"
}
