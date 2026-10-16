package com.github.jvsena42.loopky.mcp

import com.github.jvsena42.loopky.cli.CLI_VERSION
import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import com.github.jvsena42.loopky.mcp.security.ClientKey
import com.github.jvsena42.loopky.mcp.security.OriginValidator
import com.github.jvsena42.loopky.mcp.security.RateLimitPolicy
import com.github.jvsena42.loopky.mcp.security.TokenBucketLimiter
import com.github.jvsena42.loopky.mcp.security.UserLimiter
import com.github.jvsena42.loopky.mcp.tenant.TenantPool
import com.github.jvsena42.loopky.mcp.tools.InlineScratch
import com.github.jvsena42.loopky.mcp.tools.McpInstructions
import com.github.jvsena42.loopky.mcp.tools.ToolDispatcher
import com.github.jvsena42.loopky.mcp.tools.UrlGuard
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.util.AttributeKey
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Whether a request's `Origin` may reach the server — the Streamable HTTP spec's DNS-rebinding
 * defence. The seam the HTTP layer takes; [OriginValidator] is the one implementation, so there
 * is a single notion of what `https://claude.ai:443/` is equal to.
 */
fun interface OriginPolicy {
    fun allows(origin: String?): Boolean

    companion object {
        /** No `Origin` (a server-side client such as Claude's connector) or one of [origins], as [OriginValidator] compares them. */
        fun allowing(vararg origins: String): OriginPolicy = OriginValidator(origins.toSet())::allows.let { OriginPolicy(it) }
    }
}

/** The bounds on the HTTP surface itself; the tenant bounds are [TenantPool]'s. */
data class HttpLimits(
    /** The most a POST body may be. The SDK reads at most this many bytes plus one. */
    val maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES,
    /** MCP sessions kept at once; the least recently seen is closed when a new one is needed. */
    val maxSessions: Int = DEFAULT_MAX_SESSIONS,
    /**
     * Sessions one principal may hold; its own least recently seen goes first. A handful covers
     * a connector and a reconnect or two, and it is what keeps one bearer churning `initialize`
     * from pushing every other principal out of the global cap.
     */
    val maxSessionsPerPrincipal: Int = DEFAULT_MAX_SESSIONS_PER_PRINCIPAL,
    /** A session nobody has used for this long is closed on the next initialize. */
    val sessionIdleTtl: Duration = DEFAULT_SESSION_IDLE_TTL,
    /**
     * Bearer checks that *failed*, per client address. Every request costs a token lookup, and
     * nothing else stood in front of it; charging only the failures means an address that
     * Claude's users share pays nothing for their valid traffic, and a flood of made-up tokens
     * from one place is answered 429 before the store sees it.
     */
    val failedAuthRate: RateLimitPolicy = RateLimitPolicy.perMinute(count = DEFAULT_FAILED_AUTH_PER_MINUTE),
) {
    private companion object {
        const val DEFAULT_MAX_BODY_BYTES = 2L * 1024 * 1024
        const val DEFAULT_MAX_SESSIONS = 1_000
        const val DEFAULT_MAX_SESSIONS_PER_PRINCIPAL = 8
        const val DEFAULT_FAILED_AUTH_PER_MINUTE = 60
        val DEFAULT_SESSION_IDLE_TTL = 1.hours
    }
}

/** Everything [installMcp] needs, each seam injectable so the binding is testable against fakes. */
@Suppress("LongParameterList")
class McpDeps(
    val environment: PubkyEnvironment,
    /** The `https://` origin Claude reaches this server at; names the protected-resource metadata. */
    val publicUrl: String,
    val verifier: AccessTokenVerifier,
    val originPolicy: OriginPolicy,
    val tenants: TenantPool,
    /** Where inline content is written for the commands to read. Owner-only directories under it. */
    val scratchDir: Path,
    val urlGuard: UrlGuard = UrlGuard.PUBLIC_HTTPS,
    val instructions: String = McpInstructions.load(),
    val limits: HttpLimits = HttpLimits(),
    val clock: () -> Long = System::currentTimeMillis,
    val userLimiter: UserLimiter? = null,
)

/**
 * Mounts the MCP server at `/mcp` over Streamable HTTP, JSON responses (#214).
 *
 * Every request passes three gates before the SDK sees it: a bearer token the [AccessTokenVerifier]
 * accepts (else 401 with the `WWW-Authenticate` that points Claude at the OAuth metadata), an
 * `Origin` the [OriginPolicy] allows (else 403), and — for a request naming an MCP session — a
 * session this principal created (else 404, the same answer an unknown session gets, so a foreign
 * id learns nothing). A principal whose credential the homeserver refused is answered 401 too, so
 * the client reconnects rather than retrying.
 *
 * One SDK [Server] per MCP session, created on `initialize` with the principal bound into its tool
 * handlers: a session id is therefore the principal's, not a key anyone can present.
 */
