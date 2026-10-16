package com.github.jvsena42.loopky.mcp.auth

import com.github.jvsena42.loopky.mcp.McpConfig
import com.github.jvsena42.loopky.mcp.security.receiveTextLimited
import com.github.jvsena42.loopky.mcp.store.McpStore
import com.github.jvsena42.loopky.mcp.store.RegisteredClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Clock

/**
 * RFC 7591, narrowed: public clients only, and every redirect URI an exact member of the
 * deployment's allowlist — Claude's callback at launch (#486). Open registration is what lets an
 * attacker mint a client; the allowlist is what keeps the code from reaching them.
 */
internal class Registration(
    private val store: McpStore,
    private val config: McpConfig,
    private val limits: OAuthLimits,
    private val minter: TokenMinter,
    private val clock: Clock,
) {
    suspend fun register(call: ApplicationCall) {
        val body = call.receiveTextLimited(limits.maxBodyBytes) ?: return
        val outcome = runCatching { parse(body) }
        outcome.onFailure {
            call.respondOAuthError(it as? OAuthException ?: OAuthException(OAuthException.INVALID_CLIENT_METADATA, "Malformed JSON"))
        }
        val client = outcome.getOrNull() ?: return
        store.clients.insert(client)
        call.respondJson(
            buildJsonObject {
                put("client_id", client.clientId)
                put("client_id_issued_at", client.createdAt.epochSecond)
                put("client_name", client.clientName)
                putJsonArray("redirect_uris") { client.redirectUris.forEach { add(it) } }
                put("token_endpoint_auth_method", "none")
                putJsonArray("grant_types") {
                    add("authorization_code")
                    add("refresh_token")
                }
                putJsonArray("response_types") { add("code") }
            },
            HttpStatusCode.Created,
        )
    }

    private fun parse(body: String): RegisteredClient {
        val json = Json.parseToJsonElement(body).jsonObject
        val redirectUris = json["redirect_uris"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        if (redirectUris.isEmpty() || redirectUris.any { it !in config.allowedRedirectUris }) {
            throw OAuthException(OAuthException.INVALID_REDIRECT_URI, "redirect_uris must be exactly one of the allowed callbacks")
        }
        val metadataProblem = metadataProblem(json)
        if (metadataProblem != null) throw OAuthException(OAuthException.INVALID_CLIENT_METADATA, metadataProblem)
        val name = json["client_name"]?.jsonPrimitive?.content?.trim().orEmpty()
            .ifEmpty { AuthorizePage.redirectDomain(redirectUris.first()) }
            .take(limits.maxClientNameLength)
        return RegisteredClient(
            clientId = CLIENT_ID_PREFIX + minter.opaque(CLIENT_ID_BYTES),
            clientName = name,
            redirectUris = redirectUris,
            createdAt = clock.instant(),
        )
    }

    private fun metadataProblem(json: JsonObject): String? {
        val authMethod = json["token_endpoint_auth_method"]?.jsonPrimitive?.content ?: "none"
        val grantTypes = json["grant_types"]?.jsonArray?.map { it.jsonPrimitive.content }
        val responseTypes = json["response_types"]?.jsonArray?.map { it.jsonPrimitive.content }
        return when {
            authMethod != "none" -> "Only public clients (token_endpoint_auth_method=none) are supported"
            grantTypes != null && grantTypes.any { it !in SUPPORTED_GRANT_TYPES } -> "Unsupported grant_types"
            responseTypes != null && responseTypes.any { it != "code" } -> "Unsupported response_types"
            else -> null
        }
    }

    private companion object {
        const val CLIENT_ID_PREFIX = "lkc_"
        const val CLIENT_ID_BYTES = 16
        val SUPPORTED_GRANT_TYPES = setOf("authorization_code", "refresh_token")
    }
}
