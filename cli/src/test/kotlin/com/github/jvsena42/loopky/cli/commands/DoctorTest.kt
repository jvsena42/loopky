package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliEnvironment
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import kotlinx.coroutines.test.runTest
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DoctorTest {

    private val environment = CliEnvironment(PubkyEnvironment.Production, Path("/tmp/loopky-doctor-test"))

    /** As `resolve_https` returns the production homeserver's packet. */
    private val records = """{"public_key":"8um7","https_records":[{"name":".","target":".","port":6286},""" +
        """{"name":".","target":"homeserver.pubky.app."}],"last_seen":0,"timestamp":0}"""

    private val resolved = mutableListOf<String>()

    private fun client(answer: Result<String> = Result.success(records)): suspend (String) -> Result<String> =
        { key -> resolved += key; answer }

    private fun reachable(redirects: Map<String, String> = emptyMap()): suspend (String) -> ProbeOutcome =
        { url -> ProbeOutcome(REACHABLE, null, redirects[url], 1) }

    @Test
    fun `the homeserver's ordinary domain is probed and every host lands on the allowlist`() = runTest {
        val manifest = "https://github.com/jvsena42/loopky/releases/latest/download/latest.json"
        val pinned = "https://github.com/jvsena42/loopky/releases/download/v1.0.0/latest.json"
        val probe = reachable(mapOf(manifest to pinned, pinned to "https://release-assets.githubusercontent.com/x"))

        val result = doctor(Args.parse(arrayOf("doctor")), client(), environment, probe, proxy = null)

        val report = result.data.toString()
        listOf(
            "httprelay.pubky.app", "pkarr.pubky.app", "pkarr.pubky.org", "homeserver.pubky.app",
            "nexus.pubky.app", "github.com", "release-assets.githubusercontent.com",
        ).forEach { host -> assert(host in report) { "$host missing from $report" } }
    }

    @Test
    fun `a refused host exits proxy_refused and still carries the report`() = runTest {
        val probe: suspend (String) -> ProbeOutcome = { url ->
            if ("nexus" in url) ProbeOutcome(REFUSED, "403", null, 1) else ProbeOutcome(REACHABLE, null, null, 1)
        }
        val error = assertFailsWith<CliError> {
            doctor(Args.parse(arrayOf("doctor")), client(), environment, probe, proxy = "http://proxy:3128")
        }
        assertEquals(ExitCode.ProxyRefused, error.exitCode)
        assert("nexus.pubky.app" in error.data.toString())
    }

    /** The sandbox #212 is about: relays blocked, so the lookup fails exactly where the list is needed. */
    @Test
    fun `a failed lookup of the default homeserver still lists its known host`() = runTest {
        val probe: suspend (String) -> ProbeOutcome = { url ->
            if ("pkarr" in url) ProbeOutcome(REFUSED, "403", null, 1) else ProbeOutcome(REACHABLE, null, null, 1)
        }
        val error = assertFailsWith<CliError> {
            doctor(
                Args.parse(arrayOf("doctor")),
                client(Result.failure(RuntimeException("no responses"))),
                environment,
                probe,
                proxy = "http://proxy:3128",
            )
        }
        assertEquals(ExitCode.ProxyRefused, error.exitCode)
        val report = error.data.toString()
        assert("homeserver.pubky.app" in report) { report }
        assert(""""allowlist_complete":true""" in report) { report }
        assert(error.message!!.startsWith("refused by the proxy: pkarr.pubky.app, pkarr.pubky.org")) { error.message!! }
    }

    @Test
    fun `a failed lookup of another homeserver is incomplete, and a network failure`() = runTest {
        val error = assertFailsWith<CliError> {
            doctor(
                Args.parse(arrayOf("doctor", "--homeserver", "otherhomeserver")),
                client(Result.failure(RuntimeException("no responses"))),
                environment,
                reachable(),
                proxy = null,
            )
        }
        assertEquals(ExitCode.Network, error.exitCode)
        assert(""""allowlist_complete":false""" in error.data.toString())
    }

    @Test
    fun `one refused pkarr relay is not a failure while the other works`() = runTest {
        val probe: suspend (String) -> ProbeOutcome = { url ->
            if ("pkarr.pubky.org" in url) ProbeOutcome(REFUSED, "403", null, 1) else ProbeOutcome(REACHABLE, null, null, 1)
        }
        val result = doctor(Args.parse(arrayOf("doctor")), client(), environment, probe, proxy = "http://proxy:3128")
        assert("pkarr.pubky.org" in result.data.toString()) { "the refused relay is still reported" }
    }

    @Test
    fun `both pkarr relays refused and no lookup is a refusal`() = runTest {
        val probe: suspend (String) -> ProbeOutcome = { url ->
            if ("pkarr" in url) ProbeOutcome(REFUSED, "403", null, 1) else ProbeOutcome(REACHABLE, null, null, 1)
        }
        val error = assertFailsWith<CliError> {
            doctor(Args.parse(arrayOf("doctor")), client(Result.failure(RuntimeException("x"))), environment, probe, null)
        }
        assertEquals(ExitCode.ProxyRefused, error.exitCode)
    }

    @Test
    fun `interception is its own status and exits tls_untrusted`() = runTest {
        val probe: suspend (String) -> ProbeOutcome = { ProbeOutcome(INTERCEPTED, "PKIX path building failed", null, 1) }
        val error = assertFailsWith<CliError> {
            doctor(Args.parse(arrayOf("doctor")), client(), environment, probe, proxy = "http://proxy:3128")
        }
        assertEquals(ExitCode.TlsUntrusted, error.exitCode)
        assert(error.message!!.startsWith("TLS intercepted:"))
    }

    @Test
    fun `the asset CDN is probed even when github never redirects to it`() = runTest {
        val probe: suspend (String) -> ProbeOutcome = { url ->
            if ("github.com" in url) ProbeOutcome(REFUSED, "403", null, 1) else ProbeOutcome(REACHABLE, null, null, 1)
        }
        val error = assertFailsWith<CliError> {
            doctor(Args.parse(arrayOf("doctor")), client(), environment, probe, proxy = "http://proxy:3128")
        }
        assert("release-assets.githubusercontent.com" in error.data.toString())
    }

    @Test
    fun `--homeserver takes a key with or without the pubky prefix`() = runTest {
        doctor(Args.parse(arrayOf("doctor", "--homeserver", "pubkyabc")), client(), environment, reachable(), null)
        assertEquals("abc", resolved.single())
    }

    @Test
    fun `a relative or malformed Location ends the chain instead of crashing`() = runTest {
        val manifest = "https://github.com/jvsena42/loopky/releases/latest/download/latest.json"
        listOf("/login", "http://bad host/x", "https:///nohost").forEach { location ->
            val result = doctor(
                Args.parse(arrayOf("doctor")),
                client(),
                environment,
                reachable(mapOf(manifest to location)),
                proxy = null,
            )
            assert("github.com" in result.data.toString()) { location }
        }
    }

    @Test
    fun `a blank --homeserver is bad input, never a lookup`() = runTest {
        val error = assertFailsWith<CliError> {
            doctor(Args.parse(arrayOf("doctor", "--homeserver", "  ")), client(), environment, reachable(), null)
        }
        assertEquals(ExitCode.BadInput, error.exitCode)
        assert(resolved.isEmpty())
    }

    @Test
    fun `a record naming no ordinary domain yields no homeserver host`() {
        assertNull(icannTarget("""{"https_records":[{"target":"."}]}"""))
        assertNull(icannTarget("not json"))
        assertEquals("homeserver.pubky.app", icannTarget(records))
    }
}
