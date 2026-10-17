package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.cli.commands.loginMismatchMessage
import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import com.github.jvsena42.loopky.domain.model.Capability
import com.github.jvsena42.loopky.domain.model.PubkyIdentity
import com.github.jvsena42.loopky.domain.model.Session
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    @Test
    fun `a refusal carries the payload and text it is handed`() {
        val payload = JsonPrimitive("result")
        val error = assertFailsWith<CliError> {
            checkEnvironmentAgrees(
                sessionOn(PubkyEnvironment.Staging.defaultHomeserver),
                cli(PubkyEnvironment.Production),
                message = { other -> "on $other" },
                data = payload,
                text = "Signed in",
            )
        }
        assertEquals("on staging", error.message)
        assertEquals(payload, error.data)
        assertEquals("Signed in", error.text)
    }

    /** `login` makes the session every other command is checked against, so it checks its own (#507). */
    @Test
    fun `login's refusal says the session was kept and which --env to use`() {
        val message = loginMismatchMessage(
            other = "staging",
            session = sessionOn(PubkyEnvironment.Staging.defaultHomeserver),
            environment = cli(PubkyEnvironment.Production),
            replaced = null,
        )
        assertTrue("--env staging" in message, message)
        assertTrue("session is stored" in message, message)
        assertTrue("replaced" !in message, message)
    }

    /** `complete()` persists before the check runs, so the session it displaced is gone (#525 review). */
    @Test
    fun `and names the session it replaced`() {
        val previous = sessionOn(PubkyEnvironment.Production.defaultHomeserver).copy(
            identity = PubkyIdentity(pubky = "pk:before", displayName = null, avatarUrl = null, bio = null),
            sessionSecret = "older",
        )
        val message = loginMismatchMessage(
            other = "staging",
            session = sessionOn(PubkyEnvironment.Staging.defaultHomeserver),
            environment = cli(PubkyEnvironment.Production),
            replaced = previous,
        )
        assertTrue("replaced the session stored before, for pk:before" in message, message)
    }
}
