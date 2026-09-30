package com.github.jvsena42.loopky.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArgsTest {

    @Test
    fun `reads positional words in order`() {
        val args = Args.parse(arrayOf("card", "edit", "deck1", "card9"))
        assertEquals(listOf("card", "edit", "deck1", "card9"), args.words)
        assertEquals("card edit", args.verb)
        assertEquals("card9", args.requireWord(3, "cardId"))
    }

    /**
     * The verb stops at the noun-plus-verb, never at "the first two words". Taking an operand as
     * part of the verb names the command `import cards.tsv` in the `--json` envelope and looks it
     * up under that name in the dispatcher, where it matches nothing.
     */
    @Test
    fun `a one-word command's operand is not part of the verb`() {
        assertEquals("import", Args.parse(arrayOf("import", "cards.tsv", "--title", "T")).verb)
        assertEquals("whoami", Args.parse(arrayOf("whoami")).verb)
        assertEquals("deck show", Args.parse(arrayOf("deck", "show", "abc123")).verb)
    }

    @Test
    fun `takes an option value from the next token or after an equals`() {
        val args = Args.parse(arrayOf("deck", "create", "--title", "Capitais", "--description=Do Brasil"))
        assertEquals("Capitais", args.option("title"))
        assertEquals("Do Brasil", args.option("description"))
    }

    @Test
    fun `keeps every occurrence of a repeated option`() {
        val args = Args.parse(arrayOf("deck", "create", "--tag", "spanish", "--tag", "language"))
        assertEquals(listOf("spanish", "language"), args.options("tag"))
    }

    /**
     * The reason switches are declared rather than inferred from "the next token starts with
     * `--`". Inference reads the *flag* as the card's front and writes it to the homeserver.
     */
    @Test
    fun `a declared switch never swallows the option after it`() {
        val args = Args.parse(arrayOf("card", "add", "d1", "--json", "--front", "hola"))
        assertTrue(args.has("json"))
        assertEquals("hola", args.option("front"))
    }

    @Test
    fun `an option with no value is a usage error rather than a silent empty`() {
        assertFailsWith<CliError> { Args.parse(arrayOf("deck", "create", "--title")) }
    }

    @Test
    fun `everything after a bare double dash is an operand`() {
        val args = Args.parse(arrayOf("card", "add", "d1", "--front", "x", "--", "--not-a-flag"))
        assertEquals(listOf("card", "add", "d1", "--not-a-flag"), args.words)
    }

    /** A bare `-` is stdin, which every read path accepts as a source. */
    @Test
    fun `a bare dash is an operand`() {
        val args = Args.parse(arrayOf("import", "-", "--title", "T"))
        assertEquals(listOf("import", "-"), args.words)
    }

    @Test
    fun `an unknown short option is refused rather than guessed at`() {
        assertFailsWith<CliError> { Args.parse(arrayOf("deck", "list", "-j")) }
    }

    @Test
    fun `an empty value is kept, because clearing a card side needs one`() {
        val args = Args.parse(arrayOf("card", "edit", "d1", "c1", "--back="))
        assertEquals("", args.option("back"))
    }

    /**
     * `toIntOrNull() ?: default` answers a question nobody asked: `--limit twenty` silently
     * becomes the default, and `--limit -5` passes straight through.
     */
    @Test
    fun `a count option refuses anything that is not a positive whole number`() {
        assertEquals(20, Args.parse(arrayOf("tag", "trending")).positiveInt("limit", 20))
        assertEquals(5, Args.parse(arrayOf("tag", "trending", "--limit", "5")).positiveInt("limit", 20))
        assertFailsWith<CliError> {
            Args.parse(arrayOf("tag", "trending", "--limit", "twenty")).positiveInt("limit", 20)
        }
        assertFailsWith<CliError> {
            Args.parse(arrayOf("tag", "trending", "--limit", "0")).positiveInt("limit", 20)
        }
        assertFailsWith<CliError> {
            Args.parse(arrayOf("tag", "trending", "--limit", "-5")).positiveInt("limit", 20)
        }
    }

    @Test
    fun `an absent option reads as absent, not as blank`() {
        val args = Args.parse(arrayOf("card", "edit", "d1", "c1", "--front", "hola"))
        assertNull(args.option("back"))
        assertFalse(args.has("back"))
    }
}
