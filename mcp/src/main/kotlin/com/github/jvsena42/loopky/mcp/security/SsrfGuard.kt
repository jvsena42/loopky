package com.github.jvsena42.loopky.mcp.security

import java.net.Inet6Address
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

class SsrfRefusedException(message: String) : IllegalArgumentException(message)

/** A URL whose every resolved address passed [SsrfGuard]; [address] is the one a connection must use. */
class VettedUrl(val uri: URI, val host: String, val port: Int, val address: InetAddress) {
    /**
     * Connects to [address] by literal, so the name is never resolved a second time (a DNS answer
     * can change between the check and the connect), while SNI, certificate verification and the
     * `Host` header all still name [host]. Redirects are off: a `Location` has to be vetted again.
     */
    fun openPinnedConnection(connectTimeoutMillis: Int = CONNECT_TIMEOUT_MS, readTimeoutMillis: Int = READ_TIMEOUT_MS): HttpsURLConnection {
        val literal = if (address is Inet6Address) "[${address.hostAddress}]" else address.hostAddress
        val pinned = URI("https", null, literal, port, uri.rawPath.ifEmpty { "/" }, uri.rawQuery, null)
        val connection = URL(pinned.toString()).openConnection() as HttpsURLConnection
        connection.sslSocketFactory = SniPinningSocketFactory(HttpsURLConnection.getDefaultSSLSocketFactory(), host)
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.setRequestProperty("Host", if (port == HTTPS_PORT) host else "$host:$port")
        check(connection.getRequestProperty("Host") != null) {
            "The JDK dropped the Host header: SsrfGuard.enablePinnedHostHeader() must run before any HttpURLConnection"
        }
        return connection
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
        const val HTTPS_PORT = 443
    }
}

/** Layers TLS over the already-pinned plain socket under the real host name, not the IP the URL carries. */
private class SniPinningSocketFactory(private val delegate: SSLSocketFactory, private val realHost: String) : SSLSocketFactory() {
    override fun createSocket(s: Socket, host: String?, port: Int, autoClose: Boolean): Socket =
        delegate.createSocket(s, realHost, port, autoClose)

    override fun createSocket(host: String?, port: Int): Socket = delegate.createSocket(host, port)

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        delegate.createSocket(host, port, localHost, localPort)

    override fun createSocket(host: InetAddress?, port: Int): Socket = delegate.createSocket(host, port)

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        delegate.createSocket(address, port, localAddress, localPort)

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
}

/**
 * Refuses any URL that could reach the box itself, the other app on it, or the cloud metadata
 * service (#486): non-https, and every address that is loopback, link-local, private, CGNAT,
 * multicast, reserved or an IPv6 form that embeds one of those. Every resolved address is checked,
 * because a name resolving to one public and one private address is the classic bypass.
 */
class SsrfGuard(private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }) {
    fun vet(url: String): Result<VettedUrl> = runCatching {
        if (url.length > MAX_URL_LENGTH) refuse("URL is too long")
        val uri = runCatching { URI(url) }.getOrElse { refuse("Not a valid URL") }
        if (!uri.scheme.equals("https", ignoreCase = true)) refuse("Only https:// URLs are fetched")
        if (uri.rawUserInfo != null) refuse("URLs with credentials are refused")
        val host = uri.host?.trim('[', ']')?.lowercase() ?: refuse("URL has no host")
        val port = if (uri.port == -1) HTTPS_PORT else uri.port
        val addresses = runCatching { if (isLiteral(host)) listOf(InetAddress.getByName(host)) else resolve(host) }
            .getOrElse { refuse("$host does not resolve") }
        if (addresses.isEmpty()) refuse("$host does not resolve")
        addresses.firstOrNull(::isForbidden)?.let { refuse("$host resolves to a non-public address") }
        VettedUrl(uri, host, port, addresses.first())
    }

    private fun refuse(reason: String): Nothing = throw SsrfRefusedException(reason)

