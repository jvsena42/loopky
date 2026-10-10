package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.nexus.NexusClient
import com.github.jvsena42.loopky.data.pubky.toDto
import com.github.jvsena42.loopky.data.repository.TaggedSubject
import com.github.jvsena42.loopky.domain.model.PubkyUri
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.testing.CountingRevalidator
import com.github.jvsena42.loopky.testing.FakeAppPreferences
import com.github.jvsena42.loopky.testing.FakeHttpFetcher
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.RecordingTagRepository
import com.github.jvsena42.loopky.testing.deckRepository
import com.github.jvsena42.loopky.testing.identityRepository
import com.github.jvsena42.loopky.testing.signedInProvider
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Deck search over tags once labels are folded (#479): the query is folded the way a write is, and
 * a deck tagged before the fold has to stay reachable from either spelling.
 */
class DiscoveryRepositoryTagSearchTest {

    private val pubky = FakePubkyClient()
    private val session = signedInProvider()
    private val revalidator = CountingRevalidator()
    private val tagRepo = RecordingTagRepository()
    private val repo = DiscoveryRepositoryImpl(
        pubky = pubky,
        session = session,
        revalidator = revalidator,
        deckRepository = deckRepository(
            pubky,
            session,
            CardRepositoryImpl(pubky, session, revalidator, Dispatchers.Unconfined),
            revalidator,
            RecordingTagRepository(),
        ),
        tagRepository = tagRepo,
        identityRepository = identityRepository(pubky = pubky, sessionProvider = session, tagRepository = tagRepo),
        nexus = NexusClient(http = FakeHttpFetcher(), baseUrl = "https://nexus.test"),
        preferences = FakeAppPreferences(),
    )

    @Test
    fun searchDecksAsksTheTagIndexForAPhraseUnderItsFoldedLabel() = runTest {
        putRemoteManifest("strangerpk", "deck1", updatedAt = 100L, title = "Spanish verbs")
        sampleOf("strangerpk" to "deck1")

        assertEquals(listOf("deck1"), repo.searchDecks("spanish verbs").map { it.id })
        // The sample read may take several windows to fill a page, so it is the set of *labels*
        // asked about that matters: a multi-word topic is stored as `spanish-verbs` (#479), and
        // the phrase with its space can never be a label.
        assertEquals(
            setOf(ReservedTags.DECK, Tag("spanish-verbs")),
            tagRepo.taggedRequests.map { it.first }.toSet(),
        )
    }

    @Test
    fun searchDecksFindsAFoldedTagFromItsAccentedSpelling() = runTest {
        putRemoteManifest("strangerpk", "folded", updatedAt = 100L, title = "Enzimas", tags = listOf(Tag("bioquimica")))
        tagRepo.subjectsByTag = mapOf(
            Tag("bioquimica") to listOf(tagged(manifestUri("strangerpk", "folded"), listOf("strangerpk"))),
        )

        assertEquals(listOf("folded"), repo.searchDecks("Bioquímica").map { it.id })
    }

    @Test
    fun searchDecksStillReachesADeckTaggedBeforeTheFold() = runTest {
        // The indexer matches byte for byte, so the query as typed is asked about too.
        putRemoteManifest("strangerpk", "legacy", updatedAt = 100L, title = "Enzimas", tags = listOf(Tag("bioquímica")))
        tagRepo.subjectsByTag = mapOf(
            Tag("bioquímica") to listOf(tagged(manifestUri("strangerpk", "legacy"), listOf("strangerpk"))),
        )

        assertEquals(listOf("legacy"), repo.searchDecks("bioquímica").map { it.id })
    }

    @Test
    fun searchDecksMatchesASampledDeckTaggedBeforeTheFoldFromThePlainSpelling() = runTest {
        // No index read can get from `bioquimica` to a record labelled `bioquímica`; the sample can,
        // because there both sides are folded before they are compared.
        putRemoteManifest("strangerpk", "legacy", updatedAt = 100L, title = "Enzimas", tags = listOf(Tag("bioquímica")))
        sampleOf("strangerpk" to "legacy")

        assertEquals(listOf("legacy"), repo.searchDecks("bioquimica").map { it.id })
    }

    private fun putRemoteManifest(
        author: String,
        deckId: String,
        updatedAt: Long,
        title: String,
        tags: List<Tag> = emptyList(),
    ) {
        val dto = testDeck(id = deckId, authorPubky = author, title = title, tags = tags, updatedAt = updatedAt).toDto()
        pubky.store[manifestUri(author, deckId).value] = loopkyJson.encodeToString(dto)
    }

    private fun sampleOf(vararg decks: Pair<String, String>) {
        tagRepo.subjectsByTag = mapOf(
            ReservedTags.DECK to decks.map { (author, deckId) -> tagged(manifestUri(author, deckId), listOf(author)) },
        )
    }

    private fun manifestUri(author: String, deckId: String) =
        PubkyUri("pubky://$author/pub/loopky/decks/$deckId/manifest.json")

    private fun tagged(uri: PubkyUri, taggers: List<String>) =
        TaggedSubject(uri = uri, taggers = taggers, taggersCount = taggers.size)
}
