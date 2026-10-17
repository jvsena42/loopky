package com.github.jvsena42.loopky.mcp.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The reservation a sign-in holds while Ring starts is released on every exit, cancellation included. */
class PendingLoginsTest {

    private val ring = FakeRingLogin()
    private val logins = PendingLogins(ring, TokenMinter(), Clock.systemUTC(), OAuthLimits())
    private val request = AuthorizeRequest(
        clientId = "client",
        clientName = "Claude",
        redirectUri = CALLBACK,
        codeChallenge = "challenge",
        state = null,
        resource = "https://mcp.test/mcp",
    )

    @Test
    fun `a sign-in cancelled while Ring starts gives its slot back`() = runTest {
        ring.startGate = CompletableDeferred()
        val begun = async { logins.begin(request, "203.0.113.7") }
        runCurrent()
        assertEquals(1, logins.reservedSlots())

        begun.cancel()
        runCurrent()

        assertEquals(0, logins.reservedSlots(), "a cancelled start kept its address's slot")
        assertEquals(0, logins.size)
    }

    @Test
    fun `a Ring that cannot start gives the slot back`() = runTest {
        ring.startFailure = IllegalStateException("relay down")
        assertIs<PendingStart.RingFailed>(logins.begin(request, "203.0.113.7"))
        assertEquals(0, logins.reservedSlots())
    }

    @Test
    fun `a started sign-in turns its reservation into the entry`() = runTest {
        assertIs<PendingStart.Started>(logins.begin(request, "203.0.113.7"))
        assertEquals(0, logins.reservedSlots())
        assertEquals(1, logins.size)
        assertEquals(emptyList(), ring.cancelled)
    }
}