fun Application.installMcp(deps: McpDeps) {
    if (pluginOrNull(SSE) == null) install(SSE)
    // The SDK answers a JSON-mode POST with `call.respond(message)`, which needs its own JSON
    // converter registered; without one Ktor has no transformation and answers 406 with no body.
    if (pluginOrNull(ContentNegotiation) == null) install(ContentNegotiation) { json(McpJson) }
    val dispatcher = ToolDispatcher(
        deps.environment,
        deps.tenants,
        InlineScratch(deps.scratchDir),
        deps.urlGuard,
        userLimiter = deps.userLimiter,
    )
    val sessions = McpSessions(deps, dispatcher)

    routing {
        route(MCP_PATH) {
            // Route-scoped, never `intercept(ApplicationCallPipeline.Plugins)`: that reached the
            // sibling routes too and answered `/healthz` with a 401. And ahead of the handlers,
            // because inside `sse {}` the response is already a committed 200 event stream —
            // which is also why the session a GET or DELETE names is checked in the gate.
            install(McpGate) { this.sessions = sessions }
            sse { call.attributes[TransportKey].handleRequest(this, call) }
            post { sessions.forPost(call).handleRequest(null, call) }
            delete { call.attributes[TransportKey].handleRequest(null, call) }
        }
    }
}

const val MCP_PATH = "/mcp"

private class McpGateConfig {
    lateinit var sessions: McpSessions
}

/** The auth, revocation and origin gates for `/mcp`; a refused call is answered here. */
private val McpGate = createRouteScopedPlugin("McpGate", ::McpGateConfig) {
    val sessions = pluginConfig.sessions
    onCall { call -> sessions.admit(call) }
}

/** The principal the bearer spoke for, attached by the gate for the handlers. */
val PrincipalKey = AttributeKey<Principal>("loopky.principal")

/** The transport of the session a request named, attached by the gate once it is known to be this principal's. */
private val TransportKey = AttributeKey<StreamableHttpServerTransport>("loopky.transport")

private class McpSession(
    val transport: StreamableHttpServerTransport,
    val pubky: String,
    @Volatile var lastSeen: Long,
)

/**
 * The MCP sessions and the gates in front of them. One object rather than route-level locals so
 * the gate, the session table and the per-session servers see one principal.
 */
private class McpSessions(private val deps: McpDeps, private val dispatcher: ToolDispatcher) {

    private val sessions = ConcurrentHashMap<String, McpSession>()
    private val failedAuth = TokenBucketLimiter(deps.limits.failedAuthRate, FunctionClock(deps.clock))

    /** The auth, revocation and origin gates. False means the call was answered. */
    suspend fun admit(call: ApplicationCall): Boolean {
        val principal = authenticated(call) ?: return false
        call.attributes.put(PrincipalKey, principal)
        // A request naming a session — every GET and DELETE, and a POST after initialize — is
        // checked here and not in its handler: Ktor's `sse {}` runs the handler inside the
        // response body, after `200 text/event-stream` has gone out, so a refusal there raised
        // ResponseAlreadySentException and a foreign session id got a stream that closed instead
        // of the 404 that tells a client to initialize again.
        val sessionId = call.request.header(MCP_SESSION_ID_HEADER)
        if (sessionId == null && call.request.httpMethod == HttpMethod.Post) return true
        val session = owned(call, principal, sessionId) ?: return false
        call.attributes.put(TransportKey, session.transport)
        return true
    }

    /** The principal the bearer speaks for, or null once the call is answered. */
    private suspend fun authenticated(call: ApplicationCall): Principal? {
        if (!wellFormed(call)) return null
        val address = ClientKey.of(call.request.origin.remoteHost)
        if (failedAuth.retryAfterSeconds(address) > 0) {
            call.response.header(HttpHeaders.RetryAfter, failedAuth.retryAfterSeconds(address).toString())
            call.reject(HttpStatusCode.TooManyRequests, "Too many failed authentications from this address.")
            return null
        }
        val principal = principalOf(call)
        when {
            principal == null -> {
                failedAuth.tryAcquire(address)
                call.unauthorized(null)
            }
            deps.tenants.isRevoked(principal.pubky) -> call.unauthorized("The Loopky session was revoked; connect again.")
            else -> return principal
        }
        return null
    }

    /**
     * The origin and the declared body size, checked before the body is read: an oversized body
     * is refused by the SDK too, but with the whole thing already pulled through the gate.
     */
    private suspend fun wellFormed(call: ApplicationCall): Boolean {
        if (!deps.originPolicy.allows(call.request.header(HttpHeaders.Origin))) {
            call.reject(HttpStatusCode.Forbidden, "Origin not allowed.")
            return false
        }
        val declared = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
        if (call.request.httpMethod == HttpMethod.Post && declared != null && declared > deps.limits.maxBodyBytes) {
            call.reject(HttpStatusCode.PayloadTooLarge, "Request body exceeds ${deps.limits.maxBodyBytes} bytes.")
            return false
        }
        return true
    }

