package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.cli.commands.CLI_CAPABILITIES
import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import com.github.jvsena42.loopky.domain.model.Capability
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.Session
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A login from before #616 holds `/pub/loopky/:rw` only, so its deck tags never reach the Nexus that
 * reads `/pub/pubky.app/tags/`. Its commands still work, which is why it is a warning and not a refusal.
 */
class OldScopeWarningTest {

    private val environment = CliEnvironment(PubkyEnvironment.Staging, Path.of("/tmp/loopky-test"))

    private fun session(vararg capabilities: String) = Session(
        identity = PubkyIdentity(pubky = "pk:test", displayName = null, avatarUrl = null, bio = null),
        sessionSecret = "pk:test:cookie",
        homeserver = "hs:test",
        capabilities = capabilities.map(::Capability),
    )

    @Test
    fun `a login from before the tag scope is told to log in again`() = runBlocking {
        val warnings = mutableListOf<String>()

        requireSession(CountingIdentityRepository(session("/pub/loopky/:rw")), environment, { null }, warnings::add)

        assertEquals(listOf(OLD_SCOPE_WARNING), warnings)
    }

    @Test
    fun `a login with the current scope is not`() = runBlocking {
        val warnings = mutableListOf<String>()
        val current = session(*CLI_CAPABILITIES.split(',').toTypedArray())

        requireSession(CountingIdentityRepository(current), environment, { null }, warnings::add)

        assertEquals(emptyList(), warnings)
    }
}
