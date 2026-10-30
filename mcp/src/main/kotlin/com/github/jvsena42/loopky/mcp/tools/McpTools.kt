package com.github.jvsena42.loopky.mcp.tools

import com.github.jvsena42.loopky.cli.CliCommand
import com.github.jvsena42.loopky.cli.CliOption
import com.github.jvsena42.loopky.cli.Operand
import com.github.jvsena42.loopky.cli.OptionValue
import com.github.jvsena42.loopky.cli.cliCommands

/**
 * The tools the connector offers: a whitelist over the CLI's command table, each entry carrying
 * the decisions the server makes about that command's options (#214, #486).
 *
 * **The table stays the one authority.** A tool's operands, options, summaries and types come
 * from `cliCommands()`; what lives here is only which commands a remote caller may reach and which
 * of their options it may not. Both lists are **explicit**: a tool names every option it exposes
 * and every one it refuses, and `ToolCatalogTest` holds the two against the table — so an option
 * added to `deck create` fails the build until somebody decides its fate here, rather than
 * reaching the network with nobody having looked.
 *
 * Out of v1, deliberately (#486): `deck delete`, `deck compact` and `deck sync` (destructive or
 * expensive and not needed to build a deck), `card reorder` and `batch` (file-driven), `card
 * check-images` (the server fetching caller URLs), `.apkg` import (untrusted zip and SQLite
 * parsing on the server, and the one path that uploads bytes), and everything about being a
 * binary — `login`, `logout`, `doctor`, `update`, `completion`, `commands`.
 */
@Suppress("LongParameterList") // one per thing the catalog test holds a tool to
class McpTool(
    val name: String,
    /** What a person sees for the tool, in a client's tool list or a confirmation prompt. */
    val title: String,
    val command: CliCommand,
    /** Option names the tool takes, as the table spells them. */
    val exposed: Set<String>,
    /** Option names the server refuses, each with the reason a caller is told. */
    val refused: Map<String, String>,
    /** Inline content standing in for the file the command would read from disk. */
    val inline: InlineInput? = null,
    val hints: ToolHints = ToolHints(),
    /** The command's summary, where the table's says something this tool cannot do. */
    val summary: String? = null,
) {
    init {
        val known = command.options.mapTo(mutableSetOf()) { it.name }
        val unknown = (exposed + refused.keys) - known
        require(unknown.isEmpty()) { "$name names options `${command.path}` does not have: $unknown" }
        val undecided = known - exposed - refused.keys
        require(undecided.isEmpty()) { "$name leaves options of `${command.path}` undecided: $undecided" }
        val both = exposed intersect refused.keys
        require(both.isEmpty()) { "$name both exposes and refuses: $both" }
    }

    val exposedOptions: List<CliOption> get() = command.options.filter { it.name in exposed }
}

/** What the tool tells a client about itself beyond what the table says. */
data class ToolHints(
    /**
     * `destructiveHint`: the tool may overwrite or remove what is there. MCP reads `false` as "only
     * additive", so an edit that replaces a card's text or a deck's tags is destructive too, and a
     * client asks before running it.
     */
    val destructive: Boolean = false,
    /** `idempotentHint`: a retry with the same arguments changes nothing more. */
    val idempotent: Boolean = false,
)

private val IDEMPOTENT = ToolHints(idempotent = true)
private val OVERWRITES = ToolHints(destructive = true, idempotent = true)

/** How a tool takes the content a command would otherwise read from a path. */
enum class InlineInput {
    /** `cards` (objects) or `text` (TSV/JSONL), standing in for `--from-file`. Optional. */
    CardFile,

    /** `text`, standing in for `import`'s positional file. Required. */
    ImportSource,
}

/** Why every `OptionValue.Path` option is refused. */
const val PATH_REFUSAL = "names a file on the server, which a remote caller has no business reading. " +
    "Give the content inline instead."

/** Why `--check-images` and its dial are refused. */
const val IMAGE_CHECK_REFUSAL = "asks the server to fetch picture URLs on your behalf, which it does not do. " +
    "Picture URLs are stored as given; the phone fetches them."

private const val APKG_REFUSAL = "applies to an Anki .apkg, which cannot be imported through the connector."

private val IMAGE_CHECK_OPTIONS = mapOf(
    "check-images" to IMAGE_CHECK_REFUSAL,
    "check-images-concurrency" to IMAGE_CHECK_REFUSAL,
)

private val DECK_METADATA = setOf("title", "description", "tag", "cover-url", "cover-emoji")
private val STUDY_OPT_INS = setOf("listen", "speak", "type", "reverse")
private val STUDY_OPT_OUTS = STUDY_OPT_INS.mapTo(mutableSetOf()) { "no-$it" }
private val LANGUAGES = setOf("front-lang", "back-lang")
private val CARD_FIELDS = setOf("front", "back", "front-image", "back-image")

