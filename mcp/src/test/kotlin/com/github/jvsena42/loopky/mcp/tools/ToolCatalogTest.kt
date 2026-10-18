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
            "deck delete", "deck sync", "deck compact", "card reorder", "card check-images", "batch",
            "login", "logout", "doctor", "update", "completion", "commands",
        ).forEach { assertFalse(it in served, "$it is served") }
        assertEquals(
            listOf(
                "whoami", "deck_list", "deck_show", "deck_create", "deck_edit", "card_list", "card_add", "card_edit",
                "card_remove", "card_move", "import", "tag_trending",
            ),
            tools.map { it.name },
        )
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
                "deck_edit" to "overwrites", "card_list" to "read", "card_add" to "adds",
                "card_edit" to "overwrites", "card_remove" to "overwrites", "card_move" to "overwrites",
                "import" to "adds", "tag_trending" to "read",
            ),
            kinds,
        )
        assertEquals(true, annotations.getValue("tag_trending").openWorldHint)
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
