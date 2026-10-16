package com.github.jvsena42.loopky.mcp.auth

import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import com.github.jvsena42.loopky.mcp.LoginPoll
import com.github.jvsena42.loopky.mcp.McpConfig
import com.github.jvsena42.loopky.mcp.MutableClock
import com.github.jvsena42.loopky.mcp.PendingLogin
import com.github.jvsena42.loopky.mcp.RingLogin
import com.github.jvsena42.loopky.mcp.store.McpStore
import com.github.jvsena42.loopky.mcp.vault.CredentialVault
import com.github.jvsena42.loopky.mcp.vault.MasterKey
import com.github.jvsena42.loopky.mcp.vault.VaultBootstrap
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.server.application.install
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import kotlin.test.assertEquals

const val CALLBACK = "https://claude.ai/api/mcp/auth_callback"
const val OTHER_CALLBACK = "https://evil.example/callback"
const val PUBKY = "o1gg96ewuojmopcjbz8895478wdtxtzzuxnfjjz8o8e77csa1ngo"
const val SESSION_SECRET = "grant-secret-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
const val VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk-verifier"

class FakeRingLogin : RingLogin {
    val started = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    val revoked = mutableListOf<String>()
    var revokeResult: Result<Unit> = Result.success(Unit)
    var startFailure: Throwable? = null

    /** When set, [start] waits on it: a relay that is slow to answer. */
    var startGate: CompletableDeferred<Unit>? = null

    /** When set, [revoke] waits on it after recording the attempt: a homeserver that never answers. */
    var revokeGate: CompletableDeferred<Unit>? = null
    private val answers = HashMap<String, LoginPoll>()
    private var counter = 0

    fun authUrl(flowId: String) = "pubkyauth:///?relay=https://relay.test/link/&secret=RELAYSECRET-$flowId&caps=/pub/loopky/:rw"

    override suspend fun start(): PendingLogin {
        startFailure?.let { throw it }
        startGate?.await()
        val id = "flow-${++counter}"
        started += id
        answers[id] = LoginPoll.Pending
        return PendingLogin(id, authUrl(id))
    }

    override suspend fun poll(flowId: String): LoginPoll = answers[flowId] ?: LoginPoll.Failed("unknown flow")

    override suspend fun cancel(flowId: String) {
        cancelled += flowId
    }

    override suspend fun revoke(sessionSecret: String): Result<Unit> {
        revoked += sessionSecret
        revokeGate?.await()
        return revokeResult
    }

    fun approve(pubky: String = PUBKY, secret: String = SESSION_SECRET, flowId: String = started.last()) {
        answers[flowId] = LoginPoll.Approved(pubky, secret)
    }

    fun fail(flowId: String = started.last()) {
        answers[flowId] = LoginPoll.Failed("denied")
    }
}

/** A real SQLite file in a temp dir, a generated master key on disk at 0600, and the fakes around them. */
class OAuthHarness(val dir: Path, val limits: OAuthLimits = OAuthLimits()) : AutoCloseable {
    val key: MasterKey = MasterKey.generate()
    val config = McpConfig(
        environment = PubkyEnvironment.Staging,
        publicUrl = "https://mcp.test",
        host = "127.0.0.1",
        port = 0,
        dataDir = dir.resolve("data"),
        masterKeyFile = dir.resolve("master.key").also { writeKey(it, key) },
        pubkyClientId = "mcp.test",
        allowedRedirectUris = setOf(CALLBACK),
    )
    val clock = MutableClock(Instant.parse("2026-10-16T12:00:00Z"))
    val ring = FakeRingLogin()
    var store: McpStore = McpStore.open(config.dataDir)
        private set
    val vault: CredentialVault = runBlocking { VaultBootstrap.open(store, key, config.environment) }
    lateinit var server: OAuthServer

    fun deps() = OAuthDeps(config, store, vault, ring, clock, limits)

    /** Stops the "server": releases the data directory for an admin command, and [reopen] picks it up again after. */
    fun stop() = store.close()

    fun reopen() {
        store = McpStore.open(config.dataDir)
    }

    override fun close() {
        store.close()
        dir.toFile().deleteRecursively()
    }

    companion object {
        fun writeKey(path: Path, key: MasterKey) {
            Files.createDirectories(path.parent)
            Files.writeString(path, MasterKey.encode(key))
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        }

        fun create(limits: OAuthLimits = OAuthLimits()): OAuthHarness = OAuthHarness(Files.createTempDirectory("mcp-oauth"), limits)
    }
}

class OAuthClient(val harness: OAuthHarness, val http: HttpClient) {
    val ring get() = harness.ring
    val clock get() = harness.clock
    val server get() = harness.server

