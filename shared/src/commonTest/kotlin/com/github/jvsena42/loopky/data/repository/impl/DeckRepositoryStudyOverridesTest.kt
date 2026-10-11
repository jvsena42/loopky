package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.CHUNK_SIZE
import com.github.jvsena42.loopky.data.pubky.CardChunkDto
import com.github.jvsena42.loopky.data.pubky.ManifestDto
import com.github.jvsena42.loopky.data.pubky.StudyOverridesDto
import com.github.jvsena42.loopky.data.pubky.SubscriptionDto
import com.github.jvsena42.loopky.data.pubky.toDto
import com.github.jvsena42.loopky.domain.model.Card
import com.github.jvsena42.loopky.domain.model.Deck
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.domain.model.StudyFeatures
import com.github.jvsena42.loopky.domain.model.study
import com.github.jvsena42.loopky.testing.CountingRevalidator
import com.github.jvsena42.loopky.testing.FakeDeckCacheStore
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.deckRepository
import com.github.jvsena42.loopky.testing.signedInProvider
import com.github.jvsena42.loopky.testing.testCard
import com.github.jvsena42.loopky.testing.testCoverImage
import com.github.jvsena42.loopky.testing.testDeckWithCards
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A follower's own study opt-ins for a deck they do not own: stored on their subscription record,
 * laid over the author's on every read, and gone with the subscription.
 */
class DeckRepositoryStudyOverridesTest {

    private val pubky = FakePubkyClient()
    private val session = signedInProvider()
    private val revalidator = CountingRevalidator()
    private val cardRepo = CardRepositoryImpl(pubky, session, revalidator, Dispatchers.Unconfined)
    private val deckCache = FakeDeckCacheStore()
    private val repo = newProcess()

    /** A second repository over the same homeserver: nothing in memory, as after a restart. */
    private fun newProcess() = deckRepository(pubky, session, cardRepo, revalidator, deckCache = deckCache)

    // The fixture's author opt-ins: listen and speak on, type and reverse off.
    private val authored = StudyFeatures(listen = true, speak = true, type = false, reverse = false)
    private val mine = StudyFeatures(listen = false, speak = true, type = true, reverse = false)

    @Test
    fun onlyTheOptInsThatDifferFromTheAuthorsAreStoredOnTheSubscription() = runTest {
        val deck = followedDeck()

        repo.customizeStudy(deck.id, mine).getOrThrow()

        assertEquals(
            StudyOverridesDto(listen_enabled = false, type_enabled = true),
            subscription().study,
        )
        // Not a record of its own: one would outlive the unfollow that deletes this one.
        assertEquals(
            listOf(SUBSCRIPTION_URL),
            pubky.store.keys.filter { it.startsWith("pubky://$TEST_PUBKY/") },
        )
    }

    @Test
    fun theAuthorsManifestIsNeverWritten() = runTest {
        val deck = followedDeck()
        val manifest = pubky.store.getValue(MANIFEST_URL)

        repo.customizeStudy(deck.id, mine).getOrThrow()

        assertEquals(manifest, pubky.store.getValue(MANIFEST_URL))
        assertTrue(pubky.puts.none { it.first.startsWith("pubky://$AUTHOR/") })
    }

    @Test
    fun everyReadOfTheDeckCarriesTheReadersOptIns() = runTest {
        val deck = followedDeck()

        val returned = repo.customizeStudy(deck.id, mine).getOrThrow()

        for (read in listOf(
            returned,
            assertNotNull(repo.getLocal(deck.id)),
            repo.fetchRemote(AUTHOR, deck.id).getOrThrow(),
            repo.sync(deck.id).getOrThrow(),
            repo.listFollowed().single(),
        )) {
            assertEquals(mine, read.study)
            assertEquals(authored, read.authorStudy)
        }
    }

