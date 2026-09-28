package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.nexus.HttpError
import com.github.jvsena42.loopky.data.nexus.NexusClient
import com.github.jvsena42.loopky.data.pubky.TagDto
import com.github.jvsena42.loopky.domain.model.PubkyUri
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.testing.CountingRevalidator
import com.github.jvsena42.loopky.testing.FakeHttpFetcher
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.signedInProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TagRepositoryImplTest {

    private val pubky = FakePubkyClient()
    private val http = FakeHttpFetcher()
    private val repo = TagRepositoryImpl(
        pubky = pubky,
        session = signedInProvider(),
        revalidator = CountingRevalidator(),
        nexus = NexusClient(http, baseUrl = NEXUS_BASE),
    )

    private val deckUri = PubkyUri("pubky://$TEST_PUBKY/pub/loopky/decks/deck1/manifest.json")
    private val profileUri = PubkyUri("pubky://$TEST_PUBKY/pub/pubky.app/profile.json")

    /** Deck subjects are indexed as generic resources, so their records live in `pub/loopky`. */
    private fun tagUrlFor(label: String, subject: PubkyUri = deckUri): String {
        val tagId = pubky.createTagId(subject.value, label).getOrThrow()
        return "pubky://$TEST_PUBKY/pub/loopky/tags/$tagId"
    }

    /** Profile subjects must stay in the pubky.app namespace to reach the user graph. */
    private fun pubkyAppTagUrlFor(label: String, subject: PubkyUri): String {
        val tagId = pubky.createTagId(subject.value, label).getOrThrow()
        return "pubky://$TEST_PUBKY/pub/pubky.app/tags/$tagId"
    }

    // ── putTag ───────────────────────────────────────────────────────────

    @Test
    fun putTagSanitizesLabelAndWritesTagRecord() = runTest {
        val result = repo.putTag(deckUri, Tag("  SPANish "))

        assertTrue(result.isSuccess)
        val body = pubky.store.getValue(tagUrlFor("spanish"))
        val dto = loopkyJson.decodeFromString<TagDto>(body)
        assertEquals(deckUri.value, dto.uri)
        assertEquals("spanish", dto.label)
        assertTrue(dto.created_at > 0)
    }

    @Test
    fun putTagOnADeckWritesIntoTheLoopkyNamespace() = runTest {
        // Nexus only indexes a deck manifest subject when the record itself sits outside
        // pubky.app — under pubky.app the watcher drops it silently (#40).
        repo.putTag(deckUri, Tag("spanish")).getOrThrow()

        val url = pubky.puts.single().first
        assertTrue(url.startsWith("pubky://$TEST_PUBKY/pub/loopky/tags/"), "was $url")
    }

    @Test
    fun putTagOnAProfileStaysInThePubkyAppNamespace() = runTest {
        // Profile subjects are the one case that must stay in pubky.app: that is the only
        // namespace Nexus files into the user graph, which is what makes the tag globally
        // queryable by label.
        repo.putTag(profileUri, Tag("teacher")).getOrThrow()

        val url = pubky.puts.single().first
        assertEquals(pubkyAppTagUrlFor("teacher", profileUri), url)
        val dto = loopkyJson.decodeFromString<TagDto>(pubky.store.getValue(url))
        assertEquals(profileUri.value, dto.uri)
    }

    @Test
    fun putTagRejectsEmptyLabel() = runTest {
        assertTrue(repo.putTag(deckUri, Tag("")).isFailure)
        assertTrue(repo.putTag(deckUri, Tag("   ")).isFailure)
        assertTrue(pubky.puts.isEmpty())
    }

    @Test
    fun putTagRejectsLabelOverTwentyChars() = runTest {
        val result = repo.putTag(deckUri, Tag("a".repeat(21)))

        assertTrue(result.isFailure)
        assertTrue(pubky.puts.isEmpty())
    }

    @Test
    fun putTagAcceptsLabelAtExactlyTwentyChars() = runTest {
        val result = repo.putTag(deckUri, Tag("a".repeat(20)))

        assertTrue(result.isSuccess)
        assertEquals(expected = 1, actual = pubky.puts.size)
    }

    @Test
    fun putTagRejectsLabelWithInnerWhitespace() = runTest {
        val result = repo.putTag(deckUri, Tag("two words"))

        assertTrue(result.isFailure)
        assertTrue(pubky.puts.isEmpty())
    }

    // ── removeTag ────────────────────────────────────────────────────────

    @Test
    fun removeTagDeletesTheSamePathPutTagWrote() = runTest {
        repo.putTag(deckUri, Tag("Spanish")).getOrThrow()
        val url = tagUrlFor("spanish")
        assertTrue(url in pubky.store)

        repo.removeTag(deckUri, Tag(" spanish ")).getOrThrow()

        assertTrue(url !in pubky.store)
        assertEquals(url, pubky.deletes.first())
    }

    @Test
    fun removeTagAlsoClearsThePreNamespaceRoutingRecord() = runTest {
        // Deck tags used to be written (and silently never indexed) under pubky.app.
        val legacyUrl = pubkyAppTagUrlFor("spanish", deckUri)
        pubky.store[legacyUrl] = "{}"

        repo.removeTag(deckUri, Tag("spanish")).getOrThrow()

        assertTrue(legacyUrl !in pubky.store)
        assertEquals(listOf(tagUrlFor("spanish"), legacyUrl), pubky.deletes)
    }

    @Test
    fun removeTagOnAProfileTouchesOnlyThePubkyAppRecord() = runTest {
        repo.putTag(profileUri, Tag("teacher")).getOrThrow()

        repo.removeTag(profileUri, Tag("teacher")).getOrThrow()

        assertEquals(listOf(pubkyAppTagUrlFor("teacher", profileUri)), pubky.deletes)
    }

    @Test
    fun removeTagRejectsInvalidLabel() = runTest {
        assertTrue(repo.removeTag(deckUri, Tag("")).isFailure)
        assertTrue(pubky.deletes.isEmpty())
    }

    // ── the reserved namespace ───────────────────────────────────────────

    @Test
    fun putTagRefusesToAuthorAReservedLabel() = runTest {
        // Reserved labels are Loopky's global index, not user content (#40) — a user must not be
        // able to mint one by typing it, whether or not it is a label we write today.
        assertTrue(repo.putTag(deckUri, ReservedTags.DECK).isFailure)
        assertTrue(repo.putTag(deckUri, ReservedTags.USER).isFailure)
        assertTrue(repo.putTag(deckUri, Tag("LOOPKY-deck")).isFailure)
        assertTrue(repo.putTag(deckUri, Tag("loopky-anything")).isFailure)
        assertTrue(pubky.puts.isEmpty())
    }

    @Test
    fun removeTagRefusesAReservedLabelToo() = runTest {
        assertTrue(repo.removeTag(deckUri, ReservedTags.DECK).isFailure)
        assertTrue(pubky.deletes.isEmpty())
    }

    @Test
    fun putReservedTagOnlyAcceptsLoopkysOwnLabels() = runTest {
        assertTrue(repo.putReservedTag(deckUri, Tag("spanish")).isFailure)
        assertTrue(repo.putReservedTag(deckUri, Tag("loopky-madeup")).isFailure)
        assertTrue(pubky.puts.isEmpty())

        assertTrue(repo.putReservedTag(deckUri, ReservedTags.DECK).isSuccess)
        assertEquals(expected = 1, actual = pubky.puts.size)
    }

    @Test
    fun rewritingAReservedTagOverwritesTheSameRecord() = runTest {
        // The acceptance criterion "re-running sign-in does not duplicate": ids are derived from
        // subject + label, so the second write lands on the first one's path.
        repo.putReservedTag(profileUri, ReservedTags.USER).getOrThrow()
        repo.putReservedTag(profileUri, ReservedTags.USER).getOrThrow()

        val url = pubkyAppTagUrlFor(ReservedTags.USER.value, profileUri)
        assertEquals(listOf(url, url), pubky.puts.map { it.first })
        assertEquals(expected = 1, actual = pubky.store.keys.count { it.contains("/tags/") })
    }

    @Test
    fun reservedTagsRouteBySubjectLikeAnyOther() = runTest {
        repo.putReservedTag(profileUri, ReservedTags.USER).getOrThrow()
        repo.putReservedTag(deckUri, ReservedTags.DECK).getOrThrow()

        assertEquals(pubkyAppTagUrlFor(ReservedTags.USER.value, profileUri), pubky.puts[0].first)
        assertEquals(tagUrlFor(ReservedTags.DECK.value), pubky.puts[1].first)
    }

    @Test
    fun removeReservedTagDeletesWhatPutReservedTagWrote() = runTest {
        repo.putReservedTag(deckUri, ReservedTags.DECK).getOrThrow()
        val url = tagUrlFor(ReservedTags.DECK.value)
        assertTrue(url in pubky.store)

        repo.removeReservedTag(deckUri, ReservedTags.DECK).getOrThrow()

        assertTrue(url !in pubky.store)
        assertEquals(url, pubky.deletes.first())
    }

    // ── trending ─────────────────────────────────────────────────────────

    // ── deck topics (client-side aggregation) ────────────────────────────

    /**
     * Shape copied from a live staging response for a deck published by this project, so the
     * aggregation is pinned against what the indexer really returns rather than an invented shape:
     * a resource carries its *whole* label list, reserved marker included.
     */
    @Test
    fun trendingDeckTagsAggregatesEveryLabelOnEachDeck() = runTest {
        http.respond(
            deckStreamUrl(sampleSize = 50),
            """
            [
              {"details":{"id":"32856b46","uri":"pubky://$TEST_PUBKY/pub/loopky/decks/d1/manifest.json",
                "scheme":"pubky","indexed_at":1786917046604},
               "tags":[{"label":"spanish","taggers":["$TEST_PUBKY"],"taggers_count":1},
                       {"label":"loopky-deck","taggers":["$TEST_PUBKY"],"taggers_count":1}],
               "taggers_count":2},
              {"details":{"uri":"pubky://$TEST_PUBKY/pub/loopky/decks/d2/manifest.json"},
               "tags":[{"label":"spanish","taggers_count":1},{"label":"biology","taggers_count":1}],
               "taggers_count":2}
            ]
            """.trimIndent(),
        )

        // spanish is on two decks, biology on one; loopky-deck is Loopky's index, not a topic.
        assertEquals(listOf(Tag("spanish"), Tag("biology")), repo.trendingDeckTags().getOrThrow())
    }

    @Test
    fun trendingDeckTagsRanksByDeckCountNotTaggerCount() = runTest {
        http.respond(
            deckStreamUrl(sampleSize = 50),
            """
            [
              {"details":{"uri":"pubky://$TEST_PUBKY/pub/loopky/decks/d1/manifest.json"},
               "tags":[{"label":"hyped","taggers_count":99}]},
              {"details":{"uri":"pubky://$TEST_PUBKY/pub/loopky/decks/d2/manifest.json"},
               "tags":[{"label":"broad","taggers_count":1}]},
              {"details":{"uri":"pubky://$TEST_PUBKY/pub/loopky/decks/d3/manifest.json"},
               "tags":[{"label":"broad","taggers_count":1}]}
            ]
            """.trimIndent(),
        )

        // One deck tagged 99 times is not a topic; two decks sharing a label is.
        assertEquals(listOf(Tag("broad"), Tag("hyped")), repo.trendingDeckTags().getOrThrow())
    }

    @Test
    fun trendingDeckTagsRespectsTheLimit() = runTest {
        http.respond(
            deckStreamUrl(sampleSize = 50),
            """
            [
              {"details":{"uri":"pubky://$TEST_PUBKY/pub/loopky/decks/d1/manifest.json"},
               "tags":[{"label":"aaa","taggers_count":3},{"label":"bbb","taggers_count":2},
                       {"label":"ccc","taggers_count":1}]}
            ]
            """.trimIndent(),
        )

        assertEquals(listOf(Tag("aaa"), Tag("bbb")), repo.trendingDeckTags(limit = 2).getOrThrow())
    }

    @Test
    fun trendingDeckTagsFailsWhenTheIndexerFails() = runTest {
        http.fail(deckStreamUrl(sampleSize = 50), HttpError(statusCode = 500, message = "boom"))

        assertTrue(repo.trendingDeckTags().isFailure)
    }

    private fun deckStreamUrl(sampleSize: Int) =
        "$NEXUS_BASE/v0/stream/resources?app=loopky&tags=loopky-deck" +
            "&sorting=taggers_count&limit=$sampleSize&skip=0"

    private companion object {
        const val NEXUS_BASE = "https://nexus.test"
    }
}
