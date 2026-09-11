package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.storage.ConfigHome
import com.github.jvsena42.loopky.di.initKoinJvm
import com.github.jvsena42.loopky.domain.model.Session
import com.github.jvsena42.loopky.platform.PassThroughMediaProcessor
import com.github.jvsena42.loopky.util.Log
import org.koin.core.Koin
import org.koin.core.context.stopKoin
import org.koin.mp.KoinPlatform
import java.nio.file.Path

/**
 * Which network this invocation talks to, and where its state lives.
 *
 * The apps read the network off the build type, pinned and un-overridable (#42). A binary has
 * neither a build variant nor a Settings screen, so it has to be told: `--env` or `LOOPKY_ENV`,
 * **defaulting to production**. An unrecognised value resolves to production too — a confused
 * invocation must not quietly point at a network its user does not publish to.
 *
 * One value, never three flags: `PubkyEnvironment` exists precisely so the Homegate, the default
 * homeserver and the indexer cannot be configured apart, and there is no `--nexus-url` on purpose.
 */
class CliEnvironment(val pubky: PubkyEnvironment, val configHome: Path) {
    val name: String get() = pubky.name.lowercase()
    val indexer: String get() = pubky.nexusBaseUrl

    companion object {
        fun resolve(args: Args, env: (String) -> String? = System::getenv): CliEnvironment {
            val requested = args.option("env") ?: env("LOOPKY_ENV")
            return CliEnvironment(
                pubky = PubkyEnvironment.fromNameOrProduction(requested),
                configHome = ConfigHome.resolve(env),
            )
        }
    }
}

/**
 * Starts Koin for this invocation and hands back the graph.
 *
 * Every argument is named and none is left to a default, which matters for one of them:
 * `JvmMediaProcessor` reaches `javax.imageio` and therefore `java.awt`, and a reachable AWT makes
 * `native-image` ship five JDK `.so`s beside the executable instead of one file (#210).
 * [PassThroughMediaProcessor] is bound here so a caller appearing later degrades rather than
 * resurrecting AWT. The one path that does compress — `.apkg` import (#211) — takes the reader's
 * `compressImage` parameter directly rather than reaching through this graph.
 */
fun startCli(environment: CliEnvironment): Koin {
    // Both before Koin, because Koin is what resolves `PubkyClient`: the SDK reads `RUST_LOG` from
    // the process environment on its way to the first network call, and its `tracing` subscriber
    // writes to fd 1 — which has to already be pointing somewhere other than the result channel.
    reserveStdoutForResults()
    defaultRustLogToWarn()
    initKoinJvm(
        pubkyEnvironment = environment.pubky,
        mediaProcessor = PassThroughMediaProcessor(),
        configHome = environment.configHome,
    )
    return KoinPlatform.getKoin()
}

fun stopCli() = stopKoin()

/**
 * The session this invocation runs as, resolved at most **once** per process.
 *
 * A process has exactly one session for its lifetime — `login` and `logout` are separate
 * invocations, and `batch` refuses both as operations — so resolving it per command is pure cost.
 * Not a micro-optimisation, and `batch` is why it exists: every homeserver command goes through
 * `authed`, so a 100-operation run called [requireSession] 100 times. With `LOOPKY_SESSION` set —
 * the documented way an agent runs this — that is `adoptSession` → `revalidateSession`, a full
 * homeserver round trip **each**; without it, a `sessionStore.load()` each, which on macOS is a
 * `security(1)` fork/exec. The session round trip was listed as one of the three costs `batch`
 * collapses and was the one it did not.
 */
class SessionCache {
    private var resolved: Session? = null

    suspend fun require(
        identity: IdentityRepository,
        environment: CliEnvironment,
        env: (String) -> String? = System::getenv,
    ): Session = resolved ?: requireSession(identity, environment, env).also { resolved = it }
}

/**
 * The session this invocation runs as.
 *
 * Call it through [SessionCache] from anything that may run more than once in a process.
 *
 * `LOOPKY_SESSION` is read **before** the stored one, and the ordering is the point: a cloud
 * agent's sandbox is recreated per task, so `$XDG_CONFIG_HOME/loopky` is gone every run and nobody
 * is watching that terminal to scan a QR code. The variable is the only way in on such a box, and
 * it has to win over a file that might also exist on a developer's machine.
 *
 * The secret alone is not a session — `IdentityRepository.adoptSession` trades it for the real
 * thing and proves it is still live in the same round trip.
 */
