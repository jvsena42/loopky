package com.github.jvsena42.loopky.mcp.auth

import com.github.jvsena42.loopky.mcp.AccessTokenVerifier
import com.github.jvsena42.loopky.mcp.SessionSecrets
import com.github.jvsena42.loopky.mcp.security.RateLimitByIp
import com.github.jvsena42.loopky.mcp.security.RedactingLogger
import com.github.jvsena42.loopky.mcp.security.TokenBucketLimiter
import com.github.jvsena42.loopky.mcp.security.receiveTextLimited
import com.github.jvsena42.loopky.mcp.vault.VaultSessionSecrets
import com.github.jvsena42.loopky.util.runSuspendCatching
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.parseUrlEncodedParameters
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The OAuth 2.1 authorization server in front of `/mcp` (#486): metadata, dynamic registration,
 * the Ring sign-in page, tokens and revocation. [verifier], [sessionSecrets] and [unlinker] are
 * what the MCP half is handed; nothing else here is reachable from it.
 */
class OAuthServer(private val deps: OAuthDeps) {
    private val config = deps.config
    private val store = deps.store
    private val minter = TokenMinter()
    private val log = RedactingLogger("loopky-mcp.oauth")

    val unlinker = Unlinker(store, deps.vault, deps.ringLogin, deps.clock)
    val verifier: AccessTokenVerifier = StoredTokenVerifier(store, config, deps.clock)
    val sessionSecrets: SessionSecrets = VaultSessionSecrets(store, deps.vault, deps.clock)

    internal val tokens = TokenService(store, config, deps.clock, deps.limits.lifetimes, minter, unlinker)
    internal val pending = PendingLogins(deps.ringLogin, minter, deps.clock, deps.limits)
    private val requestLimiter = TokenBucketLimiter(deps.limits.requestRate, deps.clock)
    private val statusLimiter = TokenBucketLimiter(deps.limits.statusRate, deps.clock)
    private val registration = Registration(store, config, deps.limits, minter, deps.clock)
    private val authorization = Authorization(deps, pending, tokens)

    fun install(application: Application) {
        application.routing { routes() }
    }

    /** Run periodically from Main: cancels stale Ring flows and unlinks accounts past the inactivity bound. */
    suspend fun sweep() {
        pending.sweep()
        unlinker.sweepExpired()
    }

    private fun Route.routes() {
        get("/.well-known/oauth-protected-resource") { call.respondJson(protectedResourceMetadata()) }
        get("/.well-known/oauth-protected-resource/mcp") { call.respondJson(protectedResourceMetadata()) }
        get("/.well-known/oauth-authorization-server") { call.respondJson(authorizationServerMetadata()) }
        limited("/register", HttpMethod.Post, requestLimiter, registration::register)
        limited("/authorize", HttpMethod.Get, requestLimiter, authorization::authorize)
        limited("/authorize/status", HttpMethod.Get, statusLimiter, authorization::status)
        limited("/token", HttpMethod.Post, requestLimiter, ::token)
        limited("/revoke", HttpMethod.Post, requestLimiter, ::revoke)
    }

    /** The limiter goes on the method node, so `/authorize/status`'s own budget is not drained by `/authorize`'s. */
    private fun Route.limited(path: String, method: HttpMethod, limiter: TokenBucketLimiter, body: suspend (ApplicationCall) -> Unit) {
        route(path, method) {
            install(RateLimitByIp) { this.limiter = limiter }
            handle { body(call) }
        }
    }

    private suspend fun token(call: ApplicationCall) {
        val form = call.receiveTextLimited(deps.limits.maxBodyBytes)?.parseUrlEncodedParameters() ?: return
        val outcome = runSuspendCatching {
            when (form["grant_type"]) {
                "authorization_code" -> tokens.exchangeCode(form)
                "refresh_token" -> tokens.refresh(form)
                null -> invalidRequest("grant_type is required")
                else -> throw OAuthException(OAuthException.UNSUPPORTED_GRANT_TYPE, "Unsupported grant_type")
            }
        }
        outcome.onSuccess { call.respondJson(it.toJson()) }
        outcome.onFailure { call.respondFailure(it) }
    }

    private suspend fun revoke(call: ApplicationCall) {
        val form = call.receiveTextLimited(deps.limits.maxBodyBytes)?.parseUrlEncodedParameters() ?: return
        val token = form["token"]?.takeIf { it.isNotBlank() }
        if (token == null) {
            call.respondOAuthError(OAuthException.invalidRequest("token is required"))
            return
        }
        val outcome = runSuspendCatching { tokens.revoke(token) }
        outcome.onSuccess {
            call.noStore()
            call.respondText("", status = HttpStatusCode.OK)
        }
        outcome.onFailure { call.respondFailure(it) }
    }

    private suspend fun ApplicationCall.respondFailure(cause: Throwable) {
        if (cause is OAuthException) {
            respondOAuthError(cause)
        } else {
            log.error("Token endpoint failed", cause)
            respondJson(OAuthException("server_error", "Internal error").toJson(), HttpStatusCode.InternalServerError)
        }
    }

    private fun protectedResourceMetadata(): JsonObject = buildJsonObject {
        put("resource", config.resourceUrl)
        putJsonArray("authorization_servers") { add(JsonPrimitive(config.publicUrl)) }
        putJsonArray("bearer_methods_supported") { add(JsonPrimitive("header")) }
        putJsonArray("scopes_supported") {}
    }

    private fun authorizationServerMetadata(): JsonObject = buildJsonObject {
        put("issuer", config.publicUrl)
        put("authorization_endpoint", "${config.publicUrl}/authorize")
        put("token_endpoint", "${config.publicUrl}/token")
        put("registration_endpoint", "${config.publicUrl}/register")
        put("revocation_endpoint", "${config.publicUrl}/revoke")
        putJsonArray("response_types_supported") { add(JsonPrimitive("code")) }
        putJsonArray("grant_types_supported") {
            add(JsonPrimitive("authorization_code"))
            add(JsonPrimitive("refresh_token"))
        }
        putJsonArray("code_challenge_methods_supported") { add(JsonPrimitive("S256")) }
        putJsonArray("token_endpoint_auth_methods_supported") { add(JsonPrimitive("none")) }
        putJsonArray("revocation_endpoint_auth_methods_supported") { add(JsonPrimitive("none")) }
        putJsonArray("scopes_supported") {}
    }
}

fun Application.installOAuth(deps: OAuthDeps): OAuthServer = OAuthServer(deps).also { it.install(this) }