    private suspend fun principalOf(call: ApplicationCall): Principal? {
        val header = call.request.header(HttpHeaders.Authorization) ?: return null
        val token = header.substringAfter("Bearer ", missingDelimiterValue = "").trim()
        if (token.isEmpty()) return null
        return deps.verifier.verify(token)
    }

    /** The session [sessionId] names if [principal] owns it, or null once the call is answered. */
    private suspend fun owned(call: ApplicationCall, principal: Principal, sessionId: String?): McpSession? {
        if (sessionId.isNullOrEmpty()) {
            call.reject(HttpStatusCode.BadRequest, "Mcp-Session-Id header is required.")
            return null
        }
        val session = sessions[sessionId]
        // A session another principal created is "not found", exactly as an unknown id is: the
        // client starts a session of its own, and the id reveals nothing about whose it was.
        if (session == null || session.pubky != principal.pubky) {
            call.reject(HttpStatusCode.NotFound, "Session not found.")
            return null
        }
        session.lastSeen = deps.clock()
        return session
    }

    /** A POST without a session id is an `initialize`, and gets a server bound to this principal. */
    suspend fun forPost(call: ApplicationCall): StreamableHttpServerTransport {
        call.attributes.getOrNull(TransportKey)?.let { return it }
        val principal = call.attributes[PrincipalKey]
        // Before the transport exists rather than in its initialized callback, which is not
        // suspending: closing a session from there meant runBlocking inside the request.
        trim(principal)
        val transport = StreamableHttpServerTransport(
            StreamableHttpServerTransport.Configuration(
                enableJsonResponse = true,
                maxRequestBodySize = deps.limits.maxBodyBytes,
            ),
        )
        transport.setOnSessionInitialized { id -> sessions[id] = McpSession(transport, principal.pubky, deps.clock()) }
        transport.setOnSessionClosed { id -> sessions.remove(id) }
        val server = serverFor(principal)
        server.onClose { transport.sessionId?.let { sessions.remove(it) } }
        server.createSession(transport)
        return transport
    }

    private fun serverFor(principal: Principal): Server {
        val server = Server(
            serverInfo = Implementation(name = SERVER_NAME, version = CLI_VERSION, title = SERVER_TITLE),
            options = ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
            instructions = deps.instructions,
        )
        dispatcher.tools().forEach { tool ->
            server.addTool(tool) { request ->
                dispatcher.call(principal, request.name, request.arguments ?: JsonObject(emptyMap()))
            }
        }
        return server
    }

    /**
     * Makes room for a session of [principal]'s: idle ones go, then the principal's own oldest
     * past its cap, and only then the oldest of anyone's past the global cap — in that order, so
     * a client that churns `initialize` evicts its own sessions and not everybody else's.
     */
    private suspend fun trim(principal: Principal) {
        val now = deps.clock()
        sessions.filterValues { now - it.lastSeen > deps.limits.sessionIdleTtl.inWholeMilliseconds }.keys.forEach { close(it) }
        val own = sessions.filterValues { it.pubky == principal.pubky }
        oldest(own, own.size - deps.limits.maxSessionsPerPrincipal + 1).forEach { close(it) }
        oldest(sessions, sessions.size - deps.limits.maxSessions + 1).forEach { close(it) }
    }

    private fun oldest(among: Map<String, McpSession>, count: Int): List<String> =
        if (count <= 0) emptyList() else among.entries.sortedBy { it.value.lastSeen }.take(count).map { it.key }

    private suspend fun close(sessionId: String) {
        val session = sessions.remove(sessionId) ?: return
        session.transport.close()
    }

    private suspend fun ApplicationCall.unauthorized(description: String?) {
        val challenge = buildString {
            append("Bearer resource_metadata=\"${deps.publicUrl}$RESOURCE_METADATA_PATH\"")
            if (description != null) append(", error=\"invalid_token\", error_description=\"$description\"")
        }
        response.header(HttpHeaders.WWWAuthenticate, challenge)
        reject(HttpStatusCode.Unauthorized, description ?: "A bearer token is required.")
    }

    private companion object {
        const val SERVER_NAME = "loopky"
        const val SERVER_TITLE = "Loopky"
        const val RESOURCE_METADATA_PATH = "/.well-known/oauth-protected-resource"
    }
}

private const val MCP_SESSION_ID_HEADER = "Mcp-Session-Id"

/** [McpDeps.clock] as the `java.time.Clock` the limiter reads. */
private class FunctionClock(private val now: () -> Long) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(now())
}

/** A JSON-RPC-shaped refusal, the way the SDK words its own, so a client reads one shape. */
private suspend fun ApplicationCall.reject(status: HttpStatusCode, message: String) {
    val body = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", null as String?)
        putJsonObject("error") {
            put("code", CONNECTION_CLOSED)
            put("message", message)
        }
    }
    respondText(body.toString(), ContentType.Application.Json, status)
}

/** The SDK's `RPCError.ErrorCode.CONNECTION_CLOSED`. */
private const val CONNECTION_CLOSED = -32_000
