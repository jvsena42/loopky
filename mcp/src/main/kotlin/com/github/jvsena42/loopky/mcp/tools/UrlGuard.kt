package com.github.jvsena42.loopky.mcp.tools

import java.net.IDN
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
            val host = uri.host?.trim('[', ']')?.lowercase() ?: return "has no host"
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

        private fun String.isNonPublicAddress(): Boolean {
            val ascii = runCatching { IDN.toASCII(this) }.getOrDefault(this)
            ipv4Octets(ascii)?.let { return it.isNonPublicIpv4() }
            if (':' in ascii) return ascii.isNonPublicIpv6()
            return false
        }

        private fun ipv4Octets(host: String): List<Int>? {
            val parts = host.split('.')
            if (parts.size != IPV4_OCTETS) return null
            val octets = parts.map { it.toIntOrNull() ?: return null }
            return octets.takeIf { octets.all { it in 0..OCTET_MAX } }
        }

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

        private fun String.isNonPublicIpv6(): Boolean {
            val lower = lowercase()
            return lower == "::" || lower == "::1" || lower.startsWith("fe8") || lower.startsWith("fe9") ||
                lower.startsWith("fea") || lower.startsWith("feb") || lower.startsWith("fc") ||
                lower.startsWith("fd") || lower.startsWith("::ffff:") || lower.startsWith("ff")
        }

        private const val IPV4_OCTETS = 4
        private const val OCTET_MAX = 255
    }
}
