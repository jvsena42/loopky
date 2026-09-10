package com.github.jvsena42.loopky.cli

/**
 * The command surface as data, so a shell can be told about it.
 *
 * `loopky completion bash|zsh|fish` generates its script from this table and nothing else. That
 * is the whole reason the table exists rather than three checked-in scripts: a completion that
 * has drifted from the binary is worse than none — it offers a flag that is refused and hides one
 * that works, and nothing reports either. `CompletionTest` holds it against [Args] and the usage
 * block, so a new flag that is not described here fails the build.
 *
 * **Nothing here reaches the network, and that is a constraint rather than an omission.** The
 * obvious next feature is completing a deck id after `deck show`, and it would mean a homeserver
 * round trip on a keypress — a tab that hangs for a second, or forever on a dead session, in the
 * one place a user cannot interrupt without losing the line they were typing. Deck ids are
 * [Operand.Opaque]: the shell says what the word *is* and offers nothing.
 */
internal sealed interface OptionValue {

    /**
     * Takes nothing.
     *
     * Every one of these must also be in [Args.SWITCHES] — an option the parser does not know is
     * a switch consumes the next token as its value, so completing `--listen` before a deck id
     * would produce a command that silently reads the id as a language tag.
     */
    data object Switch : OptionValue

    /** Takes a value nothing local can guess: a title, a tag, a card front. */
    data object Text : OptionValue

    /** Takes a path. */
    data object Path : OptionValue

    /** Takes one of a closed set, which is the case worth completing. */
    data class OneOf(val choices: List<String>) : OptionValue
}

/** A `--long` option. There is no short form anywhere in this CLI; see [Args]. */
internal data class CliOption(
    val name: String,
    val summary: String,
    val value: OptionValue = OptionValue.Text,
)

/** What the non-option words after a command are. */
internal sealed interface Operand {

    data object None : Operand

    /** A file, so the shell completes paths. `import`, and nothing else. */
    data object Path : Operand

    /** A closed set of words. [name] is what the operand is called when it is shown. */
    data class OneOf(val name: String, val choices: List<String>) : Operand

    /**
     * Deck and card ids: known to the homeserver, unknowable here. The names are shown as a hint.
     *
     * A list rather than one string because arity is part of the surface — `card edit` takes two
     * words and the second is optional, which a machine-readable surface has to be able to say
     * (#240, finding 4) and `"deckId cardId"` could only imply.
     */
    data class Opaque(val required: List<String>, val optional: List<String> = emptyList()) : Operand {
        constructor(vararg required: String) : this(required.toList())

        val names: List<String> get() = required + optional
    }
}

/**
 * One command. [path] is exactly [Args.verb] — one word, or a group noun plus its verb.
 *
 * [needsSession], [writes] and [local] exist for one consumer: `loopky commands --json` derives
 * the exit codes a command can produce from them rather than listing them per command by hand
 * (see `exitCodes`). Derived, so the answer cannot drift from the table the way a hand-kept list
 * would — and useful, because "`tag trending` can never answer `session_expired`" is exactly the
 * kind of thing an agent otherwise learns by hitting it.
 */
internal data class CliCommand(
    val path: String,
    val summary: String,
    val operand: Operand = Operand.None,
    val options: List<CliOption> = emptyList(),
    /** Refuses with [ExitCode.NotSignedIn] rather than running when there is no session. */
    val needsSession: Boolean = true,
    /** Writes to the homeserver, so it can hit the quota wall. */
    val writes: Boolean = false,
    /** Runs before Koin: no FFI, no homeserver, no relay. `update`, `completion`, `commands`. */
    val local: Boolean = false,
    /** Codes this command alone can produce, which nothing about its shape implies. */
    val alsoExits: List<ExitCode> = emptyList(),
    /**
     * Exits with a *relayed* code: whatever the operation it ran exited with.
     *
     * `batch` does, and nothing about its own shape says so — it takes a path, so the derivation
     * would give it `bad_input` and stop, while `{"argv":["deck","show","nope"]}` makes it exit
     * `not_found`. Under-listing is the direction that hurts: an agent that sees a code the table
     * says is impossible has no rule for it.
     */
    val relaysExitCodes: Boolean = false,
    /**
     * Why this command cannot run inside a `batch`, or null when it can.
     *
     * Declared here rather than as a list in `Batch.kt` so the table is the one authority: `batch`
     * validates a line against it, and [relaysExitCodes] takes its union from it. A command added
     * without a thought about batching is batchable by default, which is the right default — the
     * six that are not each have a reason, and the reason is the message.
     */
    val notBatchable: String? = null,
)