    suspend fun register(name: String = "Claude", redirects: List<String> = listOf(CALLBACK), extra: String = ""): HttpResponse =
        http.post("/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"client_name":${Json.encodeToString(String.serializer(), name)},"redirect_uris":${redirects.toJson()}$extra}""")
        }

    suspend fun registerClient(name: String = "Claude"): String = register(name).json()["client_id"]!!.jsonPrimitive.content

    suspend fun authorize(
        clientId: String,
        redirect: String = CALLBACK,
        verifier: String = VERIFIER,
        state: String? = "xyz",
        resource: String? = harness.config.resourceUrl,
        ip: String = "203.0.113.7",
        overrides: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse {
        val params = mutableMapOf(
            "client_id" to clientId,
            "redirect_uri" to redirect,
            "response_type" to "code",
            "code_challenge" to Pkce.challengeOf(verifier),
            "code_challenge_method" to "S256",
            "state" to state,
            "resource" to resource,
        )
        params.putAll(overrides)
        val query = params.filterValues { it != null }.entries.joinToString("&") { (k, v) -> "$k=${encode(v!!)}" }
        return http.get("/authorize?$query") {
            header("X-Forwarded-For", ip)
            headers.forEach { (name, value) -> header(name, value) }
        }
    }

    suspend fun authorizeTxn(clientId: String, verifier: String = VERIFIER, state: String? = "xyz"): String {
        val page = authorize(clientId, verifier = verifier, state = state)
        assertEquals(HttpStatusCode.OK, page.status, page.bodyAsText())
        return txnOf(page.bodyAsText())
    }

    suspend fun status(txn: String, ip: String = "203.0.113.7"): JsonObject = http.get("/authorize/status") {
        header(Authorization.TXN_HEADER, txn)
        header("X-Forwarded-For", ip)
    }.json()

    /** Registers, authorizes, approves in Ring and returns the code the page would redirect with. */
    suspend fun codeFor(clientId: String, verifier: String = VERIFIER, pubky: String = PUBKY, secret: String = SESSION_SECRET): String {
        val txn = authorizeTxn(clientId, verifier)
        ring.approve(pubky, secret)
        val approved = status(txn)
        assertEquals("approved", approved["status"]?.jsonPrimitive?.content, approved.toString())
        return codeOf(approved["redirect"]!!.jsonPrimitive.content)
    }

    suspend fun exchange(
        code: String,
        clientId: String,
        verifier: String = VERIFIER,
        redirect: String = CALLBACK,
        resource: String? = null,
    ): HttpResponse {
        return http.submitForm(
            "/token",
            parameters {
                append("grant_type", "authorization_code")
                append("code", code)
                append("client_id", clientId)
                append("redirect_uri", redirect)
                append("code_verifier", verifier)
                resource?.let { append("resource", it) }
            },
        )
    }

    suspend fun refresh(token: String, clientId: String) = http.submitForm(
        "/token",
        parameters {
            append("grant_type", "refresh_token")
            append("refresh_token", token)
            append("client_id", clientId)
        },
    )

    suspend fun revoke(token: String) = http.submitForm("/revoke", parameters { append("token", token) })

    suspend fun tokensFor(clientId: String, verifier: String = VERIFIER): TokenPair {
        val response = exchange(codeFor(clientId, verifier), clientId, verifier)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.json().tokens()
    }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

    private fun List<String>.toJson() = joinToString(",", "[", "]") { Json.encodeToString(String.serializer(), it) }
}

data class TokenPair(val access: String, val refresh: String)

fun JsonObject.tokens() = TokenPair(this["access_token"]!!.jsonPrimitive.content, this["refresh_token"]!!.jsonPrimitive.content)

suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

fun txnOf(page: String): String = requireNotNull(Regex("const txn = \"([^\"]+)\"").find(page)) { "no txn in page" }.groupValues[1]

fun codeOf(redirect: String): String = requireNotNull(Regex("[?&]code=([^&]+)").find(redirect)) { "no code in $redirect" }.groupValues[1]

/** Boots the OAuth server inside Ktor's test host over a fresh harness, with `X-Forwarded-For` honoured for per-IP tests. */
fun oauthTest(limits: OAuthLimits = OAuthLimits(), block: suspend OAuthClient.() -> Unit) = testApplication {
    val harness = OAuthHarness.create(limits)
    try {
        application {
            install(XForwardedHeaders)
            harness.server = installOAuth(harness.deps())
        }
        startApplication()
        val http = createClient { followRedirects = false }
        OAuthClient(harness, http).block()
    } finally {
        harness.close()
    }
}
