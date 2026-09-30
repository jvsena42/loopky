package com.github.jvsena42.loopky.cli

/**
 * The command line, taken apart and nothing more.
 *
 * Deliberately dumb: **no logic lives in the parser.** Every command is a function taking the
 * values it needs and returning its `--json` shape, so the same functions can be driven by
 * something that is not a shell — a remote MCP server serves an audience the CLI cannot reach at
 * all, and it should be a thin binding rather than a second implementation (#54, open question 2).
 *
 * Hand-rolled rather than a library because the surface is a flat `noun verb [args]` and the
 * dependency would have to survive whatever packaging comes next.
 */
class Args private constructor(
    /** Positional words in order: `deck`, `create`, then any operands. */
    val words: List<String>,
    private val options: Map<String, List<String>>,
    private val switches: Set<String>,
) {

    /**
     * The command being asked for: `deck create`, `card add`, `import`, `whoami`.
     *
     * Two words for the grouped commands and one for the rest, rather than "the first two words"
     * — an operand is not part of the verb, and taking it as one names the command
     * `import cards.tsv` in the `--json` envelope and looks it up under that name in the
     * dispatcher, where it matches nothing.
     */
    val verb: String
        get() {
            val head = words.firstOrNull() ?: return ""
            return if (head in GROUPS) listOfNotNull(head, word(1)).joinToString(" ") else head
        }

    fun word(index: Int): String? = words.getOrNull(index)

    /**
     * A required operand — a deck or card id — checked for being addressable at all.
     *
     * Absent is [ExitCode.Usage]; present but unusable is [ExitCode.BadInput], via
     * [requireUsableOperand]. The second half is the point: it is what stops a blank id reaching a
     * homeserver path and coming back as `internal`, the one code an agent retries.
     */
    fun requireWord(index: Int, name: String): String =
        requireUsableOperand(word(index) ?: throw CliError(ExitCode.Usage, "Missing <$name>."), name)

    fun option(name: String): String? = options[name]?.lastOrNull()

    fun requireOption(name: String): String =
        option(name) ?: throw CliError(ExitCode.Usage, "Missing --$name.")

    /** Every occurrence, so `--tag a --tag b` is two tags rather than the last one. */
    fun options(name: String): List<String> = options[name].orEmpty()

    fun has(name: String): Boolean = name in switches || name in options

    /**
     * Every `--long` name this invocation actually carried.
     *
     * The parser has no opinion about which of them a command accepts — that is
     * [requireKnownOptions]'s job, against the one table that describes the surface. This is only
     * what was typed.
     */
    fun givenOptions(): Set<String> = switches + options.keys

    /**
     * A `--name` / `--no-name` pair as a tri-state: null when the caller said nothing about it.
     *
     * The difference matters wherever a value is being *overlaid* on something that already
     * exists. "Absent" and "explicitly false" are the same to [Boolean], and treating them alike
     * on a resumed import would turn every opt-in the deck already had off again.
     */
    fun flagOrNull(name: String): Boolean? = when {
        has("no-$name") -> false
        has(name) -> true
        else -> null
    }

    /**
     * A count option, or [default] when it was not given.
     *
     * Refuses a value that is not a positive integer rather than falling back to the default.
     * `toIntOrNull() ?: default` turns `--limit twenty` into the default and `--limit -5` into
     * whatever the caller does with a negative — both of which answer a question nobody asked,
     * which is the failure mode this surface exists to avoid.
     */
    fun positiveInt(name: String, default: Int): Int = positiveIntOrNull(name) ?: default

    /**
     * The same, for an option whose absence is not a number.
     *
     * `login --timeout` is the case: not passing it means *wait forever*, which no default can
     * stand in for.
     */
    fun positiveIntOrNull(name: String): Int? {
        val raw = option(name) ?: return null
        return raw.toIntOrNull()?.takeIf { it > 0 }
            ?: throw CliError(ExitCode.Usage, "--$name must be a positive whole number, not '$raw'.")
    }

    companion object {
        /** Commands that are a noun plus a verb. Everything else is one word. */
        private val GROUPS = setOf("deck", "card", "tag")

        /**
         * Which long options take no value.
         *
         * Declared rather than inferred from "the next token starts with `--`". Inference gets
         * `--front --back x` wrong in the direction that matters: it would silently read the
         * *flag* as the front of a card and write it to the homeserver.
         *
         * `internal` so `CompletionTest` can hold it against the completion table: a switch the
         * shell offers that the parser does not know here eats the next word as its value.
         */
        internal val SWITCHES = setOf(
            "json", "verbose", "help", "version",
            "resume", "export", "url-only", "force", "yes", "dry-run",
            // `update --check` asks without doing; `--no-update-check` is the global opt-out.
            "check", "no-update-check",
            // The four deck study opt-ins, each with an explicit off form. A `--no-` switch is
            // not decoration: every one of these defaults to off here, and a caller editing an
            // existing deck needs a way to say so rather than only a way to agree.
            "listen", "no-listen", "speak", "no-speak",
            "type", "no-type", "reverse", "no-reverse",
            // `card list`'s two filters. Opposites, so passing both is a usage error rather than
            // a precedence rule nobody would remember.
            "missing-image", "has-image",
            // Opt-in, because it is the one flag here that makes network requests of its own —
            // one HEAD per distinct picture URL, against hosts this client otherwise never talks to.
            "check-images",
            // `deck edit`'s two emptiers. Switches rather than options taking an empty value,
            // because "remove every tag" is a different gesture from "set the tags to this" and
            // `--tag ""` would have to be read as both.
            "clear-tags", "clear-cover",
            // `deck create --if-not-exists`, which only means anything beside `--id`. A switch
            // rather than a mode, because "make sure this deck exists" is one gesture.
            "if-not-exists",
            // `batch`. Off by default: when the same rows apply singly, one refused operation is
            // not a reason to abandon the six hundred after it.
            "stop-on-error",
        )

        fun parse(argv: Array<String>): Args {
            val words = mutableListOf<String>()
            val options = mutableMapOf<String, MutableList<String>>()
            val switches = mutableSetOf<String>()

            var index = 0
            while (index < argv.size) {
                val token = argv[index]
                when {
                    token == "--" -> {
                        // Everything after `--` is an operand, so a card front may begin with a
                        // dash without being read as a flag.
                        words += argv.drop(index + 1)
                        return Args(words, options, switches)
                    }

                    token.startsWith("--") -> {
                        val body = token.removePrefix("--")
                        val eq = body.indexOf('=')
                        if (eq >= 0) {
                            options.getOrPut(body.take(eq)) { mutableListOf() } += body.substring(eq + 1)
                        } else if (body in SWITCHES) {
                            switches += body
                        } else {
                            val value = argv.getOrNull(index + 1)
                                ?: throw CliError(ExitCode.Usage, "--$body needs a value.")
                            options.getOrPut(body) { mutableListOf() } += value
                            index++
                        }
                    }

                    // A bare "-" is stdin, which is an operand, not a flag.
                    token.startsWith("-") && token != "-" ->
                        throw CliError(ExitCode.Usage, "Unknown option '$token'. Options are --long form.")

                    else -> words += token
                }
                index++
            }
            return Args(words, options, switches)
        }
    }
}
