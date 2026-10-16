package com.github.jvsena42.loopky.mcp

import com.github.jvsena42.loopky.data.nexus.JvmHttpFetcher
import com.github.jvsena42.loopky.data.pubky.UniffiPubkyClient
import com.github.jvsena42.loopky.di.TenantServices
import com.github.jvsena42.loopky.mcp.auth.OAuthDeps
import com.github.jvsena42.loopky.mcp.auth.OAuthLimits
import com.github.jvsena42.loopky.mcp.auth.installOAuth
import com.github.jvsena42.loopky.mcp.auth.runAdminCommand
import com.github.jvsena42.loopky.mcp.security.OriginValidator
import com.github.jvsena42.loopky.mcp.security.SsrfGuard
import com.github.jvsena42.loopky.mcp.security.UserLimiter
import com.github.jvsena42.loopky.mcp.store.McpStore
import com.github.jvsena42.loopky.mcp.tenant.TenantFactory
import com.github.jvsena42.loopky.mcp.tenant.TenantGraphFactory
import com.github.jvsena42.loopky.mcp.tenant.TenantLimits
import com.github.jvsena42.loopky.mcp.tenant.TenantPool
import com.github.jvsena42.loopky.mcp.vault.MasterKey
import com.github.jvsena42.loopky.mcp.vault.VaultBootstrap
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
                scratchDir = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "loopky-mcp")),
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
    // Caddy is the only peer; without this every per-IP limit keys on 127.0.0.1.
    install(XForwardedHeaders)
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

    val sweeper = launch {
        while (isActive) {
            delay(parts.sweepEvery)
            oauth.sweep()
            tenants.sweep()
        }
    }
    monitor.subscribe(ApplicationStopped) {
        sweeper.cancel()
        runBlocking { tenants.close() }
        parts.oauth.store.close()
    }
}
