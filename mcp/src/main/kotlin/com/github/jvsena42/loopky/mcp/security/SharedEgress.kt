package com.github.jvsena42.loopky.mcp.security

import java.net.InetAddress

/**
 * Address ranges where one address stands for many callers — Anthropic's outbound range, which
 * every Claude user's connector calls leave from (#538). A limit keyed per address there throttles
 * every Loopky user together, so the routes Claude's backend calls key on a credential instead.
 *
 * The same `LOOPKY_MCP_SHARED_EGRESS` the edge reads (`deploy/Caddyfile.example`), with the same
 * default: CIDRs separated by spaces or commas. Parsed without DNS; a name is never resolved.
 */
class SharedEgress(private val ranges: List<Cidr>) {

    fun contains(remoteHost: String): Boolean {
        val bytes = literalBytes(remoteHost) ?: return false
        return ranges.any { it.contains(bytes) }
    }

    class Cidr(private val network: ByteArray, private val prefix: Int) {
        fun contains(address: ByteArray): Boolean {
            if (address.size != network.size) return false
            val whole = prefix / BITS
            if ((0 until whole).any { address[it] != network[it] }) return false
            val rest = prefix % BITS
            if (rest == 0) return true
            val mask = (BYTE shl (BITS - rest)) and BYTE
            return (address[whole].toInt() and mask) == (network[whole].toInt() and mask)
        }
    }

    companion object {
        /** platform.claude.com/docs/en/api/ip-addresses. Change it here and in the Caddyfile together. */
        const val ANTHROPIC = "160.79.104.0/21"

        val NONE = SharedEgress(emptyList())

        fun parse(spec: String): SharedEgress = SharedEgress(
            spec.split(' ', ',').map { it.trim() }.filter { it.isNotEmpty() }.map { entry ->
                val address = entry.substringBefore('/')
                val bytes =
                    requireNotNull(literalBytes(address)) { "LOOPKY_MCP_SHARED_EGRESS: $entry is not an address" }
                val prefix = entry.substringAfter('/', (bytes.size * BITS).toString()).toIntOrNull()
                require(prefix != null && prefix in 0..bytes.size * BITS) { "LOOPKY_MCP_SHARED_EGRESS: $entry has a bad prefix" }
                Cidr(bytes, prefix)
            },
        )

        private fun literalBytes(host: String): ByteArray? {
            val literal = host.trim('[', ']')
            val isLiteral = ':' in literal || literal.matches(IPV4)
            if (!isLiteral) return null
            return runCatching { InetAddress.getByName(literal).address }.getOrNull()
        }

        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
        private const val BITS = 8
        private const val BYTE = 0xff
    }
}