    @Test
    fun theOptInsSurviveARestartEvenWhenTheDeckIsTheFirstThingRead() = runTest {
        val deck = followedDeck()
        repo.customizeStudy(deck.id, mine).getOrThrow()

        // A deck opened from a link: nothing has listed the subscriptions yet.
        val read = newProcess().fetchRemote(AUTHOR, deck.id).getOrThrow()

        assertEquals(mine, read.study)
    }

    @Test
    fun theLibrarySnapshotPaintsTheReadersOptIns() = runTest {
        val deck = followedDeck()
        repo.customizeStudy(deck.id, mine).getOrThrow()

        repo.listFollowed()

        assertEquals(mine, assertNotNull(newProcess().listCached()).followed.single().study)
    }

    @Test
    fun unfollowingLeavesNothingBehind() = runTest {
        val deck = followedDeck()
        repo.customizeStudy(deck.id, mine).getOrThrow()

        repo.unfollowDeck(AUTHOR, deck.id).getOrThrow()

        assertTrue(
            pubky.store.keys.none { it.startsWith("pubky://$TEST_PUBKY/") },
            "left on the reader's homeserver: ${pubky.store.keys}",
        )
        // The deck already in memory stops carrying them too, not only the next fetch.
        val local = assertNotNull(repo.getLocal(deck.id))
        assertEquals(authored, local.study)
        assertNull(local.authorStudy)
        assertEquals(authored, repo.fetchRemote(AUTHOR, deck.id).getOrThrow().study)
    }

    @Test
    fun followingAgainStartsFromTheAuthorsOptIns() = runTest {
        val deck = followedDeck()
        repo.customizeStudy(deck.id, mine).getOrThrow()
        repo.unfollowDeck(AUTHOR, deck.id).getOrThrow()

        repo.followDeck(deck).getOrThrow()

        assertNull(subscription().study)
        assertEquals(authored, assertNotNull(repo.getLocal(deck.id)).study)
        assertEquals(authored, newProcess().listFollowed().single().study)
    }

    @Test
    fun choosingTheAuthorsOptInsBackClearsTheOverrides() = runTest {
        val deck = followedDeck()
        repo.customizeStudy(deck.id, mine).getOrThrow()

        val reset = repo.customizeStudy(deck.id, authored).getOrThrow()

        assertNull(reset.authorStudy)
        assertFalse("study" in pubky.store.getValue(SUBSCRIPTION_URL), "an empty override block was written")
    }

    @Test
    fun anOptInTheReaderNeverChangedKeepsFollowingTheAuthor() = runTest {
        val deck = followedDeck()
        repo.customizeStudy(deck.id, authored.copy(type = true)).getOrThrow()

        // The author turns Both directions on; the reader only ever touched Type.
        putManifest(deck.copy(reverseEnabled = true, updatedAt = 9_000L))
        val read = repo.fetchRemote(AUTHOR, deck.id).getOrThrow()

        assertEquals(authored.copy(type = true, reverse = true), read.study)
    }

    @Test
    fun markingTheDeckSeenKeepsTheOverrides() = runTest {
        val deck = followedDeck()
        repo.customizeStudy(deck.id, mine).getOrThrow()

        repo.markSeen(deck.copy(updatedAt = 9_000L))

        assertEquals(9_000L, subscription().last_seen_updated_at)
        assertEquals(StudyOverridesDto(listen_enabled = false, type_enabled = true), subscription().study)
    }

    @Test
    fun aDeckThatIsNotFollowedIsRefusedAndNothingIsWritten() = runTest {
        val deck = putRemoteDeck()
        repo.fetchRemote(AUTHOR, deck.id).getOrThrow()

        assertTrue(repo.customizeStudy(deck.id, mine).isFailure)

        assertTrue(pubky.puts.isEmpty(), "wrote ${pubky.puts.map { it.first }}")
    }

