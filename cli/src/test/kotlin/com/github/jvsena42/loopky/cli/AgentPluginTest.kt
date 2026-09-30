package com.github.jvsena42.loopky.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The agent plugin under `plugins/` against the binary it describes (#388).
 *
 * The skill is prose an agent follows literally, so a renamed flag there is not a stale doc — it is
 * a command the agent runs and gets exit 2 from, in a session nobody is watching. Nothing else in the
 * build reads those files, so this is what makes a surface change fail until the skill catches up.
 */
class AgentPluginTest {

    private val root = File(requireNotNull(System.getProperty("loopky.repoRoot")) { "set by cli/build.gradle.kts" })
    private val cliVersion = requireNotNull(System.getProperty("loopky.cliVersion"))
    private val skills = File(root, "plugins").walk().filter { it.name == "SKILL.md" }.toList()

    @Test
    fun `there is a skill to check`() {
        assertTrue(skills.isNotEmpty(), "no SKILL.md under ${root.resolve("plugins")}")
    }

    @Test
    fun `every command a skill names is one the binary has`() {
        val paths = cliCommands().map { it.path }.toSet()
        skills.forEach { skill ->
            invocations(skill).forEach { (line, words) ->
                val command = commandOf(words, paths)
                if (command != null) assertTrue(command in paths, "${skill.relative()}:$line names `loopky $command`")
            }
        }
    }

    @Test
    fun `every flag a skill names is one its command takes`() {
        val commands = cliCommands().associateBy { it.path }
        val globals = GLOBAL_OPTIONS.map { it.name }.toSet() + UNOFFERED_SWITCHES
        skills.forEach { skill ->
            invocations(skill).forEach { (line, words) ->
                val taken = commandOf(words, commands.keys)?.let { commands[it] }?.options?.map { it.name }.orEmpty()
                words.filter { it.startsWith("--") }.map { it.removePrefix("--").substringBefore('=') }.forEach { flag ->
                    assertTrue(
                        flag in globals || flag in taken,
                        "${skill.relative()}:$line passes --$flag to `loopky ${words.joinToString(" ")}`",
                    )
                }
            }
        }
    }

    /** A flag named on its own — `` `--id <word> --if-not-exists` `` — has no command to check against. */
    @Test
    fun `every flag a skill names on its own is one some command takes`() {
        val known = (cliCommands().flatMap { it.options } + GLOBAL_OPTIONS).map { it.name }.toSet() + UNOFFERED_SWITCHES
        skills.forEach { skill ->
            skill.text().lines().forEachIndexed { index, line ->
                INLINE_CODE.findAll(line).map { it.groupValues[1] }.filterNot { it.startsWith("loopky ") }
                    .flatMap { span -> FLAG.findAll(span.replace(QUOTED, "X")).map { it.groupValues[1] } }
                    .forEach { assertTrue(it in known, "${skill.relative()}:${index + 1} names --$it") }
            }
        }
    }

    /** Every code, so a new one fails here until the skill says what to do about it. */
    @Test
    fun `a skill's exit-code table is the binary's, whole`() {
        skills.forEach { skill ->
            val rows = skill.text().lines().mapNotNull { EXIT_ROW.find(it) }
                .associate { it.groupValues[1].toInt() to it.groupValues[2] }
            assertEquals(
                ExitCode.entries.associate { it.code to it.json },
                rows,
                "${skill.relative()}'s exit-code table",
            )
        }
    }

    @Test
    fun `a skill's frontmatter names its directory and describes when to trigger`() {
        skills.forEach { skill ->
            val front = skill.text().substringAfter("---\n").substringBefore("\n---")
            val fields = front.lines().filter { ':' in it }
                .associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
            assertEquals(skill.parentFile.name, fields["name"], "${skill.relative()} name")
            val description = fields["description"].orEmpty()
            assertTrue(description.length in 1..MAX_DESCRIPTION, "${skill.relative()} description is ${description.length} chars")
        }
    }

    /** The installer is the release's; `main`'s would pipe an unreviewed moving target into `sh`. */
    @Test
    fun `everything that installs uses the release installer`() {
        val installers = skills + File(root, "plugins").walk().filter { it.extension == "sh" }
        installers.forEach { file ->
            val text = file.text()
            assertTrue("raw.githubusercontent.com" !in text.replace(RAW_WARNING, ""), "${file.relative()} installs from main")
            if ("install.sh" in text) assertTrue(RELEASE_INSTALLER in text, "${file.relative()} lacks $RELEASE_INSTALLER")
        }
    }

    /** One number to bump at release, checked by `release.yml` as well; a stale one hides the update. */
    @Test
    fun `plugin versions follow loopkyCliVersion`() {
        File(root, "plugins").walk().filter { it.name == "plugin.json" }.forEach { manifest ->
            assertEquals(cliVersion, manifest.json()["version"]?.jsonPrimitive?.content, "${manifest.relative()} version")
        }
        val marketplaces = listOf(File(root, ".claude-plugin/marketplace.json")).filter { it.exists() }
        if (marketplaces.isEmpty()) fail("no marketplace manifest at the repository root")
        marketplaces.forEach { marketplace ->
            marketplace.json()["plugins"]!!.jsonArray.forEach { entry ->
                val plugin = entry.jsonObject
                assertEquals(cliVersion, plugin["version"]?.jsonPrimitive?.content, "${marketplace.relative()} ${plugin["name"]}")
                val source = plugin["source"]!!.jsonPrimitive.content
                assertTrue(File(root, source).resolve(".claude-plugin/plugin.json").exists(), "$source has no plugin.json")
            }
        }
    }

    /** `loopky …` in inline code or at the start of a fenced line, as line number and words. */
    private fun invocations(skill: File): List<Pair<Int, List<String>>> =
        skill.text().lines().flatMapIndexed { index, line ->
            val spans = INLINE_CODE.findAll(line).map { it.groupValues[1] } +
                listOfNotNull(line.trim().takeIf { it.startsWith("loopky ") })
            spans.filter { it.startsWith("loopky ") }
                .map { index + 1 to words(it.removePrefix("loopky ")) }
                .toList()
        }

    /** Quoted values and `<placeholders>` are operands, never commands or flags. */
    private fun words(invocation: String): List<String> =
        invocation.substringBefore(" |").replace(QUOTED, "X").split(Regex("\\s+")).filter { it.isNotBlank() }

    /** The command path, or null when the words start with a global flag or a placeholder. */
    private fun commandOf(words: List<String>, paths: Set<String>): String? {
        val first = words.firstOrNull() ?: return null
        if (first.startsWith("--") || first.startsWith("<")) return null
        val two = words.take(2).joinToString(" ")
        return if (two in paths || paths.any { it.startsWith("$first ") }) two else first
    }

    /** A Windows checkout may carry CRLF, which the frontmatter split would otherwise trip on. */
    private fun File.text(): String = readText().replace("\r\n", "\n")

    private fun File.json(): JsonObject = Json.parseToJsonElement(readText()).jsonObject

    private fun File.relative(): String = relativeTo(root).path

    private companion object {
        const val MAX_DESCRIPTION = 1024
        const val RELEASE_INSTALLER = "https://github.com/jvsena42/loopky/releases/latest/download/install.sh"
        const val RAW_WARNING = "never `raw.githubusercontent.com"
        val EXIT_ROW = Regex("""^\|\s*(\d+)\s*\|\s*([a-z_]+)\s*\|""")
        val INLINE_CODE = Regex("`([^`]+)`")
        val QUOTED = Regex("\"[^\"]*\"")
        val FLAG = Regex("""(?<![\w-])--([a-z][a-z-]*)""")
    }
}
