package com.github.jvsena42.loopky.cli

import java.net.Authenticator
import java.net.PasswordAuthentication
import java.net.URI
import java.net.URLDecoder

/**
 * The conventional proxy variables (`HTTPS_PROXY`, `HTTP_PROXY`, `ALL_PROXY`, `NO_PROXY`), carried
 * over to the JVM's own `https.proxyHost` & co.
 *
 * Two HTTP stacks live in this process and only one of them reads the environment: the Rust side
 * (relay, pkarr, homeserver) is reqwest, which honours these variables; the JVM side (Nexus, the
 * update check, `--check-images`) is `HttpURLConnection`, which does not. Behind an allowlist proxy
 * that split made `tag trending` time out after 15s and answer `ok` with no tags (#212). Mapping
 * them here keeps both stacks on the same route; an explicit `-D` property still wins.
 */
internal class ProxyEnvironment private constructor(
    val properties: Map<String, String>,
    val credentials: List<ProxyCredential>,
    val warnings: List<String>,
) {

    fun install(
        getProperty: (String) -> String? = System::getProperty,
        setProperty: (String, String) -> Unit = { key, value -> System.setProperty(key, value) },
        warn: (String) -> Unit = System.err::println,
    ) {
        applyProperties(getProperty, setProperty)
        if (credentials.isNotEmpty()) {
            // Basic is allowed on the CONNECT and nowhere else. An origin answering 407 from inside
            // the tunnel was checked on JDK 17, 21 and 25 and gets nothing; disabling Basic for plain
            // proxying keeps that true on paths nobody checked, and every URL here is https.
            if (getProperty(TUNNELING_DISABLED_SCHEMES) == null) setProperty(TUNNELING_DISABLED_SCHEMES, "")
            if (getProperty(PROXYING_DISABLED_SCHEMES) == null) setProperty(PROXYING_DISABLED_SCHEMES, "Basic")
            Authenticator.setDefault(ProxyAuthenticator(credentials))
        }
        warnings.forEach(warn)
    }

    /**
     * Per scheme, never per property: an explicit `-Dhttps.proxyHost` beside `HTTPS_PROXY`'s port
     * would describe a proxy neither of them named. Agent sandboxes set both, via
     * `JAVA_TOOL_OPTIONS`, and agree today — this keeps a drift between them from mixing.
     */
    private fun applyProperties(getProperty: (String) -> String?, setProperty: (String, String) -> Unit) {
        val explicitSchemes = SCHEMES.filter { scheme -> PROXY_KEYS.any { getProperty("$scheme.$it") != null } }
        properties
            .filterKeys { key -> key == NON_PROXY_HOSTS || explicitSchemes.none { key.startsWith("$it.proxy") } }
            .forEach { (key, value) -> if (getProperty(key) == null) setProperty(key, value) }
    }

    companion object {
        fun from(env: Map<String, String>): ProxyEnvironment {
            val properties = linkedMapOf<String, String>()
            val credentials = mutableListOf<ProxyCredential>()
            val warnings = mutableListOf<String>()

            // hyper-util's order, since reqwest carries the homeserver: the first variable that is
            // *set* wins, uppercase first, even when empty — and an empty one falls back to ALL_PROXY.
            fun firstSet(vararg names: String): String? = names.firstOrNull { it in env }
            val all = firstSet("ALL_PROXY", "all_proxy")?.takeIf { env.getValue(it).isNotBlank() }

            fun map(prefix: String, upper: String, lower: String) {
                val name = firstSet(upper, lower)?.takeIf { env.getValue(it).isNotBlank() } ?: all ?: return
                when (val parsed = parseProxy(env.getValue(name).trim())) {
                    is ParsedProxy.Usable -> {
                        properties["$prefix.proxyHost"] = parsed.host
                        properties["$prefix.proxyPort"] = parsed.port.toString()
                        parsed.credential?.let(credentials::add)
                    }
                    is ParsedProxy.Unusable -> warnings += "loopky: ignoring $name: ${parsed.reason}"
                }
            }

            map("https", "HTTPS_PROXY", "https_proxy")
            map("http", "HTTP_PROXY", "http_proxy")

            firstSet("NO_PROXY", "no_proxy")?.let { env.getValue(it) }
                ?.let(::nonProxyHosts)
                ?.let { properties["http.nonProxyHosts"] = it }

            return ProxyEnvironment(properties, credentials.distinct(), warnings)
        }
    }
}

