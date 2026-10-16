package com.github.jvsena42.loopky.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The regression this file exists for (#240, finding 1): `loopky card list ""` reached the
 * homeserver as `pubky://…/decks//manifest.json` and came back `internal` / exit 1 — the one code
 * an agent is supposed to retry, against an input that can never succeed.
 */
class OperandsTest {

    @Test
    fun `a blank id is bad input, never internal`() {
        for (blank in listOf("", " ", "\t", "\n")) {
            val error = assertFailsWith<CliError> {
                Args.parse(arrayOf("card", "list", blank)).requireWord(2, "deckId")
            }
            assertEquals(ExitCode.BadInput, error.exitCode, "for '${blank.trim()}'")
        }
    }

    /** Absent and blank are different mistakes, and only one of them is a command line error. */
    @Test
    fun `a missing id is still a usage error`() {
        val error = assertFailsWith<CliError> {
            Args.parse(arrayOf("card", "list")).requireWord(2, "deckId")
        }
        assertEquals(ExitCode.Usage, error.exitCode)
    }

    @Test
    fun `refuses what cannot be a path segment`() {
        for (bad in listOf("a/b", "a\\b", "a?b", "a#b", "with space", ".", "..", "line\nbreak")) {
            val error = assertFailsWith<CliError>("'$bad' should be refused") {
                requireUsableOperand(bad, "deckId")
            }
            assertEquals(ExitCode.BadInput, error.exitCode, "for '$bad'")
        }
    }

    /**
     * The allowlist this deliberately is not. Ids are twelve lowercase alphanumerics today; a
     * charset check would refuse a shape a later release mints, from a binary nobody in that
     * sandbox can upgrade.
     */
    @Test
    fun `accepts anything that can address a record`() {
        for (ok in listOf("abc123def456", "A-Z_0.9", "deck~1", "café")) {
            assertEquals(ok, requireUsableOperand(ok, "deckId"))
        }
    }

    @Test
    fun `the message says what to run instead`() {
        val error = assertFailsWith<CliError> { requireUsableOperand("", "deckId") }
        assertTrue("deck list" in error.message.orEmpty(), error.message.orEmpty())
    }
}
