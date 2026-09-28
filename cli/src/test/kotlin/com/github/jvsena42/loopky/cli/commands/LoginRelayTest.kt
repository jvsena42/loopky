package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.ExitCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** `login` asks the relay before showing a QR, so an unreachable one is never reported as 13 (#360). */
class LoginRelayTest {

    private fun answering(status: String): suspend (String) -> ProbeOutcome = { ProbeOutcome(status, "why", null, 1) }

    @Test
    fun `a reachable relay lets the sign-in go ahead`() = runTest {
        requireRelayReachable(answering(REACHABLE))
    }

    @Test
    fun `an unreachable relay is a network failure, not a timeout`() = runTest {
        val error = assertFailsWith<CliError> { requireRelayReachable(answering(UNREACHABLE)) }
        assertEquals(ExitCode.Network, error.exitCode)
    }

    @Test
    fun `a refused relay is proxy_refused and an intercepted one tls_untrusted`() = runTest {
        assertEquals(ExitCode.ProxyRefused, assertFailsWith<CliError> { requireRelayReachable(answering(REFUSED)) }.exitCode)
        assertEquals(ExitCode.TlsUntrusted, assertFailsWith<CliError> { requireRelayReachable(answering(INTERCEPTED)) }.exitCode)
    }

    @Test
    fun `the relay asked is the one Ring posts to`() = runTest {
        val asked = mutableListOf<String>()
        requireRelayReachable { url -> asked += url; ProbeOutcome(REACHABLE, null, null, 1) }
        assertEquals(listOf("https://httprelay.pubky.app/"), asked)
    }
}
