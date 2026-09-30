package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.domain.model.ChunkMeta
import com.github.jvsena42.loopky.domain.model.Deck
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `--json` view is a verification channel, so what it *omits* is as much a decision as what it
 * carries — a caller diffs intent against result from these bytes and cannot check what is absent.
 */
class ViewsTest {

    private fun deck(chunks: List<ChunkMeta>) = Deck(
        id = "d1",
        authorPubky = "pk:author",
        title = "Capitais",
        description = null,
        coverImageRef = null,
        tags = emptyList(),
        createdAt = 1,
        updatedAt = 2,
        cardCount = chunks.sumOf { it.count },
        chunks = chunks,
    )

    /**
     * `deck compact`'s entire job is rearranging the chunk table. Without it here its effect is
     * invisible except as counts in its own result, and a caller can only take the command's word.
     */
    @Test
    fun `a deck carries its chunk table`() {
        val view = deck(listOf(ChunkMeta(n = 0, count = 100, updatedAt = 10))).toView()

        assertEquals(1, view.chunks.size)
        assertEquals(0, view.chunks.single().n)
        assertEquals(100, view.chunks.single().count)
        assertEquals(10, view.chunks.single().updatedAt)
    }

    /**
     * Compaction folds a pair of neighbours and drops the higher `n`, so the numbering has gaps.
     * Anything walking this must read `n` — the view must not renumber them into a dense list.
     */
    @Test
    fun `a compacted deck's gaps survive the view`() {
        val view = deck(
            listOf(
                ChunkMeta(n = 0, count = 100, updatedAt = 10),
                ChunkMeta(n = 3, count = 40, updatedAt = 20),
            ),
        ).toView()

        assertEquals(listOf(0, 3), view.chunks.map { it.n })
    }

    /**
     * snake_case throughout, because the homeserver records use it and a caller juggling both
     * should not have to remember which side of the wire it is on. Two result types shipped
     * camelCase once already, and both docs then pointed at a field that did not exist.
     */
    @Test
    fun `the wire keys stay snake_case, like every other result`() {
        val view = deck(listOf(ChunkMeta(n = 0, count = 2, updatedAt = 10))).toView()
        val json = cliJson.encodeToJsonElement(DeckView.serializer(), view).jsonObject

        assertTrue("author_pubky" in json, "keys were ${json.keys}")
        assertTrue("card_count" in json)
        assertEquals("Capitais", json.getValue("title").jsonPrimitive.content)

        val chunk = json.getValue("chunks").jsonArray.single().jsonObject
        assertEquals(setOf("n", "count", "updated_at"), chunk.keys)
        assertEquals("10", chunk.getValue("updated_at").jsonPrimitive.content)
    }
}
