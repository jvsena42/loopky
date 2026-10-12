package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.nexus.NexusClient
import com.github.jvsena42.loopky.data.pubky.PostDto
import com.github.jvsena42.loopky.data.pubky.PostKinds
import com.github.jvsena42.loopky.domain.model.ReservedTags
import com.github.jvsena42.loopky.testing.CountingRevalidator
import com.github.jvsena42.loopky.testing.FakeAppPreferences
import com.github.jvsena42.loopky.testing.FakeHttpFetcher
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.RecordingTagRepository
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.deckRepository
import com.github.jvsena42.loopky.testing.identityRepository
import com.github.jvsena42.loopky.testing.signedInProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoveryRepositoryRecapTest {

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
    fun `a recap post is a link post to its page - with nothing attached`() = runTest {
        val url = "https://loopky.app/recap/?pubky=$TEST_PUBKY&year=2026"

        val uri = repo.postRecap("My 2026 on Loopky\n\n$url", url).getOrThrow()

        val (path, body) = pubky.puts.last()
        val post = loopkyJson.decodeFromString<PostDto>(body)
        assertEquals(uri.value, path)
        assertTrue(path.contains("/pub/pubky.app/posts/"), path)
        assertEquals(PostKinds.LINK, post.kind)
        assertEquals(PostKinds.LINK, post.embed?.kind)
        assertEquals(url, post.embed?.uri)
        assertNull(post.attachments)
        assertTrue(post.content.endsWith(url), post.content)
    }

    @Test
    fun `a recap post carries the reserved recap label`() = runTest {
        val uri = repo.postRecap("My year", "https://loopky.app/recap/").getOrThrow()

        assertEquals(listOf(uri to ReservedTags.RECAP), tagRepo.putReservedTags)
        assertEquals("loopky-recap", ReservedTags.RECAP.value)
        assertTrue(ReservedTags.RECAP in ReservedTags.ALL)
    }

    @Test
    fun `a recap post survives its label failing to write`() = runTest {
        tagRepo.failWith = IllegalStateException("tags are down")

        val result = repo.postRecap("My year", "https://loopky.app/recap/")

        assertTrue(result.isSuccess)
    }

    @Test
    fun `a recap posts even with deck announcements turned off`() = runTest {
        preferences.setShareOnPubky(false)

        val result = repo.postRecap("My year", "https://loopky.app/recap/")

        assertTrue(result.isSuccess)
    }

    @Test
    fun `a recap post over the content limit is refused before anything is written`() = runTest {
        val before = pubky.puts.size

        val result = repo.postRecap("x".repeat(PostKinds.MAX_CONTENT_LENGTH + 1), "https://loopky.app/recap/")

        assertTrue(result.isFailure)
        assertEquals(before, pubky.puts.size)
    }
}
