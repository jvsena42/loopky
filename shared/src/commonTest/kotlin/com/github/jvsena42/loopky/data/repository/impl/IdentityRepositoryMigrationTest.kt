package com.github.jvsena42.loopky.data.repository.impl

import com.github.jvsena42.loopky.data.pubky.SocialEpochs
import com.github.jvsena42.loopky.data.pubky.SocialLayout
import com.github.jvsena42.loopky.data.storage.SecureSessionStore
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.testing.FakePubkyClient
import com.github.jvsena42.loopky.testing.TEST_PUBKY
import com.github.jvsena42.loopky.testing.fakeSession
import com.github.jvsena42.loopky.testing.identityRepository
import com.github.jvsena42.loopky.testing.signedInProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** How [IdentityRepositoryImpl] hands an account to [SocialMigrator] (#616). */
@OptIn(ExperimentalCoroutinesApi::class)
class IdentityRepositoryMigrationTest {

    private val pubky = FakePubkyClient()
    private val epochs = SocialEpochs(pubky)
    private var nexusReady = true
    private val repo = identityRepository(
        pubky = pubky,
        sessionProvider = signedInProvider(),
        scope = CoroutineScope(UnconfinedTestDispatcher()),
        epochs = epochs,
        migrator = SocialMigrator(pubky, PubkyAppFootprintReader(pubky), epochs, servesSocialV1 = { nexusReady }),
    )
    private val v0 = SocialLayout.Legacy.profile(TEST_PUBKY)
    private val v1 = SocialLayout.SocialV1.profile(TEST_PUBKY)

    @Test
    fun aNewAccountsFirstProfileStartsItInSocialV1() = runTest {
        repo.updateProfile(name = "Ana", bio = null).getOrThrow()

        assertEquals("""{"name":"Ana"}""", pubky.store[v1])
        assertFalse(v0 in pubky.store)
    }

    @Test
    fun aLoopkyOnlyAccountMovesWhenItsProfileIsEdited() = runTest {
        pubky.store[v0] = """{"name":"Ana"}"""
        pubky.store[SocialLayout.Legacy.follow(TEST_PUBKY, FRIEND)] = """{"created_at":5}"""

        repo.updateProfile(name = "Ana B", bio = "hi").getOrThrow()

        assertEquals("""{"name":"Ana B","bio":"hi"}""", pubky.store[v1])
        assertTrue(SocialLayout.SocialV1.follow(TEST_PUBKY, FRIEND) in pubky.store)
        assertEquals("""{"name":"Ana"}""", pubky.store[v0], "the v0 tree is left as it was")
    }

    @Test
    fun beforeNexusReadsSocialV1TheProfileStaysInPubkyApp() = runTest {
        nexusReady = false

        repo.updateProfile(name = "Ana", bio = null).getOrThrow()

        assertTrue("Ana" in pubky.store.getValue(v0))
        assertFalse(v1 in pubky.store)
    }

    @Test
    fun aProfileV1WouldRefuseIsNotWrittenThere() = runTest {
        pubky.store[v1] = """{"name":"Ana"}"""

        assertTrue(repo.updateProfile(name = "Al", bio = null).isFailure)
        assertEquals("""{"name":"Ana"}""", pubky.store[v1])
    }

    @Test
    fun aV1ProfileEditKeepsWhatLoopkyDoesNotWrite() = runTest {
        pubky.store[v1] = """{"name":"Ana","bio":"old","links":[{"title":"x","url":"https://x.y"}],"status":"busy"}"""

        val shown = repo.updateProfile(name = " Ana B ", bio = null).getOrThrow()

        assertEquals("Ana B", shown.displayName, "the session shows what was stored")

        assertEquals("""{"name":"Ana B","links":[{"title":"x","url":"https://x.y"}],"status":"busy"}""", pubky.store[v1])
    }

    @Test
    fun aShortNameKeepsANewAccountInPubkyAppUntilAValidOne() = runTest {
        repo.updateProfile(name = "Al", bio = null).getOrThrow()

        assertTrue("Al" in pubky.store.getValue(v0))
        assertFalse(v1 in pubky.store)
    }

    @Test
    fun loadingTheSessionMigratesInTheBackground() = runTest {
        pubky.store[v0] = """{"name":"Ana"}"""
        val stored = identityRepository(
            pubky = pubky,
            sessionStore = SingleSessionStore(),
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            epochs = epochs,
            migrator = SocialMigrator(pubky, PubkyAppFootprintReader(pubky), epochs, servesSocialV1 = { true }),
        )

        stored.loadPersistedSession()

        assertEquals("""{"name":"Ana"}""", pubky.store[v1])
    }
}

/** A pubky v1 takes as a followee: 52 characters of z-base32. */
private const val FRIEND = "ybndrfg8ejkmcpqxot1uwisza345h769ybndrfg8ejkmcpqxot1y"

private class SingleSessionStore : SecureSessionStore {
    override val location: String = "in memory"
    private var saved: Session? = fakeSession()

    override suspend fun save(session: Session) {
        saved = session
    }

    override suspend fun load(): Session? = saved

    override suspend fun clear() {
        saved = null
    }
}
