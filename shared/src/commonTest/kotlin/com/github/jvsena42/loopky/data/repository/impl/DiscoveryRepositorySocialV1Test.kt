package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import com.github.jvsena42.loopky.data.nexus.NexusClient
import com.github.jvsena42.loopky.data.pubky.FollowDto
import com.github.jvsena42.loopky.data.pubky.SocialLayout
import com.github.jvsena42.loopky.data.pubky.SocialPostDto
import com.github.jvsena42.loopky.data.pubky.SocialPostKinds
import com.github.jvsena42.loopky.data.report.ReportClient
import com.github.jvsena42.loopky.domain.model.DeckAnnouncement
import com.github.jvsena42.loopky.testing.CountingRevalidator
import com.github.jvsena42.loopky.testing.FakeAppPreferences
import com.github.jvsena42.loopky.testing.FakeHttpFetcher
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.RecordingTagRepository
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.deckRepository
import com.github.jvsena42.loopky.testing.identityRepository
import com.github.jvsena42.loopky.testing.signedInProvider
import com.github.jvsena42.loopky.testing.testDeck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A user pubky.app has migrated: their `/pub/social/v1/profile.json` exists, so Nexus ignores their
 * pubky.app writes and every social record has to go to social/v1 (#616).
 */
class DiscoveryRepositorySocialV1Test {

    private val pubky = FakePubkyClient()
    private val session = signedInProvider()
    private val revalidator = CountingRevalidator()
    private val tagRepo = RecordingTagRepository()

    private fun repo() = DiscoveryRepositoryImpl(
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
        preferences = FakeAppPreferences(),
        reportClient = ReportClient(FakeHttpFetcher(), "https://pubky.test"),
        environment = PubkyEnvironment.Staging,
    )

    private val repo = repo()

    @BeforeTest
    fun migrate() {
        pubky.store[SocialLayout.SocialV1.profile(TEST_PUBKY)] = """{"name":"Tester"}"""
    }

    @Test
    fun `a follow is written and listed under social v1`() = runTest {
        repo.followUser("friendpk").getOrThrow()

        val path = "pubky://$TEST_PUBKY/pub/social/v1/follows/friendpk.json"
        val createdAt = loopkyJson.decodeFromString<FollowDto>(pubky.store.getValue(path)).created_at
        assertTrue(createdAt > 1_000_000_000_000_000L, "microseconds, was $createdAt")
        assertTrue(pubky.store.keys.none { "/pub/pubky.app/follows/" in it })
        // A second instance has a cold cache, so this list comes off the homeserver.
        assertEquals(listOf("friendpk"), repo().following())
    }

    @Test
    fun `an unfollow deletes the social v1 record`() = runTest {
        repo.followUser("friendpk").getOrThrow()

        repo.unfollowUser("friendpk").getOrThrow()

        assertEquals(listOf("pubky://$TEST_PUBKY/pub/social/v1/follows/friendpk.json"), pubky.deletes)
    }

    @Test
    fun `an announcement is a v1 link post embedding the manifest`() = runTest {
        val deck = testDeck(id = "d1", title = "Kanji N5")

        val uri = repo.announceDeck(DeckAnnouncement.of(deck, DeckAnnouncement.Kind.Created)).getOrThrow()

        // Tags and embeds name the versionless reference; the body sits in the version's file.
        val postId = uri.value.substringAfterLast('/')
        assertEquals("pubky://$TEST_PUBKY/pub/social/v1/posts/$postId", uri.value)
        val post = loopkyJson.decodeFromString<SocialPostDto>(pubky.store.getValue("${uri.value}/$postId.json"))
        assertEquals(SocialPostKinds.LINK, post.kind)
        assertEquals(deck.pubkyUri.value, post.embed)
        assertTrue(post.content.contains("Kanji N5"), post.content)
        assertEquals(uri, tagRepo.putReservedTags.first().first)
    }

    @Test
    fun `a block is a private mute under priv social v1, read back with the session`() = runTest {
        repo.blockUser("spammerpk").getOrThrow()

        val path = "pubky://$TEST_PUBKY/priv/social/v1/mutes/spammerpk.json"
        assertTrue(path in pubky.store)
        assertTrue(pubky.store.keys.none { "/pub/pubky.app/mutes/" in it })

        // A cold instance reads the list off the homeserver, through the session.
        val cold = BlockedAccounts(pubky, session)
        assertEquals(setOf("spammerpk"), cold.load())
        assertTrue(pubky.sessionReads.any { it.startsWith("pubky://$TEST_PUBKY/priv/social/v1/mutes/") })

        repo.unblockUser("spammerpk").getOrThrow()
        assertTrue(path !in pubky.store)
    }

    @Test
    fun `a deck request is a note`() = runTest {
        val uri = repo.postDeckRequest("Anyone have a Kanji deck?").getOrThrow()

        val postId = uri.value.substringAfterLast('/')
        val post = loopkyJson.decodeFromString<SocialPostDto>(pubky.store.getValue("${uri.value}/$postId.json"))
        assertEquals(SocialPostKinds.NOTE, post.kind)
        assertNull(post.embed)
    }
}
