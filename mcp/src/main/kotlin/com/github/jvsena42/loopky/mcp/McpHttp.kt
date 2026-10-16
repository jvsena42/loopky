package com.github.jvsena42.loopky.mcp

import com.github.jvsena42.loopky.cli.CLI_VERSION
import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
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
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Whether a request's `Origin` may reach the server — the Streamable HTTP spec's DNS-rebinding
 * defence. The OAuth half provides a policy too; this one is the shape they share.
 */
fun interface OriginPolicy {
    fun allows(origin: String?): Boolean

    companion object {
        /**
         * No `Origin` (a server-side client such as Claude's connector) or one of [origins],
         * compared whole — scheme and host — never by hostname alone.
         */
        fun allowing(vararg origins: String): OriginPolicy {
            val allowed = origins.map { it.trimEnd('/').lowercase() }.toSet()
            return OriginPolicy { origin -> origin == null || origin.trimEnd('/').lowercase() in allowed }
        }
    }
}

/** The bounds on the HTTP surface itself; the tenant bounds are [TenantPool]'s. */
data class HttpLimits(
    /** The most a POST body may be. The SDK reads at most this many bytes plus one. */
    val maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES,
    /** MCP sessions kept at once; the least recently seen is closed when a new one is needed. */
    val maxSessions: Int = DEFAULT_MAX_SESSIONS,
    /** A session nobody has used for this long is closed on the next initialize. */
    val sessionIdleTtl: Duration = DEFAULT_SESSION_IDLE_TTL,
) {
    private companion object {
        const val DEFAULT_MAX_BODY_BYTES = 2L * 1024 * 1024
        const val DEFAULT_MAX_SESSIONS = 1_000
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
    val dispatcher = ToolDispatcher(deps.environment, deps.tenants, InlineScratch(deps.scratchDir), deps.urlGuard)
    val sessions = McpSessions(deps, dispatcher)

    routing {
        route(MCP_PATH) {
            intercept(ApplicationCallPipeline.Plugins) {
                if (!sessions.admit(context)) finish()
            }
            sse {
                val transport = sessions.existing(call) ?: return@sse
                transport.handleRequest(this, call)
            }
            post {
                val transport = sessions.forPost(call) ?: return@post
                transport.handleRequest(null, call)
            }
            delete {
                val transport = sessions.existing(call) ?: return@delete
                transport.handleRequest(null, call)
            }
        }
    }
}

const val MCP_PATH = "/mcp"

/** The principal the bearer spoke for, attached by the gate for the handlers. */
val PrincipalKey = AttributeKey<Principal>("loopky.principal")

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

    /** The auth, revocation and origin gates. False means the call was answered. */
    suspend fun admit(call: ApplicationCall): Boolean {
        if (!deps.originPolicy.allows(call.request.header(HttpHeaders.Origin))) {
            call.reject(HttpStatusCode.Forbidden, "Origin not allowed.")
            return false
        }
        // Checked before the body is read: an oversized body is refused by the SDK too, but with
        // the whole thing already pulled through the gate.
        val declared = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
        if (call.request.httpMethod == HttpMethod.Post && declared != null && declared > deps.limits.maxBodyBytes) {
            call.reject(HttpStatusCode.PayloadTooLarge, "Request body exceeds ${deps.limits.maxBodyBytes} bytes.")
            return false
        }
        val principal = principalOf(call) ?: run {
            call.unauthorized(null)
            return false
        }
        if (deps.tenants.isRevoked(principal.pubky)) {
            call.unauthorized("The Loopky session was revoked; connect again.")
            return false
        }
        call.attributes.put(PrincipalKey, principal)
        return true
    }

    private suspend fun principalOf(call: ApplicationCall): Principal? {
        val header = call.request.header(HttpHeaders.Authorization) ?: return null
        val token = header.substringAfter("Bearer ", missingDelimiterValue = "").trim()
        if (token.isEmpty()) return null
        return deps.verifier.verify(token)
    }

    /** The transport for the session the request names, owned by this principal, or null after answering. */
    suspend fun existing(call: ApplicationCall): StreamableHttpServerTransport? {
        val principal = call.attributes[PrincipalKey]
        val sessionId = call.request.header(MCP_SESSION_ID_HEADER)
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
        return session.transport
    }

    /** A POST without a session id is an `initialize`, and gets a server bound to this principal. */
    suspend fun forPost(call: ApplicationCall): StreamableHttpServerTransport? {
        if (call.request.header(MCP_SESSION_ID_HEADER) != null) return existing(call)
        val principal = call.attributes[PrincipalKey]
        val transport = StreamableHttpServerTransport(
            StreamableHttpServerTransport.Configuration(
                enableJsonResponse = true,
                maxRequestBodySize = deps.limits.maxBodyBytes,
            ),
        )
        transport.setOnSessionInitialized { id ->
            trim()
            sessions[id] = McpSession(transport, principal.pubky, deps.clock())
        }
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

    /** Drops idle sessions, and the oldest ones past the cap. Called as a session is being created. */
    private fun trim() {
        val now = deps.clock()
        val stale = sessions.filterValues { now - it.lastSeen > deps.limits.sessionIdleTtl.inWholeMilliseconds }
        stale.keys.forEach { close(it) }
        val overflow = sessions.size - deps.limits.maxSessions + 1
        if (overflow > 0) {
            sessions.entries.sortedBy { it.value.lastSeen }.take(overflow).forEach { close(it.key) }
        }
    }

    private fun close(sessionId: String) {
        val session = sessions.remove(sessionId) ?: return
        // The SDK's close is suspending and never blocks on the network; this runs on a session
        // being created, which is already inside a request.
        runBlocking { session.transport.close() }
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