internal data class ProxyCredential(val host: String, val port: Int, val user: String, val password: String)

private class ProxyAuthenticator(private val credentials: List<ProxyCredential>) : Authenticator() {
    override fun getPasswordAuthentication(): PasswordAuthentication? {
        if (requestorType != RequestorType.PROXY) return null
        val match = credentials.firstOrNull {
            it.host.equals(requestingHost, ignoreCase = true) && it.port == requestingPort
        } ?: return null
        return PasswordAuthentication(match.user, match.password.toCharArray())
    }
}

private sealed interface ParsedProxy {
    data class Usable(val host: String, val port: Int, val credential: ProxyCredential?) : ParsedProxy
    data class Unusable(val reason: String) : ParsedProxy
}

private fun parseProxy(value: String): ParsedProxy {
    // A bare `host:port` is an HTTP proxy, as it is to curl and reqwest.
    val uri = runCatching { URI(if ("://" in value) value else "http://$value") }
        .getOrElse { return ParsedProxy.Unusable("not a proxy URL") }
    if (uri.scheme?.lowercase() != "http") {
        return ParsedProxy.Unusable(
            "a ${uri.scheme}:// proxy is not supported; only http:// proxies are (CONNECT for https)",
        )
    }
    val host = uri.host ?: return ParsedProxy.Unusable("no host in the proxy URL")
    val port = if (uri.port == -1) DEFAULT_HTTP_PORT else uri.port
    val credential = uri.rawUserInfo?.let { info ->
        val user = info.substringBefore(':')
        val password = info.substringAfter(':', missingDelimiterValue = "")
        ProxyCredential(host, port, decode(user), decode(password))
    }
    return ParsedProxy.Usable(host, port, credential)
}

private fun decode(value: String): String = URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8)

/**
 * `NO_PROXY` in the JDK's `http.nonProxyHosts` form, which covers https too. An entry matches the
 * domain and its subdomains, as in reqwest; CIDR ranges and IPv6 literals have no working JDK
 * equivalent and are dropped. Setting the property replaces the JDK's default, so that default
 * (loopback) is kept at the front.
 */
internal fun nonProxyHosts(noProxy: String): String? {
    val entries = noProxy.split(',').map { it.trim() }
        // A CIDR, and anything with more than one colon (IPv6), cannot be expressed.
        .filter { it.isNotEmpty() && '/' !in it && it.count { c -> c == ':' } <= 1 && '[' !in it }
    if (entries.any { it == "*" }) return "*"
    val patterns = entries.flatMap { entry ->
        val host = entry.removePrefix("*").removePrefix(".").substringBefore(':')
        when {
            host.isEmpty() -> emptyList()
            host.all { it.isDigit() || it == '.' } -> listOf(host)
            else -> listOf(host, "*.$host")
        }
    }
    if (patterns.isEmpty()) return null
    return (JDK_DEFAULT_NON_PROXY_HOSTS + patterns).distinct().joinToString("|")
}

private const val DEFAULT_HTTP_PORT = 80
private val PROXY_KEYS = listOf("proxyHost", "proxyPort")

private val SCHEMES = listOf("https", "http")

/** Shares the `http.` prefix but is its own setting, not the http proxy's. */
private const val NON_PROXY_HOSTS = "http.nonProxyHosts"

private const val TUNNELING_DISABLED_SCHEMES = "jdk.http.auth.tunneling.disabledSchemes"
private const val PROXYING_DISABLED_SCHEMES = "jdk.http.auth.proxying.disabledSchemes"
private val JDK_DEFAULT_NON_PROXY_HOSTS = listOf("localhost", "127.*")
