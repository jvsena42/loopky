package com.github.jvsena42.loopky.mcp.security

import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SsrfGuardTest {
    private val public = InetAddress.getByName("93.184.216.34")
    private val names = mapOf(
        "public.test" to listOf(public),
        "v6public.test" to listOf(InetAddress.getByName("2606:2800:220:1:248:1893:25c8:1946")),
        "rebind.test" to listOf(public, InetAddress.getByName("10.0.0.1")),
        "meta.test" to listOf(InetAddress.getByName("169.254.169.254")),
        "ec2v6.test" to listOf(InetAddress.getByName("fd00:ec2::254")),
        "nat64.test" to listOf(InetAddress.getByName("64:ff9b::7f00:1")),
        "mapped.test" to listOf(InetAddress.getByAddress(ByteArray(16).also { it[10] = -1; it[11] = -1; it[12] = 10; it[13] = 1 })),
    )
    private val guard = SsrfGuard { host -> names[host] ?: throw UnknownHostException(host) }

    private fun refused(url: String): String =
        guard.vet(url).exceptionOrNull()?.message ?: error("$url was allowed")

    @Test
    fun `a public https host passes and carries its vetted address`() {
        val vetted = guard.vet("https://public.test/image.png?x=1").getOrThrow()
        assertEquals("public.test", vetted.host)
        assertEquals(443, vetted.port)
        assertEquals(public, vetted.address)
        assertEquals(8443, guard.vet("https://v6public.test:8443/").getOrThrow().port)
    }

    @Test
    fun `the scheme, userinfo and shape are checked before any resolution`() {
        assertTrue("https" in refused("http://public.test/"))
        assertTrue("credentials" in refused("https://user:pw@public.test/"))
        assertTrue("host" in refused("https:///path"))
        assertTrue("valid" in refused("https://exa mple.test/"))
        assertTrue("long" in refused("https://public.test/" + "a".repeat(3000)))
        assertTrue("resolve" in refused("https://nowhere.test/"))
    }

    @Test
    fun `every resolved address is checked, not just the first`() {
        assertTrue("non-public" in refused("https://rebind.test/"))
    }

    @Test
    fun `loopback, private, link-local, CGNAT, multicast, reserved and metadata addresses are refused`() {
        listOf(
            "127.0.0.1", "127.1.2.3", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1", "169.254.169.254",
            "169.254.1.1", "100.64.0.1", "100.127.255.255", "0.0.0.0", "224.0.0.1", "239.255.255.255", "240.0.0.1",
            "255.255.255.255", "192.0.0.1", "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.1",
            "[::1]", "[::]", "[fe80::1]", "[fc00::1]", "[fd00:ec2::254]", "[fdff::1]", "[ff02::1]", "[2001:db8::1]",
            "[::ffff:127.0.0.1]", "[::ffff:10.0.0.1]", "[64:ff9b::7f00:1]", "[64:ff9b::a00:1]",
        ).forEach { literal ->
            assertTrue("non-public" in refused("https://$literal/"), literal)
        }
        assertTrue("non-public" in refused("https://meta.test/"))
        assertTrue("non-public" in refused("https://ec2v6.test/"))
        assertTrue("non-public" in refused("https://nat64.test/"))
        assertTrue("non-public" in refused("https://mapped.test/"))
    }

    @Test
    fun `public addresses of both families are allowed`() {
        assertFalse(SsrfGuard.isForbidden(public))
        assertFalse(SsrfGuard.isForbidden(InetAddress.getByName("1.1.1.1")))
        assertFalse(SsrfGuard.isForbidden(InetAddress.getByName("8.8.8.8")))
        assertFalse(SsrfGuard.isForbidden(InetAddress.getByName("172.32.0.1")))
        assertFalse(SsrfGuard.isForbidden(InetAddress.getByName("100.128.0.1")))
        assertFalse(SsrfGuard.isForbidden(InetAddress.getByName("2606:4700::1111")))
        assertFalse(SsrfGuard.isForbidden(InetAddress.getByName("64:ff9b::5db8:d822")), "NAT64 of a public address")
    }

    @Test
    fun `a pinned connection targets the vetted address, keeps the host name and never follows redirects`() {
        SsrfGuard.enablePinnedHostHeader()
        val vetted = guard.vet("https://public.test:8443/img.png?s=1").getOrThrow()
        val connection = vetted.openPinnedConnection()
        assertEquals("93.184.216.34", connection.url.host)
        assertEquals(8443, connection.url.port)
        assertEquals("/img.png?s=1", connection.url.file)
        assertEquals("public.test:8443", connection.getRequestProperty("Host"))
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.sslSocketFactory.javaClass.simpleName.contains("SniPinning"))
    }
}