suspend fun requireSession(
    identity: IdentityRepository,
    environment: CliEnvironment,
    env: (String) -> String? = System::getenv,
): Session {
    val injected = env("LOOPKY_SESSION")?.trim()?.takeIf { it.isNotEmpty() }
    val session = if (injected != null) {
        Log.d(TAG, "using the session from LOOPKY_SESSION")
        requireSessionSecretShape(injected)
        identity.adoptSession(injected).getOrElse { throw asCliError(it, injected = true) }
    } else {
        identity.loadPersistedSession()
            ?: throw CliError(
                ExitCode.NotSignedIn,
                "Not signed in. Run `loopky login`, or set LOOPKY_SESSION to a session secret.",
            )
    }
    checkEnvironmentAgrees(session, environment)
    return session
}

/**
 * Refuse a `LOOPKY_SESSION` that is not a session secret at all.
 *
 * Without this the FFI rejects the string and the failure classifies as [ExitCode.SessionExpired] —
 * the code that exists so an agent can tell a dead session from a wobbly network. A typo'd
 * environment variable teaching it "expired" is that same confusion one layer up.
 *
 * **Two shapes, because there are two auth flows** (#130). `loopky login` goes through the Ring
 * deeplink, which since the grant switch mints
 * `pubky-grant-credential-v1:<homeserver>:<secret>:<jws>` — four parts, where the cookie flow's
 * `<pubkey>:<cookie>` has two. Matching only the latter refused every deeplink session as
 * `bad_input`. The prefix is matched by *family* rather than by `v1`, so a future version of the
 * token is not refused here by a check that never sees whether it works.
 *
 * Shape only. Whether the secret is *live* is the homeserver's answer, and `adoptSession` asks it.
 */
internal fun requireSessionSecretShape(secret: String) {
    val parts = secret.split(':')
    val expected = if (secret.startsWith(GRANT_SECRET_PREFIX_FAMILY)) {
        GRANT_SECRET_PARTS
    } else {
        SESSION_SECRET_PARTS
    }
    if (parts.size != expected || parts.any { it.isBlank() }) {
        throw CliError(
            ExitCode.BadInput,
            "LOOPKY_SESSION is not a session secret — it should look like `<pubkey>:<cookie>` " +
                "or `${GRANT_SECRET_PREFIX_FAMILY}v1:<homeserver>:<secret>:<jws>`. " +
                "Mint one with `loopky login --export`.",
        )
    }
}

/** Mirrors `STORED_GRANT_CREDENTIAL_PREFIX_FAMILY` in pubky's `actors/auth/grant/credential.rs`. */
private const val GRANT_SECRET_PREFIX_FAMILY = "pubky-grant-credential-"

private const val SESSION_SECRET_PARTS = 2
private const val GRANT_SECRET_PARTS = 4

/**
 * Refuse to run when the session and the requested environment disagree.
 *
 * An error rather than a warning, checked before any command does anything. A session minted on
 * staging still publishes fine to its own homeserver, so only the indexer-backed reads would be
 * wrong — *silently*, because Nexus answers a mismatched query successfully and empty. An agent
 * that writes a deck tag, reads it back and gets `[]` concludes the write failed and retries.
 *
 * Deliberately not "does the session's homeserver equal [PubkyEnvironment.defaultHomeserver]",
 * which would refuse a legitimate self-hosted one. It fires only when the session sits on the
 * *other* environment's known default — a fact, not an inference — and therefore **fails open**:
 * it catches the common mistake rather than every mistake.
 */
internal fun checkEnvironmentAgrees(session: Session, environment: CliEnvironment) {
    val other = PubkyEnvironment.entries.firstOrNull {
        it != environment.pubky && it.defaultHomeserver == session.homeserver
    } ?: return
    throw CliError(
        ExitCode.EnvironmentMismatch,
        "This session is on ${other.name.lowercase()} (homeserver ${session.homeserver}) but " +
            "--env/LOOPKY_ENV says ${environment.name}. Reads from the ${environment.name} " +
            "indexer would come back empty rather than wrong, so this is refused. " +
            "Re-run with --env ${other.name.lowercase()}.",
    )
}

/**
 * A shared-layer failure as something the process can exit with. [injected] changes only the
 * advice: `loopky login` is not the fix on a box whose session came from an environment variable.
 */
fun asCliError(error: Throwable, injected: Boolean = false): CliError {
    val code = ExitCode.of(error)
    val hint = when {
        code != ExitCode.SessionExpired -> ""
        injected -> " LOOPKY_SESSION is no longer valid; mint a new one with `loopky login --export`."
        else -> " Run `loopky login` again."
    }
    return CliError(code, (error.message ?: error::class.simpleName ?: "failed") + hint)
}

private const val TAG = "Loopky/Cli"
