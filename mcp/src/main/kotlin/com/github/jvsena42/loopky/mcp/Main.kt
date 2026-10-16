package com.github.jvsena42.loopky.mcp

import com.github.jvsena42.loopky.data.nexus.JvmHttpFetcher
import com.github.jvsena42.loopky.data.pubky.UniffiPubkyClient
import com.github.jvsena42.loopky.di.TenantServices
import com.github.jvsena42.loopky.mcp.auth.OAuthDeps
import com.github.jvsena42.loopky.mcp.auth.OAuthLimits
import com.github.jvsena42.loopky.mcp.auth.installOAuth
import com.github.jvsena42.loopky.mcp.auth.runAdminCommand
import com.github.jvsena42.loopky.mcp.security.OriginValidator
import com.github.jvsena42.loopky.mcp.security.RedactingLogger
import com.github.jvsena42.loopky.mcp.security.SsrfGuard
import com.github.jvsena42.loopky.mcp.security.UserLimiter
import com.github.jvsena42.loopky.mcp.store.McpStore
import com.github.jvsena42.loopky.mcp.tenant.TenantFactory
import com.github.jvsena42.loopky.mcp.tenant.TenantGraphFactory
import com.github.jvsena42.loopky.mcp.tenant.TenantLimits
import com.github.jvsena42.loopky.mcp.tenant.TenantPool
import com.github.jvsena42.loopky.mcp.vault.MasterKey
import com.github.jvsena42.loopky.mcp.vault.VaultBootstrap
import com.github.jvsena42.loopky.util.runSuspendCatching
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

fun main(args: Array<String>) {
    val config = McpConfig.fromEnvironment()
    // Before anything class-loads HttpURLConnection, `:shared`'s Nexus reads included.
    SsrfGuard.enablePinnedHostHeader()
    val pubky = UniffiPubkyClient(clientId = config.pubkyClientId)
    val ringLogin = FfiRingLogin(pubky)
    val oauthLimits = OAuthLimits()
    val tenantLimits = TenantLimits()
    pubky.configureForServer(
        // Every pooled graph's grant stays minted, plus headroom for ones just evicted.
        sessions = tenantLimits.maxTenants * 2,
        pendingFlows = oauthLimits.pending.global,
        flowSeconds = oauthLimits.lifetimes.pendingLogin.seconds.toInt(),
        // Every read is buffered whole several times over (Rust, RustBuffer, ByteArray, String), up
        // to maxConcurrentCalls at once, against a 384 MB heap; the tools read manifests and card
        // chunks, never media, so the FFI's 50 MiB default would only size an OOM.
        maxBodyBytes = MAX_RECORD_BYTES,
    )

    if (args.firstOrNull() == "admin") {
        exitProcess(runBlocking { runAdminCommand(args.drop(1), config, ringLogin) })
    }

    val store = McpStore.open(config.dataDir)
    val vault = runBlocking { VaultBootstrap.open(store, MasterKey.load(config.masterKeyFile), config.environment) }
    val services = TenantServices(config.environment, pubky, JvmHttpFetcher())

    embeddedServer(Netty, host = config.host, port = config.port) {
        loopkyMcp(
            ServerParts(
                oauth = OAuthDeps(config, store, vault, ringLogin, limits = oauthLimits),
                tenantLimits = tenantLimits,
                tenantFactory = TenantGraphFactory(services),
                scratchDir = privateScratch(config.dataDir),
            ),
        )
    }.start(wait = true)
}

/** Everything [loopkyMcp] composes, built by [main] for real and by tests over fakes. */
class ServerParts(
    /** Also the config and the store: the OAuth half is what owns them. */
    val oauth: OAuthDeps,
    val tenantFactory: TenantFactory,
    val scratchDir: Path,
    val tenantLimits: TenantLimits = TenantLimits(),
    val sweepEvery: Duration = 5.minutes,
)

fun Application.loopkyMcp(parts: ServerParts) {
    // Without it every per-IP limit keys on Caddy's 127.0.0.1. The last hop, not the first: the
    // first is whatever the client sent, once anything but Caddy can reach this port (#518).
    install(XForwardedHeaders) { useLastProxy() }
    routing {
        get("/healthz") { call.respondText("ok") }
    }

    val oauth = installOAuth(parts.oauth)
    val tenants = TenantPool(parts.tenantFactory, oauth.sessionSecrets, parts.tenantLimits)
    val origins = OriginValidator(setOf(OriginValidator.CLAUDE_ORIGIN))
    installMcp(
        McpDeps(
            environment = parts.oauth.config.environment,
            publicUrl = parts.oauth.config.publicUrl,
            verifier = oauth.verifier,
            originPolicy = OriginPolicy(origins::allows),
            tenants = tenants,
            scratchDir = parts.scratchDir,
            userLimiter = UserLimiter(),
        ),
    )

    // Each step guarded on its own: one that threw (a busy database) used to end the loop for
    // good, and with it the inactivity unlink, the stale-flow reap and the idle-graph close.
    val sweeper = launch {
        while (isActive) {
            delay(parts.sweepEvery)
            runSuspendCatching { oauth.sweep() }.onFailure { sweepLog.warn("The OAuth sweep failed; retrying next tick", it) }
            runSuspendCatching { tenants.sweep() }.onFailure { sweepLog.warn("The tenant sweep failed; retrying next tick", it) }
        }
    }
    monitor.subscribe(ApplicationStopped) {
        sweeper.cancel()
        runBlocking { tenants.close() }
        parts.oauth.store.close()
    }
}

/**
 * Inline content is users' deck text, so its root lives under the data directory — private, and
 * held by the data-directory lock — rather than a shared /tmp another local user could pre-create.
 */
private fun privateScratch(dataDir: Path): Path {
    val root = dataDir.resolve("scratch")
    if (Files.notExists(root)) Files.createDirectories(root, PosixFilePermissions.asFileAttribute(OWNER_ONLY))
    Files.setPosixFilePermissions(root, OWNER_ONLY)
    return root
}

private val OWNER_ONLY = PosixFilePermissions.fromString("rwx------")
private val sweepLog = RedactingLogger("loopky-mcp.sweep")

/** The largest record a tool reads: a deck manifest or a card chunk. */
private const val MAX_RECORD_BYTES = 8L * 1024 * 1024