/**
 * Options accepted by every command, so they are offered on every command.
 *
 * `--version` is here because it genuinely is global: [Main] answers it before it looks at a verb,
 * so `loopky deck list --version` prints the version.
 */
internal val GLOBAL_OPTIONS = listOf(
    CliOption("json", "machine-readable output on stdout", OptionValue.Switch),
    CliOption("env", "which network to talk to", OptionValue.OneOf(listOf("staging", "production"))),
    CliOption("no-update-check", "do not look for a newer release on this invocation", OptionValue.Switch),
    CliOption("verbose", "debug logging on stderr", OptionValue.Switch),
    CliOption("help", "print the usage block", OptionValue.Switch),
    CliOption("version", "print the version and the --json schema", OptionValue.Switch),
)

/**
 * Switches the parser accepts and completion deliberately never offers.
 *
 * `--yes` and `--force` are taken and ignored: nothing in this client prompts, so there is nothing
 * to confirm, and they exist only so a caller in the habit of passing one is not refused. Offering
 * them would advertise a confirmation step that does not exist — and `--force` in particular reads
 * like it overrides something. Listed rather than dropped from [Args] so the pair stays a decision
 * with a reason attached, and so `CompletionTest` can tell "not described yet" from "not offered".
 */
internal val UNOFFERED_SWITCHES = setOf("yes", "force")

private val LANGUAGE_OPTIONS = listOf(
    CliOption("front-lang", "BCP-47 tag for the front side, such as en-US"),
    CliOption("back-lang", "BCP-47 tag for the back side, such as es-ES"),
)

private val STUDY_OPT_INS = listOf(
    CliOption("listen", "read the card aloud - needs a declared language pair", OptionValue.Switch),
    CliOption("speak", "grade a spoken answer - needs a declared language pair", OptionValue.Switch),
    CliOption("type", "type the answer instead of revealing it", OptionValue.Switch),
    CliOption("reverse", "ask each card in both directions", OptionValue.Switch),
)

private val STUDY_OPT_OUTS = STUDY_OPT_INS.map {
    CliOption("no-${it.name}", "turn ${it.name} off", OptionValue.Switch)
}

/**
 * `--check-images` and the dial for how hard it asks, shared by every command that writes a card.
 *
 * The dial is not decoration: the default is deliberately low, because the check rate-limited
 * itself into 432 false findings on one Wikimedia deck (#257), and a friendlier host has no other
 * way to be asked faster.
 */
private val IMAGE_CHECK_OPTIONS = listOf(
    CliOption("check-images", "HEAD each picture URL and warn about the ones that are not images", OptionValue.Switch),
    CliOption("check-images-concurrency", "how many of those requests may be in flight, up to 16 - 3 by default"),
)

/**
 * `--dry-run`, on each of the three commands that take a file of cards.
 *
 * One list rather than a repeated literal because the three have to stay one gesture: a pre-flight
 * that exists on `import` alone sends people through the wrong parser to get it (#257, item 8).
 */
private val DRY_RUN_OPTION = listOf(
    CliOption("dry-run", "report what would be written and write nothing", OptionValue.Switch),
)

private val CARD_FIELDS = listOf(
    CliOption("front", "the front text"),
    CliOption("back", "the back text"),
    CliOption("front-image", "https URL for a picture on the front"),
    CliOption("back-image", "https URL for a picture on the back"),
    CliOption("from-file", "a TSV or JSONL card file instead of the flags above", OptionValue.Path),
) + IMAGE_CHECK_OPTIONS

private val DECK_METADATA = listOf(
    CliOption("title", "the deck title"),
    CliOption("description", "the deck description"),
    CliOption("tag", "a tag - repeat for several, and it replaces rather than appends"),
    CliOption("cover-url", "https URL for the cover image"),
    CliOption("cover-emoji", "an emoji to use as the cover"),
)

/** The separators the paste parser accepts, spelled as `--separator` takes them. */
private val SEPARATORS = listOf(
    "auto", "tab", "comma", "semicolon", "pipe", "dash", "colon", "blank", "markdown",
)

/** The shells [completion] can generate for. */
internal val COMPLETION_SHELLS = listOf("bash", "zsh", "fish")

