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
        now = { 1_700_000_000_000L },
    )
    private val owner = TEST_PUBKY
    private val session = fakeSession(owner)
    private val v0 = SocialLayout.Legacy
    private val v1 = SocialLayout.SocialV1
    private val deck = "pubky://$owner/pub/loopky/decks/deck1/manifest.json"
    private val flag = "pubky://$owner/priv/social/v1/_migrated.json"

    private fun legacyTag(uri: String, label: String): String {
        val path = v0.tag(owner, pubky.createTagId(uri, label).getOrThrow())
        pubky.store[path] = """{"uri":"$uri","label":"$label","created_at":7}"""
        return path
    }

    /** A Loopky-only account: a profile, a follow, a block, an announcement, a deck request and their tags. */
    private fun loopkyAccount() {
        pubky.store[v0.profile(owner)] = """{"name":"Ana","bio":"hi"}"""
        pubky.store[v0.follow(owner, FRIEND)] = """{"created_at":1700000000000}"""
        pubky.store[v0.mute(owner, TROLL)] = """{"created_at":6}"""
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
        val writes = pubky.puts.map { it.first }
        assertEquals(listOf(v1.profile(owner), flag), writes.takeLast(2), "the profile is the flip, then the flag")
        assertEquals("""{"name":"Ana","bio":"hi"}""", pubky.store[v1.profile(owner)])
        assertEquals("""{"created_at":1700000000000000}""", pubky.store[v1.follow(owner, FRIEND)], "ms become µs")
        assertEquals("""{"created_at":6}""", pubky.store[v1.mute(owner, TROLL)])
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
        assertTrue(pubky.store.keys.containsAll(listOf(v0.profile(owner), v0.follow(owner, FRIEND))), "v0 stays")
        assertEquals(SocialEpoch.V1, epochs.of(owner))
    }

    @Test
    fun theFlagTellsPubkyAppsMigratorTheAccountIsDone() = runTest {
        loopkyAccount()
        val long = legacyTag(v0.profile(owner), "a-label-longer-than-twenty")

        migrator.migrate(session)

        val written = loopkyJson.decodeFromString(MigrationFlagDto.serializer(), pubky.store.getValue(flag))
        assertEquals(1_700_000_000_000_000L, written.migrated_at)
        assertEquals(4, written.transform_rev)
        assertEquals(mapOf("invalid" to listOf(long.removePrefix("pubky://$owner/"))), written.skipped)
        assertEquals(9, written.migrated.size, "the profile and the eight records that moved")
        assertTrue("pub/pubky.app/profile.json" in written.migrated)
        assertEquals(written.migrated.sorted(), written.migrated)
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
        val reads = pubky.gets.size
        migrator.migrate(session)
        assertTrue(pubky.gets.size - reads <= 1, "the answer is kept for the process: only the flip is asked again")
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
        pubky.store[flag] = """{"migrated_at":1,"transform_rev":4,"skipped":{},"migrated":[]}"""

        assertEquals(MigrationOutcome.AlreadyV1, migrator.migrate(session))
        assertTrue(pubky.puts.isEmpty())
    }

    @Test
    fun aFlagAFlippedRunCouldNotWriteIsWrittenLater() = runTest {
        loopkyAccount()
        migrator.migrate(session)
        pubky.store.remove(flag)
        // The user unfollowed since: the flag must still list the follow, or pubky.app copies it back.
        pubky.store.remove(v1.follow(owner, FRIEND))

        val later = SocialMigrator(pubky, PubkyAppFootprintReader(pubky), SocialEpochs(pubky), { true })
        assertEquals(MigrationOutcome.AlreadyV1, later.migrate(session))
        val written = loopkyJson.decodeFromString(MigrationFlagDto.serializer(), pubky.store.getValue(flag))
        assertTrue(v0.follow(owner, FRIEND).removePrefix("pubky://$owner/") in written.migrated)
    }

    @Test
    fun aProfileEditedOnAnotherDeviceDuringTheCopyIsTheOneFlipped() = runTest {
        loopkyAccount()
        val gate = CompletableDeferred<Unit>()
        pubky.putGate = gate
        val run = async { migrator.migrate(session) }
        runCurrent()

        pubky.store[v0.profile(owner)] = """{"name":"Ana Maria"}"""
        pubky.putGate = null
        gate.complete(Unit)

        run.await()
        assertEquals("""{"name":"Ana Maria"}""", pubky.store[v1.profile(owner)])
    }

    @Test
    fun aSettledAnswerIsForgottenAfterAProfileWrite() = runTest {
        loopkyAccount()
        pubky.store[v0.profile(owner)] = """{"name":"Al"}"""
        assertEquals(MigrationOutcome.InvalidProfile, migrator.migrate(session))

        pubky.store[v0.profile(owner)] = """{"name":"Alex"}"""
        assertEquals(MigrationOutcome.InvalidProfile, migrator.migrate(session), "kept for the process")
        migrator.forget(owner)

        assertEquals(MigrationOutcome.Migrated(records = 8), migrator.migrate(session))
    }

    @Test
    fun aTagThe0xReaderRefusesIsSkipped() = runTest {
        loopkyAccount()
        // Written before #479: the id is of the label as typed, which the 0.x reader refuses.
        val path = v0.tag(owner, pubky.createTagId(deck, "Spanish").getOrThrow())
        pubky.store[path] = """{"uri":"$deck","label":"Spanish","created_at":7}"""

        migrator.migrate(session)

        assertFalse(v1.tag(owner, pubky.createTagId(deck, "spanish").getOrThrow()) in pubky.store)
        val written = loopkyJson.decodeFromString(MigrationFlagDto.serializer(), pubky.store.getValue(flag))
        assertEquals(listOf(path.removePrefix("pubky://$owner/")), written.skipped["invalid"])
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
    fun aRecordAnotherDeviceWritesDuringTheCopyIsCopiedBeforeTheFlip() = runTest {
        loopkyAccount()
        val gate = CompletableDeferred<Unit>()
        pubky.putGate = gate
        val run = async { migrator.migrate(session) }
        runCurrent()

        pubky.store[v0.follow(owner, TROLL)] = """{"created_at":9}"""
        pubky.putGate = null
        gate.complete(Unit)

        assertEquals(MigrationOutcome.Migrated(records = 9), run.await())
        assertTrue(v1.follow(owner, TROLL) in pubky.store)
    }

    @Test
    fun anotherDeviceFlippingFirstIsNotOverwritten() = runTest {
        loopkyAccount()
        val gate = CompletableDeferred<Unit>()
        pubky.putGate = gate
        val run = async { migrator.migrate(session) }
        runCurrent()

        pubky.store[v1.profile(owner)] = """{"name":"Ana from the other phone"}"""
        pubky.putGate = null
        gate.complete(Unit)

        assertEquals(MigrationOutcome.AlreadyV1, run.await())
        assertEquals("""{"name":"Ana from the other phone"}""", pubky.store[v1.profile(owner)])
    }

    @Test
    fun anAccountWithoutAProfileWaitsForItsFirstOne() = runTest {
        pubky.store[v0.follow(owner, FRIEND)] = """{"created_at":5}"""

        assertEquals(MigrationOutcome.NoProfile, migrator.migrate(session))
        assertTrue(pubky.puts.isEmpty())

        val outcome = migrator.migrate(session, newProfile = ProfileDto(name = "Bob"))

        assertEquals(MigrationOutcome.Migrated(records = 1), outcome)
        assertEquals("""{"name":"Bob"}""", pubky.store[v1.profile(owner)])
    }

    @Test
    fun aProfileV1WouldRefuseIsNotTheFlip() = runTest {
        loopkyAccount()
        pubky.store[v0.profile(owner)] = """{"name":"Al"}"""

        assertEquals(MigrationOutcome.InvalidProfile, migrator.migrate(session))
        assertEquals(MigrationOutcome.InvalidProfile, migrator.migrate(session, ProfileDto(name = "A".repeat(51))))
        assertEquals(MigrationOutcome.InvalidProfile, migrator.migrate(session, ProfileDto("Ana", "b".repeat(161))))
        assertTrue(pubky.puts.isEmpty())

        assertEquals(MigrationOutcome.Migrated(records = 8), migrator.migrate(session, ProfileDto(name = "Alex")))
    }

    @Test
    fun aWriteInFlightHoldsTheMigrationBack() = runTest {
        loopkyAccount()
        val release = CompletableDeferred<Unit>()
        val write = async {
            epochs.writing(owner) { layout ->
                release.await()
                pubky.store[layout.follow(owner, TROLL)] = """{"created_at":9}"""
            }
        }
        runCurrent()
        val run = async { migrator.migrate(session) }
        runCurrent()
        assertFalse(run.isCompleted)

        release.complete(Unit)
        write.await()

        assertEquals(MigrationOutcome.Migrated(records = 9), run.await())
        assertTrue(v1.follow(owner, TROLL) in pubky.store, "the write landed in v0 before the copy, and moved")
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

    private companion object {
        /** Pubkys v1 takes as followees: 52 characters of z-base32. */
        const val FRIEND = "ybndrfg8ejkmcpqxot1uwisza345h769ybndrfg8ejkmcpqxot1y"
        const val TROLL = "967h543azsiwu1toxqpcmkje8gfrdnby967h543azsiwu1toxqpo"
    }
}
