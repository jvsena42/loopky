package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.nexus.NexusClient
import com.github.jvsena42.loopky.data.pubky.MuteDto
import com.github.jvsena42.loopky.data.pubky.PubkyError
import com.github.jvsena42.loopky.data.pubky.PubkyPaths
import com.github.jvsena42.loopky.data.pubky.toDto
import com.github.jvsena42.loopky.data.repository.BlockChange
import com.github.jvsena42.loopky.data.repository.TaggedSubject
import com.github.jvsena42.loopky.domain.model.PubkyUri
import com.github.jvsena42.loopky.domain.model.ReservedTags
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscoveryRepositoryBlockTest {

    private val pubky = FakePubkyClient()
    private val session = signedInProvider()
    private val revalidator = CountingRevalidator()
    private val blockedAccounts = BlockedAccounts(pubky, session)
    private val deckRepo = DeckRepositoryImpl(
        pubky = pubky,
        session = session,
        cardRepo = CardRepositoryImpl(pubky, session, revalidator, Dispatchers.Unconfined),
        revalidator = revalidator,
        tagRepo = RecordingTagRepository(),
        mediaRepo = FakeMediaRepository(),
        backgroundTasks = FakeBackgroundTasks(),
        deckCache = FakeDeckCacheStore(),
        preferences = FakeAppPreferences(),
        scope = CoroutineScope(SupervisorJob()),
        blockedAccounts = blockedAccounts,
    )
    private val tagRepo = RecordingTagRepository()
    private val repo = DiscoveryRepositoryImpl(
        pubky = pubky,
        session = session,
        revalidator = revalidator,
        deckRepository = deckRepo,
        tagRepository = tagRepo,
        identityRepository = identityRepository(pubky = pubky, sessionProvider = session, tagRepository = tagRepo),
        nexus = NexusClient(http = FakeHttpFetcher(), baseUrl = "https://nexus.test"),
        preferences = FakeAppPreferences(),
        blockedAccounts = blockedAccounts,
    )

    private fun muteUrl(mutee: String) = "pubky://$TEST_PUBKY/pub/pubky.app/mutes/$mutee"

    private fun putRemoteManifest(author: String, deckId: String, updatedAt: Long = 100L) {
        val dto = testDeck(id = deckId, authorPubky = author, title = "Deck $deckId", updatedAt = updatedAt).toDto()
        pubky.store["pubky://$author/pub/loopky/decks/$deckId/manifest.json"] = loopkyJson.encodeToString(dto)
    }

    private fun manifestUri(author: String, deckId: String) =
        PubkyUri("pubky://$author/pub/loopky/decks/$deckId/manifest.json")

    @Test
    fun `a block is pubky app's mute record timestamped in microseconds`() = runTest {
        repo.blockUser("trollpk").getOrThrow()

        val body = pubky.store.getValue(muteUrl("trollpk"))
        // Microseconds, as pubky-app-specs writes it: a millisecond value is a date in 1970 to it.
        assertTrue(loopkyJson.decodeFromString<MuteDto>(body).created_at > 1_000_000_000_000_000L)
        assertTrue(repo.isBlocked("trollpk"))
    }

    @Test
    fun `unblock deletes the mute record`() = runTest {
        repo.blockUser("trollpk").getOrThrow()

        repo.unblockUser("trollpk").getOrThrow()

        assertFalse(muteUrl("trollpk") in pubky.store)
        assertFalse(repo.isBlocked("trollpk"))
    }

    @Test
    fun `a mute made on pubky app counts as a block here`() = runTest {
        pubky.store[muteUrl("mutedpk")] = """{"created_at":1}"""
        pubky.store[muteUrl("otherpk")] = """{"created_at":2}"""

        assertEquals(setOf("mutedpk", "otherpk"), repo.blocked())
    }

    @Test
    fun `a block after a cold start keeps the mutes already on the homeserver`() = runTest {
        pubky.store[muteUrl("mutedpk")] = """{"created_at":1}"""

        repo.blockUser("trollpk").getOrThrow()

        assertEquals(setOf("mutedpk", "trollpk"), repo.blocked())
    }

    @Test
    fun `you cannot block yourself`() = runTest {
        assertTrue(repo.blockUser(TEST_PUBKY).isFailure)
        assertFalse(muteUrl(TEST_PUBKY) in pubky.store)
    }

    @Test
    fun `blocking needs an account`() = runTest {
        session.set(null)

        assertTrue(repo.blockUser("trollpk").isFailure)
        assertEquals(emptySet(), repo.blocked())
    }

    @Test
    fun `blocking unfollows the person and every deck of theirs you follow`() = runTest {
        putRemoteManifest(author = "trollpk", deckId = "theirs")
        putRemoteManifest(author = "friendpk", deckId = "kept")
        repo.followUser("trollpk").getOrThrow()
        deckRepo.followDeck(deckRepo.fetchRemote("trollpk", "theirs").getOrThrow()).getOrThrow()
        deckRepo.followDeck(deckRepo.fetchRemote("friendpk", "kept").getOrThrow()).getOrThrow()

        repo.blockUser("trollpk").getOrThrow()

        assertFalse(repo.isFollowing("trollpk"))
        assertEquals(listOf("kept"), deckRepo.listFollowed().map { it.id })
    }

    @Test
    fun `a deck you follow by an account muted on pubky app is hidden but stays followed`() = runTest {
        putRemoteManifest(author = "mutedpk", deckId = "theirs")
        putRemoteManifest(author = "friendpk", deckId = "kept")
        deckRepo.followDeck(deckRepo.fetchRemote("mutedpk", "theirs").getOrThrow()).getOrThrow()
        deckRepo.followDeck(deckRepo.fetchRemote("friendpk", "kept").getOrThrow()).getOrThrow()
        pubky.store[muteUrl("mutedpk")] = """{"created_at":1}"""

        assertEquals(listOf("kept"), deckRepo.listFollowed().map { it.id })
        // Hidden, not unfollowed: only a block made here unfollows anything.
        assertTrue(deckRepo.isFollowingDeck("theirs"))
    }

    @Test
    fun `the cached library leaves out a blocked author once the block is known`() = runTest {
        putRemoteManifest(author = "trollpk", deckId = "theirs")
        deckRepo.followDeck(deckRepo.fetchRemote("trollpk", "theirs").getOrThrow()).getOrThrow()
        deckRepo.listFollowed()
        assertEquals(listOf("theirs"), deckRepo.listCached()?.followed?.map { it.id })

        // The list is a session cache, like follows: a block made here lands through `add`.
        blockedAccounts.add("trollpk")

        assertEquals(emptyList(), deckRepo.listCached()?.followed?.map { it.id })
    }

    @Test
    fun `block and unblock are announced`() = runTest {
        val changes = async { repo.blockChanges.first() }
        runCurrent()

        repo.blockUser("trollpk").getOrThrow()

        assertEquals(BlockChange("trollpk", isBlocked = true), changes.await())
    }

    @Test
    fun `global browse leaves out a blocked author and fills the page from the rest`() = runTest {
        putRemoteManifest(author = "trollpk", deckId = "bad")
        putRemoteManifest(author = "friendpk", deckId = "good")
        tagRepo.subjectsByTag = mapOf(
            ReservedTags.DECK to listOf(
                TaggedSubject(manifestUri("trollpk", "bad"), listOf("trollpk"), 1),
                TaggedSubject(manifestUri("friendpk", "good"), listOf("friendpk"), 1),
            ),
        )
        repo.blockUser("trollpk").getOrThrow()

        assertEquals(listOf("good"), repo.decksByTagGlobal(ReservedTags.DECK).map { it.id })
    }

    @Test
    fun `a blocked person is left out of follow lists and the directory`() = runTest {
        repo.followUser("friendpk").getOrThrow()
        repo.followUser("trollpk").getOrThrow()
        tagRepo.selfTaggers = setOf("friendpk", "trollpk")
        tagRepo.usersByTag = mapOf(ReservedTags.USER to listOf("friendpk", "trollpk"))
        pubky.store[muteUrl("trollpk")] = """{"created_at":1}"""

        assertEquals(listOf("friendpk"), repo.followingProfiles(TEST_PUBKY).map { it.pubky })
        assertEquals(listOf("friendpk"), repo.loopkyUsers().map { it.pubky })
    }

    @Test
    fun `an unreadable mute list hides nobody rather than failing browse`() = runTest {
        putRemoteManifest(author = "friendpk", deckId = "good")
        tagRepo.subjectsByTag = mapOf(
            ReservedTags.DECK to listOf(TaggedSubject(manifestUri("friendpk", "good"), listOf("friendpk"), 1)),
        )
        pubky.failListWhenUrlContains = "/pub/pubky.app/mutes/"

        assertEquals(listOf("good"), repo.decksByTagGlobal(ReservedTags.DECK).map { it.id })
    }

    @Test
    fun `blocked throws when the homeserver is unreachable`() = runTest {
        pubky.failListWith = PubkyError("HTTP transport error: error sending request for url (...)")

        assertFailsWith<PubkyError> { repo.blocked() }
    }

    @Test
    fun `blocked profiles resolve names and keep an account with no profile`() = runTest {
        pubky.store[PubkyPaths.profile("namedpk")] = """{"name":"Ada","bio":null,"image":null}"""
        pubky.store[muteUrl("namedpk")] = """{"created_at":1}"""
        pubky.store[muteUrl("barepk")] = """{"created_at":1}"""

        val people = repo.blockedProfiles()

        assertEquals(listOf("barepk", "namedpk"), people.map { it.pubky })
        assertEquals("Ada", people.single { it.pubky == "namedpk" }.displayName)
    }
}
