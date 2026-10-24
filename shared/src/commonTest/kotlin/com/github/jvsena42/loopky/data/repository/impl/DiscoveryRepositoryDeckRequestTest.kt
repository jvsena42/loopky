package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.nexus.NexusClient
import com.github.jvsena42.loopky.data.pubky.PostDto
import com.github.jvsena42.loopky.data.pubky.PostKinds
import com.github.jvsena42.loopky.domain.model.DeckRequests
import com.github.jvsena42.loopky.domain.model.Tag
import com.github.jvsena42.loopky.testing.CountingRevalidator
import com.github.jvsena42.loopky.testing.FakeAppPreferences
import com.github.jvsena42.loopky.testing.FakeHttpFetcher
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.RecordingTagRepository
import com.github.jvsena42.loopky.testing.deckRepository
import com.github.jvsena42.loopky.testing.identityRepository
import com.github.jvsena42.loopky.testing.signedInProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoveryRepositoryDeckRequestTest {

    private val pubky = FakePubkyClient()
    private val session = signedInProvider()
    private val revalidator = CountingRevalidator()
    private val tagRepo = RecordingTagRepository()
    private val preferences = FakeAppPreferences()
    private val repo = DiscoveryRepositoryImpl(
        pubky = pubky,
        session = session,
        revalidator = revalidator,
        deckRepository = deckRepository(
            pubky = pubky,
            session = session,
            cardRepo = CardRepositoryImpl(pubky, session, revalidator, Dispatchers.Unconfined),
            revalidator = revalidator,
        ),
        tagRepository = tagRepo,
        identityRepository = identityRepository(pubky = pubky, sessionProvider = session, tagRepository = tagRepo),
        nexus = NexusClient(http = FakeHttpFetcher(), baseUrl = "https://nexus.test"),
        preferences = preferences,
    )

    @Test
    fun `a deck request is a short post with the content as written`() = runTest {
        val content = DeckRequests.compose("I'd love a Loopky deck about:", "Japanese hiragana")

        val uri = repo.postDeckRequest(content).getOrThrow()

        val (path, body) = pubky.puts.last()
        val post = loopkyJson.decodeFromString<PostDto>(body)
        assertEquals(uri.value, path)
        assertTrue(path.contains("/pub/pubky.app/posts/"), path)
        assertEquals(PostKinds.SHORT, post.kind)
        assertNull(post.embed)
        assertEquals(content, post.content)
    }

    @Test
    fun `a deck request is labelled loopky and deck-request, as ordinary tags`() = runTest {
        val uri = repo.postDeckRequest("Japanese hiragana").getOrThrow()

        assertEquals(listOf(uri to Tag("loopky"), uri to Tag("deck-request")), tagRepo.putTags)
        assertTrue(tagRepo.putReservedTags.isEmpty())
    }

    @Test
    fun `a request whose label fails is deleted again, so a retry is not a second request`() = runTest {
        tagRepo.failWith = IllegalStateException("tags are down")

        val result = repo.postDeckRequest("Japanese hiragana")

        assertTrue(result.isFailure)
        assertEquals(listOf(pubky.puts.last().first), pubky.deletes)
    }

    @Test
    fun `a deck request posts even with deck announcements turned off`() = runTest {
        preferences.setShareOnPubky(false)

        assertTrue(repo.postDeckRequest("Japanese hiragana").isSuccess)
    }

    @Test
    fun `a blank or oversized request is refused before anything is written`() = runTest {
        val before = pubky.puts.size

        assertTrue(repo.postDeckRequest("   ").isFailure)
        assertTrue(repo.postDeckRequest("x".repeat(PostKinds.MAX_CONTENT_LENGTH + 1)).isFailure)
        assertEquals(before, pubky.puts.size)
    }
}
