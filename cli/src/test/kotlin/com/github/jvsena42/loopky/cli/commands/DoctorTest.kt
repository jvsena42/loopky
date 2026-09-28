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

        val result =
            doctor(Args.parse(arrayOf("doctor")), client(), environment, probes(probe), proxy = null)

        val report = result.data.toString()
        listOf(
            "httprelay.pubky.app", "pkarr.pubky.app", "pkarr.pubky.org", "homeserver.pubky.app",
            "nexus.pubky.app", "github.com", "release-assets.githubusercontent.com",
        ).forEach { host -> assert(host in report) { "$host missing from $report" } }
    }

    @Test
    fun `a probe that times out once and then answers is reachable, and says so`() = runTest {
        val attempts = mutableMapOf<String, Int>()
        val probe: suspend (String) -> ProbeOutcome = { url ->
            val attempt = attempts.merge(url, 1, Int::plus)!!
            if ("nexus" in url && attempt == 1) {
                ProbeOutcome(UNREACHABLE, "Read timed out", null, PROBE_TIMEOUT, timedOut = true)
            } else {
                ProbeOutcome(REACHABLE, null, null, 1)
            }
        }

        val result =
            doctor(Args.parse(arrayOf("doctor")), client(), environment, probes(probe), proxy = null)

        val nexus = result.data.toString().substringAfter("nexus.pubky.app").substringBefore("}")
        assert(""""status":"reachable"""" in nexus) { nexus }
        assert("second try" in nexus) { nexus }
        assertEquals(listOf(2), attempts.filterKeys { "nexus" in it }.values.toList())
    }

    @Test
    fun `a probe that times out twice is unreachable`() = runTest {
        val probe: suspend (String) -> ProbeOutcome = { url ->
            if ("nexus" in url) {
                ProbeOutcome(UNREACHABLE, "Read timed out", null, PROBE_TIMEOUT, timedOut = true)
            } else {
                ProbeOutcome(REACHABLE, null, null, 1)
            }
        }

        val error = assertFailsWith<CliError> {
            doctor(Args.parse(arrayOf("doctor")), client(), environment, probes(probe), proxy = null)
        }

        assertEquals(ExitCode.Network, error.exitCode)
        assert("timed out twice" in error.data.toString())
    }

    @Test
    fun `a refusal is not retried`() = runTest {
        var nexusProbes = 0
        val probe: suspend (String) -> ProbeOutcome = { url ->
            if ("nexus" in url) {
                nexusProbes++
                ProbeOutcome(REFUSED, "403", null, 1)
            } else {
                ProbeOutcome(REACHABLE, null, null, 1)
            }
        }

        assertFailsWith<CliError> {
            doctor(Args.parse(arrayOf("doctor")), client(), environment, probes(probe), proxy = "http://proxy:3128")
        }

        assertEquals(1, nexusProbes)
    }

    @Test
    fun `a proxy that answers writes itself is method_blocked and exits proxy_refused`() = runTest {
        val writes = mutableListOf<String>()
        val readOnly: suspend (String) -> WriteAnswer? = { url -> writes += url; WriteAnswer(403, "method PUT not allowed") }

        val error = assertFailsWith<CliError> {
            doctor(Args.parse(arrayOf("doctor")), client(), environment, DoctorProbes(reachable(), readOnly), "http://proxy:3128")
        }

        assertEquals(ExitCode.ProxyRefused, error.exitCode)
        assertEquals(listOf("https://homeserver.pubky.app/pub/loopky/doctor"), writes)
        val report = error.data.toString()
        assert(""""status":"method_blocked"""" in report) { report }
        assert("GET, HEAD and OPTIONS" in report) { report }
        assert(error.message!!.startsWith("writes refused by the proxy: homeserver.pubky.app")) { error.message!! }
    }

    @Test
    fun `no write probe is sent when the homeserver's read already failed`() = runTest {
        var writes = 0
        val probe: suspend (String) -> ProbeOutcome = { url ->
            if ("homeserver" in url) ProbeOutcome(REFUSED, "403", null, 1) else ProbeOutcome(REACHABLE, null, null, 1)
        }

        assertFailsWith<CliError> {
            doctor(Args.parse(arrayOf("doctor")), client(), environment, DoctorProbes(probe) { writes++; null }, "http://proxy:3128")
        }

        assertEquals(0, writes)
    }

    @Test
    fun `a refused host exits proxy_refused and still carries the report`() = runTest {
        val probe: suspend (String) -> ProbeOutcome = { url ->
            if ("nexus" in url) ProbeOutcome(REFUSED, "403", null, 1) else ProbeOutcome(REACHABLE, null, null, 1)
        }
        val error = assertFailsWith<CliError> {
            doctor(Args.parse(arrayOf("doctor")), client(), environment, probes(probe), proxy = "http://proxy:3128")
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
                probes(probe),
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
                probes(reachable()),
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
        val result =
            doctor(Args.parse(arrayOf("doctor")), client(), environment, probes(probe), proxy = "http://proxy:3128")
        assert("pkarr.pubky.org" in result.data.toString()) { "the refused relay is still reported" }
    }

    @Test
    fun `both pkarr relays refused and no lookup is a refusal`() = runTest {
        val probe: suspend (String) -> ProbeOutcome = { url ->
            if ("pkarr" in url) ProbeOutcome(REFUSED, "403", null, 1) else ProbeOutcome(REACHABLE, null, null, 1)
        }
        val error = assertFailsWith<CliError> {
            doctor(Args.parse(arrayOf("doctor")), client(Result.failure(RuntimeException("x"))), environment, probes(probe), null)
        }
        assertEquals(ExitCode.ProxyRefused, error.exitCode)
    }

    @Test
    fun `interception is its own status and exits tls_untrusted`() = runTest {
        val probe: suspend (String) -> ProbeOutcome = { ProbeOutcome(INTERCEPTED, "PKIX path building failed", null, 1) }
        val error = assertFailsWith<CliError> {
            doctor(Args.parse(arrayOf("doctor")), client(), environment, probes(probe), proxy = "http://proxy:3128")
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
            doctor(Args.parse(arrayOf("doctor")), client(), environment, probes(probe), proxy = "http://proxy:3128")
        }
        assert("release-assets.githubusercontent.com" in error.data.toString())
    }

    @Test
    fun `--homeserver takes a key with or without the pubky prefix`() = runTest {
        doctor(Args.parse(arrayOf("doctor", "--homeserver", "pubkyabc")), client(), environment, probes(reachable()), null)
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
                probes(reachable(mapOf(manifest to location))),
                proxy = null,
            )
            assert("github.com" in result.data.toString()) { location }
        }
    }

    @Test
    fun `a blank --homeserver is bad input, never a lookup`() = runTest {
        val error = assertFailsWith<CliError> {
            doctor(Args.parse(arrayOf("doctor", "--homeserver", "  ")), client(), environment, probes(reachable()), null)
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

    @Test
    fun `a refusal tells the agent what to ask the human for, and where`() = runTest {
        val probe: suspend (String) -> ProbeOutcome = { url ->
            if ("nexus" in url) ProbeOutcome(REFUSED, "403", null, 1) else ProbeOutcome(REACHABLE, null, null, 1)
        }
        val error = assertFailsWith<CliError> {
            doctor(Args.parse(arrayOf("doctor")), client(), environment, probes(probe), proxy = "http://proxy:3128")
        }
        val report = error.data.toString()
        assert("Ask the user to allow these hosts" in report && "nexus.pubky.app" in report) { report }
        assert("Allowed domains" in report && "GET/HEAD/OPTIONS" in report) { report }
        assert("Next step:" in error.message!!)
    }

    @Test
    fun `nothing wrong means no next step`() = runTest {
        val result =
            doctor(Args.parse(arrayOf("doctor")), client(), environment, probes(reachable()), proxy = null)
        assert(""""next_step":null""" in result.data.toString()) { result.data.toString() }
    }

    /** sandbox-sim's codex-custom with JVM_TRUSTS_PROXY_CA=1: the JVM passes, the SDK cannot. */
    @Test
    fun `the SDK failing relays the JVM reached, behind a proxy, is not a clean bill of health`() = runTest {
        val error = assertFailsWith<CliError> {
            doctor(
                Args.parse(arrayOf("doctor")),
                client(Result.failure(RuntimeException("No signed packet found: resolve query received no responses"))),
                environment,
                probes(reachable()),
                proxy = "http://proxy:3128",
            )
        }
        assertEquals(ExitCode.TlsUntrusted, error.exitCode)
        assert(error.message!!.startsWith("the pubky SDK could not use the pkarr relays"))
    }
}

private const val PROBE_TIMEOUT = 5_000L

/** The production homeserver's answer to an unauthenticated PUT (checked 2026-09-28) as the write probe. */
private fun probes(read: suspend (String) -> ProbeOutcome) =
    DoctorProbes(read) { WriteAnswer(401, "No authenticated session found") }
