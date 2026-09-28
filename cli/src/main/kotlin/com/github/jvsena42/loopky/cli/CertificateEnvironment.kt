package com.github.jvsena42.loopky.cli

import java.io.File
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager

/**
 * `SSL_CERT_FILE` and `SSL_CERT_DIR`, trusted by the JVM half of the process on top of its own store.
 *
 * Behind a TLS-intercepting proxy the proxy's CA is trusted system-wide and named by these variables,
 * but the JDK reads neither: the jar can be pointed at it with `-Djavax.net.ssl.trustStore`, the
 * native binary cannot, since it ignores `JAVA_TOOL_OPTIONS` (#362). Layered rather than replacing,
 * so a variable naming only the proxy's CA does not take the public roots away. The Rust half keeps
 * its bundled roots whatever this does — that is pubky/pubky-homeserver#648.
 */
internal class CertificateEnvironment private constructor(
    val certificates: List<X509Certificate>,
    val warnings: List<String>,
) {

    fun install(warn: (String) -> Unit = System.err::println) {
        warnings.forEach(warn)
        val context = sslContext() ?: return
        SSLContext.setDefault(context)
        HttpsURLConnection.setDefaultSSLSocketFactory(context.socketFactory)
    }

    /** The JDK's default trust plus [certificates], or null when there is nothing to add. */
    fun trustManager(): X509ExtendedTrustManager? {
        if (certificates.isEmpty()) return null
        val extra = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            certificates.forEachIndexed { index, certificate -> setCertificateEntry("env-$index", certificate) }
        }
        return LayeredTrustManager(trustManagerOf(null), trustManagerOf(extra))
    }

    fun sslContext(): SSLContext? = trustManager()?.let { trust ->
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
    }

    companion object {
        fun from(env: Map<String, String>): CertificateEnvironment {
            val certificates = mutableListOf<X509Certificate>()
            val warnings = mutableListOf<String>()
            env["SSL_CERT_FILE"]?.takeIf { it.isNotBlank() }?.let { path ->
                val found = readPem(File(path))
                if (found == null) {
                    warnings += "loopky: ignoring SSL_CERT_FILE: $path cannot be read"
                } else if (found.isEmpty()) {
                    warnings += "loopky: ignoring SSL_CERT_FILE: $path holds no PEM certificate"
                }
                certificates += found.orEmpty()
            }
            // OpenSSL's separator; a directory holds one certificate per file plus hash links to them.
            env["SSL_CERT_DIR"]?.split(File.pathSeparatorChar)?.filter { it.isNotBlank() }?.forEach { dir ->
                val files = File(dir).listFiles()
                if (files == null) {
                    warnings += "loopky: ignoring SSL_CERT_DIR entry $dir: not a readable directory"
                } else {
                    files.filter { it.isFile }.sortedBy { it.name }.forEach { certificates += readPem(it).orEmpty() }
                }
            }
            return CertificateEnvironment(certificates.distinct(), warnings)
        }
    }
}

/**
 * Accepts a chain either manager accepts. The default is asked first, so a public host is verified
 * exactly as it was before and the extra roots only ever widen what is trusted.
 */
private class LayeredTrustManager(
    private val primary: X509ExtendedTrustManager,
    private val extra: X509ExtendedTrustManager,
) : X509ExtendedTrustManager() {

    private inline fun either(check: (X509ExtendedTrustManager) -> Unit) {
        try {
            check(primary)
        } catch (rejected: CertificateException) {
            try {
                check(extra)
            } catch (alsoRejected: CertificateException) {
                rejected.addSuppressed(alsoRejected)
                throw rejected
            }
        }
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
        either { it.checkServerTrusted(chain, authType) }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        either { it.checkServerTrusted(chain, authType, socket) }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        either { it.checkServerTrusted(chain, authType, engine) }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        either { it.checkClientTrusted(chain, authType) }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        either { it.checkClientTrusted(chain, authType, socket) }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        either { it.checkClientTrusted(chain, authType, engine) }

    override fun getAcceptedIssuers(): Array<X509Certificate> = primary.acceptedIssuers + extra.acceptedIssuers
}

internal fun trustManagerOf(store: KeyStore?): X509ExtendedTrustManager =
    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(store) }
        .trustManagers
        .filterIsInstance<X509ExtendedTrustManager>()
        .first()

/**
 * Every `CERTIFICATE` block in [file], or null when it cannot be read. Parsed block by block because
 * bundles carry comments and other PEM types between certificates, which `generateCertificates`
 * rejects outright.
 */
private fun readPem(file: File): List<X509Certificate>? {
    val text = runCatching { file.readText(Charsets.US_ASCII) }.getOrNull() ?: return null
    val factory = CertificateFactory.getInstance("X.509")
    return PEM_CERTIFICATE.findAll(text).mapNotNull { block ->
        runCatching {
            val der = Base64.getMimeDecoder().decode(block.groupValues[1])
            factory.generateCertificate(der.inputStream()) as X509Certificate
        }.getOrNull()
    }.toList()
}

private val PEM_CERTIFICATE = Regex("-----BEGIN CERTIFICATE-----([A-Za-z0-9+/=\\s]+?)-----END CERTIFICATE-----")
