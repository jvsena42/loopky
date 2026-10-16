package com.github.jvsena42.loopky.mcp.security

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import java.net.URI

/**
 * The MCP spec's DNS-rebinding defence for Streamable HTTP: a browser always sends `Origin`, so one
 * outside the allowlist is a page on some other site driving this server. No `Origin` at all is a
 * non-browser client (Claude's connector, curl) and is allowed through.
 */
class OriginValidator(allowedOrigins: Set<String>) {
    private val allowed: Set<String> = allowedOrigins.mapNotNull(::normalize).toSet()

    fun allows(origin: String?): Boolean = origin == null || normalize(origin)?.let { it in allowed } == true

    private fun normalize(origin: String): String? {
        val uri = runCatching { URI(origin.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        val bareOrigin = uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.rawPath.isEmpty() || uri.rawPath == "/")
        if (scheme == null || host == null || !bareOrigin) return null
        val port = if (uri.port == -1 || uri.port == defaultPort(scheme)) "" else ":${uri.port}"
        return "$scheme://$host$port"
    }

    private fun defaultPort(scheme: String) = when (scheme) {
        "https" -> HTTPS_PORT
        "http" -> HTTP_PORT
        else -> -1
    }

    companion object {
        const val CLAUDE_ORIGIN = "https://claude.ai"
        private const val HTTPS_PORT = 443
        private const val HTTP_PORT = 80
    }
}

class OriginGuardConfig {
    lateinit var validator: OriginValidator
}

/** Route-scoped: `install(OriginGuard) { validator = … }` answers a bad `Origin` with 403 before the handler runs. */
val OriginGuard = createRouteScopedPlugin("OriginGuard", ::OriginGuardConfig) {
    val validator = pluginConfig.validator
    onCall { call ->
        if (!validator.allows(call.request.header("Origin"))) {
            call.respondText("Origin not allowed", status = HttpStatusCode.Forbidden)
        }
    }
}
