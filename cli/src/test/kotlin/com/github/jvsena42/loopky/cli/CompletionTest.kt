package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.cli.commands.completion
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What this file is actually for: **drift**.
 *
 * A completion script cannot be wrong in a way anyone notices. It offers a flag that the parser
 * refuses, or stays silent about one that works, and either way the user shrugs and types it out.
 * So the generator reads one table and these tests hold that table against the parser and the
 * usage block — a new flag that nothing here knows about fails the build rather than shipping as
 * a completion that quietly lies.
 */
class CompletionTest {

    private fun parse(vararg argv: String) = Args.parse(arrayOf(*argv))

    // -- the table against the rest of the CLI ---------------------------------------------------

    @Test
    fun `every command path is a verb the parser produces`() {
        cliCommands().forEach { command ->
            val argv = command.path.split(" ").toTypedArray()
            assertEquals(command.path, Args.parse(argv).verb, "'${command.path}' does not parse as its own verb")
        }
    }

    @Test
    fun `every command is documented in the usage block`() {
        cliCommands().forEach { command ->
            assertContains(USAGE, command.path, message = "'${command.path}' is missing from USAGE")
        }
    }

    /**
     * The direction that corrupts data: a switch the shell offers but the parser does not know is
     * a switch swallows the next word as its value. `--listen 3f9a…` becomes a deck id read as a
     * language tag, with nothing reporting it.
     */
    @Test
    fun `every option the table calls a switch is one to the parser`() {
        offeredOptions()
            .filter { it.value == OptionValue.Switch }
            .forEach { assertContains(Args.SWITCHES, it.name, "--${it.name} is offered as a switch but takes a value") }
    }

    /** And the reverse: a switch the parser knows and nothing offers is either new or deliberate. */
    @Test
    fun `every switch the parser knows is offered or deliberately withheld`() {
        val offered = offeredOptions().filter { it.value == OptionValue.Switch }.map { it.name }.toSet()
        val unaccounted = Args.SWITCHES - offered - UNOFFERED_SWITCHES
        assertEquals(
            emptySet(),
            unaccounted,
            "add these to a command in CommandSurface.kt, or to UNOFFERED_SWITCHES with the reason",
        )
    }

    /**
     * The hole the switch checks above leave: an option that *takes a value* is invisible to
     * [Args], which only declares the switches. Nothing would notice one being added to a command
     * and not to the table — it would simply never be completed. The usage block is the third
     * copy of the surface and the one a person writes when adding a flag, so the two are held
     * against each other in both directions.
     */
    @Test
    fun `the usage block and the completion table describe the same flags`() {
        val documented = Regex("--[a-z][a-z0-9-]*").findAll(USAGE).map { it.value.removePrefix("--") }.toSet()
        val described = offeredOptions().map { it.name }.toSet() + UNOFFERED_SWITCHES
        assertEquals(emptySet(), documented - described, "in USAGE, missing from CommandSurface.kt")
        assertEquals(emptySet(), described - documented - UNOFFERED_SWITCHES, "in CommandSurface.kt, missing from USAGE")
    }

    @Test
    fun `an option that takes a value is never also declared a switch`() {
        offeredOptions()
            .filter { it.value != OptionValue.Switch }
            .forEach { assertTrue(it.name !in Args.SWITCHES, "--${it.name} takes a value but the parser drops it") }
    }

    /** One name, one meaning. The scripts write one arm per kind, so two kinds would pick one. */
    @Test
    fun `an option name means the same thing wherever it appears`() {
        offeredOptions().groupBy { it.name }.forEach { (name, uses) ->
            assertEquals(1, uses.map { it.value }.distinct().size, "--$name takes different things in different commands")
        }
    }

    /**
     * zsh's `_describe` splits on the first colon, and fish's `-d` takes a single-quoted string.
     * A stray quote or colon breaks the script at *source* time, in the user's shell, where the
     * error is a syntax complaint about a generated file nobody wrote.
     */
    @Test
    fun `no summary carries a character the scripts quote with`() {
        val summaries = cliCommands().map { it.summary } + offeredOptions().map { it.summary } +
            topLevelWords().map { it.second }
        summaries.forEach { summary ->
            assertTrue(
                summary.none { it in ":'\"$`\\" },
                "'$summary' contains a character that would break the generated script",
            )
        }
    }

