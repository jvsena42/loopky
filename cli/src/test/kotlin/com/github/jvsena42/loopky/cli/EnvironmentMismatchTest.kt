package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import com.github.jvsena42.loopky.domain.model.Capability
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.Session
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A session on one network and an `--env` naming the other has to be refused, and refused *loudly*.
 *
 * The homeserver half would work either way — pkarr resolves both networks — so the only thing
 * that breaks is the indexer-backed reads, and they break **silently**: Nexus answers a query
 * aimed at the wrong network successfully, with an empty result. An agent that writes a deck tag,
 * reads it back, sees `[]` and concludes the write failed will retry.
 */
class EnvironmentMismatchTest {

    private fun cli(env: PubkyEnvironment) = CliEnvironment(env, Paths.get("/tmp/loopky-test"))

    private fun sessionOn(homeserver: String) = Session(
        identity = PubkyIdentity(pubky = "pk:test", displayName = null, avatarUrl = null, bio = null),
        sessionSecret = "secret",
        capabilities = listOf(Capability("/pub/loopky/:rw")),
        homeserver = homeserver,
    )

    @Test
    fun `a staging session under --env production is refused`() {
        val error = assertFailsWith<CliError> {
            checkEnvironmentAgrees(
                sessionOn(PubkyEnvironment.Staging.defaultHomeserver),
                cli(PubkyEnvironment.Production),
            )
        }
        assertEquals(ExitCode.EnvironmentMismatch, error.exitCode)
    }

    @Test
    fun `and the message says which way to fix it`() {
        val error = assertFailsWith<CliError> {
            checkEnvironmentAgrees(
                sessionOn(PubkyEnvironment.Production.defaultHomeserver),
                cli(PubkyEnvironment.Staging),
            )
        }
        assertEquals(true, error.message.orEmpty().contains("--env production"), error.message.orEmpty())
    }

    @Test
    fun `a matching pair passes`() {
        checkEnvironmentAgrees(
            sessionOn(PubkyEnvironment.Production.defaultHomeserver),
            cli(PubkyEnvironment.Production),
        )
    }

    /**
     * Fails open, deliberately. The check is "this session sits on the *other* environment's known
     * default", which is a fact; an equality check against the requested environment's default
     * would refuse a legitimate self-hosted homeserver.
     */
    @Test
    fun `a self-hosted homeserver is not refused`() {
        checkEnvironmentAgrees(sessionOn("somebodyelseshomeserverpubky"), cli(PubkyEnvironment.Production))
    }
}
