package com.github.jvsena42.loopky.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An unknown verb names its near miss, the way an unknown flag names where it belongs (#293).
 *
 * The code an agent branches on stays `Usage`/2 and the message stays one line; these pin what the
 * extra clause says, and — the half that matters more — when it says nothing.
 */
class UnknownCommandTest {

    private fun parse(vararg argv: String) = Args.parse(arrayOf(*argv))

    /** The exact case from the issue: `commands` minus one character. */
    @Test
    fun `a dropped plural names the command`() {
        assertEquals(
            "Unknown command 'command'. Did you mean `commands`? Try `loopky --help`.",
            parse("command", "--json").unknownCommandMessage(),
        )
    }

    /** Full paths, not first words: `deck ls` points at `deck list`, not at `deck`. */
    @Test
    fun `a mistyped group verb names the full path`() {
        assertEquals("Did you mean `deck list`?", parse("deck", "ls").nearMissHint())
        assertEquals("Did you mean `card list`?", parse("card", "lst", "d1").nearMissHint())
    }

    /** A mistyped noun leaves the verb as one word, so the first two words are matched as well. */
    @Test
    fun `a mistyped group noun still reaches the command`() {
        assertEquals("Did you mean `deck list`?", parse("dek", "list").nearMissHint())
        assertEquals("Did you mean `import`?", parse("imprt", "deck.tsv").nearMissHint())
    }

    @Test
    fun `case is not a reason to miss`() {
        assertEquals("Did you mean `whoami`?", parse("WhoAmI").nearMissHint())
    }

    /**
     * Two candidates within reach means no guess. `card ed` is two edits from `card add` and from
     * `card edit`, so it gets the group's verbs instead of either.
     */
    @Test
    fun `an ambiguous near miss guesses nothing`() {
        val hint = parse("card", "ed").nearMissHint()

        assertEquals("`card` takes one of: list, add, edit, rm.", hint)
        assertFalse(parse("card", "ed").unknownCommandMessage().contains("Did you mean"))
    }

    /**
     * A synonym is nowhere near by edits, and the nearest *word* can be the worst answer:
     * `card delete` spelled as `deck delete` would delete a deck. The group's verbs are offered.
     */
    @Test
    fun `a synonym gets the group's verbs, never another group's command`() {
        assertEquals("`card` takes one of: list, add, edit, rm.", parse("card", "delete").nearMissHint())
        assertEquals("`card` takes one of: list, add, edit, rm.", parse("card", "remove").nearMissHint())
    }

    @Test
    fun `a group noun on its own lists its verbs`() {
        assertEquals(
            "Unknown command 'deck'. `deck` takes one of: list, show, create, edit, delete, sync, compact. " +
                "Try `loopky --help`.",
            parse("deck").unknownCommandMessage(),
        )
    }

    @Test
    fun `nothing close says nothing extra`() {
        assertNull(parse("teleport", "--nowhere", "x").nearMissHint())
        assertEquals("Unknown command 'teleport'. Try `loopky --help`.", parse("teleport").unknownCommandMessage())
    }

    /**
     * Held against the whole table, since the next command added could sit two edits from an old
     * one. Silence is allowed — `logou` is as near `login` as `logout` — naming another command is not.
     */
    @Test
    fun `a one-edit typo never names a different command`() {
        cliCommands().forEach { command ->
            val typo = command.path.dropLast(1)
            val hint = Args.parse(typo.split(" ").toTypedArray()).nearMissHint()
            assertTrue(
                hint == null || !hint.startsWith("Did you mean") || hint == "Did you mean `${command.path}`?",
                "'$typo' hinted $hint",
            )
        }
    }
}
