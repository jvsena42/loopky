package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.FakeDeckRepository
import com.github.jvsena42.loopky.cli.testDeck
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `deck list`'s paging, title filter and compact shape (#559). */
class DeckListTest {

    private val owned = listOf(
        testDeck("d3").copy(title = "Spanish verbs"),
        testDeck("d1").copy(title = "Capitals of Europe"),
        testDeck("d2").copy(title = "SPANISH nouns"),
        testDeck("d4").copy(title = "Rivers"),
    )
    private val decks = FakeDeckRepository(testDeck(), owned = owned)

    private suspend fun list(vararg argv: String, notes: MutableList<String> = mutableListOf()): JsonObject =
        deckList(Args.parse(arrayOf("deck", "list", *argv)), decks) { notes += it }.data.jsonObject

    private fun JsonObject.ids() = getValue("decks").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }

    @Test
    fun `plain deck list is every deck, in full, with nothing more to read`() = runBlocking {
        val data = list()
        assertEquals(listOf("d1", "d2", "d3", "d4"), data.ids())
        assertEquals(4, data.getValue("count").jsonPrimitive.int)
        assertEquals(4, data.getValue("total").jsonPrimitive.int)
        assertEquals(JsonNull, data.getValue("next_cursor"))
        assertFalse(data.getValue("compact").jsonPrimitive.boolean)
        assertTrue("chunks" in data.getValue("decks").jsonArray.first().jsonObject)
    }

    @Test
    fun `pages walk the decks in id order, and the last one has no cursor`() = runBlocking {
        val notes = mutableListOf<String>()
        val first = list("--limit", "3", notes = notes)
        assertEquals(listOf("d1", "d2", "d3"), first.ids())
        assertEquals("d3", first.getValue("next_cursor").jsonPrimitive.content)
        assertEquals(4, first.getValue("total").jsonPrimitive.int)
        assertTrue(notes.single().contains("--cursor d3"))

        val second = list("--limit", "3", "--cursor", "d3")
        assertEquals(listOf("d4"), second.ids())
        assertEquals(JsonNull, second.getValue("next_cursor"))
    }

    @Test
    fun `a query narrows by title, ignoring case, before paging`() = runBlocking {
        val data = list("--query", "spanish", "--limit", "1")
        assertEquals(listOf("d2"), data.ids())
        assertEquals(2, data.getValue("total").jsonPrimitive.int)
        assertEquals("d2", data.getValue("next_cursor").jsonPrimitive.content)
        assertEquals(listOf("d3"), list("--query", "SPANISH", "--cursor", "d2").ids())
    }

    @Test
    fun `compact keeps what choosing a deck takes and drops the rest`() = runBlocking {
        val data = list("--compact")
        assertTrue(data.getValue("compact").jsonPrimitive.boolean)
        val deck = data.getValue("decks").jsonArray.first().jsonObject
        assertEquals(
            setOf("id", "title", "card_count", "tags", "front_lang", "back_lang", "updated_at", "incomplete"),
            deck.keys,
        )
        assertEquals("Capitals of Europe", deck.getValue("title").jsonPrimitive.content)
    }
}
