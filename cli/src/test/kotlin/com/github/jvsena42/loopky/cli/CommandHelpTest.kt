package com.github.jvsena42.loopky.cli

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandHelpTest {

    @Test
    fun `a command's help is its synopsis and every option it takes`() {
        val command = requireNotNull(commandFor("card edit"))
        val help = commandHelp(command)

        assertTrue(help.startsWith("loopky card edit <deckId> [cardId] [options]"), help)
        command.options.forEach { assertContains(help, "--${it.name}") }
        assertContains(help, "--from-file FILE")
        assertContains(help, "--check-images ")
    }

    @Test
    fun `a closed-set operand lists its choices`() {
        val help = commandHelp(requireNotNull(commandFor("completion")))
        assertTrue(help.startsWith("loopky completion bash|zsh|fish"), help)
    }

    @Test
    fun `every command has help, and an unknown verb has none`() {
        cliCommands().forEach { assertEquals(it, commandFor(it.path)) }
        assertNull(commandFor("deck"))
        assertNull(commandFor(""))
    }
}
