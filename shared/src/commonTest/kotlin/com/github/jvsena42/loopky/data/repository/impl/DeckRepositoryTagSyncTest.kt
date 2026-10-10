package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.nexus.NexusClient
import com.github.jvsena42.loopky.data.pubky.PubkyPaths
import com.github.jvsena42.loopky.data.pubky.toDto
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.testing.CountingRevalidator
import com.github.jvsena42.loopky.testing.FakeHttpFetcher
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.RecordingTagRepository
import com.github.jvsena42.loopky.testing.deckRepository
import com.github.jvsena42.loopky.testing.signedInProvider
import com.github.jvsena42.loopky.testing.testCard
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tag records are separate records from the deck manifest, so a manifest write on its own changes
 * nothing an indexer can see. Every path that can change a deck's tag list therefore has to
 * reconcile the records too — including the metadata-only save a tag edit actually takes (#47),
 * which wrote the manifest and stopped.
 */
class DeckRepositoryTagSyncTest {

    private val pubky = FakePubkyClient()
    private val session = signedInProvider()
    private val revalidator = CountingRevalidator()
    private val tagRepo = RecordingTagRepository()
    private val cardRepo = CardRepositoryImpl(pubky, session, revalidator, Dispatchers.Unconfined)
    private val repo = deckRepository(pubky, session, cardRepo, revalidator, tagRepo)
    private val realRepo = deckRepository(
        pubky,
        session,
        cardRepo,
        revalidator,
        TagRepositoryImpl(pubky, session, revalidator, NexusClient(FakeHttpFetcher(), "https://nexus.test")),
    )

    @Test
    fun updateMetadataMirrorsANewlyAddedTag() = runTest {
        // A tag-only edit takes the metadata-only path, so this is where the tag record has to be
        // written — the manifest alone is not what Nexus indexes (#47).
        val deck = repo.publish(testDeck(id = "deck1"), listOf(testCard("c1"))).getOrThrow()
        tagRepo.putTags.clear()

        repo.updateMetadata(deck.copy(tags = listOf(Tag("spanish")))).getOrThrow()

        assertEquals(listOf(deck.pubkyUri to Tag("spanish")), tagRepo.putTags)
    }

    @Test
    fun updateMetadataRemovesTheRecordOfADroppedTag() = runTest {
        val deck = testDeck(id = "deck1", tags = listOf(Tag("spanish"), Tag("language")))
        repo.publish(deck, listOf(testCard("c1"))).getOrThrow()

        repo.updateMetadata(deck.copy(tags = listOf(Tag("spanish")))).getOrThrow()

        // Left behind, the dropped label keeps the deck listed under a topic it no longer carries.
        assertEquals(listOf(deck.pubkyUri to Tag("language")), tagRepo.removedTags)
        assertEquals(listOf(Tag("spanish")), repo.getLocal("deck1")?.tags)
    }

    @Test
    fun publishStoresAndIndexesTagsFolded() = runTest {
        // The repository is the one place no caller can skip, whatever it did to its input (#479).
        val deck = testDeck(id = "deck1", tags = listOf(Tag("Bioquímica"), Tag("bioquimica"), Tag("first year")))

        val published = repo.publish(deck, listOf(testCard("c1"))).getOrThrow()

        assertEquals(listOf(Tag("bioquimica"), Tag("first-year")), published.tags)
        assertEquals(published.tags, tagRepo.putTags.map { it.second })
    }

    @Test
    fun updateMetadataFoldsADeckTaggedBeforeLabelsWereFolded() = runTest {
        val deck = repo.publish(testDeck(id = "deck1"), listOf(testCard("c1"))).getOrThrow()
        seedLegacyTags(deck.id, listOf(Tag("café")))
        tagRepo.putTags.clear()

        val updated = repo.updateMetadata(deck.copy(title = "Renamed", tags = listOf(Tag("café")))).getOrThrow()

        // The accented record goes and the folded one arrives, or the deck sits on both shelves.
        assertEquals(listOf(Tag("cafe")), updated.tags)
        assertEquals(listOf(deck.pubkyUri to Tag("cafe")), tagRepo.putTags)
        assertEquals(listOf(deck.pubkyUri to Tag("café")), tagRepo.removedTags)
    }

    /**
     * Over the real tag repository, because the recording fake cannot see it: a record is keyed
     * by its lowercased label, so `Geography` and `geography` are one record under two names.
     * An older CLI stored `--tag` with its case and wrote the record lowercased.
     */
    @Test
    fun aLabelThatDiffersOnlyByCaseKeepsItsRecordThroughTheRepair() = runTest {
        val deck = realRepo.publish(testDeck(id = "deck1", tags = listOf(Tag("geography"))), listOf(testCard("c1")))
            .getOrThrow()
        pubky.store[PubkyPaths.manifest(deck.authorPubky, deck.id)] =
            loopkyJson.encodeToString(deck.copy(tags = listOf(Tag("Geography"))).toDto())
        val legacy = realRepo.sync(deck.id).getOrThrow()
        val record = PubkyPaths.loopkyTag(deck.authorPubky, pubky.createTagId(deck.pubkyUri.value, "geography").getOrThrow())

        val updated = realRepo.updateMetadata(legacy.copy(title = "Renamed")).getOrThrow()

        assertEquals(listOf(Tag("geography")), updated.tags)
        assertTrue(record in pubky.store, "the repair deleted the record it had just written")
    }

    @Test
    fun republishingADeckWhoseLabelDiffersOnlyByCaseKeepsItsRecord() = runTest {
        val deck = realRepo.publish(testDeck(id = "deck1", tags = listOf(Tag("geography"))), listOf(testCard("c1")))
            .getOrThrow()
        pubky.store[PubkyPaths.manifest(deck.authorPubky, deck.id)] =
            loopkyJson.encodeToString(deck.copy(tags = listOf(Tag(" Geography"))).toDto())
        val legacy = realRepo.sync(deck.id).getOrThrow()
        val record = PubkyPaths.loopkyTag(deck.authorPubky, pubky.createTagId(deck.pubkyUri.value, "geography").getOrThrow())

        realRepo.publish(legacy, listOf(testCard("c1"))).getOrThrow()

        assertTrue(record in pubky.store, "the republish deleted the record it had just written")
    }

    @Test
    fun updateMetadataKeepsTheDeckMarkedForGlobalBrowse() = runTest {
        val deck = repo.publish(testDeck(id = "deck1"), listOf(testCard("c1"))).getOrThrow()

        repo.updateMetadata(deck.copy(title = "Renamed")).getOrThrow()

        // Idempotent, and it re-asserts the marker for a deck published before #40 added it.
        assertEquals(emptyList(), tagRepo.removedReservedTags)
        assertEquals(
            listOf(deck.pubkyUri to ReservedTags.DECK, deck.pubkyUri to ReservedTags.DECK),
            tagRepo.putReservedTags,
        )
    }

    @Test
    fun updateMetadataSurvivesAFailedTagWrite() = runTest {
        val deck = repo.publish(testDeck(id = "deck1"), listOf(testCard("c1"))).getOrThrow()
        tagRepo.failWith = IllegalStateException("homeserver refused the tag")

        // Same rule as publish: discoverability never fails the save the user asked for.
        assertTrue(repo.updateMetadata(deck.copy(tags = listOf(Tag("spanish")))).isSuccess)
    }

    @Test
    fun theLabelADeclaredLanguageContributesGoesThroughTheOrdinaryTagPath() = runTest {
        // The language labels are user tags now, not a reserved family, so the deck picks them up
        // from `tags` like any other topic — nothing here derives them from the pair.
        val deck = testDeck(
            id = "deck1",
            tags = listOf(Tag("language"), Tag("english"), Tag("spanish")),
            frontLang = "en-US",
            backLang = "es-ES",
        )

        repo.publish(deck, listOf(testCard("c1"))).getOrThrow()

        assertEquals(
            listOf(
                deck.pubkyUri to Tag("language"),
                deck.pubkyUri to Tag("english"),
                deck.pubkyUri to Tag("spanish"),
            ),
            tagRepo.putTags,
        )
        assertEquals(emptyList(), tagRepo.putReservedTags.filter { it.second != ReservedTags.DECK })
    }

    @Test
    fun republishingDropsTheTagRecordsThatFellAway() = runTest {
        val deck = testDeck(id = "deck1", tags = listOf(Tag("spanish"), Tag("language")))
        repo.publish(deck, listOf(testCard("c1"))).getOrThrow()

        // A card edit alongside a tag edit goes down the publish path instead — same staleness.
        repo.publish(deck.copy(tags = listOf(Tag("spanish"))), listOf(testCard("c2"))).getOrThrow()

        assertEquals(listOf(deck.pubkyUri to Tag("language")), tagRepo.removedTags)
    }

    /** Puts [tags] in the manifest the way a client older than #479 wrote them, and reloads it. */
    private suspend fun seedLegacyTags(deckId: String, tags: List<Tag>) {
        val deck = requireNotNull(repo.getLocal(deckId))
        pubky.store[PubkyPaths.manifest(deck.authorPubky, deckId)] =
            loopkyJson.encodeToString(deck.copy(tags = tags).toDto())
        repo.sync(deckId).getOrThrow()
    }
}
