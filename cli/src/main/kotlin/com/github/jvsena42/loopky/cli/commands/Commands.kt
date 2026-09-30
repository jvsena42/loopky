package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.CLI_VERSION
import com.github.jvsena42.loopky.cli.CliCommand
import com.github.jvsena42.loopky.cli.CliOption
import com.github.jvsena42.loopky.cli.CommandResult
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.GLOBAL_OPTIONS
import com.github.jvsena42.loopky.cli.Operand
import com.github.jvsena42.loopky.cli.OptionValue
import com.github.jvsena42.loopky.cli.SCHEMA_VERSION
import com.github.jvsena42.loopky.cli.cliCommands
import com.github.jvsena42.loopky.cli.result
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The command surface, for something that is not a person (#240, finding 4).
 *
 * To construct a valid invocation, an agent driving this binary read the `USAGE` constant out of
 * the repository, because parsing prose was easier than parsing `--help`. That is a fine outcome
 * for somebody with the source checked out and **no outcome at all** for an agent holding only the
 * binary — which is the audience this client was built for.
 *
 * It is generated from the same table `completion` is, so it cannot describe a surface the binary
 * does not have; the shell scripts were already this data, only encoded for shells.
 *
 * `--json` is documented as a versioned API rather than a print format, so this travels under the
 * same `schema` and the same rule: fields may be added, meanings may not change.
 */
@Serializable
data class CommandSurfaceResult(
    @SerialName("cli_version") val cliVersion: String,
    /** The `--json` envelope's version, the same number every other result carries. */
    @SerialName("envelope_schema") val envelopeSchema: Int,
    /** Options every command takes, listed once rather than repeated on each. */
    val globals: List<OptionSpec>,
    val commands: List<CommandSpec>,
    @SerialName("exit_codes") val exitCodes: List<ExitCodeSpec>,
)

@Serializable
data class CommandSpec(
    /** Exactly what goes on the command line, and exactly what the envelope's `command` says. */
    val path: String,
    val summary: String,
    /** Positional words, in order. Arity is the point: `card edit` takes one or two. */
    val operands: List<OperandSpec>,
    val options: List<OptionSpec>,
    @SerialName("needs_session") val needsSession: Boolean,
    /** Writes to the homeserver, so it can hit the 1 GB quota and answer `storage_full`. */
    val writes: Boolean,
    /**
     * Every code this command can exit with, derived from its shape rather than hand-listed.
     *
     * Derived so it cannot drift, and worth having because the *absences* carry information: an
     * agent reading that `tag trending` never answers `session_expired` knows not to sign in
     * before calling it.
     */
    @SerialName("exit_codes") val exitCodes: List<Int>,
)

/** `id` for a deck or card id, `path` for a file, `one_of` when [choices] is the whole set. */
@Serializable
data class OperandSpec(
    val name: String,
    val kind: String,
    val required: Boolean,
    val choices: List<String> = emptyList(),
)

/** [value] is `switch` when the option takes nothing — the distinction that decides parsing. */
@Serializable
data class OptionSpec(
    val name: String,
    val summary: String,
    val value: String,
    val choices: List<String> = emptyList(),
)

@Serializable
data class ExitCodeSpec(val code: Int, val name: String, val summary: String)

/** Needs no session, no network and no FFI — it is a table this binary was compiled with. */
fun commandSurface(): CommandResult {
    val payload = CommandSurfaceResult(
        cliVersion = CLI_VERSION,
        envelopeSchema = SCHEMA_VERSION,
        globals = GLOBAL_OPTIONS.map { it.spec() },
        commands = cliCommands().map { it.spec() },
        exitCodes = ExitCode.entries.map { ExitCodeSpec(it.code, it.json, it.summary) },
    )
    return result(payload, payload.commands.joinToString("\n") { it.line() })
}

private fun CliCommand.spec() = CommandSpec(
    path = path,
    summary = summary,
    operands = operand.specs(),
    options = options.map { it.spec() },
    needsSession = needsSession,
    writes = writes,
    exitCodes = exitCodes().map { it.code },
)

/**
 * What this command can exit with.
 *
 * Universal first: every command can succeed, be given a wrong command line, or fail in a way the
 * table has no better word for. Everything after that follows from one declared fact each — which
 * is what keeps this honest as commands are added.
 */
private fun CliCommand.exitCodes(): List<ExitCode> = buildList {
    add(ExitCode.Ok)
    add(ExitCode.Internal)
    add(ExitCode.Usage)
    // Over-listing is the safe direction here and under-listing is not: an agent that sees a code
    // it was not told about has no rule for it. A closed-set operand is the one case that cannot
    // produce it — a word outside the set is a wrong command line, which is `usage`.
    if (operand is Operand.Opaque || operand is Operand.Path || options.any { it.value == OptionValue.Path }) {
        add(ExitCode.BadInput)
    }
    if (operand is Operand.Opaque) add(ExitCode.NotFound)
    if (!local) {
        // Anything past the pre-Koin boundary loads `libpubkycore` and talks to a homeserver.
        add(ExitCode.Network)
        add(ExitCode.ProxyRefused)
        add(ExitCode.TlsUntrusted)
        add(ExitCode.ServerError)
        add(ExitCode.HomeserverUnresolved)
        add(ExitCode.UnsupportedHost)
    }
    if (!local && needsSession) {
        add(ExitCode.NotSignedIn)
        add(ExitCode.SessionExpired)
        add(ExitCode.EnvironmentMismatch)
        // Not implied by the operands: `requireSession` refuses a `LOOPKY_SESSION` that is not
        // `<pubkey>:<cookie>` as bad input, so `LOOPKY_SESSION=garbage loopky deck list` exits 9
        // from a command with no operand and no options at all.
        add(ExitCode.BadInput)
    }
    if (writes) add(ExitCode.StorageFull)
    addAll(alsoExits)
    // A relaying command answers with the code of whatever it ran, so its own shape says nothing
    // about the set. The union is over what it can actually *run* — the batchable commands — which
    // is why `notBatchable` lives on the table rather than in a list beside the runner: taking the
    // union over everything would promise `timeout` and `update_unsupported`, from the two commands
    // a batch is not allowed to contain. No recursion: every relaying command is unbatchable.
    if (relaysExitCodes) {
        cliCommands().filter { it.notBatchable == null }.forEach { addAll(it.exitCodes()) }
    }
}.distinct().sortedBy { it.code }

private fun Operand.specs(): List<OperandSpec> = when (this) {
    Operand.None -> emptyList()
    Operand.Path -> listOf(OperandSpec("file", "path", required = true))
    is Operand.OneOf -> listOf(OperandSpec(name, "one_of", required = true, choices = choices))
    is Operand.Opaque ->
        required.map { OperandSpec(it, "id", required = true) } +
            optional.map { OperandSpec(it, "id", required = false) }
}

private fun CliOption.spec() = OptionSpec(
    name = name,
    summary = summary,
    value = when (value) {
        OptionValue.Switch -> "switch"
        OptionValue.Text -> "text"
        OptionValue.Path -> "path"
        is OptionValue.OneOf -> "one_of"
    },
    choices = (value as? OptionValue.OneOf)?.choices.orEmpty(),
)

/** One line per command for a person, since `--help` is the rendering they actually want. */
private fun CommandSpec.line(): String {
    val words = operands.joinToString(" ") { if (it.required) "<${it.name}>" else "[${it.name}]" }
    return "loopky $path${if (words.isEmpty()) "" else " $words"} — $summary"
}
