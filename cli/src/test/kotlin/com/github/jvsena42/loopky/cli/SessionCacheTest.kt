package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import com.github.jvsena42.loopky.domain.model.Capability
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.Session
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * `batch` listed the session round trip as one of the three costs it collapses, and was the one it
 * did not collapse (#240 review). Every homeserver command goes through `authed`, which resolved
 * the session unconditionally — so a 100-operation run resolved it 100 times, and with
 * `LOOPKY_SESSION` set (the documented way an agent runs this) each of those is an
 * `adoptSession` → `revalidateSession` **homeserver round trip**.
 *
 * A process has exactly one session for its lifetime: `login` and `logout` are separate
 * invocations, and `batch` refuses both as operations.
 */
class SessionCacheTest {

    private val session = Session(
        identity = PubkyIdentity(pubky = "pk:test", displayName = null, avatarUrl = null, bio = null),
        sessionSecret = "pk:test:cookie",
        homeserver = "hs:test",
        capabilities = listOf(Capability("/pub/loopky/:rw")),
    )

    private val environment = CliEnvironment(PubkyEnvironment.Staging, Path.of("/tmp/loopky-test"))

    @Test
    fun `resolves once however many times it is asked`() = runBlocking {
        val identity = CountingIdentityRepository(session)
        val cache = SessionCache()

        val first = cache.require(identity, environment) { null }
        repeat(99) { cache.require(identity, environment) { null } }

        assertEquals(1, identity.loads, "a 100-operation batch resolved the session 100 times")
        assertSame(first, cache.require(identity, environment) { null })
    }

    /** The injected path is the expensive one: `adoptSession` is a homeserver round trip. */
    @Test
    fun `an injected session is adopted once, not revalidated per operation`() = runBlocking {
        val identity = CountingIdentityRepository(session)
        val cache = SessionCache()
        val env = { name: String -> "pktest:cookie".takeIf { name == "LOOPKY_SESSION" } }

        repeat(50) { cache.require(identity, environment, env) }

        assertEquals(1, identity.adoptions)
        assertEquals(0, identity.loads)
    }

    /** Each invocation is its own process; nothing is shared between two caches. */
    @Test
    fun `a fresh cache resolves again`() = runBlocking {
        val identity = CountingIdentityRepository(session)

        SessionCache().require(identity, environment) { null }
        SessionCache().require(identity, environment) { null }

        assertEquals(2, identity.loads)
    }
}
