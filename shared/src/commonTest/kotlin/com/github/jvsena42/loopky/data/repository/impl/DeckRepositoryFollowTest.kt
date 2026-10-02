package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.CHUNK_SIZE
import com.github.jvsena42.loopky.data.pubky.CardChunkDto
import com.github.jvsena42.loopky.data.pubky.PubkyError
import com.github.jvsena42.loopky.data.pubky.SubscriptionDto
import com.github.jvsena42.loopky.data.pubky.toDto
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.testing.CountingRevalidator
import com.github.jvsena42.loopky.testing.FakeAppPreferences
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.RecordingTagRepository
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.deckRepository
import com.github.jvsena42.loopky.testing.fakeSession
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Following someone else's deck (#33) — subscriptions, the decks they resolve to, and the writes
 * that must refuse a deck you do not own.
 *
 * Split out of `DeckRepositoryImplTest` rather than sharing its file: this is the one part of the
 * repository whose subject is a *stranger's* homeserver, and it is the whole of what
 * `listFollowedBy` and the friend profile rest on.
 */
class DeckRepositoryFollowTest {

    private val pubky = FakePubkyClient()
    private val session = signedInProvider()
    private val revalidator = CountingRevalidator()
    private val tagRepo = RecordingTagRepository()
    private val cardRepo = CardRepositoryImpl(pubky, session, revalidator, Dispatchers.Unconfined)
    private val prefs = FakeAppPreferences()
    private val repo = deckRepository(pubky, session, cardRepo, revalidator, tagRepo, preferences = prefs)

    @Test
    fun followDeckWritesTheSubscriptionRecordAndTheReservedTag() = runTest {
        val deck = putRemoteDeck("friendpk", "foreign", listOf(testCard("c1", deckId = "foreign")))

        repo.followDeck(deck).getOrThrow()

        // On *your* homeserver, author-keyed so two authors' decks cannot collide.
        val record = pubky.store.getValue(subscriptionUrl("friendpk", "foreign"))
        val dto = loopkyJson.decodeFromString<SubscriptionDto>(record)
        assertEquals("friendpk", dto.author_pubky)
        assertEquals("foreign", dto.deck_id)
        assertEquals(deck.pubkyUri.value, dto.deck_uri)
        // Following from deck detail means you are looking at it — no phantom "updated" badge.
        assertEquals(deck.updatedAt, dto.last_seen_updated_at)

        assertTrue(repo.isFollowingDeck("foreign"))
        // The reserved label is what makes "N people follow this" fall out of the indexer.
        assertEquals(listOf(deck.pubkyUri to ReservedTags.FOLLOWED), tagRepo.putReservedTags)
    }

    @Test
    fun unfollowDeckDeletesTheRecordAndNothingElse() = runTest {
        val deck = putRemoteDeck("friendpk", "foreign", listOf(testCard("c1", deckId = "foreign")))
        repo.followDeck(deck).getOrThrow()
        pubky.deletes.clear()

        repo.unfollowDeck("friendpk", "foreign").getOrThrow()

        // Only the subscription. SRS state is yours, not the author's, and re-following must not
        // reset your progress (Architecture.md §8.3).
        assertEquals(listOf(subscriptionUrl("friendpk", "foreign")), pubky.deletes)
        assertFalse(repo.isFollowingDeck("foreign"))
        assertEquals(listOf(deck.pubkyUri to ReservedTags.FOLLOWED), tagRepo.removedReservedTags)
    }

    @Test
    fun listFollowedReadsDecksFromTheirAuthorsHomeservers() = runTest {
        val alpha = putRemoteDeck("friendpk", "alpha", listOf(testCard("a1", deckId = "alpha")))
        val beta = putRemoteDeck("otherpk", "beta", listOf(testCard("b1", deckId = "beta")))
        repo.followDeck(alpha).getOrThrow()
        repo.followDeck(beta).getOrThrow()

        val followed = repo.listFollowed()

        assertEquals(listOf("alpha", "beta"), followed.map { it.id }.sorted())
        assertEquals(listOf("friendpk", "otherpk"), followed.map { it.authorPubky }.sorted())
    }

    @Test
    fun listFollowedForgetsTheSubscriptionsOfAPreviousAccount() = runTest {
        val alpha = putRemoteDeck("friendpk", "alpha", listOf(testCard("a1", deckId = "alpha")))
        repo.followDeck(alpha).getOrThrow()
        assertEquals(listOf("alpha"), repo.listFollowed().map { it.id })

        // Sign out, then sign in as a pubky that has never followed anything. The process — and so
        // the repository and its caches — survives, which is exactly the case that broke: the
        // subscription cache was served before anything checked whose session it belonged to, so a
        // brand-new account opened Home and found the previous user's decks waiting for it.
        session.set(null)
        session.set(fakeSession("freshpk"))

        assertEquals(emptyList(), repo.listFollowed().map { it.id })
    }

