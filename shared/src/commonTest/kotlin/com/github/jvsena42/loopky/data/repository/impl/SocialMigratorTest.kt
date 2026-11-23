package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.ProfileDto
import com.github.jvsena42.loopky.data.pubky.SocialEpoch
import com.github.jvsena42.loopky.data.pubky.SocialEpochs
import com.github.jvsena42.loopky.data.pubky.SocialLayout
import com.github.jvsena42.loopky.domain.model.Capability
import com.github.jvsena42.loopky.domain.model.PubkyAppFootprint
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.fakeSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SocialMigratorTest {

    private val pubky = FakePubkyClient()
    private val epochs = SocialEpochs(pubky)
    private var nexusReady = true
    private val migrator = SocialMigrator(
        pubky = pubky,
        footprint = PubkyAppFootprintReader(pubky),
        epochs = epochs,
        servesSocialV1 = { nexusReady },
    )
    private val owner = TEST_PUBKY
    private val session = fakeSession(owner)
    private val v0 = SocialLayout.Legacy
    private val v1 = SocialLayout.SocialV1
    private val deck = "pubky://$owner/pub/loopky/decks/deck1/manifest.json"

    private fun legacyTag(uri: String, label: String): String {
        val path = v0.tag(owner, pubky.createTagId(uri, label).getOrThrow())
        pubky.store[path] = """{"uri":"$uri","label":"$label","created_at":7}"""
        return path
    }

    /** A Loopky-only account: a profile, a follow, a block, an announcement, a deck request and their tags. */
    private fun loopkyAccount() {
        pubky.store[v0.profile(owner)] = """{"name":"Ana","bio":"hi"}"""
        pubky.store[v0.follow(owner, "friendpk")] = """{"created_at":5}"""
        pubky.store[v0.mute(owner, "trollpk")] = """{"created_at":6}"""
        pubky.store[v0.post(owner, "0034A0X7NJ52G")] =
            """{"content":"new deck","kind":"link","embed":{"kind":"link","uri":"$deck"}}"""
        pubky.store[v0.post(owner, "0034A0X7NJ52H")] = """{"content":"a deck about tides","kind":"short"}"""
        legacyTag(v0.profile(owner), "loopky-user")
        legacyTag(v0.post(owner, "0034A0X7NJ52H"), "loopky")
        legacyTag(v0.post(owner, "0034A0X7NJ52H"), "deck-request")
        legacyTag(deck, "loopky-deck")
    }

    @Test
    fun aLoopkyOnlyAccountIsCopiedToSocialV1WithTheProfileLast() = runTest {
        loopkyAccount()

        val outcome = migrator.migrate(session)

        assertEquals(MigrationOutcome.Migrated(records = 8), outcome)
        assertEquals(v1.profile(owner), pubky.puts.last().first, "the profile is the flip, so it goes last")
        assertEquals("""{"name":"Ana","bio":"hi"}""", pubky.store[v1.profile(owner)])
        assertEquals("""{"created_at":5}""", pubky.store[v1.follow(owner, "friendpk")])
        assertEquals("""{"created_at":6}""", pubky.store["pubky://$owner/priv/social/v1/mutes/trollpk.json"])
        assertEquals(
            """{"content":"new deck","kind":"link","embed":"$deck","attachments":[]}""",
            pubky.store[v1.postVersion(owner, "0034A0X7NJ52G")],
        )
        assertEquals(
            """{"content":"a deck about tides","kind":"note","attachments":[]}""",
            pubky.store[v1.postVersion(owner, "0034A0X7NJ52H")],
        )
        val request = v1.post(owner, "0034A0X7NJ52H")
        val selfTag = v1.tag(owner, pubky.createTagId(v1.profile(owner), "loopky-user").getOrThrow())
        assertEquals("""{"uri":"${v1.profile(owner)}","label":"loopky-user","created_at":7}""", pubky.store[selfTag])
        assertTrue(v1.tag(owner, pubky.createTagId(request, "deck-request").getOrThrow()) in pubky.store)
        assertTrue(v1.tag(owner, pubky.createTagId(deck, "loopky-deck").getOrThrow()) in pubky.store)
        assertTrue(pubky.store.keys.containsAll(listOf(v0.profile(owner), v0.follow(owner, "friendpk"))), "v0 stays")
        assertEquals(SocialEpoch.V1, epochs.of(owner))
    }

    @Test
    fun nothingMovesBeforeNexusReadsSocialV1() = runTest {
        loopkyAccount()
        nexusReady = false

        assertEquals(MigrationOutcome.NexusNotReady, migrator.migrate(session))
        assertTrue(pubky.puts.isEmpty())
    }

    @Test
    fun anAccountAnotherAppWroteToIsLeftToPubkyApp() = runTest {
        loopkyAccount()
        pubky.store[v0.post(owner, "0034A0X7NJ52J")] = """{"content":"gm","kind":"short"}"""

        assertEquals(MigrationOutcome.NotLoopkys(PubkyAppFootprint.Direct), migrator.migrate(session))
        assertTrue(pubky.puts.isEmpty())
    }

    @Test
    fun aSessionWithoutTheSocialScopesIsNotMigrated() = runTest {
        loopkyAccount()
        val old = session.copy(capabilities = listOf(Capability("/pub/loopky/:rw"), Capability("/pub/pubky.app/:rw")))

        assertEquals(MigrationOutcome.MissingScope, migrator.migrate(old))
        assertTrue(pubky.gets.isEmpty(), "decided before any read")
    }

    @Test
    fun aMigratedAccountIsLeftAlone() = runTest {
        loopkyAccount()
        pubky.store[v1.profile(owner)] = """{"name":"Ana"}"""

        assertEquals(MigrationOutcome.AlreadyV1, migrator.migrate(session))
        assertTrue(pubky.puts.isEmpty())
    }

    @Test
    fun anInterruptedRunFlipsNothingAndTheNextOneFinishesIt() = runTest {
        loopkyAccount()
        pubky.sessionCallsBeforeFailure = 3

        assertTrue(runCatching { migrator.migrate(session) }.isFailure)
        assertFalse(v1.profile(owner) in pubky.store)
        val copied = pubky.puts.map { it.first }.toSet()

        // The announcement was deleted in between: its copy must not come back with the flip.
        pubky.store.remove(v0.post(owner, "0034A0X7NJ52G"))
        pubky.sessionCallsBeforeFailure = null
        pubky.puts.clear()

        assertEquals(MigrationOutcome.Migrated(records = 7), migrator.migrate(session))
        assertTrue(pubky.puts.none { it.first in copied }, "a copy already there is not written again")
        assertFalse(v1.postVersion(owner, "0034A0X7NJ52G") in pubky.store)
        assertTrue(v1.profile(owner) in pubky.store)
    }

    @Test
    fun anAccountWithoutAProfileWaitsForItsFirstOne() = runTest {
        pubky.store[v0.follow(owner, "friendpk")] = """{"created_at":5}"""

        assertEquals(MigrationOutcome.NoProfile, migrator.migrate(session))
        assertTrue(pubky.puts.isEmpty())

        val outcome = migrator.migrate(session, newProfile = ProfileDto(name = "Bo"))

        assertEquals(MigrationOutcome.Migrated(records = 1), outcome)
        assertEquals("""{"name":"Bo"}""", pubky.store[v1.profile(owner)])
    }

    @Test
    fun aWriteDuringTheMigrationWaitsForIt() = runTest {
        val release = CompletableDeferred<Unit>()
        launch { epochs.migrating(owner) { release.await() } }
        runCurrent()

        val epoch = async { epochs.of(owner) }
        runCurrent()
        assertFalse(epoch.isCompleted)

        epochs.markV1(owner)
        release.complete(Unit)
        assertEquals(SocialEpoch.V1, epoch.await())
    }

    @Test
    fun referencesTakeTheirV1Spelling() {
        assertEquals("pubky://x/pub/social/v1/profile.json", rewrite("pubky://x/pub/pubky.app/profile.json"))
        assertEquals("pubky://x/pub/social/v1/posts/0034A0X7NJ52G", rewrite("pubky://x/pub/pubky.app/posts/0034A0X7NJ52G"))
        assertEquals("pubky://x/pub/social/v1/follows/y.json", rewrite("pubky://x/pub/pubky.app/follows/y"))
        assertEquals(deck, rewrite(deck))
        assertEquals("pubky://x/pub/pubky.app/tags/abc", rewrite("pubky://x/pub/pubky.app/tags/abc"))
        assertEquals("https://loopky.app/recap/x", rewrite("https://loopky.app/recap/x"))
    }
}