@Suppress("LongMethod")
internal fun cliCommands(): List<CliCommand> = listOf(
    CliCommand(
        path = "login",
        summary = "print a QR code for Pubky Ring and wait for approval",
        options = listOf(
            CliOption("export", "also print the session secret, for LOOPKY_SESSION", OptionValue.Switch),
            CliOption("qr-out", "write the QR code to a file", OptionValue.Path),
            CliOption("url-only", "print the pubkyauth URL and no QR code", OptionValue.Switch),
            CliOption("timeout", "give up after this many seconds rather than waiting forever"),
        ),
        // Sign-in is what produces a session; it cannot require one.
        needsSession = false,
        alsoExits = listOf(ExitCode.Timeout),
        notBatchable = "A batch runs as one session; sign in before it.",
    ),
    CliCommand(
        path = "logout",
        summary = "forget the stored session",
        notBatchable = "It would revoke the session the remaining operations run as.",
    ),
    CliCommand("whoami", "pubky, homeserver, capabilities, environment, and whether the session is live"),

    CliCommand("deck list", "every deck you have published"),
    CliCommand("deck show", "one deck and its manifest", Operand.Opaque("deckId")),
    CliCommand(
        path = "deck create",
        summary = "publish a new deck",
        options = DECK_METADATA +
            CliOption("id", "publish under this deck id rather than a fresh one") +
            CliOption(
                "if-not-exists",
                "with --id, return the deck that is already there rather than publishing",
                OptionValue.Switch,
            ) +
            CliOption("from-file", "a TSV or JSONL card file to publish with it", OptionValue.Path) +
            // No STUDY_OPT_OUTS: `deckCreate` reads these with `flag(name, default = false)`, so
            // `--no-listen` could only restate the default. `deck edit` and `import --resume`
            // overlay an existing deck and genuinely need them.
            DRY_RUN_OPTION + IMAGE_CHECK_OPTIONS + STUDY_OPT_INS + LANGUAGE_OPTIONS,
        writes = true,
    ),
    CliCommand(
        path = "deck edit",
        summary = "change deck metadata - one manifest write, cards untouched",
        operand = Operand.Opaque("deckId"),
        options = DECK_METADATA + STUDY_OPT_INS + STUDY_OPT_OUTS + LANGUAGE_OPTIONS + listOf(
            CliOption("clear-tags", "remove every tag", OptionValue.Switch),
            CliOption("clear-cover", "remove the cover image and emoji", OptionValue.Switch),
        ),
        writes = true,
    ),
    CliCommand("deck delete", "delete a deck and its cards", Operand.Opaque("deckId"), writes = true),
    CliCommand("deck sync", "re-read a deck from the homeserver", Operand.Opaque("deckId")),
    CliCommand(
        path = "deck compact",
        summary = "fold away the holes card deletes leave in the chunk table",
        operand = Operand.Opaque("deckId"),
        writes = true,
    ),

    CliCommand(
        path = "card list",
        summary = "every card in a deck, or a page of them",
        operand = Operand.Opaque("deckId"),
        options = listOf(
            CliOption("limit", "how many cards to return - reads only the chunks a page needs"),
            CliOption("cursor", "carry on from a previous page, as its next_cursor said"),
            CliOption("missing-image", "only cards with no picture on either side", OptionValue.Switch),
            CliOption("has-image", "only cards with a picture", OptionValue.Switch),
        ),
    ),
    CliCommand(
        path = "card add",
        summary = "add one card, or a file of them",
        operand = Operand.Opaque("deckId"),
        options = CARD_FIELDS + DRY_RUN_OPTION,
        writes = true,
    ),
    CliCommand(
        path = "card edit",
        summary = "change a card - a field you omit is left alone",
        // The card id is optional: `--from-file` names one per row instead.
        operand = Operand.Opaque(required = listOf("deckId"), optional = listOf("cardId")),
        options = CARD_FIELDS,
        writes = true,
    ),
    CliCommand("card rm", "remove one card", Operand.Opaque("deckId", "cardId"), writes = true),

    CliCommand(
        path = "import",
        summary = "publish a deck from a pasted file or an Anki .apkg",
        operand = Operand.Path,
        options = listOf(
            CliOption("title", "the deck title"),
            CliOption("description", "the deck description"),
            CliOption("tag", "a tag - repeat for several"),
            CliOption("separator", "how the columns are split", OptionValue.OneOf(SEPARATORS)),
            CliOption("resume", "carry on an import that stopped part way", OptionValue.Switch),
            CliOption("front-field", "which .apkg field becomes the front, by number or name"),
            CliOption("back-field", "which .apkg field becomes the back, by number or name"),
            // A resumed import overlays whatever metadata this invocation names, cover included,
            // so the flags it can carry are `deck create`'s rather than a subset (#257, item 5).
            CliOption("cover-url", "https URL for the cover image"),
            CliOption("cover-emoji", "an emoji to use as the cover"),
        ) + DRY_RUN_OPTION + IMAGE_CHECK_OPTIONS + STUDY_OPT_INS + STUDY_OPT_OUTS + LANGUAGE_OPTIONS,
        writes = true,
    ),

    CliCommand(
        path = "tag trending",
        summary = "what is being tagged on the Nexus indexer",
        options = listOf(CliOption("limit", "how many to return")),
        // A Nexus read is plain HTTP against a public index.
        needsSession = false,
    ),

    CliCommand(
        path = "batch",
        summary = "run a file of operations against one session, one JSON object per line",
        operand = Operand.Path,
        options = listOf(
            CliOption("stop-on-error", "stop at the first operation that fails", OptionValue.Switch),
        ),
        writes = true,
        relaysExitCodes = true,
        notBatchable = "A batch that contains itself is a loop.",
    ),

    CliCommand(
        path = "update",
        summary = "replace this binary with the newest release",
        options = listOf(CliOption("check", "ask without doing", OptionValue.Switch)),
        needsSession = false,
        local = true,
        // `local` suppresses the network codes, so everything this command really answers with has
        // to be named. `UnsupportedHost` because it refuses before downloading a binary it has no
        // asset for, and `NotFound` because `fetchVerifiedBinary` fetches the asset itself through
        // `download`, which throws that on a 404 — a release with no artifact for this host.
        alsoExits = listOf(
            ExitCode.Network,
            ExitCode.NotFound,
            ExitCode.UnsupportedHost,
            ExitCode.UpdateUnsupported,
        ),
        notBatchable = "Run it before or after; not while this binary is executing a file.",
    ),
    CliCommand(
        path = "completion",
        summary = "print a shell completion script",
        operand = Operand.OneOf("shell", COMPLETION_SHELLS),
        needsSession = false,
        local = true,
        notBatchable = "It prints a shell script and does no homeserver work.",
    ),
    CliCommand(
        path = "commands",
        summary = "this table, as JSON - every verb, its operands, its flags and its exit codes",
        needsSession = false,
        local = true,
        notBatchable = "It prints the command table and does no homeserver work.",
    ),
)

