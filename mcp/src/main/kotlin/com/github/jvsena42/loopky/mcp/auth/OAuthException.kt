package com.github.jvsena42.loopky.mcp.auth

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** An RFC 6749 §5.2 error: [error] is the wire code, [status] 400 unless the client itself failed to authenticate. */
class OAuthException(
    val error: String,
    val description: String,
    val status: HttpStatusCode = HttpStatusCode.BadRequest,
) : RuntimeException("$error: $description") {
    fun toJson(): JsonObject = buildJsonObject {
        put("error", error)
        put("error_description", description)
    }

    companion object {
        const val INVALID_REQUEST = "invalid_request"
        const val INVALID_CLIENT = "invalid_client"
        const val INVALID_GRANT = "invalid_grant"
        const val UNSUPPORTED_GRANT_TYPE = "unsupported_grant_type"
        const val INVALID_TARGET = "invalid_target"
        const val INVALID_REDIRECT_URI = "invalid_redirect_uri"
        const val INVALID_CLIENT_METADATA = "invalid_client_metadata"
        const val UNSUPPORTED_RESPONSE_TYPE = "unsupported_response_type"
        const val TEMPORARILY_UNAVAILABLE = "temporarily_unavailable"

        fun invalidRequest(description: String) = OAuthException(INVALID_REQUEST, description)

        fun invalidGrant(description: String) = OAuthException(INVALID_GRANT, description)

        fun invalidClient(description: String) = OAuthException(INVALID_CLIENT, description, HttpStatusCode.Unauthorized)
    }
}

fun invalidRequest(description: String): Nothing = throw OAuthException.invalidRequest(description)

fun invalidGrant(description: String): Nothing = throw OAuthException.invalidGrant(description)

fun invalidTarget(): Nothing = throw OAuthException(OAuthException.INVALID_TARGET, "Unknown resource")

/** Every OAuth response carries `Cache-Control: no-store` (RFC 6749 §5.1); a token in a cache is a token on disk. */
fun ApplicationCall.noStore() {
    response.header(HttpHeaders.CacheControl, "no-store")
    response.header(HttpHeaders.Pragma, "no-cache")
    response.header("X-Content-Type-Options", "nosniff")
}

suspend fun ApplicationCall.respondJson(json: JsonObject, status: HttpStatusCode = HttpStatusCode.OK) {
    noStore()
    respondText(json.toString(), ContentType.Application.Json, status)
}

suspend fun ApplicationCall.respondOAuthError(error: OAuthException) = respondJson(error.toJson(), error.status)
