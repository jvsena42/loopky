package com.github.jvsena42.loopky.mcp.tools

import com.github.jvsena42.loopky.cli.OptionValue
import com.github.jvsena42.loopky.cli.cliCommands
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Drift. The tool list is derived from the command table, so the failure mode is not a wrong tool
 * but a stale one: an option the table gained that no tool decided about, a path option a tool
 * quietly exposed, a command served that the whitelist never admitted.
 */
class ToolCatalogTest {

    private val tools = mcpTools()
    private val commands = cliCommands().associateBy { it.path }

    @Test
    fun `every tool maps to a command in the table`() {
        tools.forEach { tool -> assertEquals(commands[tool.command.path], tool.command, tool.name) }
        assertEquals(tools.map { it.name }, tools.map { it.name }.distinct(), "tool names are unique")
    }

    /** The decision the server makes about every option, held explicit: nothing is exposed by default. */
    @Test
    fun `every option of a served command is either exposed or refused, and never both`() {
        tools.forEach { tool ->
            val options = tool.command.options.map { it.name }.toSet()
            assertEquals(options, tool.exposed + tool.refused.keys, "${tool.name} has undecided options")
            assertEquals(emptySet(), tool.exposed intersect tool.refused.keys, "${tool.name} exposes and refuses")
        }
    }

    @Test
    fun `no tool exposes a path option, and import has no path operand`() {
        tools.forEach { tool ->
            tool.command.options.filter { it.value == OptionValue.Path }.forEach { option ->
                assertContains(tool.refused.keys, option.name, "${tool.name} exposes --${option.name}, a path")
                assertEquals(PATH_REFUSAL, tool.refused[option.name])
            }
            assertFalse(tool.operandNames().contains("file"), "${tool.name} takes a file operand")
        }
        val import = tools.first { it.name == "import" }
        assertEquals(InlineInput.ImportSource, import.inline)
    }

    @Test
    fun `no tool exposes an image probe option`() {
        tools.forEach { tool ->
            tool.command.options.filter { it.name.startsWith("check-images") }.forEach { option ->
                assertContains(tool.refused.keys, option.name, "${tool.name} exposes --${option.name}")
            }
        }
        assertTrue(tools.none { it.command.path == "card check-images" })
    }

    @Test
    fun `the commands out of v1 are not served`() {
        val served = tools.map { it.command.path }.toSet()
        listOf(
            "deck sync", "deck compact", "card check-images", "batch",
            "login", "logout", "doctor", "update", "completion", "commands",
        ).forEach { assertFalse(it in served, "$it is served") }
        assertEquals(
            listOf(
                "whoami", "deck_list", "deck_show", "deck_create", "deck_edit", "deck_delete", "card_list", "card_add",
                "card_edit", "card_remove", "card_move", "card_reorder", "import", "tag_trending",
            ),
            tools.map { it.name },
        )
    }

    /** The guard the CLI leaves to a person typing the id, the tool requires (#559). */
    @Test
    fun `deck_delete requires the deck's title and says it destroys`() {
        val delete = schemaOf("deck_delete")
        assertEquals(listOf("deck_id", "confirm_title"), delete.required)
        assertEquals("string", delete.properties["confirm_title"].type())
        val annotations = assertNotNull(tools.first { it.name == "deck_delete" }.toTool().annotations)
        assertEquals(true, annotations.destructiveHint)
        assertEquals(false, annotations.idempotentHint)
        // The command itself still takes the option as optional, so the CLI is unchanged.
        assertTrue(commands.getValue("deck delete").options.any { it.name == "confirm-title" })
    }

    @Test
    fun `card_reorder takes its order inline, as an array of ids`() {
        val reorder = schemaOf("card_reorder")
        assertEquals(listOf("deck_id", "card_ids"), reorder.required)
        assertEquals("array", reorder.properties["card_ids"].type())
        assertEquals(InlineLimits.MAX_CARD_IDS, reorder.properties["card_ids"]!!.jsonObject["maxItems"]!!.jsonPrimitive.content.toInt())
        assertFalse("from_file" in reorder.properties)
    }

    @Test
    fun `deck_list pages, narrows and slims`() {
        val list = schemaOf("deck_list")
        assertEquals("integer", list.properties["limit"].type())
        assertEquals("string", list.properties["cursor"].type())
        assertEquals("string", list.properties["query"].type())
        assertEquals("boolean", list.properties["compact"].type())
    }

    @Test
    fun `each write that has a dry run takes its hash back`() {
        listOf("deck_create", "card_add", "import").forEach { name ->
            assertEquals("string", schemaOf(name).properties["expect_hash"].type(), name)
            assertContains(tools.first { it.name == name }.toTool().description.orEmpty(), "content_hash")
        }
    }