/**
 * Refuse a `--flag` the command does not take, **naming it**.
 *
 * The parser accepts any `--long` it sees, so a flag carried over from a sibling command was
 * silently ignored: `loopky import --dry-run --from-file deck.tsv` parsed, dropped `--from-file`
 * on the floor, and failed with "Missing <file>" followed by sixty lines of manual — nothing in
 * the output named the flag that was wrong (#257, item 5). An agent cannot act on that, and it is
 * expensive in context.
 *
 * Checked against [cliCommands] rather than a per-command list, which is what makes it free to
 * keep true: a flag a command reads has to be in the table anyway, or completion does not offer it
 * and `loopky commands` does not describe it.
 *
 * A verb that is not in the table is left alone — `dispatch` has a better message for that.
 */
internal fun Args.requireKnownOptions() {
    val command = cliCommands().firstOrNull { it.path == verb } ?: return
    val known = (command.options + GLOBAL_OPTIONS).mapTo(mutableSetOf()) { it.name } + UNOFFERED_SWITCHES
    val unknown = givenOptions().filterNot { it in known }
    if (unknown.isEmpty()) return
    throw CliError(ExitCode.Usage, unknownOptionMessage(command, unknown))
}

private fun unknownOptionMessage(command: CliCommand, unknown: List<String>): String = buildString {
    val others = cliCommands().filter { it.path != command.path }
    val named = unknown.joinToString(", ") { "--$it" }
    append(if (unknown.size == 1) "Unknown option $named for" else "Unknown options $named for")
    append(" '${command.path}'.")

    unknown.forEach { name ->
        val elsewhere = others.filter { other -> other.options.any { it.name == name } }
        if (elsewhere.isNotEmpty()) {
            append(" --$name belongs to ${elsewhere.joinToString(", ") { "`${it.path}`" }}.")
        }
        // The one substitution worth spelling out: a command whose input is a positional file,
        // refusing a flag whose job elsewhere is naming a file. That is the mistake this exists for.
        val takesAPath = elsewhere.any { other -> other.options.any { it.name == name && it.value == OptionValue.Path } }
        if (takesAPath && command.operand == Operand.Path) {
            append(" `${command.path}` takes its file as a positional operand: loopky ${command.path} <file>.")
        }
    }

    append(" It takes: ")
    append((command.options + GLOBAL_OPTIONS).joinToString(", ") { "--${it.name}" })
    append(".")
}

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