    /** `InetAddress.getByName` never queries DNS for a literal, so these skip the resolver. */
    private fun isLiteral(host: String) = ':' in host || host.all { it.isDigit() || it == '.' }

    companion object {
        private const val MAX_URL_LENGTH = 2_048
        private const val HTTPS_PORT = 443

        /**
         * `HttpURLConnection` silently ignores `Host` unless this property is set before the class
         * loads; a pinned connection needs it, so call this once at startup.
         */
        fun enablePinnedHostHeader() {
            System.setProperty("sun.net.http.allowRestrictedHeaders", "true")
        }

        fun isForbidden(address: InetAddress): Boolean {
            val unwrapped = embeddedIpv4(address) ?: address
            if (unwrapped !== address && isForbidden(unwrapped)) return true
            return unwrapped.isAnyLocalAddress || unwrapped.isLoopbackAddress || unwrapped.isLinkLocalAddress ||
                unwrapped.isSiteLocalAddress || unwrapped.isMulticastAddress ||
                FORBIDDEN_RANGES.any { it.contains(unwrapped) }
        }

        /** IPv4-mapped (`::ffff:a.b.c.d`) and NAT64 (`64:ff9b::a.b.c.d`) addresses are their IPv4 for this purpose. */
        private fun embeddedIpv4(address: InetAddress): InetAddress? {
            if (address !is Inet6Address) return null
            val bytes = address.address
            val embedded = EMBEDDING_RANGES.any { it.contains(address) }
            if (!embedded) return null
            return InetAddress.getByAddress(bytes.copyOfRange(bytes.size - IPV4_BYTES, bytes.size))
        }

        private const val IPV4_BYTES = 4
        private const val IPV6_BYTES = 16

        private val FORBIDDEN_RANGES = listOf(
            "0.0.0.0/8", // "this" network
            "10.0.0.0/8",
            "100.64.0.0/10", // CGNAT
            "127.0.0.0/8",
            "169.254.0.0/16", // link-local, 169.254.169.254 among it
            "172.16.0.0/12",
            "192.0.0.0/24",
            "192.0.2.0/24", // TEST-NET-1
            "192.168.0.0/16",
            "198.18.0.0/15", // benchmarking
            "198.51.100.0/24", // TEST-NET-2
            "203.0.113.0/24", // TEST-NET-3
            "224.0.0.0/4", // multicast
            "240.0.0.0/4", // reserved, broadcast included
            "::/128",
            "::1/128",
            "fc00::/7", // ULA, fd00:ec2::254 among it
            "fe80::/10", // link-local
            "fec0::/10", // deprecated site-local
            "ff00::/8", // multicast
            "2001:db8::/32", // documentation
        ).map(Cidr::parse)

        // Built from bytes: `InetAddress.getByName("::ffff:0:0")` already answers an Inet4Address.
        private val EMBEDDING_RANGES = listOf(
            Cidr(ByteArray(IPV6_BYTES).also { it[10] = -1; it[11] = -1 }, 96), // ::ffff:0:0/96
            Cidr(ByteArray(IPV6_BYTES).also { it[1] = 0x64; it[2] = -1; it[3] = 0x9b.toByte() }, 96), // 64:ff9b::/96
        )
    }
}

private class Cidr(private val network: ByteArray, private val prefixBits: Int) {
    fun contains(address: InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size != network.size) return false
        val wholeBytes = prefixBits / Byte.SIZE_BITS
        for (i in 0 until wholeBytes) if (bytes[i] != network[i]) return false
        val remaining = prefixBits % Byte.SIZE_BITS
        if (remaining == 0) return true
        val mask = (0xff shl (Byte.SIZE_BITS - remaining)) and 0xff
        return (bytes[wholeBytes].toInt() and mask) == (network[wholeBytes].toInt() and mask)
    }

    companion object {
        fun parse(cidr: String): Cidr {
            val (network, bits) = cidr.split('/')
            return Cidr(InetAddress.getByName(network).address, bits.toInt())
        }
    }
}
