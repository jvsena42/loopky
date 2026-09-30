package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.CHUNK_SIZE
import com.github.jvsena42.loopky.data.pubky.CardChunkDto
import com.github.jvsena42.loopky.data.pubky.ManifestDto
import com.github.jvsena42.loopky.data.pubky.PubkyError
import com.github.jvsena42.loopky.data.pubky.toDto
import com.github.jvsena42.loopky.data.repository.PublishProgress
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.CardSide
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.DeckSource
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.testing.CountingRevalidator
import com.github.jvsena42.loopky.testing.FailingChunkCardRepository
import com.github.jvsena42.loopky.testing.FakeBackgroundTasks
import com.github.jvsena42.loopky.testing.FakeDeckCacheStore
import com.github.jvsena42.loopky.testing.FakeMediaRepository
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.RecordingTagRepository
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.deckRepository
import com.github.jvsena42.loopky.testing.signedInProvider
import com.github.jvsena42.loopky.testing.testCard
import com.github.jvsena42.loopky.testing.testDeck
import com.github.jvsena42.loopky.testing.testDeckWithCards
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeckRepositoryImplTest {

    private val pubky = FakePubkyClient()
    private val session = signedInProvider()
    private val revalidator = CountingRevalidator()
    private val tagRepo = RecordingTagRepository()
    private val cardRepo = CardRepositoryImpl(pubky, session, revalidator, Dispatchers.Unconfined)
    private val repo = deckRepository(pubky, session, cardRepo, revalidator, tagRepo)

    private val deckRoot = "pubky://$TEST_PUBKY/pub/loopky/decks/deck1"

    // ── publish ──────────────────────────────────────────────────────────

    @Test
    fun publishWritesOneChunkRecordPlusManifest() = runTest {
        val cards = listOf(testCard("c1", updatedAt = 10L), testCard("c2", updatedAt = 20L))

        val published = repo.publish(testDeck(id = "deck1"), cards).getOrThrow()

        // Two cards, one chunk record — not one record per card.
        assertTrue("$deckRoot/cards/0.json" in pubky.store)
        // claim manifest + chunk + settled manifest — still not one record per card.
        assertEquals(expected = 3, actual = pubky.puts.size)

        val manifest = loopkyJson.decodeFromString<ManifestDto>(
            pubky.store.getValue("$deckRoot/manifest.json"),
        )
        assertEquals("deck1", manifest.deck_id)
        assertEquals(TEST_PUBKY, manifest.author_pubky)
        assertEquals(expected = 2, actual = manifest.card_count)
        assertEquals(listOf(0), manifest.chunks.map { it.n })
        assertEquals(listOf(2), manifest.chunks.map { it.count })
        assertEquals(expected = 2, actual = published.cardCount)
    }

    @Test
    fun publishSplitsCardsAcrossChunksAtTheBoundary() = runTest {
        val cards = (1..250).map { testCard("c$it") }

        val published = repo.publish(testDeck(id = "deck1"), cards).getOrThrow()

        val manifest = loopkyJson.decodeFromString<ManifestDto>(
            pubky.store.getValue("$deckRoot/manifest.json"),
        )
        assertEquals(listOf(0, 1, 2), manifest.chunks.map { it.n })
        assertEquals(listOf(100, 100, 50), manifest.chunks.map { it.count })
        assertEquals(expected = 250, actual = manifest.card_count)
        assertEquals(expected = 250, actual = published.cardCount)
        // 3 chunks + 2 manifest writes (claim, then settle), against 251 under the old layout.
        assertEquals(expected = 5, actual = pubky.puts.size)
    }

    @Test
    fun publishAssignsSparseStudyOrder() = runTest {
        val cards = listOf(testCard("c1"), testCard("c2"), testCard("c3"))

        repo.publish(testDeck(id = "deck1"), cards).getOrThrow()

        val chunk = loopkyJson.decodeFromString<CardChunkDto>(
            pubky.store.getValue("$deckRoot/cards/0.json"),
        )
        assertEquals(listOf(0L, 1000L, 2000L), chunk.cards.map { it.ord })
    }

    @Test
    fun republishingASmallerDeckRemovesTheChunksThatFellAway() = runTest {
        repo.publish(testDeck(id = "deck1"), (1..250).map { testCard("c$it") }).getOrThrow()

        repo.publish(testDeck(id = "deck1"), listOf(testCard("c1"))).getOrThrow()

        assertTrue("$deckRoot/cards/0.json" in pubky.store)
        assertTrue("$deckRoot/cards/1.json" !in pubky.store, "stale chunk 1 left behind")
        assertTrue("$deckRoot/cards/2.json" !in pubky.store, "stale chunk 2 left behind")
    }

    @Test
    fun publishMirrorsDeckTagsAsTagRecords() = runTest {
        val deck = testDeck(id = "deck1", tags = listOf(Tag("spanish"), Tag("language")))

        repo.publish(deck, listOf(testCard("c1"))).getOrThrow()

        assertEquals(
            listOf(deck.pubkyUri to Tag("spanish"), deck.pubkyUri to Tag("language")),
            tagRepo.putTags,
        )
    }

    @Test
    fun publishMarksTheDeckForGlobalBrowse() = runTest {
        // Without loopky-deck the deck is only reachable by people already following the author.
        val deck = testDeck(id = "deck1", tags = listOf(Tag("spanish")))

        repo.publish(deck, listOf(testCard("c1"))).getOrThrow()

        assertEquals(listOf(deck.pubkyUri to ReservedTags.DECK), tagRepo.putReservedTags)
    }

    @Test
    fun publishSurvivesAFailedTagWrite() = runTest {
        tagRepo.failWith = IllegalStateException("homeserver refused the tag")
        val deck = testDeck(id = "deck1", tags = listOf(Tag("spanish")))

        // Discoverability is a bonus on top of the publish, never a precondition for it.
        assertTrue(repo.publish(deck, listOf(testCard("c1"))).isSuccess)
    }

    @Test
    fun publishRejectsAuthorMismatch() = runTest {
        val foreign = testDeck(id = "deck1", authorPubky = "someone-else")

        val result = repo.publish(foreign, listOf(testCard("c1")))

        assertTrue(result.isFailure)
        assertTrue(pubky.puts.isEmpty())
    }

    @Test
    fun publishRejectsCardsWithAnEmptySide() = runTest {
        val blankBack = testCard("c1").copy(back = CardSide(text = "  "))

        val result = repo.publish(testDeck(id = "deck1"), listOf(blankBack))

        assertTrue(result.isFailure)
        assertTrue(pubky.puts.isEmpty())
    }

    @Test
    fun publishClaimsTheDeckWithAMarkerManifestBeforeUploadingCards() = runTest {
        repo.publish(testDeck(id = "deck1"), (1..250).map { testCard("c$it") }).getOrThrow()

        // Manifest first (marked incomplete), chunks, then the manifest again to clear the mark.
        assertEquals("$deckRoot/manifest.json", pubky.puts.first().first)
        assertEquals("$deckRoot/manifest.json", pubky.puts.last().first)

        val marker = loopkyJson.decodeFromString<ManifestDto>(pubky.puts.first().second)
        assertTrue(marker.incomplete, "the claim manifest was not marked incomplete")
        val settled = loopkyJson.decodeFromString<ManifestDto>(
            pubky.store.getValue("$deckRoot/manifest.json"),
        )
        assertFalse(settled.incomplete, "the mark was not cleared once the chunks were up")
    }

    @Test
    fun aPublishThatDiesPartWayLeavesTheDeckListableSoItCanBeDeleted() = runTest {
        val cardRepo =
            FailingChunkCardRepository(CardRepositoryImpl(pubky, session, revalidator, Dispatchers.Unconfined))
        val failing = deckRepository(pubky, session, cardRepo, revalidator, tagRepo)

        val result = failing.publish(testDeck(id = "deck1"), (1..250).map { testCard("c$it") })

        assertTrue(result.isFailure)
        // The old order wrote the manifest last, so a failure here left orphaned chunk records
        // under a deck root that listByAuthor could not see — unreachable and undeletable.
        val manifest = pubky.store["$deckRoot/manifest.json"]
        assertNotNull(manifest, "an interrupted publish left no manifest, orphaning its chunks")
        assertTrue(loopkyJson.decodeFromString<ManifestDto>(manifest).incomplete)
        assertEquals(listOf("deck1"), repo.listByAuthor(TEST_PUBKY).map { it.id })
    }

    @Test
    fun publishReportsProgressEndingAtDone() = runTest {
        val seen = mutableListOf<PublishProgress>()

        repo.publish(testDeck(id = "deck1"), (1..250).map { testCard("c$it") }) { seen.add(it) }
            .getOrThrow()

        assertTrue(seen.isNotEmpty())
        assertTrue(seen.last().done)
        assertEquals(1f, seen.last().fraction)
        assertEquals(expected = 250, actual = seen.last().cardsWritten)
        // Every chunk reports exactly once, so the counter never loses an increment.
        assertEquals(listOf(1, 2, 3), seen.filterNot { it.done }.map { it.chunksWritten }.filter { it > 0 })
    }

    @Test
    fun deleteSweepsPastTheFirstListingPage() = runTest {
        repo.publish(testDeck(id = "deck1"), (1..250).map { testCard("c$it") }).getOrThrow()
        pubky.listPageSize = 2 // force the sweep to page

        repo.delete("deck1").getOrThrow()

        assertTrue(
            pubky.store.keys.none { it.startsWith(deckRoot) },
            "records survived the sweep: ${pubky.store.keys.filter { it.startsWith(deckRoot) }}",
        )
    }

    @Test
    fun aRateLimitedWriteIsRetriedRatherThanFailingThePublish() = runTest {
        // Measured against a real homeserver: publishing with several writes in flight returns
        // 429. The request is well-formed and transient, so surfacing it would be wrong.
        pubky.rateLimitNextCalls = 3

        val published = repo.publish(testDeck(id = "deck1"), listOf(testCard("c1"))).getOrThrow()

        assertEquals(expected = 1, actual = published.cardCount)
        assertTrue("$deckRoot/cards/0.json" in pubky.store)
    }

    @Test
    fun aPersistentRateLimitEventuallyGivesUp() = runTest {
        // Retries are bounded — a homeserver that is down must not hang the publish forever.
        pubky.rateLimitNextCalls = 100

        assertTrue(repo.publish(testDeck(id = "deck1"), listOf(testCard("c1"))).isFailure)
    }

    // ── delete ───────────────────────────────────────────────────────────

    @Test
    fun deleteSweepsEverythingUnderTheDeckRootManifestLast() = runTest {
        val deck = testDeck(id = "deck1", tags = listOf(Tag("spanish")))
        repo.publish(deck, listOf(testCard("c1"), testCard("c2"))).getOrThrow()
        // An SRS record under the deck root must be swept too.
        pubky.store["$deckRoot/srs/c1.json"] = "{}"

        repo.delete("deck1").getOrThrow()

        assertTrue(pubky.store.keys.none { it.startsWith(deckRoot) })
        assertEquals("$deckRoot/manifest.json", pubky.deletes.last())
        assertTrue(pubky.deletes.containsAll(listOf("$deckRoot/cards/0.json", "$deckRoot/srs/c1.json")))
        assertEquals(listOf(deck.pubkyUri to Tag("spanish")), tagRepo.removedTags)
        // Otherwise global browse keeps listing a deck whose manifest no longer resolves.
        assertEquals(listOf(deck.pubkyUri to ReservedTags.DECK), tagRepo.removedReservedTags)
        assertNull(repo.getLocal("deck1"))
    }

    @Test
    fun deleteFinishesWhenTheManifestNamesChunksThatWereNeverWritten() = runTest {
        // The state an import that dies part-way leaves behind, and the one the user hit: the
        // manifest advertises chunks whose records never landed. The sweep derives paths from that
        // manifest, so it asks the homeserver to delete records that are not there and gets a 404.
        // Treating that as a failure abandoned the sweep before the manifest — deleted last, on
        // purpose — so the deck kept listing, the next attempt hit the same missing record, and the
        // deck could never be deleted.
        val deck = testDeck(id = "deck1")
        repo.publish(deck, listOf(testCard("c1"))).getOrThrow()
        pubky.store.remove("$deckRoot/cards/0.json")

        repo.delete("deck1").getOrThrow()

        assertTrue(pubky.store.keys.none { it.startsWith(deckRoot) })
        assertEquals("$deckRoot/manifest.json", pubky.deletes.last())
        assertNull(repo.getLocal("deck1"))
    }

    // ── listByAuthor / fetchRemote / cache ───────────────────────────────

    @Test
    fun listByAuthorParsesDeckIdsFromTheListPayload() = runTest {
        putRemoteManifest(author = "friendpk", deckId = "alpha", title = "Alpha")
        putRemoteManifest(author = "friendpk", deckId = "beta", title = "Beta")
        // A record from another namespace must not leak in.
        pubky.store["pubky://friendpk/pub/pubky.app/profile.json"] = "{}"

        val decks = repo.listByAuthor("friendpk")

        assertEquals(listOf("alpha", "beta"), decks.map { it.id }.sorted())
        assertEquals(listOf("Alpha", "Beta"), decks.map { it.title }.sorted())
    }

    @Test
    fun listOwnedReturnsEmptyWhenSignedOut() = runTest {
        session.set(null)

        assertEquals(emptyList(), repo.listOwned())
    }

    // An unreachable homeserver must never look like an empty library: Pubky is the only
    // source of truth, so "no decks" would read to the user as "my decks are gone".

    @Test
    fun listByAuthorThrowsWhenTheHomeserverIsUnreachable() = runTest {
        pubky.failListWith = PubkyError("HTTP transport error: error sending request for url (...)")

        assertFailsWith<PubkyError> { repo.listByAuthor("friendpk") }
    }

    @Test
    fun listByAuthorReturnsEmptyWhenNothingHasBeenPublished() = runTest {
        pubky.failListWith = PubkyError("not found: pubky://friendpk/pub/loopky/decks/")

        assertEquals(emptyList(), repo.listByAuthor("friendpk"))
    }

    @Test
    fun listByAuthorThrowsWhenTheListingHasDecksButNoneCanBeRead() = runTest {
        putRemoteManifest(author = "friendpk", deckId = "alpha", title = "Alpha")
        pubky.failGetWith = PubkyError("HTTP transport error: error sending request for url (...)")

        assertFailsWith<PubkyError> { repo.listByAuthor("friendpk") }
    }

    @Test
    fun listByAuthorStillReturnsTheDecksItCanRead() = runTest {
        putRemoteManifest(author = "friendpk", deckId = "alpha", title = "Alpha")
        // A listed deck whose manifest is missing must not hide the readable one.
        pubky.store["pubky://friendpk/pub/loopky/decks/broken/manifest.json"] = "{ not json"

        val decks = repo.listByAuthor("friendpk")

        assertEquals(listOf("alpha"), decks.map { it.id })
    }

    @Test
    fun fetchRemotePopulatesTheLocalCache() = runTest {
        putRemoteManifest(author = "friendpk", deckId = "alpha", title = "Alpha")
        assertNull(repo.getLocal("alpha"))

        val fetched = repo.fetchRemote("friendpk", "alpha").getOrThrow()

        assertEquals(fetched, repo.getLocal("alpha"))
        assertEquals("Alpha", assertNotNull(repo.getLocal("alpha")).title)
    }

    @Test
    fun fetchRemoteFailsForMissingDeck() = runTest {
        assertTrue(repo.fetchRemote("friendpk", "nope").isFailure)
    }

    // ── sync ─────────────────────────────────────────────────────────────

    @Test
    fun syncPullsTheCardsIntoTheCacheWithoutWritingThemBack() = runTest {
        val cards = listOf(testCard("c1", updatedAt = 10L), testCard("c2", updatedAt = 20L))
        repo.publish(testDeck(id = "deck1"), cards).getOrThrow()

        // A fresh repo pair over the same store: the manifest and card records are on the
        // homeserver, nothing is cached.
        val coldCardRepo = CardRepositoryImpl(pubky, session, revalidator, Dispatchers.Unconfined)
        val coldRepo = deckRepository(pubky, session, coldCardRepo, revalidator, tagRepo)
        pubky.puts.clear()

        coldRepo.sync("deck1").getOrThrow()

        // publish() stamps a sparse ord onto each card, so compare identity and order.
        assertEquals(cards.map { it.id }, coldCardRepo.listByDeck("deck1").map { it.id })
        // sync used to re-`upsert` every card it had just downloaded, PUTting each one straight
        // back to the homeserver it came from.
        assertTrue(pubky.puts.isEmpty(), "sync wrote ${pubky.puts.map { it.first }}")
    }

    @Test
    fun syncResolvesTheAuthorFromTheCachedDeckNotTheSession() = runTest {
        val cards = listOf(testCard("c1", deckId = "foreign"), testCard("c2", deckId = "foreign"))
        putRemoteDeck(author = "friendpk", deckId = "foreign", cards = cards)
        // Reaching a foreign deck warms the cache — that is what sync resolves the author from.
        repo.fetchRemote("friendpk", "foreign").getOrThrow()
        pubky.gets.clear()

        val synced = repo.sync("foreign").getOrThrow()

        assertEquals("friendpk", synced.authorPubky)
        assertEquals(listOf("c1", "c2"), cardRepo.listByDeck("foreign").map { it.id })
        // sync used to hardcode the session as the author, so it read pubky://ownerpk/… for a deck
        // that lives on someone else's homeserver and always failed (#33 blocker 1).
        assertTrue(
            pubky.gets.none { it.startsWith("pubky://$TEST_PUBKY/") },
            "sync read under the session author: ${pubky.gets}",
        )
    }

    @Test
    fun syncDropsCardsTheDeckNoLongerContains() = runTest {
        val cardRepo = CardRepositoryImpl(pubky, session, revalidator, Dispatchers.Unconfined)
        val repoWithCards = deckRepository(pubky, session, cardRepo, revalidator, tagRepo)
        repoWithCards.publish(
            testDeck(id = "deck1"),
            listOf(testCard("c1"), testCard("c2")),
        ).getOrThrow()
        assertEquals(listOf("c1", "c2"), cardRepo.listByDeck("deck1").map { it.id })

        // The deck is republished without c2, as an edit that removed a card would leave it.
        repoWithCards.publish(
            testDeck(id = "deck1", updatedAt = 9_000L),
            listOf(testCard("c1")),
        ).getOrThrow()
        repoWithCards.sync("deck1").getOrThrow()

        assertEquals(listOf("c1"), cardRepo.listByDeck("deck1").map { it.id })
    }

    // ── clone deck ───────────────────────────────────────────────────────

    @Test
    fun cloneProducesAnIndependentDeckUnderYourPubkyWithNewCardIds() = runTest {
        val source = putRemoteDeck(
            "friendpk",
            "orig",
            listOf(testCard("c1", deckId = "orig"), testCard("c2", deckId = "orig")),
        )

        val clone = repo.clone(source, "My copy").getOrThrow()

        assertEquals(TEST_PUBKY, clone.authorPubky)
        assertTrue(clone.id != source.id, "the clone reused the source's deck id")
        assertEquals("My copy", clone.title)
        assertEquals(expected = 2, actual = clone.cardCount)

        // New card ids are what stop grading the clone from moving the original's review state:
        // SRS is keyed by (author, deck, card).
        val cloned = cardRepo.listByDeck(clone.id)
        assertEquals(expected = 2, actual = cloned.size)
        assertTrue(cloned.none { it.id == "c1" || it.id == "c2" }, "card ids were reused")
        assertEquals(cloned.map { it.id }.distinct().size, cloned.size)
        assertEquals(listOf("front of c1", "front of c2"), cloned.map { it.front.text })
        assertTrue(cloned.all { it.deckId == clone.id })

        // Everything written lands under your own pubky; the source is untouched.
        assertTrue(
            pubky.puts.none { it.first.startsWith("pubky://friendpk/") },
            "clone wrote to the author's homeserver: ${pubky.puts.map { it.first }}",
        )
    }

    @Test
    fun cloneRecordsProvenanceThatSurvivesAFetchRoundTrip() = runTest {
        val source = putRemoteDeck("friendpk", "orig", listOf(testCard("c1", deckId = "orig")))

        val clone = repo.clone(source, "My copy").getOrThrow()

        // Read it back off the homeserver rather than trusting the in-memory copy.
        val refetched = DeckRepositoryImpl(
            pubky,
            session,
            CardRepositoryImpl(pubky, session, revalidator, Dispatchers.Unconfined),
            revalidator,
            tagRepo,
            FakeMediaRepository(),
            FakeBackgroundTasks(),
            FakeDeckCacheStore(),
        ).fetchRemote(TEST_PUBKY, clone.id).getOrThrow()

        assertEquals(DeckSource.Kind.Clone, refetched.source?.kind)
        assertEquals(source.pubkyUri.value, refetched.source?.uri)
    }

    @Test
    fun clonePinsMediaToTheSourceRatherThanReUploadingIt() = runTest {
        val sha = "abc123"
        val card = testCard("c1", deckId = "orig").copy(
            back = CardSide(
                text = "back",
                imageRef = MediaRef.Image(
                    path = "media/$sha.jpg",
                    mime = "image/jpeg",
                    sha256 = sha,
                    width = 10,
                    height = 10,
                ),
            ),
        )
        val source = putRemoteDeck("friendpk", "orig", listOf(card))

        val clone = repo.clone(source, "My copy").getOrThrow()

        // Clone-by-reference: the ref points at the author's blob, so cloning an Anki-sized deck
        // costs card records and nothing else.
        val ref = assertNotNull(cardRepo.listByDeck(clone.id).single().back.imageRef)
        assertEquals("pubky://friendpk/pub/loopky/decks/orig/media/$sha.jpg", ref.uri)
        assertTrue(pubky.bytePuts.isEmpty(), "clone re-uploaded blobs: ${pubky.bytePuts.map { it.first }}")
    }

    @Test
    fun cloneCopiesARemoteImageRefVerbatim() = runTest {
        val card = testCard("c1", deckId = "orig").copy(
            front = CardSide(
                text = "front",
                // An Unsplash cover: re-hosting the bytes would breach their licence, and there is
                // no blob to pin an origin to.
                imageRef = MediaRef.Image(
                    path = "",
                    mime = "image/jpeg",
                    sha256 = "",
                    width = null,
                    height = null,
                    url = "https://images.unsplash.com/photo-1",
                ),
            ),
        )
        val source = putRemoteDeck("friendpk", "orig", listOf(card))

        val clone = repo.clone(source, "My copy").getOrThrow()

        val ref = assertNotNull(cardRepo.listByDeck(clone.id).single().front.imageRef)
        assertEquals("https://images.unsplash.com/photo-1", ref.url)
        assertNull(ref.uri, "a remote image was pinned to a homeserver blob that doesn't exist")
    }

    @Test
    fun cloningACloneKeepsTheFirstOriginRatherThanChaining() = runTest {
        val sha = "abc123"
        val alreadyPinned = MediaRef.Image(
            path = "media/$sha.jpg",
            mime = "image/jpeg",
            sha256 = sha,
            width = null,
            height = null,
            uri = "pubky://firstpk/pub/loopky/decks/first/media/$sha.jpg",
        )
        val card = testCard("c1", deckId = "orig")
            .copy(back = CardSide(text = "back", imageRef = alreadyPinned))
        val source = putRemoteDeck("friendpk", "orig", listOf(card))

        val clone = repo.clone(source, "My copy").getOrThrow()

        // friendpk never hosted this blob either — pointing at them would break the chain.
        val ref = assertNotNull(cardRepo.listByDeck(clone.id).single().back.imageRef)
        assertEquals(alreadyPinned.uri, ref.uri)
    }

    @Test
    fun cloneCreditsTheOriginalAuthorAndUnfollowsTheSource() = runTest {
        val source = putRemoteDeck("friendpk", "orig", listOf(testCard("c1", deckId = "orig")))
        repo.followDeck(source).getOrThrow()

        val clone = repo.clone(source, "My copy").getOrThrow()

        // The loopky-cloned label goes on the *source*, so credit accrues to the original author.
        assertTrue(source.pubkyUri to ReservedTags.CLONED in tagRepo.putReservedTags)
        // You own a copy now, so tracking their edits on theirs is noise.
        assertFalse(repo.isFollowingDeck("orig"))
        assertTrue(subscriptionUrl("friendpk", "orig") in pubky.deletes)
        assertTrue(repo.isFollowingDeck(clone.id).not())
    }

    @Test
    fun editingACloneNeverTouchesTheOriginal() = runTest {
        val source = putRemoteDeck("friendpk", "orig", listOf(testCard("c1", deckId = "orig")))
        val clone = repo.clone(source, "My copy").getOrThrow()
        val cardId = cardRepo.listByDeck(clone.id).single().id
        pubky.puts.clear()

        repo.upsertCard(clone.id, testCard(cardId, deckId = clone.id, front = "edited")).getOrThrow()

        assertTrue(
            pubky.puts.none { it.first.startsWith("pubky://friendpk/") },
            "editing the clone wrote to the original: ${pubky.puts.map { it.first }}",
        )
        // And the original's own records still read as they did.
        val original = loopkyJson.decodeFromString<CardChunkDto>(
            pubky.store.getValue("pubky://friendpk/pub/loopky/decks/orig/cards/0.json"),
        )
        assertEquals(listOf("front of c1"), original.cards.map { it.front.text })
    }

    @Test
    fun cloneRefusesABlankTitleRatherThanFallingBackToTheSourceName() = runTest {
        val source = putRemoteDeck("friendpk", "orig", listOf(testCard("c1", deckId = "orig")))

        // The copy lands next to the deck it forked; two identical titles there are the thing the
        // mandatory rename exists to prevent, so a blank one is a failure and never a default.
        val writesBefore = pubky.puts.size
        assertTrue(repo.clone(source, "   ").isFailure)
        assertEquals(writesBefore, pubky.puts.size, "a refused clone still wrote records")
    }

    @Test
    fun cloneTrimsTheTitleItIsGiven() = runTest {
        val source = putRemoteDeck("friendpk", "orig", listOf(testCard("c1", deckId = "orig")))

        assertEquals("My copy", repo.clone(source, "  My copy  ").getOrThrow().title)
    }

    @Test
    fun cloningYourOwnDeckDuplicatesItRatherThanFailing() = runTest {
        val mine = repo.publish(testDeck(id = "deck1"), listOf(testCard("c1"))).getOrThrow()

        val clone = repo.clone(mine, "My copy").getOrThrow()

        assertTrue(clone.id != mine.id)
        assertEquals(TEST_PUBKY, clone.authorPubky)
        // Your own blobs, under your own deck — no origin to pin.
        assertEquals(DeckSource.Kind.Clone, clone.source?.kind)
    }

    // ── single-card writes ───────────────────────────────────────────────

    @Test
    fun upsertCardRewritesOnlyItsChunkAndBumpsTheManifest() = runTest {
        repo.publish(testDeck(id = "deck1"), (1..250).map { testCard("c$it") }).getOrThrow()
        pubky.puts.clear()

        val edited = testCard("c5", front = "edited", updatedAt = 7_000L)
        val deck = repo.upsertCard("deck1", edited).getOrThrow()

        // Exactly two writes: the one chunk holding c5, and the manifest.
        assertEquals(
            listOf("$deckRoot/cards/0.json", "$deckRoot/manifest.json"),
            pubky.puts.map { it.first },
        )
        assertEquals(expected = 250, actual = deck.cardCount, message = "an edit changed the count")

        val chunk = loopkyJson.decodeFromString<CardChunkDto>(
            pubky.store.getValue("$deckRoot/cards/0.json"),
        )
        assertEquals("edited", chunk.cards.single { it.id == "c5" }.front.text)
        assertEquals(expected = 100, actual = chunk.cards.size)
    }

    @Test
    fun editingACardReadsOnlyTheChunkThatHoldsIt() = runTest {
        repo.publish(testDeck(id = "deck1"), (1..500).map { testCard("c$it") }).getOrThrow()
        pubky.gets.clear()

        // c450 lives in chunk 4. Locating it must not walk chunks 0..3 on the way.
        repo.upsertCard("deck1", testCard("c450", front = "edited")).getOrThrow()

        assertEquals(
            listOf("$deckRoot/cards/4.json"),
            pubky.gets,
            "a single-card edit read more than its own chunk",
        )
    }

    @Test
    fun upsertCardAppendsANewCardToTheLastChunkWithRoom() = runTest {
        repo.publish(testDeck(id = "deck1"), (1..150).map { testCard("c$it") }).getOrThrow()

        val deck = repo.upsertCard("deck1", testCard("brand-new")).getOrThrow()

        assertEquals(expected = 151, actual = deck.cardCount)
        assertEquals(listOf(100, 51), deck.chunks.map { it.count })
        val chunk = loopkyJson.decodeFromString<CardChunkDto>(
            pubky.store.getValue("$deckRoot/cards/1.json"),
        )
        assertTrue(chunk.cards.any { it.id == "brand-new" })
    }

    @Test
    fun upsertCardOpensANewChunkWhenTheLastOneIsFull() = runTest {
        repo.publish(testDeck(id = "deck1"), (1..100).map { testCard("c$it") }).getOrThrow()

        val deck = repo.upsertCard("deck1", testCard("overflow")).getOrThrow()

        assertEquals(listOf(0, 1), deck.chunks.map { it.n })
        assertEquals(listOf(100, 1), deck.chunks.map { it.count })
        assertEquals(expected = 101, actual = deck.cardCount)
    }

    @Test
    fun deleteCardLeavesAHoleRatherThanResequencingTheDeck() = runTest {
        repo.publish(testDeck(id = "deck1"), (1..250).map { testCard("c$it") }).getOrThrow()
        pubky.puts.clear()

        val deck = repo.deleteCard("deck1", "c5").getOrThrow()

        // Only the affected chunk shrinks; later chunks are untouched.
        assertEquals(listOf(99, 100, 50), deck.chunks.map { it.count })
        assertEquals(expected = 249, actual = deck.cardCount)
        assertEquals(
            listOf("$deckRoot/cards/0.json", "$deckRoot/manifest.json"),
            pubky.puts.map { it.first },
        )
    }

    @Test
    fun deletingTheLastCardInAChunkDropsTheChunkRecord() = runTest {
        repo.publish(testDeck(id = "deck1"), listOf(testCard("only"))).getOrThrow()

        val deck = repo.deleteCard("deck1", "only").getOrThrow()

        assertEquals(emptyList(), deck.chunks)
        assertEquals(expected = 0, actual = deck.cardCount)
        assertTrue("$deckRoot/cards/0.json" !in pubky.store)
    }

    @Test
    fun upsertCardRejectsADeckYouDoNotOwn() = runTest {
        putRemoteManifest("friendpk", "foreign", "Someone else's")

        assertTrue(repo.upsertCard("foreign", testCard("c1")).isFailure)
    }

    private fun subscriptionUrl(author: String, deckId: String) =
        "pubky://$TEST_PUBKY/pub/loopky/subscriptions/$author/$deckId.json"

    private fun putRemoteManifest(author: String, deckId: String, title: String) {
        val dto = testDeck(id = deckId, authorPubky = author, title = title).toDto()
        pubky.store["pubky://$author/pub/loopky/decks/$deckId/manifest.json"] =
            loopkyJson.encodeToString(dto)
    }

    /** A whole deck — manifest plus chunk records — on someone else's homeserver. */
    private fun putRemoteDeck(author: String, deckId: String, cards: List<Card>): Deck {
        val deck = testDeckWithCards(cards, id = deckId, authorPubky = author)
        val root = "pubky://$author/pub/loopky/decks/$deckId"
        pubky.store["$root/manifest.json"] = loopkyJson.encodeToString(deck.toDto())
        cards.chunked(CHUNK_SIZE).forEachIndexed { n, batch ->
            pubky.store["$root/cards/$n.json"] = loopkyJson.encodeToString(
                CardChunkDto(deck_id = deckId, chunk = n, cards = batch.map { it.toDto() }),
            )
        }
        return deck
    }
}
