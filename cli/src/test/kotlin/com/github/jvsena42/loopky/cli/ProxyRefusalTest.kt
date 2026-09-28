package com.github.jvsena42.loopky.cli

import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Messages as each stack produced them behind a Squid allowlist proxy (#212). */
class ProxyRefusalTest {

    @Test
    fun `a refused CONNECT from the JDK is a proxy refusal`() {
        val error = RuntimeException("Unable to tunnel through proxy. Proxy returns \"HTTP/1.1 403 Forbidden\"")
        assertEquals(ExitCode.ProxyRefused, ExitCode.of(error))
    }

    @Test
    fun `a 407 from Nexus is a proxy refusal`() {
        val error = RuntimeException(
            "GET https://nexus.pubky.app/v0/stream/resources?app=loopky&limit=50 failed with HTTP 407",
        )
        assertEquals(ExitCode.ProxyRefused, ExitCode.of(error))
    }

    @Test
    fun `a refused tunnel from the FFI is a proxy refusal`() {
        val error = RuntimeException(
            "Failed to send list request: Request failed: HTTP transport error: error sending request for url " +
                "(https://homeserver.pubky.app/pub/loopky/decks/): client error (Connect): tunnel error: unsuccessful",
        )
        assertEquals(ExitCode.ProxyRefused, ExitCode.of(error))
    }

    @Test
    fun `a proxy failing to reach an allowed host is retryable, not refused`() {
        listOf("502 Bad Gateway", "503 Service Unavailable", "504 Gateway Timeout").forEach { status ->
            val error = RuntimeException("Unable to tunnel through proxy. Proxy returns \"HTTP/1.1 $status\"")
            assertNotEquals(ExitCode.ProxyRefused, ExitCode.of(error), status)
        }
    }

    @Test
    fun `a proxy asking for credentials on the tunnel is a refusal`() {
        val error = RuntimeException(
            "Unable to tunnel through proxy. Proxy returns \"HTTP/1.1 407 Proxy Authentication Required\"",
        )
        assertEquals(ExitCode.ProxyRefused, ExitCode.of(error))
    }

    @Test
    fun `a proxy that drops rather than refuses is still a network failure`() {
        assertEquals(ExitCode.Network, ExitCode.of(RuntimeException("Connect timed out")))
    }

    /** Deck and card ids are random alphanumerics, and every failure carries a URL. */
    @Test
    fun `407 inside an id is not a status`() {
        val error = RuntimeException("Not found: pubky://abc/pub/loopky/decks/http4070abcd/manifest.json")
        assertEquals(ExitCode.NotFound, ExitCode.of(error))
    }

    /** As sandbox-sim's `intercepting` profile produced it, trending's failure through Nexus. */
    @Test
    fun `a certificate the JDK cannot verify is tls_untrusted, not internal`() {
        val pkix = RuntimeException(
            "(certificate_unknown) PKIX path building failed: sun.security.provider.certpath." +
                "SunCertPathBuilderException: unable to find valid certification path to requested target",
        )
        assertEquals(ExitCode.TlsUntrusted, ExitCode.of(pkix))
    }

    @Test
    fun `the PKIX cause is found below a wrapping exception`() {
        val wrapped = RuntimeException(
            "GET https://nexus.pubky.app/v0/info failed",
            SSLHandshakeException("PKIX path building failed"),
        )
        assertEquals(ExitCode.TlsUntrusted, ExitCode.of(wrapped))
    }

    @Test
    fun `rustls rejecting the chain is tls_untrusted`() {
        val error = RuntimeException(
            "… error sending request for url (…): client error (Connect): invalid peer certificate: UnknownIssuer",
        )
        assertEquals(ExitCode.TlsUntrusted, ExitCode.of(error))
    }
}
