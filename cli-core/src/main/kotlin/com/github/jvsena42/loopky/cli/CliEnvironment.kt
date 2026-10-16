package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.data.homegate.PubkyEnvironment
import com.github.jvsena42.loopky.data.repository.DeckReorderPendingException
import com.github.jvsena42.loopky.data.storage.ConfigHome
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
 * A shared-layer failure as something the process can exit with. [injected] changes only the
 * advice: `loopky login` is not the fix on a box whose session came from an environment variable.
 */
fun asCliError(error: Throwable, injected: Boolean = false): CliError {
    val code = ExitCode.of(error)
    val hint = when {
        error is DeckReorderPendingException ->
            " Run `loopky card reorder ${error.deckId} --from-file <the same file>`. If the file is gone, " +
                "`loopky card list ${error.deckId}` prints every card once, and that output works as the file."
        // The one host that failed is rarely the only one missing; doctor names them all, and says
        // what to ask the human for (#212).
        code == ExitCode.ProxyRefused -> " Run `loopky doctor` for every host to ask the user to allowlist."
        code == ExitCode.TlsUntrusted -> " Run `loopky doctor`: the hosts need exempting from TLS inspection."
        // doctor would find every host reachable (#389); the relays simply did not answer in time.
        code == ExitCode.HomeserverUnresolved -> " Not an allowlist problem: pkarr did not answer in time. Retry the command."
        code != ExitCode.SessionExpired -> ""
        injected -> " LOOPKY_SESSION is no longer valid; mint a new one with `loopky login --export`."
        else -> " Run `loopky login` again."
    }
    return CliError(code, (error.message ?: error::class.simpleName ?: "failed") + hint)
}