    @Test
    fun listFollowedDropsADeckItsAuthorDeleted() = runTest {
        val alpha = putRemoteDeck("friendpk", "alpha", listOf(testCard("a1", deckId = "alpha")))
        val gone = putRemoteDeck("otherpk", "gone", listOf(testCard("g1", deckId = "gone")))
        repo.followDeck(alpha).getOrThrow()
        repo.followDeck(gone).getOrThrow()
        // The author deleted it; the subscription outlived the deck.
        pubky.store.remove("pubky://otherpk/pub/loopky/decks/gone/manifest.json")

        // One dead subscription must not empty the whole library.
        assertEquals(listOf("alpha"), repo.listFollowed().map { it.id })
    }

    @Test
    fun listFollowedThrowsWhenNothingCanBeRead() = runTest {
        val alpha = putRemoteDeck("friendpk", "alpha", listOf(testCard("a1", deckId = "alpha")))
        repo.followDeck(alpha).getOrThrow()
        pubky.failGetWith = PubkyError("HTTP transport error: error sending request for url (...)")

        // An unreachable homeserver must not read as "you follow nothing" — same rule as
        // listByAuthor, and for the same reason.
        assertFailsWith<PubkyError> { repo.listFollowed() }
    }

    @Test
    fun listFollowedIsEmptyWhenNothingIsFollowed() = runTest {
        assertEquals(emptyList(), repo.listFollowed())
    }

    @Test
    fun listFollowedByReadsAStrangersSubscriptionsFromTheirOwnHomeserver() = runTest {
        val alpha = putRemoteDeck("authorpk", "alpha", listOf(testCard("a1", deckId = "alpha")))
        // A subscription record under *someone else's* pubky — what their profile has to read to
        // show what they study. Nothing about it involves the signed-in account.
        pubky.store["pubky://strangerpk/pub/loopky/subscriptions/authorpk/alpha.json"] =
            loopkyJson.encodeToString(
                SubscriptionDto(
                    deck_uri = alpha.pubkyUri.value,
                    author_pubky = "authorpk",
                    deck_id = "alpha",
                    followed_at = 1L,
                    last_seen_updated_at = alpha.updatedAt,
                ),
            )

        val followed = repo.listFollowedBy("strangerpk")

        assertEquals(listOf("alpha"), followed.map { it.id })
        assertEquals(listOf("authorpk"), followed.map { it.authorPubky })
        // And it did not quietly answer with the reader's own subscriptions, which is empty here.
        assertEquals(emptyList(), repo.listFollowed())
    }

    @Test
    fun listFollowedByAnswersTheSignedInUserFromTheSessionCache() = runTest {
        val alpha = putRemoteDeck("friendpk", "alpha", listOf(testCard("a1", deckId = "alpha")))
        repo.followDeck(alpha).getOrThrow()

        // Own profile goes through listFollowed, so a follow made a moment ago is already there
        // rather than waiting on the homeserver listing to catch up.
        assertEquals(listOf("alpha"), repo.listFollowedBy(TEST_PUBKY).map { it.id })
    }

    @Test
    fun listFollowedByIsEmptyForSomeoneWhoFollowsNothing() = runTest {
        assertEquals(emptyList(), repo.listFollowedBy("strangerpk"))
    }

    @Test
    fun syncResolvesTheAuthorFromTheSubscriptionOnAColdCache() = runTest {
        val deck = putRemoteDeck("friendpk", "foreign", listOf(testCard("c1", deckId = "foreign")))
        repo.followDeck(deck).getOrThrow()

        // A fresh repo over the same store: the subscription is on the homeserver, nothing cached.
        val coldCards = CardRepositoryImpl(pubky, session, revalidator, Dispatchers.Unconfined)
        val coldRepo = deckRepository(pubky, session, coldCards, revalidator, tagRepo)

        val synced = coldRepo.sync("foreign").getOrThrow()

        assertEquals("friendpk", synced.authorPubky)
        assertEquals(listOf("c1"), coldCards.listByDeck("foreign").map { it.id })
    }

