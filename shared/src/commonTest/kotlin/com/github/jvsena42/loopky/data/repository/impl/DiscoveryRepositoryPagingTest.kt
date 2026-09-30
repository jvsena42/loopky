package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.nexus.NexusClient
import com.github.jvsena42.loopky.data.nexus.NexusResourceSorting
import com.github.jvsena42.loopky.data.pubky.toDto
import com.github.jvsena42.loopky.data.repository.TaggedSubject
import com.github.jvsena42.loopky.domain.model.PubkyUri
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.testing.CountingRevalidator
import com.github.jvsena42.loopky.testing.FakeAppPreferences
import com.github.jvsena42.loopky.testing.FakeBackgroundTasks
import com.github.jvsena42.loopky.testing.FakeDeckCacheStore
import com.github.jvsena42.loopky.testing.FakeHttpFetcher
import com.github.jvsena42.loopky.testing.FakeMediaRepository
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.RecordingTagRepository
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.identityRepository
import com.github.jvsena42.loopky.testing.signedInProvider
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Paging and page-filling for the indexer-backed browse (#321).
 *
 * Split from `DiscoveryRepositoryImplTest` for size, not for subject — it builds the same
 * repository over the same fakes.
 */
class DiscoveryRepositoryPagingTest {

    private val pubky = FakePubkyClient()
    private val session = signedInProvider()
    private val revalidator = CountingRevalidator()
    private val cardRepo = CardRepositoryImpl(pubky, session, revalidator, Dispatchers.Unconfined)
    private val deckRepo = DeckRepositoryImpl(
        pubky = pubky,
        session = session,
        cardRepo = cardRepo,
        revalidator = revalidator,
        tagRepo = RecordingTagRepository(),
        mediaRepo = FakeMediaRepository(),
        backgroundTasks = FakeBackgroundTasks(),
        deckCache = FakeDeckCacheStore(),
    )
    private val tagRepo = RecordingTagRepository()
    private val identityRepo = identityRepository(
        pubky = pubky,
        sessionProvider = session,
        tagRepository = tagRepo,
    )
    private val http = FakeHttpFetcher()
    private val preferences = FakeAppPreferences()
    private val repo = DiscoveryRepositoryImpl(
        pubky = pubky,
        session = session,
        revalidator = revalidator,
        deckRepository = deckRepo,
        tagRepository = tagRepo,
        identityRepository = identityRepo,
        nexus = NexusClient(http = http, baseUrl = "https://nexus.test"),
        preferences = preferences,
    )

    private fun putRemoteManifest(
        author: String,
        deckId: String,
        updatedAt: Long,
        title: String = "Deck $deckId",
        tags: List<Tag> = emptyList(),
    ) {
        val dto = testDeck(
            id = deckId,
            authorPubky = author,
            title = title,
            tags = tags,
            updatedAt = updatedAt,
        ).toDto()
        pubky.store["pubky://$author/pub/loopky/decks/$deckId/manifest.json"] =
            loopkyJson.encodeToString(dto)
    }

    private fun manifestUri(author: String, deckId: String) =
        PubkyUri("pubky://$author/pub/loopky/decks/$deckId/manifest.json")

    private fun tagged(uri: PubkyUri, taggers: List<String>) =
        TaggedSubject(uri = uri, taggers = taggers, taggersCount = taggers.size)

    /** [count] decks by one author, all indexed and all fetchable. */
    private fun publishGlobalDecks(count: Int, author: String = "strangerpk") {
        repeat(count) { i -> putRemoteManifest(author, "deck$i", updatedAt = i.toLong()) }
        tagRepo.subjectsByTag = mapOf(
            ReservedTags.DECK to (0 until count).map {
                tagged(manifestUri(author, "deck$it"), taggers = listOf(author))
            },
        )
    }

    @Test
    fun globalBrowseFillsThePageWhenVerificationDropsEntries() = runTest {
        // The bug: `limit` was spent at the indexer and the drops came off the top, so a 3-deck ask
        // over a corpus half of which is yours returned 1. Discover showed 11 tiles of a 12-ask on
        // staging, and 5 for the account that had published 30 of the network's 71 decks.
        putRemoteManifest(TEST_PUBKY, "mine1", updatedAt = 1L)
        putRemoteManifest(TEST_PUBKY, "mine2", updatedAt = 2L)
        putRemoteManifest("strangerpk", "theirs1", updatedAt = 3L)
        putRemoteManifest("strangerpk", "theirs2", updatedAt = 4L)
        putRemoteManifest("strangerpk", "theirs3", updatedAt = 5L)
        tagRepo.subjectsByTag = mapOf(
            ReservedTags.DECK to listOf(
                tagged(manifestUri(TEST_PUBKY, "mine1"), listOf(TEST_PUBKY)),
                tagged(manifestUri(TEST_PUBKY, "mine2"), listOf(TEST_PUBKY)),
                tagged(manifestUri("strangerpk", "theirs1"), listOf("strangerpk")),
                tagged(manifestUri("strangerpk", "theirs2"), listOf("strangerpk")),
                tagged(manifestUri("strangerpk", "theirs3"), listOf("strangerpk")),
            ),
        )

        val page = repo.decksByTagGlobalPage(ReservedTags.DECK, limit = 3)

        assertEquals(listOf("theirs1", "theirs2", "theirs3"), page.decks.map { it.id })
    }

    @Test
    fun globalBrowsePagesThroughEverythingIndexed() = runTest {
        publishGlobalDecks(count = 7)

        val first = repo.decksByTagGlobalPage(ReservedTags.DECK, limit = 3)
        val second = repo.decksByTagGlobalPage(ReservedTags.DECK, limit = 3, cursor = first.nextCursor)
        val third = repo.decksByTagGlobalPage(ReservedTags.DECK, limit = 3, cursor = second.nextCursor)

        assertEquals(listOf("deck0", "deck1", "deck2"), first.decks.map { it.id })
        assertEquals(listOf("deck3", "deck4", "deck5"), second.decks.map { it.id })
        assertEquals(listOf("deck6"), third.decks.map { it.id })
        assertTrue(first.hasMore)
        assertTrue(second.hasMore)
        // A short page is not the end — only an empty read is, which is what the third page's
        // refill found when it asked past deck6.
        assertFalse(third.hasMore)
    }

    @Test
    fun globalBrowseAsksTheIndexerForNewestFirst() = runTest {
        // taggers_count re-ranks whenever anyone tags anything, so a cursor into it is not a
        // cursor — and it sums labels rather than followers, which is not popularity either.
        publishGlobalDecks(count = 2)

        repo.decksByTagGlobalPage(ReservedTags.DECK, limit = 2)

        assertTrue(tagRepo.taggedSortings.all { it == NexusResourceSorting.Timeline })
    }

    @Test
    fun globalBrowseAdvancesTheCursorByTheWindowNotByWhatCameBack() = runTest {
        // `skip` indexes the indexer's raw sorted set, and a page routinely arrives short of
        // entries the indexer dropped itself. Advancing by the arrival count re-reads those
        // positions forever, which is a browse that never reaches the tail.
        publishGlobalDecks(count = 1)

        val page = repo.decksByTagGlobalPage(ReservedTags.DECK, limit = 5)

        assertEquals(listOf("deck0"), page.decks.map { it.id })
        assertTrue(page.nextCursor >= 5, "cursor advanced by arrivals, not by the window asked for")
    }

    @Test
    fun globalBrowseStopsRefillingRatherThanWalkingTheWholeIndex() = runTest {
        // Nothing verifies: a stale index, or every author's homeserver down. Without a ceiling
        // this walks the entire tag index one window at a time to return nothing.
        tagRepo.subjectsByTag = mapOf(
            ReservedTags.DECK to (0 until 200).map {
                tagged(manifestUri("strangerpk", "ghost$it"), taggers = listOf("strangerpk"))
            },
        )

        val page = repo.decksByTagGlobalPage(ReservedTags.DECK, limit = 4)

        assertEquals(emptyList(), page.decks)
        assertTrue(tagRepo.taggedRequests.size <= 4, "refill ran away: ${tagRepo.taggedRequests.size}")
        // Still more to look at — the ceiling is a budget, not a claim about the network.
        assertTrue(page.hasMore)
    }

    @Test
    fun globalBrowseKeepsOnlyDecksCarryingEveryExtraTag() = runTest {
        putRemoteManifest("strangerpk", "both", updatedAt = 1L, tags = listOf(Tag("portuguese"), Tag("language")))
        putRemoteManifest("strangerpk", "onlypt", updatedAt = 2L, tags = listOf(Tag("portuguese")))
        tagRepo.subjectsByTag = mapOf(
            Tag("portuguese") to listOf(
                tagged(manifestUri("strangerpk", "both"), listOf("strangerpk")),
                tagged(manifestUri("strangerpk", "onlypt"), listOf("strangerpk")),
            ),
        )

        val page = repo.decksByTagGlobalPage(Tag("portuguese"), limit = 5, alsoTagged = setOf(Tag("language")))

        assertEquals(listOf("both"), page.decks.map { it.id })
    }

    @Test
    fun aFilteredBrowsePagesWithoutRepeatingOrSkippingAMatch() = runTest {
        // The filter drops decks after the indexer read, so the cursor still indexes the tag's raw
        // index: page two has to resume exactly where page one stopped reading, not where its last
        // match sat.
        repeat(7) { i ->
            val tags = if (i % 2 == 0) listOf(Tag("even")) else emptyList()
            putRemoteManifest("strangerpk", "deck$i", updatedAt = i.toLong(), tags = tags)
        }
        tagRepo.subjectsByTag = mapOf(
            ReservedTags.DECK to (0 until 7).map { tagged(manifestUri("strangerpk", "deck$it"), listOf("strangerpk")) },
        )
        val even = setOf(Tag("even"))

        val first = repo.decksByTagGlobalPage(ReservedTags.DECK, limit = 2, alsoTagged = even)
        val second = repo.decksByTagGlobalPage(ReservedTags.DECK, limit = 2, first.nextCursor, even)
        val third = repo.decksByTagGlobalPage(ReservedTags.DECK, limit = 2, second.nextCursor, even)

        assertEquals(listOf("deck0", "deck2"), first.decks.map { it.id })
        assertEquals(listOf("deck4", "deck6"), second.decks.map { it.id })
        assertEquals(emptyList(), third.decks)
        assertTrue(second.hasMore)
        assertFalse(third.hasMore)
    }

    @Test
    fun suggestedPeoplePagesWithoutRepeatingAnyone() = runTest {
        val directory = (0 until 5).map { "userpk$it" }
        tagRepo.usersByTag = mapOf(ReservedTags.USER to directory)
        tagRepo.selfTaggers = directory.toSet()

        val first = repo.suggestedPeoplePage(seedDecks = emptyList(), limit = 2)
        val second = repo.suggestedPeoplePage(emptyList(), limit = 2, cursor = first.nextCursor)
        val third = repo.suggestedPeoplePage(emptyList(), limit = 2, cursor = second.nextCursor)

        assertEquals(listOf("userpk0", "userpk1"), first.people.map { it.pubky })
        assertEquals(listOf("userpk2", "userpk3"), second.people.map { it.pubky })
        assertEquals(listOf("userpk4"), third.people.map { it.pubky })
        assertFalse(third.hasMore)
    }

    @Test
    fun suggestedPeopleStillKeepsADeckAuthorWhoNeverSelfTagged() = runTest {
        // The roll has to remember *how* a candidate got on it. An author whose manifest has
        // already fetched and parsed has proved themselves by publishing; holding them to the
        // self-tag as well drops exactly the people the seed exists to reach.
        val page = repo.suggestedPeoplePage(
            seedDecks = listOf(testDeck(authorPubky = "neverselftaggedpk")),
            limit = 5,
        )

        assertEquals(listOf("neverselftaggedpk"), page.people.map { it.pubky })
    }
}
