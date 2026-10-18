package com.github.jvsena42.loopky.mcp.tools

import java.net.IDN
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Decides whether the server may touch a caller-supplied URL — an SSRF guard, in the shape the
 * OAuth half provides one in (#486).
 *
 * **No whitelisted tool fetches a caller's URL today.** The audit behind the commit that added
 * this: `--check-images` and `card check-images` (`ImageProbe`, the only `HttpURLConnection` in
 * `:cli-core`) are refused; a cover or card picture URL is validated as a string by
 * `requireRenderableImageUrl` and stored for the phones to fetch; an import's remote images are
 * `DraftCardImage(url)` wrapped as a ref, never downloaded, and an `.apkg`'s bytes are refused
 * with the format; `tag trending` reads the deployment's own Nexus; the homeserver is the one
 * resolved from the user's key. Every picture URL a call carries still goes through this, so that
 * a future fetch path cannot be reached by an address the guard would refuse, and so a stored deck
 * never points a phone at a private address.
 */
fun interface UrlGuard {
    /** Why [url] is refused, or null to allow it. */
    suspend fun refusal(url: String): String?

    companion object {
        /**
         * Syntax only, no DNS: `https://`, a host that is a name or a public IP literal, no
         * credentials. The OAuth half's guard resolves the name and checks the address too.
         */
        val PUBLIC_HTTPS: UrlGuard = UrlGuard { url -> syntacticRefusal(url) }

        fun syntacticRefusal(url: String): String? {
            val uri = runCatching { URI(url) }.getOrElse { return "is not a valid URL" }
            val host = uri.host?.trim('[', ']')?.lowercase()?.trimEnd('.') ?: return "has no host"
            return when {
                !uri.scheme.equals("https", ignoreCase = true) -> "must be an https:// address"
                uri.rawUserInfo != null -> "carries credentials, which an address on a card never should"
                host in LOCAL_NAMES || LOCAL_SUFFIXES.any { host.endsWith(it) } -> "points at this server's own network"
                host.isNonPublicAddress() -> "is a private or local IP address"
                else -> null
            }
        }

        private val LOCAL_NAMES = setOf("localhost", "localhost.localdomain", "0.0.0.0", "metadata.google.internal")
        private val LOCAL_SUFFIXES = listOf(".localhost", ".local", ".internal", ".home.arpa", ".lan")

        /**
         * Reads the host the way a browser's URL parser does, so `2130706433`, `0x7f000001` and
         * `0177.0.0.1` are the loopback they resolve to rather than names (#535): a host whose last
         * label is a number is an IPv4 address, and one that does not parse as one is refused.
         */
        private fun String.isNonPublicAddress(): Boolean {
            val ascii = runCatching { IDN.toASCII(this) }.getOrDefault(this)
            if (':' in ascii) return ascii.isNonPublicIpv6()
            if (!ascii.substringAfterLast('.').isIpv4Number()) return false
            return ipv4Octets(ascii)?.isNonPublicIpv4() ?: true
        }

        private fun String.isIpv4Number(): Boolean =
            isNotEmpty() && (all { it.isDigit() } || (startsWith("0x") && drop(2).all { it.isHexDigit() }))

        /** WHATWG's IPv4 parser: up to four parts, each decimal, `0x` hex or leading-zero octal. */
        @Suppress("MagicNumber", "ReturnCount")
        private fun ipv4Octets(host: String): List<Int>? {
            val parts = host.split('.')
            if (parts.size > IPV4_OCTETS) return null
            val numbers = parts.map { it.ipv4Part() ?: return null }
            val leading = numbers.dropLast(1)
            if (leading.any { it > OCTET_MAX }) return null
            val last = numbers.last()
            if (last >= 1L shl (8 * (IPV4_OCTETS - leading.size))) return null
            val value = leading.foldIndexed(last) { i, acc, octet -> acc + (octet shl (8 * (IPV4_OCTETS - 1 - i))) }
            return (IPV4_OCTETS - 1 downTo 0).map { ((value shr (8 * it)) and 0xFF).toInt() }
        }

        @Suppress("MagicNumber")
        private fun String.ipv4Part(): Long? = when {
            startsWith("0x") -> drop(2).ifEmpty { "0" }.takeIf { it.length <= 8 }?.toLongOrNull(16)
            length > 1 && startsWith("0") -> drop(1).takeIf { it.length <= 11 }?.toLongOrNull(8)
            else -> takeIf { it.length <= 10 }?.toLongOrNull()
        }

        private fun Char.isHexDigit(): Boolean = isDigit() || this in 'a'..'f'

        @Suppress("MagicNumber", "CyclomaticComplexMethod")
        private fun List<Int>.isNonPublicIpv4(): Boolean {
            val (a, b) = this
            return a == 0 || a == 10 || a == 127 ||
                (a == 100 && b in 64..127) ||
                (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 198 && b in 18..19) ||
                a >= 224
        }

        /**
         * Through [InetAddress], which parses a literal without DNS, so `0:0:0:0:0:0:0:1` is `::1`.
         * Anything IPv4-mapped or -compatible is refused whatever it embeds, and NAT64 when it embeds a
         * private address.
         */
        @Suppress("MagicNumber")
        private fun String.isNonPublicIpv6(): Boolean {
            val address = runCatching { InetAddress.getByName("[$this]") }.getOrElse { return true }
            if (address is Inet4Address) return true
            val bytes = (address as? Inet6Address)?.address ?: return true
            val first = bytes[0].toInt() and 0xFF
            val second = bytes[1].toInt() and 0xFF
            val embedded = bytes.drop(12).map { it.toInt() and 0xFF }
            return bytes.take(12).all { it.toInt() == 0 } ||
                (bytes.take(12) == NAT64_PREFIX && embedded.isNonPublicIpv4()) ||
                (first and 0xFE) == 0xFC ||
                (first == 0xFE && (second and 0xC0) in setOf(0x80, 0xC0)) ||
                first == 0xFF
        }

        /** 64:ff9b::/96, the well-known NAT64 prefix: the IPv4 address it embeds is the one reached. */
        private val NAT64_PREFIX: List<Byte> = listOf(0, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0).map { it.toByte() }

        private const val IPV4_OCTETS = 4
        private const val OCTET_MAX = 255
    }
}
