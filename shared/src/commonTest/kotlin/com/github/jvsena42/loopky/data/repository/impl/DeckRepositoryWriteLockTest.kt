package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.CHUNK_SIZE
import com.github.jvsena42.loopky.domain.model.MediaRef
import com.github.jvsena42.loopky.testing.CountingRevalidator
import com.github.jvsena42.loopky.testing.FakeBackgroundTasks
import com.github.jvsena42.loopky.testing.FakeDeckCacheStore
import com.github.jvsena42.loopky.testing.FakeMediaRepository
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.RecordingTagRepository
import com.github.jvsena42.loopky.testing.signedInProvider
import com.github.jvsena42.loopky.testing.testCard
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A deck's manifest carries its whole chunk table, so every write to it is a read-modify-write of
 * the entire record. Two writers that both patch a snapshot taken before the other ran will have
 * the second silently erase the first's chunk entry — and a chunk missing from the manifest is
 * unreachable, so the cards in it leave the deck without anything reporting an error.
 *
 * Only a human tapping Save writes today, which is why this never fired. Opportunistic media
 * re-hosting (#65) adds a second, background writer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeckRepositoryWriteLockTest {

    private val pubky = FakePubkyClient()
    private val session = signedInProvider()
    private val revalidator = CountingRevalidator()
    private val cardRepo = CardRepositoryImpl(pubky, session, revalidator, Dispatchers.Unconfined)
    private val repo = DeckRepositoryImpl(
        pubky = pubky,
        session = session,
        cardRepo = cardRepo,
        revalidator = revalidator,
        tagRepo = RecordingTagRepository(),
        mediaRepo = FakeMediaRepository(),
        backgroundTasks = FakeBackgroundTasks(),
        deckCache = FakeDeckCacheStore(),
    )

    @Test
    fun concurrentChunkWritesBothSurviveInTheManifest() = runTest {
        // A full chunk 0, so appending a card has to open chunk 1.
        val cards = (1..CHUNK_SIZE).map { testCard("c$it") }
        repo.publish(testDeck(id = "deck1"), cards).getOrThrow()

        // Park both writers inside their chunk write so they are genuinely in flight together.
        // runTest is single-threaded: without a real suspension point they would simply run
        // one after the other and the race could not happen.
        val gate = CompletableDeferred<Unit>()
        pubky.putGate = gate

        val appending = launch { repo.upsertCard("deck1", testCard("new")).getOrThrow() }
        val editing = launch {
            repo.upsertCard("deck1", testCard("c1", front = "edited")).getOrThrow()
        }
        advanceUntilIdle()
        pubky.putGate = null
        gate.complete(Unit)
        appending.join()
        editing.join()

        // The appending writer opened chunk 1. The editing writer patched the manifest from the
        // deck it had captured *before* that — which listed only chunk 0 — so without the write
        // lock its manifest write dropped chunk 1 and the card in it silently left the deck.
        val deck = repo.getLocal("deck1")!!
        assertEquals(listOf(0, 1), deck.chunks.map { it.n })
        assertEquals(CHUNK_SIZE + 1, deck.cardCount)
        assertEquals(CHUNK_SIZE + 1, cardRepo.fetchByDeck(deck).getOrThrow().size)
    }

    @Test
    fun aChunkWriteRacingAMetadataUpdateDoesNotDropTheChunkTable() = runTest {
        val cards = (1..CHUNK_SIZE).map { testCard("c$it") }
        val published = repo.publish(testDeck(id = "deck1"), cards).getOrThrow()

        // A rename started before the card was added still holds the old chunk table, the way a
        // deck editor opened earlier does.
        repo.upsertCard("deck1", testCard("new")).getOrThrow()
        repo.updateMetadata(published.copy(title = "Renamed")).getOrThrow()

        val deck = repo.getLocal("deck1")!!
        assertEquals("Renamed", deck.title)
        assertEquals(listOf(0, 1), deck.chunks.map { it.n })
        assertEquals(CHUNK_SIZE + 1, deck.cardCount)
    }

    @Test
    fun upsertCardKeepsAnImageRefOnTheStoredCard() = runTest {
        // Driving the real app, a card image added in the editor came back missing. This pins the
        // repository half of that path: whatever else is wrong, `upsertCard` must not be the thing
        // dropping the ref.
        repo.publish(testDeck(id = "deck1"), listOf(testCard("c1"))).getOrThrow()
        val image = MediaRef.Image(
            path = "media/abc.jpg",
            mime = "image/jpeg",
            sha256 = "abc",
            width = null,
            height = null,
        )
        val withImage = testCard("c1").let { it.copy(back = it.back.copy(imageRef = image)) }

        repo.upsertCard("deck1", withImage).getOrThrow()

        val stored = CardRepositoryImpl(pubky, session, revalidator, Dispatchers.Unconfined)
            .fetchByDeck(repo.getLocal("deck1")!!).getOrThrow().single()
        assertEquals(image, stored.back.imageRef)
    }
}