/** The whitelist. Order is the order `tools/list` reports. */
@Suppress("LongMethod")
fun mcpTools(): List<McpTool> {
    val commands = cliCommands().associateBy { it.path }
    fun command(path: String) = requireNotNull(commands[path]) { "no command `$path` in the table" }
    return listOf(
        McpTool(
            "whoami",
            "Show the connected account",
            command("whoami"),
            exposed = emptySet(),
            refused = emptyMap(),
            hints = IDEMPOTENT,
        ),
        McpTool(
            "deck_list",
            "List decks",
            command("deck list"),
            exposed = setOf("limit", "cursor", "query", "compact"),
            refused = emptyMap(),
            hints = IDEMPOTENT,
        ),
        McpTool(
            "deck_show",
            "Show a deck",
            command("deck show"),
            exposed = emptySet(),
            refused = emptyMap(),
            hints = IDEMPOTENT,
        ),
        McpTool(
            "deck_create",
            "Create a deck",
            command("deck create"),
            exposed = DECK_METADATA + "id" + "if-not-exists" + "dry-run" + STUDY_OPT_INS + LANGUAGES,
            refused = mapOf("from-file" to PATH_REFUSAL) + IMAGE_CHECK_OPTIONS,
            inline = InlineInput.CardFile,
        ),
        McpTool(
            "deck_edit",
            "Edit a deck",
            command("deck edit"),
            exposed = DECK_METADATA + STUDY_OPT_INS + STUDY_OPT_OUTS + LANGUAGES + "clear-tags" + "clear-cover",
            refused = emptyMap(),
            hints = OVERWRITES,
        ),
        McpTool(
            "card_list",
            "List cards",
            command("card list"),
            exposed = setOf("limit", "cursor", "missing-image", "has-image"),
            refused = emptyMap(),
            hints = IDEMPOTENT,
        ),
        McpTool(
            "card_add",
            "Add cards",
            command("card add"),
            exposed = CARD_FIELDS + "dry-run",
            refused = mapOf("from-file" to PATH_REFUSAL) + IMAGE_CHECK_OPTIONS,
            inline = InlineInput.CardFile,
            hints = IDEMPOTENT,
        ),
        McpTool(
            "card_edit",
            "Edit cards",
            command("card edit"),
            exposed = CARD_FIELDS + "clear-front-image" + "clear-back-image",
            refused = mapOf("from-file" to PATH_REFUSAL) + IMAGE_CHECK_OPTIONS,
            inline = InlineInput.CardFile,
            hints = OVERWRITES,
        ),
        McpTool(
            "card_remove",
            "Remove a card",
            command("card rm"),
            exposed = emptySet(),
            refused = emptyMap(),
            hints = OVERWRITES,
        ),
        McpTool(
            "card_move",
            "Move a card",
            command("card mv"),
            exposed = setOf("to", "after"),
            refused = emptyMap(),
            hints = OVERWRITES,
        ),
        McpTool(
            "import",
            "Import a deck from text",
            command("import"),
            exposed = setOf("title", "description", "tag", "separator", "resume", "cover-url", "cover-emoji", "dry-run") +
                STUDY_OPT_INS + STUDY_OPT_OUTS + LANGUAGES,
            refused = mapOf("front-field" to APKG_REFUSAL, "back-field" to APKG_REFUSAL) + IMAGE_CHECK_OPTIONS,
            inline = InlineInput.ImportSource,
            // The table's says "a pasted file or an Anki .apkg"; the connector takes text only.
            summary = "publish a deck from pasted text",
        ),
        McpTool(
            "tag_trending",
            "List trending tags",
            command("tag trending"),
            exposed = setOf("limit"),
            refused = emptyMap(),
            hints = IDEMPOTENT,
        ),
    )
}

/** Options the table types as text that are read as a positive whole number. */
val INTEGER_OPTIONS = setOf("limit", "to")

/** Options that may be given more than once on the command line, so the tool takes an array. */
val REPEATABLE_OPTIONS = setOf("tag")

/** The JSON property a `--long-option` or a `deckId` operand becomes. */
fun propertyName(name: String): String = name.replace('-', '_').replace(Regex("([a-z])([A-Z])")) {
    "${it.groupValues[1]}_${it.groupValues[2].lowercase()}"
}

/** The operand names a tool takes, in order, as the table spells them. */
fun McpTool.operandNames(): List<String> = when (val operand = command.operand) {
    Operand.None -> emptyList()
    // `import`'s file is inline content, never a caller-named path.
    Operand.Path -> emptyList()
    is Operand.OneOf -> listOf(operand.name)
    is Operand.Opaque -> operand.names
}

/** Whether [option] takes nothing, so its property is a boolean. */
fun CliOption.isSwitch(): Boolean = value == OptionValue.Switch
