package com.github.jvsena42.loopky.mcp.security

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText

/**
 * The 401 that starts Claude's OAuth discovery (RFC 9728 §5.1): `resource_metadata` points at the
 * protected-resource document, and [error] is `invalid_token` once a token was presented and
 * refused, so the client reconnects rather than retries.
 */
suspend fun ApplicationCall.respondBearerChallenge(resourceMetadataUrl: String, error: String? = null) {
    val params = buildString {
        append("resource_metadata=\"").append(resourceMetadataUrl).append('"')
        if (error != null) append(", error=\"").append(error).append('"')
    }
    response.header(HttpHeaders.WWWAuthenticate, "Bearer $params")
    response.header(HttpHeaders.CacheControl, "no-store")
    respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
}

/** Where the protected-resource document for `/mcp` lives, per RFC 9728 §3.1's path-suffixed form. */
fun resourceMetadataUrl(publicUrl: String): String = "$publicUrl/.well-known/oauth-protected-resource/mcp"
