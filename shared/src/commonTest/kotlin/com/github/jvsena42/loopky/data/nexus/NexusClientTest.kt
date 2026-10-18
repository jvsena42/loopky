package com.github.jvsena42.loopky.data.nexus

import com.github.jvsena42.loopky.testing.FakeHttpFetcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class NexusClientTest {

    private val http = FakeHttpFetcher()
    private val client = NexusClient(http, baseUrl = BASE)

    @Test
    fun searchTagsByPrefixParsesStringArray() = runTest {
        http.respond("$BASE/v0/search/tags/by_prefix/spa?limit=10", """["spanish","spark"]""")

        assertEquals(listOf("spanish", "spark"), client.searchTagsByPrefix("spa").getOrThrow())
    }

    @Test
    fun searchUsersByNameParsesThePubkyArray() = runTest {
        http.respond("$BASE/v0/search/users/by_name/ada?limit=20", """["pk1","pk2"]""")

        assertEquals(listOf("pk1", "pk2"), client.searchUsersByName("ada").getOrThrow())
    }

    @Test
    fun searchUsersByNameEncodesThePrefixAndClampsTheLimit() = runTest {
        client.searchUsersByName("ada lovelace", limit = 5000)

        assertEquals("$BASE/v0/search/users/by_name/ada%20lovelace?limit=100", http.requestedUrls.single())
    }

    @Test
    fun searchUsersByIdParsesThePubkyArray() = runTest {
        http.respond("$BASE/v0/search/users/by_id/pk1?limit=20", """["pk1abc"]""")

        assertEquals(listOf("pk1abc"), client.searchUsersById("pk1").getOrThrow())
    }

    // ── resource + tagger reads (global discovery) ───────────────────────

    @Test
    fun resourcesByTagAsksForLoopkyResourcesSortedByTaggerCount() = runTest {
        val url = "$BASE/v0/stream/resources" +
            "?app=loopky&tags=loopky-deck&sorting=taggers_count&limit=30&skip=0"
        // Shape copied from a live staging response.
        http.respond(
            url,
            """
            [
              {
                "details": {
                  "id": "c1179e84066b657fab2ddbcf0ee2c23f",
                  "uri": "pubky://authorpk/pub/loopky/decks/deck1/manifest.json",
                  "scheme": "pubky",
                  "indexed_at": 1786197267251
                },
                "tags": [
                  {"label": "loopky-deck", "taggers": ["authorpk"], "taggers_count": 1},
                  {"label": "spanish", "taggers": ["authorpk", "otherpk"], "taggers_count": 2}
                ],
                "taggers_count": 2
              }
            ]
            """.trimIndent(),
        )

        val resources = client.resourcesByTag("loopky-deck").getOrThrow()

        val resource = resources.single()
        assertEquals("pubky://authorpk/pub/loopky/decks/deck1/manifest.json", resource.details.uri)
        assertEquals(listOf("authorpk"), resource.tags.first().taggers)
        assertEquals(expected = 2, actual = resource.tags[1].taggers_count)
    }

    @Test
    fun resourcesByTagEncodesTheLabelAndClampsTheLimit() = runTest {
        client.resourcesByTag("⭐⭐", limit = 500)

        assertEquals(
            "$BASE/v0/stream/resources" +
                "?app=loopky&tags=%E2%AD%90%E2%AD%90&sorting=taggers_count&limit=100&skip=0",
            http.requestedUrls.single(),
        )
    }

    @Test
    fun resourceByUriParsesTaggerCountsPerLabel() = runTest {
        val uri = "pubky://authorpk/pub/loopky/decks/deck1/manifest.json"
        val url = "$BASE/v0/resource/by-uri" +
            "?uri=pubky%3A%2F%2Fauthorpk%2Fpub%2Floopky%2Fdecks%2Fdeck1%2Fmanifest.json" +
            "&limit_tags=100&limit_taggers=1"
        http.respond(
            url,
            """
            {
              "resource": {"id": "abc", "uri": "$uri", "scheme": "pubky", "indexed_at": 1},
              "tags": [{"label": "loopky-followed", "taggers": ["pk1"], "taggers_count": 7}]
            }
            """.trimIndent(),
        )

        val tags = client.resourceByUri(uri).getOrThrow().tags

        assertEquals("loopky-followed", tags.single().label)
        // The count is what matters — `taggers` is capped at limit_taggers.
        assertEquals(expected = 7, actual = tags.single().taggers_count)
    }

    @Test
    fun usersByProfileTagUnwrapsTheScoredHits() = runTest {
        http.respond(
            "$BASE/v0/search/users/by_tags?tags=loopky-user&limit=50",
            """[{"user_id":"pk1","score":1},{"user_id":"pk2","score":1}]""",
        )

        assertEquals(listOf("pk1", "pk2"), client.usersByProfileTag("loopky-user").getOrThrow())
    }

    @Test
    fun usersByProfileTagEncodesTheLabelAndClampsTheLimit() = runTest {
        client.usersByProfileTag("loopky user", limit = 5000)

        assertEquals(
            "$BASE/v0/search/users/by_tags?tags=loopky%20user&limit=200",
            http.requestedUrls.single(),
        )
    }

    @Test
    fun usersByProfileTagFailsOnAnIndexerWithoutTheEndpoint() = runTest {
        // Prod today. The caller has to see this as "no such query", not "no Loopky users" (#134).
        http.enqueue(
            HttpMethod.GET,
            "$BASE/v0/search/users/by_tags?tags=loopky-user&limit=50",
            HttpResponse(statusCode = 404, body = ""),
        )

        val error = client.usersByProfileTag("loopky-user").exceptionOrNull()

        assertEquals(expected = 404, actual = (error as HttpError).statusCode)
    }

    @Test
    fun postAuthorsByTagTakesTheAuthorOffEachPostKey() = runTest {
        http.respond(
            "$BASE/v0/search/posts/by_tag/loopky-deck?limit=100",
            """
            [
              {"post_key":"pk1:0035KSA35F6XG","score":1787691071993},
              {"post_key":"pk1:0035KS8NGTDV0","score":1787690305483},
              {"post_key":"pk2:0035KJDP8R1A0","score":1787572742484}
            ]
            """.trimIndent(),
        )

        // An author who announced three decks is one entry, not three.
        assertEquals(listOf("pk1", "pk2"), client.postAuthorsByTag("loopky-deck").getOrThrow())
    }

    @Test
    fun postAuthorsByTagSkipsAMalformedKey() = runTest {
        http.respond(
            "$BASE/v0/search/posts/by_tag/loopky-deck?limit=100",
            """[{"post_key":":0035KSA35F6XG"},{"post_key":"pk1:0035KS8NGTDV0"}]""",
        )

        assertEquals(listOf("pk1"), client.postAuthorsByTag("loopky-deck").getOrThrow())
    }

    @Test
    fun userTaggersUnwrapsTheUsersField() = runTest {
        http.respond(
            "$BASE/v0/user/pk1/taggers/loopky-user?limit=40",
            """{"users":["pk1"],"relationship":false}""",
        )

        // pk1 among its own taggers is what makes this a self-tag rather than a claim.
        assertEquals(listOf("pk1"), client.userTaggers("pk1", "loopky-user").getOrThrow())
    }

    @Test
    fun followersParsesThePubkyArray() = runTest {
        http.respond("$BASE/v0/user/pk1/followers?limit=60", """["pk2","pk3"]""")

        assertEquals(listOf("pk2", "pk3"), client.followers("pk1").getOrThrow())
    }

    @Test
    fun followersEncodesTheUserAndClampsTheLimit() = runTest {
        client.followers("pk/1", limit = 5000)

        assertEquals("$BASE/v0/user/pk%2F1/followers?limit=200", http.requestedUrls.single())
    }

    private companion object {
        const val BASE = "https://nexus.test"
    }
}