    // -- the generated scripts -------------------------------------------------------------------

    @Test
    fun `bash script names the commands, the flags and the values`() {
        val script = scriptFor("bash")
        assertContains(script, "complete -F _loopky loopky")
        assertContains(script, "\"deck create\")")
        assertContains(script, "--front-lang")
        assertContains(script, "staging production")
        // `import` is the one command whose operand is a file.
        assertContains(script, "compgen -f")
    }

    @Test
    fun `zsh script is autoloadable and carries descriptions`() {
        val script = scriptFor("zsh")
        assertTrue(script.startsWith("#compdef loopky"), "zsh reads the tag on the first line")
        assertContains(script, "'--title:the deck title'")
        // Autoloaded from $fpath the function must run itself; sourced from .zshrc it must not,
        // because `_describe` does not exist outside a completion context. Both, or one of the
        // two documented install paths prints errors on every new shell.
        assertContains(script, "\$funcstack[1]")
        assertContains(script, "compdef _loopky loopky")
    }

    @Test
    fun `fish script conditions every command on the command being typed`() {
        val script = scriptFor("fish")
        assertContains(script, "function __loopky_is")
        assertContains(script, "complete -c loopky -f")
        assertContains(script, "complete -c loopky -n '__loopky_is \"deck create\"' -l title -r")
        // A switch takes no argument, so it must carry neither -r nor -x.
        assertContains(script, "complete -c loopky -n '__loopky_is \"deck create\"' -l listen -d")
    }

/**
     * fish spells a grouped command as a condition plus a word rather than as `deck create`, so
     * the check is on the verb rather than the path. It still catches the failure that matters —
     * a command in the table that no script offers at all.
     */
    @Test
    fun `every shell offers every command in the table`() {
        COMPLETION_SHELLS.forEach { shell ->
            val script = scriptFor(shell)
            cliCommands().forEach { command ->
                val verb = command.path.substringAfterLast(' ')
                assertContains(script, verb, message = "the $shell script never offers '${command.path}'")
            }
        }
    }

/**
     * Nothing generated may reach the network — the reason deck ids are not completed. A `http://`
     * anywhere in a script is either a fetch on a keypress or a comment inviting one.
     */
    @Test
    fun `no script carries an address`() {
        COMPLETION_SHELLS.forEach { shell ->
            val script = scriptFor(shell)
            assertTrue(Regex("https?://") !in script, "the $shell script would talk to the network")
        }
    }

    @Test
    fun `the script is the human output and travels in the envelope too`() {
        val output = completion(parse("completion", "bash"))
        assertTrue(output.text.startsWith("# loopky bash completion"))
        assertContains(output.data.toString(), "_loopky")
        assertContains(output.data.toString(), "\"shell\":\"bash\"")
    }

    @Test
    fun `a missing shell is a usage error naming the shells`() {
        val error = assertFailsWith<CliError> { completion(parse("completion")) }
        assertEquals(ExitCode.Usage, error.exitCode)
        COMPLETION_SHELLS.forEach { assertContains(error.message.orEmpty(), it) }
    }

    @Test
    fun `an unknown shell is refused rather than guessed at`() {
        val error = assertFailsWith<CliError> { completion(parse("completion", "powershell")) }
        assertEquals(ExitCode.Usage, error.exitCode)
        assertContains(error.message.orEmpty(), "powershell")
    }

    /**
     * The generated script lands in `.bashrc`, so this command runs on every new shell. An
     * awaited HTTPS GET there is a terminal that opens slowly once a day for no visible reason.
     */
    @Test
    fun `completion never triggers the update check`() {
        assertTrue(!UpdateChecker.enabled(parse("completion", "bash")) { null })
        // The exemption is this verb's, not a blanket one.
        assertTrue(UpdateChecker.enabled(parse("deck", "list")) { null })
    }

    private fun scriptFor(shell: String): String = completion(parse("completion", shell)).text

    private fun offeredOptions(): List<CliOption> = cliCommands().flatMap { it.options } + GLOBAL_OPTIONS
}
