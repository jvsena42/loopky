package com.github.jvsena42.loopky.cli

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.security.KeyStore
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A local HTTPS server whose certificate is signed by a CA the JDK has never heard of — what an
 * intercepting proxy looks like to this process (#362). `proxy-ca.pem` and `localhost.p12` were
 * made with `openssl` for this test; the CA's key was not kept.
 */
class CertificateEnvironmentTest {

    private lateinit var server: HttpsServer
    private val caFile = resource("tls/proxy-ca.pem")

    @BeforeTest
    fun start() {
        val keys = KeyStore.getInstance("PKCS12").apply {
            resource("tls/localhost.p12").inputStream().use { load(it, PASSWORD) }
        }
        val serverContext = SSLContext.getInstance("TLS").apply {
            init(KeyManagerFactory.getInstance("PKIX").apply { init(keys, PASSWORD) }.keyManagers, null, null)
        }
        server = HttpsServer.create(InetSocketAddress("localhost", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(serverContext)
            createContext("/") { exchange -> exchange.sendResponseHeaders(204, -1); exchange.close() }
            start()
        }
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun statusWith(context: SSLContext?): Int {
        val connection = URI("https://localhost:${server.address.port}/").toURL().openConnection() as HttpsURLConnection
        context?.let { connection.sslSocketFactory = it.socketFactory }
        return try {
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `the JDK alone refuses the intercepting CA`() {
        assertFailsWith<SSLHandshakeException> { statusWith(null) }
    }

    @Test
    fun `SSL_CERT_FILE makes the JVM trust it`() {
        val context = CertificateEnvironment.from(mapOf("SSL_CERT_FILE" to caFile.path)).sslContext()

        assertEquals(204, statusWith(context))
    }

    @Test
    fun `SSL_CERT_DIR does too, and an unreadable entry beside it only warns`() {
        val dir = createTempDirectory("certs").toFile().apply { deleteOnExit() }
        caFile.copyTo(File(dir, "ab12cd34.0"))
        File(dir, "README").writeText("not a certificate")

        val env = CertificateEnvironment.from(mapOf("SSL_CERT_DIR" to "/nonexistent${File.pathSeparator}$dir"))

        assertEquals(204, statusWith(env.sslContext()))
        assertEquals(1, env.warnings.size)
    }

    /** The variable usually names the whole system bundle, comments and all, not just one CA. */
    @Test
    fun `a bundle with comments between certificates is read`() {
        val bundle = File.createTempFile("bundle", ".pem").apply {
            deleteOnExit()
            writeText("# Loopky test bundle\n\n${caFile.readText()}\n# trailing note\n")
        }

        assertEquals(204, statusWith(CertificateEnvironment.from(mapOf("SSL_CERT_FILE" to bundle.path)).sslContext()))
    }

    @Test
    fun `the public roots are kept alongside the extra ones`() {
        val layered = requireNotNull(CertificateEnvironment.from(mapOf("SSL_CERT_FILE" to caFile.path)).trustManager())

        assertEquals(trustManagerOf(null).acceptedIssuers.size + 1, layered.acceptedIssuers.size)
    }

    @Test
    fun `nothing set, or a missing file, changes nothing`() {
        assertNull(CertificateEnvironment.from(emptyMap()).sslContext())
        val missing = CertificateEnvironment.from(mapOf("SSL_CERT_FILE" to "/nonexistent.pem"))
        assertNull(missing.sslContext())
        assertTrue(missing.warnings.single().startsWith("loopky: ignoring SSL_CERT_FILE"))
    }

    private companion object {
        val PASSWORD = "loopky".toCharArray()

        fun resource(name: String): File =
            File(requireNotNull(CertificateEnvironmentTest::class.java.classLoader.getResource(name)).toURI())
    }
}
