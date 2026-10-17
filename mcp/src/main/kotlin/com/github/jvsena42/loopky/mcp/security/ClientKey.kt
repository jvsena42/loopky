package com.github.jvsena42.loopky.mcp.security

import java.net.Inet6Address
import java.net.InetAddress

/**
 * What a per-address limit counts under. An IPv4 address is itself; an IPv6 address is its /64,
 * because that is what a host is handed and it can rotate through 2^64 addresses inside it for
 * free — a limit keyed on the full address is no limit at all there. Anything that is not an
 * address literal (a proxy that forwarded a name) is used as given; no name is ever resolved.
 */
object ClientKey {
    fun of(remoteHost: String): String {
        if (':' !in remoteHost) return remoteHost
        val literal = remoteHost.trim('[', ']')
        val address = runCatching { InetAddress.getByName(literal) }.getOrNull() as? Inet6Address ?: return remoteHost
        val hextets = address.address.take(PREFIX_BYTES).chunked(2) { (hi, lo) ->
            "%x".format((hi.toInt() and BYTE) shl BITS_PER_BYTE or (lo.toInt() and BYTE))
        }
        return hextets.joinToString(":") + "::/64"
    }

    private const val PREFIX_BYTES = 8
    private const val BYTE = 0xff
    private const val BITS_PER_BYTE = 8
}
