package com.github.jvsena42.loopky.mcp.auth

import com.github.jvsena42.loopky.mcp.LoginPoll
import com.github.jvsena42.loopky.mcp.security.RedactingLogger
import com.github.jvsena42.loopky.mcp.store.Link
import com.github.jvsena42.loopky.util.runSuspendCatching
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.util.Base64

/** `GET /authorize` and the page's `GET /authorize/status` poll. */
internal class Authorization(
    deps: OAuthDeps,
    private val pending: PendingLogins,
    private val tokens: TokenService,
) {
    private val store = deps.store
    private val config = deps.config
    private val vault = deps.vault
    private val ringLogin = deps.ringLogin
    private val clock = deps.clock
    private val random = SecureRandom()
    private val log = RedactingLogger("loopky-mcp.authorize")

    /**
     * RFC 6749 §4.1.2.1: a bad client or redirect URI is answered in place, never by redirect —
     * redirecting there is exactly what an attacker registering a client is after.
     */
    suspend fun authorize(call: ApplicationCall) {
        val params = call.request.queryParameters
        val client = params["client_id"]?.let { store.clients.find(it) }
        val redirectUri = params["redirect_uri"]
        if (client == null || redirectUri == null || redirectUri !in client.redirectUris) {
            call.plain(HttpStatusCode.BadRequest, "Unknown client or redirect_uri")
            return
        }
        val state = params["state"]
        val problem = validate(params)
        if (problem != null) {
            call.noStore()
            call.respondRedirect(errorRedirect(redirectUri, problem, state))
            return
        }
        val request = AuthorizeRequest(
            clientId = client.clientId,
            clientName = client.clientName,
            redirectUri = redirectUri,
            codeChallenge = requireNotNull(params["code_challenge"]),
            state = state,
            resource = config.resourceUrl,
        )
        when (val start = pending.begin(request, call.request.origin.remoteHost)) {
            is PendingStart.Started -> call.respondPage(start.pending)
            PendingStart.TooManyForIp ->
                call.plain(HttpStatusCode.TooManyRequests, "Too many sign-ins in progress from this address; finish or wait for one")
            PendingStart.TooManyGlobally ->
                call.plain(HttpStatusCode.ServiceUnavailable, "Too many sign-ins in progress; try again in a few minutes")
            is PendingStart.RingFailed -> {
                log.error("Starting a Ring flow failed", start.cause)
                call.plain(HttpStatusCode.BadGateway, "Could not start a Pubky Ring sign-in; try again")
            }
        }
    }

    /** The txn travels in a header, never the query string: a proxy's access log records the URL and not the headers. */
    suspend fun status(call: ApplicationCall) {
        val txn = call.request.header(TXN_HEADER)
        if (txn.isNullOrBlank()) {
            call.respondJson(statusJson("unknown"), HttpStatusCode.BadRequest)
            return
        }
        val entry = pending.find(txn)
        if (entry == null) {
            call.respondJson(statusJson("expired"))
            return
        }
        val poll = runSuspendCatching { ringLogin.poll(entry.flowId) }
            .getOrElse { LoginPoll.Failed(it.message ?: "poll failed") }
        when (poll) {
            LoginPoll.Pending -> call.respondJson(statusJson("pending"))
            is LoginPoll.Failed -> {
                pending.complete(txn)
                log.warn("Ring flow for ${entry.request.clientName} failed: ${poll.reason}")
                call.respondJson(statusJson("failed"))
            }
            is LoginPoll.Approved -> {
                pending.complete(txn)
                val redirect = approve(entry, poll)
                call.respondJson(
                    buildJsonObject {
                        put("status", "approved")
                        put("redirect", redirect)
                    },
                )
            }
        }
    }

    /** Stores the new credential before revoking the old grant, so a re-link never leaves the user with neither. */
    private suspend fun approve(entry: PendingAuthorization, approved: LoginPoll.Approved): String {
        val now = clock.instant()
        val previous = store.links.find(approved.pubky)
        store.links.upsert(
            Link(
                pubky = approved.pubky,
                credential = vault.seal(approved.pubky, approved.sessionSecret),
                keyVersion = vault.keyVersion,
                createdAt = previous?.createdAt ?: now,
                lastUsedAt = now,
            ),
        )
        val oldSecret = previous?.let { runCatching { vault.open(it.pubky, it.credential) }.getOrNull() }
        if (oldSecret != null && oldSecret != approved.sessionSecret) {
            runSuspendCatching { ringLogin.revoke(oldSecret).getOrThrow() }
                .onFailure { log.warn("Revoking the previous grant for ${approved.pubky} failed", it) }
        }
        val code = tokens.issueCode(entry, approved.pubky)
        log.info("Linked ${approved.pubky} for client ${entry.request.clientId}")
        return URLBuilder(entry.request.redirectUri).apply {
            parameters.append("code", code)
            entry.request.state?.let { parameters.append("state", it) }
        }.buildString()
    }

    private fun validate(params: Parameters): OAuthException? {
        val challenge = params["code_challenge"]
        val state = params["state"]
        val resource = params["resource"]
        return when {
            params["response_type"] != "code" ->
                OAuthException(OAuthException.UNSUPPORTED_RESPONSE_TYPE, "response_type must be code")
            challenge == null || !Pkce.isValidChallenge(challenge) -> OAuthException.invalidRequest("code_challenge is required (S256)")
            params["code_challenge_method"] != "S256" -> OAuthException.invalidRequest("code_challenge_method must be S256")
            state != null && (state.isEmpty() || state.length > MAX_STATE_LENGTH) -> OAuthException.invalidRequest("state is malformed")
            resource != null && resource != config.resourceUrl -> OAuthException(OAuthException.INVALID_TARGET, "Unknown resource")
            else -> null
        }
    }

    private fun errorRedirect(redirectUri: String, error: OAuthException, state: String?): String =
        URLBuilder(redirectUri).apply {
            parameters.append("error", error.error)
            parameters.append("error_description", error.description)
            state?.let { parameters.append("state", it) }
        }.buildString()

    private suspend fun ApplicationCall.respondPage(entry: PendingAuthorization) {
        val nonce = Base64.getEncoder().encodeToString(ByteArray(NONCE_BYTES).also(random::nextBytes))
        noStore()
        response.header("Content-Security-Policy", AuthorizePage.contentSecurityPolicy(nonce))
        response.header("X-Frame-Options", "DENY")
        response.header("Referrer-Policy", "no-referrer")
        respondText(AuthorizePage.render(entry, nonce), ContentType.Text.Html.withCharset(Charsets.UTF_8))
    }

    private suspend fun ApplicationCall.plain(status: HttpStatusCode, message: String) {
        noStore()
        respondText(message, status = status)
    }

    private fun statusJson(status: String) = buildJsonObject { put("status", status) }

    companion object {
        const val TXN_HEADER = "X-Loopky-Txn"
        private const val MAX_STATE_LENGTH = 512
        private const val NONCE_BYTES = 16
    }
}