    @Test
    fun aSubscriptionWrittenBeforeOverridesExistedReadsAsTheAuthorsOptIns() = runTest {
        val deck = putRemoteDeck()
        pubky.store[SUBSCRIPTION_URL] =
            """{"schema_version":1,"deck_uri":"${deck.pubkyUri.value}","author_pubky":"$AUTHOR",""" +
            """"deck_id":"$DECK_ID","followed_at":1}"""

        val read = repo.listFollowed().single()

        assertEquals(authored, read.study)
        assertNull(read.authorStudy)
    }

    @Test
    fun aCopyCarriesTheReadersOptInsAndNoCover() = runTest {
        val deck = followedDeck(coverEmoji = "🦊", coverImageRef = testCoverImage())
        repo.customizeStudy(deck.id, mine).getOrThrow()

        val clone = repo.clone(assertNotNull(repo.getLocal(deck.id)), "My copy").getOrThrow()

        val stored = loopkyJson.decodeFromString<ManifestDto>(
            pubky.store.getValue("pubky://$TEST_PUBKY/pub/loopky/decks/${clone.id}/manifest.json"),
        )
        assertEquals(mine, StudyFeatures(stored.listen_enabled, stored.speak_enabled, stored.type_enabled, stored.reverse_enabled))
        assertNull(stored.cover_emoji)
        assertNull(stored.cover_image_ref)
        // They are the copy's own opt-ins now: there is no author left for them to differ from.
        assertNull(clone.authorStudy)
        assertNull(assertNotNull(repo.getLocal(clone.id)).authorStudy)
        // Cloning unfollows, which takes the overrides with the subscription.
        assertNull(pubky.store[SUBSCRIPTION_URL])
    }

    @Test
    fun signingInFromAPreviewOfADeckAlreadyFollowedKeepsTheOverrides() = runTest {
        val deck = followedDeck()
        repo.customizeStudy(deck.id, mine).getOrThrow()

        // Signed out, previewed as a guest, then signed back in to the account that follows it.
        val relaunched = newProcess()
        relaunched.rememberFollowForSignIn(AUTHOR, deck.id)
        relaunched.followPendingAfterSignIn().getOrThrow()

        assertEquals(StudyOverridesDto(listen_enabled = false, type_enabled = true), subscription().study)
        assertEquals(mine, assertNotNull(relaunched.getLocal(deck.id)).study)
    }

    private fun subscription(): SubscriptionDto =
        loopkyJson.decodeFromString(pubky.store.getValue(SUBSCRIPTION_URL))

    private suspend fun followedDeck(
        coverEmoji: String? = null,
        coverImageRef: MediaRef.Image? = null,
    ): Deck = putRemoteDeck(coverEmoji, coverImageRef).also {
        repo.followDeck(it).getOrThrow()
        pubky.puts.clear()
    }

    private fun putRemoteDeck(coverEmoji: String? = null, coverImageRef: MediaRef.Image? = null): Deck {
        val cards: List<Card> = listOf(testCard("c1", deckId = DECK_ID))
        val deck = testDeckWithCards(cards, id = DECK_ID, authorPubky = AUTHOR)
            .copy(coverEmoji = coverEmoji, coverImageRef = coverImageRef)
        putManifest(deck)
        cards.chunked(CHUNK_SIZE).forEachIndexed { n, batch ->
            pubky.store["pubky://$AUTHOR/pub/loopky/decks/$DECK_ID/cards/$n.json"] = loopkyJson.encodeToString(
                CardChunkDto(deck_id = DECK_ID, chunk = n, cards = batch.map { it.toDto() }),
            )
        }
        return deck
    }

    private fun putManifest(deck: Deck) {
        pubky.store[MANIFEST_URL] = loopkyJson.encodeToString(deck.toDto())
    }

    private companion object {
        const val AUTHOR = "friendpk"
        const val DECK_ID = "foreign"
        const val MANIFEST_URL = "pubky://$AUTHOR/pub/loopky/decks/$DECK_ID/manifest.json"
        const val SUBSCRIPTION_URL = "pubky://$TEST_PUBKY/pub/loopky/subscriptions/$AUTHOR/$DECK_ID.json"
    }
}
