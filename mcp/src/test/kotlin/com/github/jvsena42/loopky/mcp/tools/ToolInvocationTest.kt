package com.github.jvsena42.loopky.mcp.tools

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.requireKnownOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Arguments to argv. The argv is what matters: it is parsed by `Args` and validated by the same
 * `requireKnownOptions` the CLI runs, so every refusal here is one the CLI would make too — except
 * the three this layer adds, which are the three a shell would never have let through.
 */
class ToolInvocationTest {

    private val tools = mcpTools().associateBy { it.name }

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun invoke(tool: String, json: String) = ToolInvocation.of(tools.getValue(tool), args(json))

    @Test
    fun `operands and options become the command line the CLI parses`() {
        val argv = invoke(
            "deck_create",
            """{"title":"Birds","tag":["aves","Science"],"dry_run":true,"front_lang":"en-US","description":""}""",
        ).argv(null)
        assertEquals(
            listOf(
                "deck", "create", "--title=Birds", "--description=", "--tag=aves", "--tag=Science",
                "--dry-run", "--front-lang=en-US", "--",
            ),
            argv,
        )
        val parsed = Args.parse(argv.toTypedArray())
        parsed.requireKnownOptions()
        assertEquals("deck create", parsed.verb)
        assertEquals(listOf("aves", "Science"), parsed.options("tag"))
        assertEquals("", parsed.option("description"), "an empty value is the clearing form")
        assertTrue(parsed.has("dry-run"))
    }

    @Test
    fun `operands go after the double dash, in the table's order`() {
        val argv = invoke("card_remove", """{"card_id":"c1","deck_id":"-d1"}""").argv(null)
        assertEquals(listOf("card", "rm", "--", "-d1", "c1"), argv)
        val parsed = Args.parse(argv.toTypedArray())
        assertEquals("-d1", parsed.word(2))
        assertEquals("c1", parsed.word(3))
    }

    @Test
    fun `a required operand missing is a usage error, before anything positional could slip`() {
        val error = assertFailsWith<CliError> { invoke("card_edit", """{"card_id":"c1","front":"x"}""") }
        assertEquals(ExitCode.Usage, error.exitCode)
        assertContains(error.message.orEmpty(), "deck_id")
    }

    @Test
    fun `a false switch is simply absent`() {
        val argv = invoke("deck_edit", """{"deck_id":"d1","listen":false,"no_listen":true}""").argv(null)
        assertEquals(listOf("deck", "edit", "--no-listen", "--", "d1"), argv)
    }

    @Test
    fun `an integer option arrives as its number`() {
        val argv = invoke("card_list", """{"deck_id":"d1","limit":25}""").argv(null)
        assertEquals(listOf("card", "list", "--limit=25", "--", "d1"), argv)
        assertEquals(25, Args.parse(argv.toTypedArray()).positiveInt("limit", 0))
    }

    // -- what a shell would never have let through ------------------------------------------------

    @Test
    fun `a path option is refused by its reason, never passed through`() {
        listOf("deck_create" to """{"title":"x","from_file":"/etc/passwd"}""", "card_add" to """{"deck_id":"d","from_file":"x"}""")
            .forEach { (tool, json) ->
                val error = assertFailsWith<CliError>(tool) { invoke(tool, json) }
                assertEquals(ExitCode.Usage, error.exitCode)
                assertContains(error.message.orEmpty(), "from_file")
                assertContains(error.message.orEmpty(), "inline")
            }
    }

    @Test
    fun `the image probe is refused`() {
        val error = assertFailsWith<CliError> {
            invoke("card_add", """{"deck_id":"d","front":"a","back":"b","check_images":true}""")
        }
        assertEquals(ExitCode.Usage, error.exitCode)
        assertContains(error.message.orEmpty(), "check_images")
        val dial = assertFailsWith<CliError> { invoke("import", """{"text":"a\tb","check_images_concurrency":4}""") }
        assertContains(dial.message.orEmpty(), "check_images_concurrency")
    }

    @Test
    fun `an input the schema lacks is refused by name, with what the tool takes`() {
        val error = assertFailsWith<CliError> { invoke("deck_list", """{"deck_id":"d1"}""") }
        assertEquals(ExitCode.Usage, error.exitCode)
        assertContains(error.message.orEmpty(), "deck_id")
        val global = assertFailsWith<CliError> { invoke("deck_show", """{"deck_id":"d1","env":"staging"}""") }
        assertContains(global.message.orEmpty(), "env")
    }

    // -- inline content ---------------------------------------------------------------------------