    @Test
    fun `global options are never tool inputs`() {
        tools.forEach { tool ->
            val properties = tool.toTool().inputSchema.properties.orEmpty().keys
            listOf("json", "env", "verbose", "help", "version", "no_update_check").forEach {
                assertFalse(it in properties, "${tool.name} takes $it")
            }
        }
    }

    // -- schema derivation -----------------------------------------------------------------------

    @Test
    fun `operands become required string properties in snake case`() {
        val edit = schemaOf("card_edit")
        assertEquals("string", edit.properties["deck_id"].type())
        assertEquals("string", edit.properties["card_id"].type())
        assertEquals(listOf("deck_id"), edit.required)
        assertEquals(listOf("deck_id", "card_id"), schemaOf("card_remove").required)
    }

    @Test
    fun `options are typed by what the table says they take`() {
        val create = schemaOf("deck_create")
        assertEquals("boolean", create.properties["dry_run"].type())
        assertEquals("boolean", create.properties["listen"].type())
        assertEquals("string", create.properties["title"].type())
        assertEquals("array", create.properties["tag"].type())
        assertEquals("string", create.properties["tag"]!!.jsonObject["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("integer", schemaOf("card_list").properties["limit"].type())
        val separator = schemaOf("import").properties["separator"]!!.jsonObject
        assertEquals("string", separator["type"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("auto", "tab", "comma", "semicolon", "pipe", "dash", "colon", "blank", "markdown"),
            separator["enum"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        // The summary travels, so a flag documented in the table is documented to the client.
        assertEquals(
            commands.getValue("deck create").options.first { it.name == "title" }.summary,
            create.properties["title"]!!.jsonObject["description"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `inline content is offered where the command would read a file`() {
        val add = schemaOf("card_add")
        assertEquals("array", add.properties["cards"].type())
        assertEquals("string", add.properties["text"].type())
        assertFalse("from_file" in add.properties)
        val import = schemaOf("import")
        assertEquals(listOf("text"), import.required)
        assertFalse("cards" in import.properties)
        assertFalse("text" in schemaOf("deck_edit").properties)
    }

    @Test
    fun `every tool says whether it reads, adds, or overwrites`() {
        val annotations = tools.associate { it.name to assertNotNull(it.toTool().annotations) }
        // Pinned per tool, so a write tool added on the default hints fails here rather than
        // reaching the directory as additive-only, which is what destructiveHint=false means.
        val kinds = annotations.mapValues { (_, it) ->
            when {
                it.readOnlyHint == true -> "read"
                it.destructiveHint == true -> "overwrites"
                else -> "adds"
            }
        }
        assertEquals(
            mapOf(
                "whoami" to "read", "deck_list" to "read", "deck_show" to "read", "deck_create" to "adds",
                "deck_edit" to "overwrites", "deck_delete" to "overwrites", "card_list" to "read", "card_add" to "adds",
                "card_edit" to "overwrites", "card_remove" to "overwrites", "card_move" to "overwrites",
                "card_reorder" to "overwrites", "import" to "adds", "tag_trending" to "read",
            ),
            kinds,
        )
        assertEquals(true, annotations.getValue("tag_trending").openWorldHint)
    }

    @Test
    fun `a description speaks the tool's own vocabulary`() {
        tools.map { it.toTool() }.forEach { tool ->
            val texts = listOf(tool.description.orEmpty()) +
                tool.inputSchema.properties.orEmpty().values.mapNotNull { it.jsonObject["description"]?.jsonPrimitive?.content }
            texts.forEach { text -> assertFalse(Regex("--[a-z]").containsMatchIn(text), "${tool.name}: a CLI flag in \"$text\"") }
        }
        val import = tools.first { it.name == "import" }.toTool().description.orEmpty()
        assertFalse(".apkg or" in import, import)
        assertTrue(import.startsWith("Publish a deck from pasted text."), import)
    }

    @Test
    fun `every tool description names the command it binds`() {
        tools.forEach { tool ->
            val description = tool.toTool().description.orEmpty()
            assertTrue(description.isNotBlank(), tool.name)
            assertEquals(tool.title, tool.toTool().annotations?.title)
            assertEquals(tool.title, tool.toTool().title)
            assertTrue(tool.title.first().isUpperCase(), "${tool.name}: a title is for people, not the CLI path")
        }
    }

    private class Schema(val properties: JsonObject, val required: List<String>)

    private fun schemaOf(name: String): Schema {
        val schema = tools.first { it.name == name }.toTool().inputSchema
        return Schema(schema.properties.orEmpty().let { JsonObject(it) }, schema.required.orEmpty())
    }

    private fun kotlinx.serialization.json.JsonElement?.type(): String? {
        val type = assertNotNull(this).jsonObject["type"]
        return if (type is JsonArray) type.first().jsonPrimitive.content else type?.jsonPrimitive?.content
    }
}