    @Test
    fun markSeenClearsTheUpdateFlagWithoutTouchingTheDeck() = runTest {
        val deck = putRemoteDeck("friendpk", "foreign", listOf(testCard("c1", deckId = "foreign")))
        repo.followDeck(deck).getOrThrow()
        // The author edited it after you followed.
        val edited = deck.copy(updatedAt = deck.updatedAt + 1_000L)
        putRemoteDeck("friendpk", "foreign", listOf(testCard("c1", deckId = "foreign")))
        pubky.store["pubky://friendpk/pub/loopky/decks/foreign/manifest.json"] =
            loopkyJson.encodeToString(edited.toDto())
        repo.fetchRemote("friendpk", "foreign").getOrThrow()
        assertTrue(repo.hasUpdate("foreign"))

        repo.markSeen(edited)

        assertFalse(repo.hasUpdate("foreign"))
        // The record moved; the author's deck did not.
        val dto = loopkyJson.decodeFromString<SubscriptionDto>(
            pubky.store.getValue(subscriptionUrl("friendpk", "foreign")),
        )
        assertEquals(edited.updatedAt, dto.last_seen_updated_at)
    }

    @Test
    fun hasUpdateIsFalseForADeckYouOwn() = runTest {
        repo.publish(testDeck(id = "deck1"), listOf(testCard("c1"))).getOrThrow()

        assertFalse(repo.hasUpdate("deck1"))
    }

    @Test
    fun deleteRejectsADeckYouDoNotOwn() = runTest {
        val deck = putRemoteDeck("friendpk", "foreign", listOf(testCard("c1", deckId = "foreign")))
        repo.followDeck(deck).getOrThrow()
        pubky.deletes.clear()

        // Deleting used to build its sweep paths from the session, so this ranged over your own
        // namespace looking for a deck that was never there.
        assertTrue(repo.delete("foreign").isFailure)
        assertEquals(emptyList(), pubky.deletes)
        assertTrue("pubky://friendpk/pub/loopky/decks/foreign/manifest.json" in pubky.store)
    }

    @Test
    fun updateMetadataRejectsADeckYouDoNotOwn() = runTest {
        val deck = putRemoteDeck("friendpk", "foreign", listOf(testCard("c1", deckId = "foreign")))

        assertTrue(repo.updateMetadata(deck.copy(title = "Hijacked")).isFailure)
    }

    @Test
    fun deleteCardRejectsADeckYouDoNotOwn() = runTest {
        putRemoteDeck("friendpk", "foreign", listOf(testCard("c1", deckId = "foreign")))

        assertTrue(repo.deleteCard("foreign", "c1").isFailure)
    }

    @Test
    fun followPendingAfterSignInFollowsTheRememberedDeckAndForgetsIt() = runTest {
        putRemoteDeck("friendpk", "foreign", listOf(testCard("c1", deckId = "foreign")))
        repo.rememberFollowForSignIn("friendpk", "foreign")

        val followed = repo.followPendingAfterSignIn().getOrThrow()

        assertEquals("foreign", followed?.id)
        assertTrue(repo.isFollowingDeck("foreign"))
        assertEquals("", prefs.pendingDeckFollowValue)
    }

    @Test
    fun followPendingAfterSignInWaitsForASession() = runTest {
        putRemoteDeck("friendpk", "foreign", listOf(testCard("c1", deckId = "foreign")))
        repo.rememberFollowForSignIn("friendpk", "foreign")
        session.set(null)

        assertNull(repo.followPendingAfterSignIn().getOrThrow())
        // Kept for the session that is coming.
        assertEquals("friendpk/foreign", prefs.pendingDeckFollowValue)
    }

    @Test
    fun followPendingAfterSignInNeverFollowsYourOwnDeck() = runTest {
        repo.rememberFollowForSignIn(TEST_PUBKY, "mine")

        assertNull(repo.followPendingAfterSignIn().getOrThrow())
        assertFalse(repo.isFollowingDeck("mine"))
        assertEquals("", prefs.pendingDeckFollowValue)
    }

    @Test
    fun followPendingAfterSignInForgetsADeckThatNoLongerExists() = runTest {
        repo.rememberFollowForSignIn("friendpk", "gone")

        assertTrue(repo.followPendingAfterSignIn().isFailure)
        assertEquals("", prefs.pendingDeckFollowValue)
    }

    @Test
    fun followPendingAfterSignInKeepsTheDeckWhenTheFollowFails() = runTest {
        putRemoteDeck("friendpk", "foreign", listOf(testCard("c1", deckId = "foreign")))
        repo.rememberFollowForSignIn("friendpk", "foreign")
        pubky.failAllSessionCallsWith = PubkyError("HTTP 500")

        assertTrue(repo.followPendingAfterSignIn().isFailure)
        assertEquals("friendpk/foreign", prefs.pendingDeckFollowValue)
    }

    private fun subscriptionUrl(author: String, deckId: String) =
        "pubky://$TEST_PUBKY/pub/loopky/subscriptions/$author/$deckId.json"

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
