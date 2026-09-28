package com.github.jvsena42.loopky.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProxyEnvironmentTest {

    @Test
    fun `HTTPS_PROXY becomes the JVM's https proxy`() {
        val proxy = ProxyEnvironment.from(mapOf("HTTPS_PROXY" to "http://proxy:3128"))
        assertEquals("proxy", proxy.properties["https.proxyHost"])
        assertEquals("3128", proxy.properties["https.proxyPort"])
        assertNull(proxy.properties["http.proxyHost"])
    }

    @Test
    fun `uppercase wins over lowercase, as reqwest reads them`() {
        val proxy = ProxyEnvironment.from(
            mapOf("https_proxy" to "http://lower:1", "HTTPS_PROXY" to "http://upper:2"),
        )
        assertEquals("upper", proxy.properties["https.proxyHost"])
    }

    /** reqwest stops at a set uppercase variable even when it is empty, then falls back to ALL_PROXY. */
    @Test
    fun `an empty HTTPS_PROXY shadows https_proxy and falls back to ALL_PROXY`() {
        assertTrue(ProxyEnvironment.from(mapOf("HTTPS_PROXY" to "", "https_proxy" to "http://b:1")).properties.isEmpty())
        val proxy = ProxyEnvironment.from(
            mapOf("HTTPS_PROXY" to "", "https_proxy" to "http://b:1", "ALL_PROXY" to "http://all:2"),
        )
        assertEquals("all", proxy.properties["https.proxyHost"])
    }

    @Test
    fun `credentials allow Basic on the tunnel only`() {
        val set = mutableMapOf<String, String>()
        ProxyEnvironment.from(mapOf("HTTPS_PROXY" to "http://u:p@proxy:3128"))
            .install(getProperty = set::get, setProperty = { k, v -> set[k] = v }, warn = {})
        assertEquals("", set["jdk.http.auth.tunneling.disabledSchemes"])
        assertEquals("Basic", set["jdk.http.auth.proxying.disabledSchemes"])
    }

    @Test
    fun `ALL_PROXY covers both schemes when nothing more specific is set`() {
        val proxy = ProxyEnvironment.from(mapOf("ALL_PROXY" to "http://all:8080"))
        assertEquals("all", proxy.properties["https.proxyHost"])
        assertEquals("all", proxy.properties["http.proxyHost"])
    }

    @Test
    fun `a bare host and port is an http proxy, and a missing port is 80`() {
        assertEquals("3128", ProxyEnvironment.from(mapOf("HTTPS_PROXY" to "proxy:3128")).properties["https.proxyPort"])
        assertEquals("80", ProxyEnvironment.from(mapOf("HTTPS_PROXY" to "http://proxy")).properties["https.proxyPort"])
    }

    @Test
    fun `credentials are held for the proxy, percent-decoded, and never put in a property`() {
        val proxy = ProxyEnvironment.from(mapOf("HTTPS_PROXY" to "http://agent:p%40ss%3Aword@proxy:3128"))
        assertEquals(listOf(ProxyCredential("proxy", 3128, "agent", "p@ss:word")), proxy.credentials)
        assertTrue(proxy.properties.values.none { "p@ss" in it || "agent" in it })
    }

    @Test
    fun `a socks or https proxy is refused with a warning rather than half-applied`() {
        val proxy = ProxyEnvironment.from(mapOf("HTTPS_PROXY" to "socks5://proxy:1080"))
        assertTrue(proxy.properties.isEmpty())
        assertEquals(1, proxy.warnings.size)
        assertTrue("HTTPS_PROXY" in proxy.warnings.single())
    }

    @Test
    fun `an explicit -D proxy wins as a whole, never mixed with the environment's`() {
        val set = mutableMapOf("https.proxyHost" to "explicit")
        ProxyEnvironment.from(mapOf("HTTPS_PROXY" to "http://env:3128", "HTTP_PROXY" to "http://env:80"))
            .install(getProperty = set::get, setProperty = { k, v -> set[k] = v }, warn = {})
        assertEquals("explicit", set["https.proxyHost"])
        assertNull(set["https.proxyPort"], "the environment's port must not be grafted onto -D's host")
        assertEquals("env", set["http.proxyHost"], "the other scheme is not affected")
    }

    @Test
    fun `NO_PROXY still applies beside an explicit -D http proxy`() {
        val set = mutableMapOf("http.proxyHost" to "explicit")
        ProxyEnvironment.from(mapOf("NO_PROXY" to "internal.example"))
            .install(getProperty = set::get, setProperty = { k, v -> set[k] = v }, warn = {})
        assertEquals("localhost|127.*|internal.example|*.internal.example", set["http.nonProxyHosts"])
    }

    @Test
    fun `no proxy variables means no properties at all`() {
        val proxy = ProxyEnvironment.from(mapOf("PATH" to "/usr/bin"))
        assertTrue(proxy.properties.isEmpty())
        assertTrue(proxy.warnings.isEmpty())
    }

    @Test
    fun `NO_PROXY matches a domain and its subdomains, and drops what the JDK cannot express`() {
        assertEquals(
            "localhost|127.*|*.localhost|internal.example|*.internal.example|10.0.0.1",
            nonProxyHosts("localhost, .internal.example, 10.0.0.1:8080, 192.168.0.0/16, ::1, [::1]:80"),
        )
        assertEquals("*", nonProxyHosts("*"))
        assertNull(nonProxyHosts(" , 10.0.0.0/8, ::1"))
    }
}
