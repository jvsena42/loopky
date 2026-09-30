package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.CommandResult
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.ok
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `loopky batch` (#240, finding 3): a sequence of *different* commands against one session.
 *
 * Nothing here touches a homeserver — the dispatcher is a stub. What is being held is the
 * mechanics an agent depends on and cannot see from a green run: that every line is validated
 * before the first one runs, that one failure does not silently abandon the rest, that the
 * per-operation stream and the summary agree, and that the exit code carries the reason the run
 * stopped rather than a code of the batch's own.
 */
class BatchTest {

    private val events = mutableListOf<String>()

    /** Rendered results — stdout in the human mode, which is where a result belongs. */
    private val texts = mutableListOf<String>()
    private val notes = mutableListOf<String>()
    private val sinks = BatchSinks({ events += it }, { texts += it }, { notes += it })

    /** Every set of args the stub dispatcher was handed, so order and content are assertable. */
    private val ran = mutableListOf<Args>()

    private fun runner(fail: (Args) -> CliError? = { null }): suspend (Args) -> CommandResult = { args ->
        ran += args
        fail(args)?.let { throw it }
        CommandResult(buildJsonObject { put("verb", args.verb) }, "did ${args.verb}")
    }

    private fun file(vararg lines: String): String =
        File.createTempFile("loopky-batch", ".ndjson").apply { deleteOnExit() }
            .also { it.writeText(lines.joinToString("\n") + "\n") }
            .absolutePath

    private fun batchArgs(source: String, vararg extra: String) =
        Args.parse(arrayOf("batch", source) + extra)

    private fun event(index: Int) = Json.parseToJsonElement(events[index]).jsonObject["data"]!!.jsonObject

    // -- the ordinary run ------------------------------------------------------------------------

    @Test
    fun `runs every line in order, through the same parser a command line uses`() = runBlocking {
        val source = file(
            """{"argv": ["deck", "create", "--title", "Capitais", "--id", "d1", "--if-not-exists"]}""",
            """{"argv": ["card", "add", "d1", "--front", "Brasil", "--back", "Brasilia"]}""",
            """{"argv": ["card", "list", "d1"]}""",
        )

        val result = batch(batchArgs(source), runner(), sinks)

        assertEquals(listOf("deck create", "card add", "card list"), ran.map { it.verb })
        assertEquals("Capitais", ran[0].option("title"))
        assertEquals("d1", ran[1].word(2))
        assertEquals("3", result.data.jsonObject["succeeded"]!!.jsonPrimitive.content)
        assertEquals("0", result.data.jsonObject["failed"]!!.jsonPrimitive.content)
    }

    /** The bare array is what `jq` naturally produces; accepting it costs one branch. */
    @Test
    fun `takes the bare argv array as well as the object`() = runBlocking {
        val source = file("""["whoami"]""", """{"argv": ["deck", "list"]}""")

        batch(batchArgs(source), runner(), sinks)

        assertEquals(listOf("whoami", "deck list"), ran.map { it.verb })
    }

    @Test
    fun `blank lines are skipped and do not shift the index`() = runBlocking {
        val source = file("""["whoami"]""", "", "   ", """["deck", "list"]""")

        val result = batch(batchArgs(source), runner(), sinks)

        assertEquals("2", result.data.jsonObject["operations"]!!.jsonPrimitive.content)
        assertEquals("1", event(1)["index"]!!.jsonPrimitive.content)
    }

    /** A caller correlating results needs its own handle, not a line number it has to keep. */
    @Test
    fun `an id is echoed back on the stream and in the summary`() = runBlocking {
        val source = file("""{"id": "add-brasil", "argv": ["card", "add", "d1", "--front", "a", "--back", "b"]}""")

        val result = batch(batchArgs(source), runner(), sinks)

        assertEquals("add-brasil", event(0)["id"]!!.jsonPrimitive.content)
        val summarised = result.data.jsonObject["results"]!!.toString()
        assertTrue("add-brasil" in summarised, summarised)
    }

    /** The streamed line carries the whole result; the summary is compact because of it. */
    @Test
    fun `each operation streams its own result line`() = runBlocking {
        val source = file("""["whoami"]""", """["deck", "list"]""")

        batch(batchArgs(source), runner(), sinks)

        assertEquals(2, events.size)
        val first = Json.parseToJsonElement(events[0]).jsonObject
        assertEquals("batch", first["command"]!!.jsonPrimitive.content)
        assertEquals("operation", first["event"]!!.jsonPrimitive.content)
        assertEquals("whoami", event(0)["command"]!!.jsonPrimitive.content)
        assertEquals("whoami", event(0)["data"]!!.jsonObject["verb"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, event(0)["error"])
    }

    // -- failure ---------------------------------------------------------------------------------

    /**
     * One refused operation is not a reason to abandon the six hundred after it — the same rule
     * `card edit --from-file` follows, and for the same reason: when the rows apply singly, the
     * row is not the problem.
     */
    @Test
    fun `a failed operation does not end the run`() = runBlocking {
        val source = file("""["whoami"]""", """["deck", "show", "gone"]""", """["deck", "list"]""")
        val runner = runner { if (it.verb == "deck show") CliError(ExitCode.NotFound, "no such deck") else null }

        val error = assertFailsWith<CliError> { batch(batchArgs(source), runner, sinks) }

        assertEquals(listOf("whoami", "deck show", "deck list"), ran.map { it.verb })
        val payload = error.data!!.jsonObject
        assertEquals("2", payload["succeeded"]!!.jsonPrimitive.content)
        assertEquals("1", payload["failed"]!!.jsonPrimitive.content)
        assertEquals("0", payload["not_attempted"]!!.jsonPrimitive.content)
    }

    /**
     * The exit code is the *first failure's*, never one of the batch's own: `session_expired` and
     * `storage_full` say different things about whether re-running the file is worth anything.
     */
    @Test
    fun `the exit code carries the reason the run went wrong`() = runBlocking {
        val source = file("""["deck", "list"]""", """["card", "add", "d1", "--front", "a", "--back", "b"]""")
        val runner = runner { if (it.verb == "card add") CliError(ExitCode.StorageFull, "507") else null }

        val error = assertFailsWith<CliError> { batch(batchArgs(source), runner, sinks) }

        assertEquals(ExitCode.StorageFull, error.exitCode)
    }

    /** The result travels on the failure envelope, so a caller knows which operations landed. */
    @Test
    fun `a failed run still reports what it did`() = runBlocking {
        val source = file("""["whoami"]""", """["deck", "show", "gone"]""")
        val runner = runner { if (it.verb == "deck show") CliError(ExitCode.NotFound, "no such deck") else null }

        val error = assertFailsWith<CliError> { batch(batchArgs(source), runner, sinks) }

        val results = error.data!!.jsonObject["results"]!!.toString()
        assertTrue("\"ok\":true" in results && "\"ok\":false" in results, results)
        assertTrue("not_found" in results, results)
        assertTrue("operation 1" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `--stop-on-error stops, and says how many were never reached`() = runBlocking {
        val source = file("""["whoami"]""", """["deck", "show", "gone"]""", """["deck", "list"]""")
        val runner = runner { if (it.verb == "deck show") CliError(ExitCode.NotFound, "no") else null }

        val error = assertFailsWith<CliError> { batch(batchArgs(source, "--stop-on-error"), runner, sinks) }

        assertEquals(listOf("whoami", "deck show"), ran.map { it.verb })
        assertEquals("1", error.data!!.jsonObject["not_attempted"]!!.jsonPrimitive.content)
    }

    /** An `Error` — an `UnsatisfiedLinkError`, say — must not end the run with nothing said. */
    @Test
    fun `a throwable that is not a CliError is classified rather than escaping`() = runBlocking {
        val source = file("""["deck", "list"]""")
        val runner: suspend (Args) -> CommandResult = { throw UnsatisfiedLinkError("pubkycore not found") }

        val error = assertFailsWith<CliError> { batch(batchArgs(source), runner, sinks) }

        assertEquals("1", error.data!!.jsonObject["failed"]!!.jsonPrimitive.content)
    }

    // -- validation, before anything runs --------------------------------------------------------

    /** A malformed line 400 fails with the homeserver untouched, not 399 operations in. */
    @Test
    fun `the whole file is parsed before the first operation runs`() = runBlocking {
        val source = file("""["whoami"]""", """not json at all""")

        val error = assertFailsWith<CliError> { batch(batchArgs(source), runner(), sinks) }

        assertEquals(ExitCode.BadInput, error.exitCode)
        assertEquals(emptyList(), ran)
        assertTrue("Line 2" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `refuses a line that is not an argv`() = runBlocking {
        for (bad in listOf("""{"command": "whoami"}""", """{"argv": []}""", """{"argv": [1, 2]}""", "42")) {
            val error = assertFailsWith<CliError>("'$bad' should be refused") {
                batch(batchArgs(file(bad)), runner(), sinks)
            }
            assertEquals(ExitCode.BadInput, error.exitCode, "for $bad")
        }
    }

    /**
     * The four verbs a run cannot contain, each for its own reason — and `batch` most of all,
     * since a file that includes itself is a loop with a homeserver on the end of it.
     */
    @Test
    fun `refuses the verbs that cannot mean anything inside a run`() = runBlocking {
        for (verb in listOf("batch", "login", "logout", "update", "completion", "commands")) {
            val error = assertFailsWith<CliError>("`$verb` should be refused") {
                batch(batchArgs(file("""["$verb"]""")), runner(), sinks)
            }
            assertEquals(ExitCode.BadInput, error.exitCode, "for `$verb`")
            assertTrue(verb in error.message.orEmpty(), error.message.orEmpty())
            assertEquals(emptyList(), ran)
        }
    }

    /**
     * A typo is a usage mistake, and letting `dispatch` report it means 399 operations have
     * already written to the homeserver by the time it does.
     */
    @Test
    fun `refuses a verb this binary does not have, before anything runs`() = runBlocking {
        val source = file("""["whoami"]""", """["deck", "crate", "--title", "T"]""")

        val error = assertFailsWith<CliError> { batch(batchArgs(source), runner(), sinks) }

        assertEquals(ExitCode.BadInput, error.exitCode)
        assertEquals(emptyList(), ran)
        assertTrue("deck crate" in error.message.orEmpty(), error.message.orEmpty())
        // The same near-miss clause `dispatch` gives a command line (#293).
        assertTrue("Did you mean `deck create`?" in error.message.orEmpty(), error.message.orEmpty())
    }

    /** The reported line is the file's line, so a blank above it does not shift the answer. */
    @Test
    fun `a parse error reports the line the file actually has`() = runBlocking {
        val source = file("""["whoami"]""", "", "", """not json at all""")

        val error = assertFailsWith<CliError> { batch(batchArgs(source), runner(), sinks) }

        assertTrue("Line 4" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `an empty file and a missing one are both bad input`() = runBlocking {
        assertEquals(ExitCode.BadInput, assertFailsWith<CliError> { batch(batchArgs(file("")), runner(), sinks) }.exitCode)
        assertEquals(
            ExitCode.BadInput,
            assertFailsWith<CliError> { batch(batchArgs("/no/such/file.ndjson"), runner(), sinks) }.exitCode,
        )
    }

    @Test
    fun `a missing operand is a usage error`() = runBlocking {
        val error = assertFailsWith<CliError> { batch(Args.parse(arrayOf("batch")), runner(), sinks) }
        assertEquals(ExitCode.Usage, error.exitCode)
    }

    /**
     * The envelope's own `ok` has to be the operation's.
     *
     * `eventEnvelope` hardcoded `true` — it was written for `login`'s `auth_url`, which cannot
     * fail — so a 500-operation run in which half the writes failed streamed 500 lines saying
     * `"ok": true`, and only the final summary disagreed. Branching on the envelope is the obvious
     * way to read a stream of envelopes, and `--json` is a versioned API, not a print format.
     */
    @Test
    fun `a failed operation streams an envelope that says so`() = runBlocking {
        val source = file("""["whoami"]""", """["deck", "show", "gone"]""")
        val runner = runner { if (it.verb == "deck show") CliError(ExitCode.NotFound, "no such deck") else null }

        assertFailsWith<CliError> { batch(batchArgs(source), runner, sinks) }

        val envelopes = events.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(listOf(true, false), envelopes.map { it["ok"]!!.jsonPrimitive.content.toBoolean() })
        // And the payload still agrees with the envelope, so neither reading is wrong.
        assertEquals(listOf(true, false), envelopes.map { it["data"]!!.jsonObject["ok"]!!.jsonPrimitive.content.toBoolean() })
        assertEquals("not_found", event(1)["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    /**
     * A result goes to the result channel.
     *
     * The human mode used to hand `outcome.text` to the `note` sink, which is stderr — so
     * `loopky batch ops.ndjson > out.txt` captured the summary line and nothing else, while every
     * answer went to the terminal. `Main` contracts stdout as the channel results go to.
     */
    @Test
    fun `an operation's rendered result is a result, not a note`() = runBlocking {
        val source = file("""["deck", "show", "d1"]""", """["deck", "list"]""")

        batch(batchArgs(source), runner(), sinks)

        assertEquals(listOf("did deck show", "did deck list"), texts)
        assertEquals(emptyList(), notes)
    }

    /**
     * Cancellation is not an operation failure. Swallowing it recorded `internal` and then ran the
     * remaining lines, each cancelling instantly at its own first suspension point — 600 reported
     * failures for one cancellation (CLAUDE.md, "Cancellation").
     */
    @Test
    fun `a cancelled run stops instead of failing every remaining operation`() = runBlocking {
        val source = file("""["whoami"]""", """["deck", "list"]""", """["deck", "sync", "d1"]""")
        val runner: suspend (Args) -> CommandResult = { args ->
            ran += args
            if (args.verb == "deck list") throw CancellationException("caller went away")
            CommandResult(buildJsonObject { put("verb", args.verb) }, "")
        }

        assertFailsWith<CancellationException> { batch(batchArgs(source), runner, sinks) }

        assertEquals(listOf("whoami", "deck list"), ran.map { it.verb })
    }

    /** A command with nothing to report is still a successful operation. */
    @Test
    fun `an operation whose result is nothing still counts`() = runBlocking {
        val source = file("""["deck", "sync", "d1"]""")
        val runner: suspend (Args) -> CommandResult = { ok("Synced") }

        val result = batch(batchArgs(source), runner, sinks)

        assertEquals("1", result.data.jsonObject["succeeded"]!!.jsonPrimitive.content)
    }
}