    @Test
    fun `cards become a JSONL card file handed over as from-file`() {
        val invocation = invoke(
            "card_add",
            """{"deck_id":"d1","cards":[{"front":"hola","back":"hello"},{"front":"adiós","back":"bye","back_image_url":null}]}""",
        )
        val content = assertNotNull(invocation.inline)
        assertEquals("jsonl", content.extension)
        assertEquals(
            """{"front":"hola","back":"hello"}""" + "\n" + """{"front":"adiós","back":"bye","back_image_url":null}""",
            String(content.bytes, Charsets.UTF_8),
        )
        val file = Path.of("/srv/scratch/call-1/x.jsonl")
        assertEquals(listOf("card", "add", "--from-file=$file", "--", "d1"), invocation.argv(file))
    }

    @Test
    fun `import text is the positional file`() {
        val invocation = invoke("import", """{"title":"T","text":"a\tb\nc\td"}""")
        val file = Path.of("/srv/scratch/call-2/x.txt")
        assertEquals(listOf("import", "--title=T", "--", file.toString()), invocation.argv(file))
        assertEquals("a\tb\nc\td", String(assertNotNull(invocation.inline).bytes, Charsets.UTF_8))
    }

    @Test
    fun `import without text is a usage error, and a single card tool without content has no file`() {
        assertEquals(ExitCode.Usage, assertFailsWith<CliError> { invoke("import", """{"title":"T"}""") }.exitCode)
        assertNull(invoke("card_add", """{"deck_id":"d1","front":"a","back":"b"}""").inline)
    }

    @Test
    fun `cards and text together are refused`() {
        val error = assertFailsWith<CliError> { invoke("card_add", """{"deck_id":"d1","cards":[],"text":"a\tb"}""") }
        assertEquals(ExitCode.Usage, error.exitCode)
    }

    @Test
    fun `a card object with a key a card does not have is refused, naming it`() {
        val error = assertFailsWith<CliError> {
            invoke("card_add", """{"deck_id":"d1","cards":[{"front":"a","back":"b","path":"/x"}]}""")
        }
        assertEquals(ExitCode.BadInput, error.exitCode)
        assertContains(error.message.orEmpty(), "path")
    }

    @Test
    fun `too many cards are refused before anything is written`() {
        val rows = (1..InlineLimits.MAX_CARDS + 1).joinToString(",") { """{"front":"f$it","back":"b$it"}""" }
        val error = assertFailsWith<CliError> { invoke("card_add", """{"deck_id":"d1","cards":[$rows]}""") }
        assertEquals(ExitCode.BadInput, error.exitCode)
        assertContains(error.message.orEmpty(), "${InlineLimits.MAX_CARDS}")
        val lines = (1..InlineLimits.MAX_CARDS + 1).joinToString("\\n") { "f$it\\tb$it" }
        assertEquals(ExitCode.BadInput, assertFailsWith<CliError> { invoke("import", """{"text":"$lines"}""") }.exitCode)
    }

    @Test
    fun `too many bytes are refused`() {
        val text = "a".repeat(InlineLimits.MAX_BYTES + 1)
        val error = assertFailsWith<CliError> { invoke("import", """{"text":"$text"}""") }
        assertEquals(ExitCode.BadInput, error.exitCode)
        assertContains(error.message.orEmpty(), "bytes")
    }

    /** An archive is an `.apkg` whatever it is called, and the reader would open it as one. */
    @Test
    fun `a zip is refused as an apkg`() {
        val zip = "PK\u0003\u0004rest of a zip"
        val error = assertFailsWith<CliError> { invoke("import", """{"title":"T","text":"$zip"}""") }
        assertEquals(ExitCode.BadInput, error.exitCode)
        assertContains(error.message.orEmpty(), ".apkg")
    }

    @Test
    fun `every picture URL the call carries is collected for the guard`() {
        val invocation = invoke(
            "card_add",
            """{"deck_id":"d1","front_image":"https://a.example/1.png",""" +
                """"cards":[{"front":"x","back":"y","back_image_url":"https://b.example/2.png"}]}""",
        )
        assertEquals(listOf("https://a.example/1.png", "https://b.example/2.png"), invocation.imageUrls)
        val tsv = invoke("import", """{"text":"a\tb\thttps://c.example/3.jpg"}""")
        assertEquals(listOf("https://c.example/3.jpg"), tsv.imageUrls)
        val semicolons = invoke("import", """{"text":"a;b;;https://d.example/4.jpg"}""")
        assertEquals(listOf("https://d.example/4.jpg"), semicolons.imageUrls)
    }

    /** A URL on a side is content the import takes as it is; only a picture position is the guard's. */
    @Test
    fun `a URL inside a front or a back is not a picture, and is not guarded`() {
        val prose = invoke(
            "card_add",
            """{"deck_id":"d1","text":"What does http://example.com serve?\thttps://192.168.1.1/admin\n""" +
                """{\"front\":\"router\",\"back\":\"https://10.0.0.1/\"}\n"}""",
        )
        assertEquals(emptyList(), prose.imageUrls)
        val imported =
            invoke("import", """{"text":"router,https://10.0.0.1/\nhttps://a.example/x: https://b.example/y"}""")
        assertEquals(emptyList(), imported.imageUrls)
    }
}
