package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.FakeDeckRepository
import com.github.jvsena42.loopky.cli.testDeck
import com.github.jvsena42.loopky.domain.model.Capability
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.Session
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** `deck delete --confirm-title`, the guard the connector's `deck_delete` requires (#559). */
class DeckDeleteTest {

    private val session = Session(
        identity = PubkyIdentity(pubky = "pk:test", displayName = null, avatarUrl = null, bio = null),
        sessionSecret = "pk:test:cookie",
        homeserver = "hs",
        capabilities = listOf(Capability("/pub/loopky/:rw")),
    )

    private fun delete(vararg argv: String) = Args.parse(arrayOf("deck", "delete", "d1", *argv))

    @Test
    fun `the deck goes when the title is exactly its current one`() = runBlocking {
        val decks = FakeDeckRepository(testDeck().copy(title = "Capitais"))

        val data = deckDelete(delete("--confirm-title", "Capitais"), decks, session).data.jsonObject

        assertEquals(listOf("d1"), decks.deletes)
        assertEquals(listOf("pk:test" to "d1"), decks.fetchRemoteCalls)
        assertEquals("Capitais", data.getValue("title").jsonPrimitive.content)
    }

    @Test
    fun `any other title deletes nothing, and the error does not hand the real one over`() = runBlocking {
        val decks = FakeDeckRepository(testDeck().copy(title = "Capitais"))

        listOf("capitais", "Capitais ", "Capitals").forEach { wrong ->
            val refused = assertFailsWith<CliError> { deckDelete(delete("--confirm-title", wrong), decks, session) }
            assertEquals(ExitCode.BadInput, refused.exitCode)
            assertFalse(refused.message.orEmpty().contains("Capitais"), refused.message)
        }
        assertEquals(emptyList(), decks.deletes)
    }

    @Test
    fun `without the option the CLI deletes by id alone, reading nothing first`() = runBlocking {
        val decks = FakeDeckRepository(testDeck())

        deckDelete(delete(), decks, session)

        assertEquals(listOf("d1"), decks.deletes)
        assertEquals(emptyList(), decks.fetchRemoteCalls)
    }
}
