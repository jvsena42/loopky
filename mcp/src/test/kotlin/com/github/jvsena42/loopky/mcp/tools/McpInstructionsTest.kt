package com.github.jvsena42.loopky.mcp.tools

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpInstructionsTest {

    private val instructions = McpInstructions.load()

    @Test
    fun `the instructions carry the skill's card-writing rules`() {
        assertTrue(instructions.isNotBlank())
        assertContains(instructions, "## Writing good cards")
        assertContains(instructions, "One fact per card")
        assertContains(instructions, "Parenthesized asides are notes, not answers")
        assertContains(instructions, "## Where to find pictures")
    }

    @Test
    fun `the binary's workflow is left out, and the tools' is put in`() {
        assertFalse("loopky --version" in instructions, "the install step is about a binary")
        assertFalse("## Exit codes" in instructions)
        assertContains(instructions, "dry_run")
        assertContains(instructions, "deck_show")
        assertContains(instructions, "reconnect")
    }
}
