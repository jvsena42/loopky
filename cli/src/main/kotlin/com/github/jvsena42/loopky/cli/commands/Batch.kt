package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.CommandResult
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.asCliError
import com.github.jvsena42.loopky.cli.cliCommands
import com.github.jvsena42.loopky.cli.cliJson
import com.github.jvsena42.loopky.cli.eventEnvelope
import com.github.jvsena42.loopky.cli.nearMissHint
import com.github.jvsena42.loopky.cli.result
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * A file of operations against one session (#240, finding 3).
 *
 * Measured on the native binary, warm, macOS arm64 against staging: `--version` is 0.00–0.02s, so
 * process start is not the cost; `whoami` is ~2.5s and `deck list` ~3.1s. The binary starts
 * instantly and then spends two and a half seconds before it does anything — invisible to a person,
 * and roughly two minutes of pure overhead for an agent driving fifty single-card operations.
 *
 * The bulk-file paths (`card add --from-file`, `import`) already amortise that where they apply.
 * What had no amortised form is a **sequence of different commands**, which is the shape an agent
 * actually produces: create a deck, add cards, edit two, read them back. This is that shape —
 * N × (process start + FFI load + session load + deck read) collapsed to one of each, with the
 * repositories' per-session cache warm across the whole run.
 *
 * Three things it deliberately is not.
 *
 * - **Not a second implementation.** Each line is parsed by [Args] and handed to the same
 *   `dispatch` a command line reaches, so a batch cannot drift from the CLI or accept a flag the
 *   CLI does not. That is the same reason `dispatch` is a `when` over verbs and nothing else.
 * - **Not transactional.** Nothing here rolls anything back — the homeserver has no such thing to
 *   offer. A failed operation does not end the run (`--stop-on-error` if you want it to), and the
 *   result says exactly which ones landed, so the recovery is to re-run the file: `card add` and
 *   `card edit` are idempotent, and `deck create --id --if-not-exists` is.
 * - **Not a session renewer.** One session, held for as long as the run takes — and it still dies
 *   after about an hour with nothing renewing it (#165). A long batch hits that wall exactly where
 *   a long sequence of separate commands would, and the operations after it fail
 *   `session_expired`, which is the code that says a human has to sign in again.
 */
@Serializable
data class BatchResult(
    val operations: Int,
    val succeeded: Int,
    val failed: Int,
    /** Operations after the one that stopped the run. Zero unless `--stop-on-error`. */
    @SerialName("not_attempted") val notAttempted: Int,
    /**
     * One entry per operation *attempted*, in order — the compact form.
     *
     * Deliberately compact: each operation's full result is streamed as its own line while the run
     * is going, so repeating every payload here would double a 500-operation run's output for a
     * caller that already has it. What is here is what a caller needs after the fact — which
     * operation, whether it landed, and the code if it did not.
     */
    val results: List<BatchOperationResult>,
)

@Serializable
data class BatchOperationResult(
    /** 0-based, matching the line's position among the non-blank lines of the file. */
    val index: Int,
    /** The line's own `"id"`, echoed back so a caller can correlate without counting. */
    val id: String? = null,
    /** The verb as the envelope spells it: `card add`, `deck create`. */
    val command: String,
    val ok: Boolean,
    /** The failure's `ExitCode.json` name, or null when it succeeded. */
    val code: String? = null,
    val message: String? = null,
)

/**
 * Where a batch writes its three kinds of output. Same split as `LoginSinks`, one wider.
 *
 * [emitText] is separate from [note] because an operation's rendered result is a **result**:
 * without it the human mode sent every `deck show` and `card list` answer to stderr and left
 * stdout holding one summary line, so `loopky batch ops.ndjson > out.txt` captured nothing but
 * "6/6 operations succeeded". `note` stays diagnostics.
 */
data class BatchSinks(
    val emitEvent: (String) -> Unit,
    val emitText: (String) -> Unit,
    val note: (String) -> Unit,
)

/**
 * Run [source]'s operations, one per line, through [runOne].
 *
 * [runOne] is `Main`'s `dispatch` — passed in rather than reached for, so this file knows nothing
 * about Koin, the session or the environment, and the whole surface stays "one function per
 * command taking plain values".
 */
suspend fun batch(
    args: Args,
    runOne: suspend (Args) -> CommandResult,
    sinks: BatchSinks,
): CommandResult {
    val source = args.word(1) ?: throw CliError(
        ExitCode.Usage,
        "Missing <file>, or - to read the operations from stdin.",
    )
    val operations = readOperations(source)
    val stopOnError = args.has("stop-on-error")

    val results = mutableListOf<BatchOperationResult>()
    var stopped = false
    for (operation in operations) {
        if (stopped) break
        val outcome = runOperation(operation, runOne, sinks)
        results += outcome
        if (!outcome.ok) {
            sinks.note("loopky: operation ${operation.index}${operation.label()} failed: ${outcome.message}")
            stopped = stopOnError
        }
    }

    val failures = results.count { !it.ok }
    val payload = BatchResult(
        operations = operations.size,
        succeeded = results.size - failures,
        failed = failures,
        notAttempted = operations.size - results.size,
        results = results,
    )
    val text = buildString {
        append("${payload.succeeded}/${payload.operations} operations succeeded")
        if (failures > 0) append(", $failures failed")
        if (payload.notAttempted > 0) append(", ${payload.notAttempted} not attempted")
    }
    if (failures == 0) return result(payload, text)

    val first = results.first { !it.ok }
    throw CliError(
        // The first failure's code, not a code of the batch's own. An agent branching on the exit
        // status of a run that was 90% writes needs the reason the writes stopped working —
        // `session_expired` and `storage_full` mean different things about re-running the file.
        ExitCode.entries.firstOrNull { it.json == first.code } ?: ExitCode.Internal,
        "$text. First failure was operation ${first.index} (${first.command}): ${first.message}",
        cliJson.encodeToJsonElement(BatchResult.serializer(), payload),
    )
}

/**
 * One operation, reported on stdout as it happens and never allowed to end the run by throwing.
 *
 * The event line carries the operation's **whole** result, because that is what a caller streaming
 * the run has instead of the individual command's envelope. A parser that ignores an `event` it
 * does not know stays correct, which is the rule `login`'s `auth_url` established.
 */
private suspend fun runOperation(
    operation: BatchOperation,
    runOne: suspend (Args) -> CommandResult,
    sinks: BatchSinks,
): BatchOperationResult {
    val command = operation.args.verb
    return try {
        val outcome = runOne(operation.args)
        sinks.emitEvent(operation.event(command, ok = true, data = outcome.data, error = null))
        if (outcome.text.isNotEmpty()) sinks.emitText(outcome.text)
        BatchOperationResult(operation.index, operation.id, command, ok = true)
    } catch (error: CliError) {
        sinks.emitEvent(operation.event(command, ok = false, data = error.data, error = error))
        BatchOperationResult(operation.index, operation.id, command, false, error.exitCode.json, error.message)
    } catch (cancelled: CancellationException) {
        // Never an operation failure. `runOne` suspends, so a cancelled run reaches here first —
        // and swallowing it recorded `internal` for this operation and then ran the remaining 599,
        // each cancelling instantly at its own first suspension point, to report 600 failures for
        // one cancellation. The repo rule (CLAUDE.md, "Cancellation") is that a catch-all around
        // suspending code must not turn "the caller went away" into an ordinary failure.
        throw cancelled
    } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
        // `Throwable`, for the same reason `Main` catches it: an `UnsatisfiedLinkError` is an
        // `Error`, and letting one past here would end the run with nothing said about the
        // operations that had already landed.
        val failure = asCliError(error)
        sinks.emitEvent(operation.event(command, ok = false, data = null, error = failure))
        BatchOperationResult(operation.index, operation.id, command, false, failure.exitCode.json, failure.message)
    }
}

/**
 * One operation as a line on the machine channel.
 *
 * [ok] is passed to [eventEnvelope] as well as put in the payload, and that is the point: the
 * envelope's own `ok` used to be hardcoded true — it was written for `login`'s `auth_url`, which
 * cannot fail — so a 500-operation run in which half the writes failed streamed 500 lines saying
 * `"ok": true`. Branching on the envelope is the obvious way to consume a stream of envelopes, and
 * `--json` is a versioned API rather than a print format.
 */
private fun BatchOperation.event(command: String, ok: Boolean, data: JsonElement?, error: CliError?): String =
    eventEnvelope(
        "batch",
        "operation",
        ok = ok,
        data = buildJsonObject {
            put("index", index)
            put("id", id?.let(::JsonPrimitive) ?: JsonNull)
            put("command", command)
            put("ok", ok)
            put("data", data ?: JsonNull)
            put(
                "error",
                if (error == null) {
                    JsonNull
                } else {
                    buildJsonObject {
                        put("code", error.exitCode.json)
                        put("exit", error.exitCode.code)
                        put("message", error.message.orEmpty())
                    }
                },
            )
        },
    )

private fun BatchOperation.label(): String = id?.let { " ($it)" }.orEmpty()

private class BatchOperation(val index: Int, val id: String?, val args: Args)

private val batchJson = Json { ignoreUnknownKeys = true }

/**
 * Parse the whole file before running any of it.
 *
 * Everything is validated first, the way `card edit --from-file` is and for the same reason: a
 * malformed line 400 must fail with the homeserver untouched rather than 399 operations in. What
 * cannot be checked here is whether an operation will *work* — only that it is a command this
 * binary has.
 */
private fun readOperations(source: String): List<BatchOperation> {
    val text = if (source == "-") {
        System.`in`.readBytes().toString(Charsets.UTF_8)
    } else {
        val file = File(source)
        if (!file.isFile) throw CliError(ExitCode.BadInput, "No such file: $source")
        file.readText(Charsets.UTF_8)
    }
    // Numbered before the blanks are dropped: a reported "Line 4" that is the file's line 5 sends
    // the caller to the wrong line of the file they wrote.
    val operations = text.lines()
        .mapIndexed { lineNumber, line -> lineNumber + 1 to line }
        .filter { (_, line) -> line.isNotBlank() }
        .mapIndexed { index, (lineNumber, line) -> parseOperation(index, lineNumber, line) }
    if (operations.isEmpty()) throw CliError(ExitCode.BadInput, "$source held no operations.")
    return operations
}

/**
 * One line: `{"argv": ["card", "add", …], "id": "…"}`, or the bare array on its own.
 *
 * Both forms, because they are the two things a caller building this file with `jq` naturally
 * produces, and accepting the array costs one branch. `id` is optional and echoed back.
 */
private fun parseOperation(index: Int, lineNumber: Int, line: String): BatchOperation {
    val where = "Line $lineNumber"
    val json = runCatching { batchJson.parseToJsonElement(line) }.getOrElse {
        throw CliError(ExitCode.BadInput, "$where is not JSON: ${it.message}")
    }
    val (argv, id) = when (json) {
        is JsonArray -> json to null
        is JsonObject -> (json["argv"] as? JsonArray ?: throw CliError(
            ExitCode.BadInput,
            "$where has no \"argv\" array. A line is {\"argv\": [\"card\", \"add\", …]} or the array alone.",
        )) to (json["id"] as? JsonPrimitive)?.contentOrNullIfJsonNull()

        else -> throw CliError(ExitCode.BadInput, "$where is neither an object nor an array.")
    }
    val words = argv.map {
        (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content
            ?: throw CliError(ExitCode.BadInput, "$where: every argv entry has to be a string.")
    }
    if (words.isEmpty()) throw CliError(ExitCode.BadInput, "$where has an empty argv.")

    val args = runCatching { Args.parse(words.toTypedArray()) }.getOrElse {
        throw CliError(ExitCode.BadInput, "$where: ${it.message}")
    }
    requireBatchable(where, args)
    return BatchOperation(index, id, args)
}

private fun JsonPrimitive.contentOrNullIfJsonNull(): String? = if (this is JsonNull) null else content

/**
 * Refuse anything that cannot run inside a batch.
 *
 * A verb this binary does not have is refused **here** rather than by `dispatch`, because that is
 * the whole promise of parsing the file first: a typo on line 400 must fail with the homeserver
 * untouched, not 399 operations in. `deck creat` and a bare `deck` both parse fine and both used
 * to reach `dispatch`'s `else ->` after 399 writes had landed.
 *
 * Both answers come from `CommandSurface.kt` rather than from a list kept here, and that is not
 * tidiness: `commands --json` derives `batch`'s own exit codes from the union of the batchable
 * commands, so a second list would make the published surface wrong the first time the two
 * disagreed. The six that are refused each carry their reason on the table entry.
 */
private fun requireBatchable(where: String, args: Args) {
    val verb = args.verb
    val command = cliCommands().firstOrNull { it.path == verb } ?: throw CliError(
        ExitCode.BadInput,
        listOfNotNull(
            "$where: `$verb` is not a loopky command.",
            args.nearMissHint(),
            "`loopky commands --json` lists every one.",
        ).joinToString(" "),
    )
    command.notBatchable?.let { reason ->
        throw CliError(ExitCode.BadInput, "$where: `$verb` cannot run inside a batch. $reason")
    }
}
