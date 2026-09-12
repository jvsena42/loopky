package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliEnvironment
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.CommandResult
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.TerminalQr
import com.github.jvsena42.loopky.cli.asCliError
import com.github.jvsena42.loopky.cli.eventEnvelope
import com.github.jvsena42.loopky.cli.requireSession
import com.github.jvsena42.loopky.cli.requireSessionSecretShape
import com.github.jvsena42.loopky.cli.result
import com.github.jvsena42.loopky.data.pubky.PubkyClient
import com.github.jvsena42.loopky.data.repository.AuthFlowHandle
import com.github.jvsena42.loopky.data.repository.IdentityRepository
import com.github.jvsena42.loopky.data.repository.SignOutOutcome
import com.github.jvsena42.loopky.data.storage.SecureSessionStore
import com.github.jvsena42.loopky.domain.model.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * The capabilities a headless client asks Pubky Ring for, and the whole of them.
 *
 * **Not `DEFAULT_CAPABILITIES`**, which is what the two apps request. An agent session therefore
 * cannot write a post, a follow or a profile edit under any bug or any prompt injection, because it
 * was never handed the capability — the guarantee is structural rather than a flag someone set.
 *
 * It costs less than it looks: deck tagging routes on the *subject*, so a deck manifest's tag record
 * goes to `/pub/loopky/tags/` and `deck create --tag …` works unchanged (§7.7). What is given up is
 * the pubky.app-native writes a headless deck tool has no business making — announcing a deck (#39),
 * tagging a profile, editing `profile.json`.
 *
 * One visible consequence, and it is why `whoami` reports no display name: that lives in
 * `/pub/pubky.app/profile.json`.
 */
const val CLI_CAPABILITIES = "/pub/loopky/:rw"

/**
 * The two places `login` writes things that are not its result.
 *
 * One parameter rather than two because they travel together and always will — and because the
 * alternative is a six-argument function, which detekt refuses for the reason it usually should.
 */
data class LoginSinks(val emitEvent: (String) -> Unit, val stderr: (String) -> Unit)

@Serializable
data class LoginResult(
    val pubky: String,
    val homeserver: String,
    val capabilities: List<String>,
    /**
     * The session secret, printed **only** for `--export`. It is what `LOOPKY_SESSION` takes, and the
     * reason `login` is a local-machine command: a sandbox recreated per task has no stored session
     * and nobody at its terminal to scan a code.
     */
    @SerialName("session_secret") val sessionSecret: String? = null,
    /**
     * The config home, as it has always been — a *directory*.
     *
     * It briefly became `sessionStore.location`, which on Linux is `…/loopky/secrets.json`: a
     * file where callers had a directory. That is the same silent change of meaning
     * [WhoamiResult.sessionSource] keeps an awkward spelling to avoid, so it is reverted and
     * [sessionStore] carries the store's identity instead — the same field name `whoami` uses, so
     * the two commands answer the question the same way.
     */
    @SerialName("stored_at") val storedAt: String? = null,
    /** Where the session itself went. See [WhoamiResult.sessionStore]. */
    @SerialName("session_store") val sessionStore: String? = null,
)

@Serializable
data class WhoamiResult(
    val pubky: String,
    val homeserver: String,
    val capabilities: List<String>,
    /**
     * `env` for `LOOPKY_SESSION`, `file` for the stored one.
     *
     * `file` outlived its literal reading when the macOS session moved to the Keychain (#213) and
     * is kept anyway: `--json` is a versioned API an agent branches on, and re-spelling a value
     * changes a meaning rather than adding one. It says *this machine's store*, and
     * [sessionStore] says which store that is.
     */
    @SerialName("session_source") val sessionSource: String,
    @SerialName("config_home") val configHome: String,
    /**
     * Where a stored session is kept, which stopped being [configHome] on macOS (#213).
     *
     * Both are reported rather than one: the rest of this client's state is still under
     * [configHome], and an agent debugging a box that has lost its session needs to be told which
     * of the two to look at. Says nothing about *this* session when [sessionSource] is `env`.
     */
    @SerialName("session_store") val sessionStore: String,
    val environment: String,
    val indexer: String,
    /**
     * Whether the homeserver still honours this session, right now, asked rather than assumed.
     *
     * There is no `expires_at` to report — the FFI's session payload carries no expiry, so a client
     * can only discover the wall, not plan around it. This is what an agent should check before
     * starting an hour-long import rather than 40 cards in (#165).
     */
    @SerialName("session_live") val sessionLive: Boolean,
    /** Null, always, and deliberately — see [CLI_CAPABILITIES]. */
    @SerialName("display_name") val displayName: String? = null,
)

/**
 * `--qr-out`, and the promise printed about it.
 *
 * **The promise is printed only when it was kept** (#301). This file *is* the `pubkyauth://` URL
 * with `secret=` in it, so somebody told it is owner-readable will leave it where it is for the
 * length of the approval window — which makes a false assurance worse than none. A filesystem that
 * cannot express the restriction is a real place to point this: an exFAT stick, a FAT or NFS mount.
 * The answer comes from the write itself rather than from `OwnerOnly.supported`, which asks the
 * *default* filesystem and so cannot speak for a path that came from argv.
 */
private fun writeQrFile(args: Args, authUrl: String, stderr: (String) -> Unit): File? =
    args.option("qr-out")?.let { path ->
        File(path).also {
            if (TerminalQr.writePng(authUrl, it)) {
                stderr("QR code written to $path (owner-readable only; deleted when this command ends)")
            } else {
                stderr(
                    "QR code written to $path — but this filesystem would not make it " +
                        "owner-readable, so anyone who can reach that path can take this session " +
                        "until the command ends. Prefer --url-only, or a path with permissions.",
                )
            }
        }
    }

/**
 * Sign in by printing a QR code for Pubky Ring, then blocking on the relay until it is approved.
 *
 * The auth URL carries **no Ring return-callbacks**: there is no app here to return to, and a
 * dangling `x-success` pointing at `loopky://` would bounce the user into Loopky on their phone
 * after a desktop login.
 *
 * The FFI's auth flow is a single global slot that `awaitAuthApproval` *takes*. A relay poll that
 * dies mid-wait is resumed inside the FFI on the same channel, so the code already on screen stays
 * valid; a failure that reaches here is final, and recovering means running `loopky login` again.
 */
suspend fun login(
    args: Args,
    identity: IdentityRepository,
    sessionStore: SecureSessionStore,
    environment: CliEnvironment,
    sinks: LoginSinks,
): CommandResult {
    val (emitEvent, stderr) = sinks
    // Read before `beginSignIn`, not at the point of use: the FFI's auth flow is a single global
    // slot the first poll takes, so refusing `--timeout twenty` after starting one would spend a
    // code nobody ever saw.
    val timeout = args.positiveIntOrNull("timeout")
    val handle = identity.beginSignIn(capabilities = CLI_CAPABILITIES, returnToApp = false)
        .getOrElse { throw asCliError(it) }

    val qrFile = writeQrFile(args, handle.authUrl, stderr)
    // A `finally` is not enough on its own. `login` blocks on the relay for as long as it takes
    // somebody to reach for their phone, so the ordinary way it ends is **^C** — which is a signal,
    // not an exception, and takes the JVM down without unwinding. Without a hook the live
    // credential simply stays on disk, which is the failure the 0600 mode is only half of.
    val sweep = qrFile?.let { file -> Thread { file.delete() } }
    sweep?.let { Runtime.getRuntime().addShutdownHook(it) }
    emitEvent(eventEnvelope("login", "auth_url", buildJsonObject { put("auth_url", handle.authUrl) }))

    // The prompt goes to stderr, all of it: stdout carries the result and nothing else, and a
    // half-megabyte of block characters in front of the JSON would make it undecodable.
    //
    // **The plaintext URL is printed only under `--url-only`**, where it is the deliverable. It
    // carries the client secret the auth token is encrypted to, so leaking it turns the relay's
    // encrypted blob back into a usable session — and stderr is precisely the stream an agent
    // harness captures into a transcript that may be logged or pasted into an issue.
    if (args.has("url-only")) {
        stderr("This URL is a credential until you approve it in Ring — treat it like a password.")
        stderr(handle.authUrl)
    } else {
        stderr(TerminalQr.render(handle.authUrl))
        stderr("Scan this with Pubky Ring. `--url-only` prints the link instead of the code.")
    }
    stderr("")
    stderr("Requesting $CLI_CAPABILITIES only — this session cannot post, follow or edit a profile.")
    stderr(timeout?.let { "Waiting for approval (up to ${it}s)…" } ?: "Waiting for approval…")

    val session = try {
        handle.completeWithin(timeout).getOrElse { throw asCliError(it) }
    } finally {
        // Whether approval landed or not: the URL in that file is either spent or dead, and
        // leaving a live one on disk is the point. Best-effort — a file the user cannot delete is
        // not a reason to fail a sign-in that worked.
        qrFile?.let { file -> runCatching { file.delete() } }
        sweep?.let { hook -> runCatching { Runtime.getRuntime().removeShutdownHook(hook) } }
    }
    val export = args.has("export")
    if (export) {
        stderr("")
        stderr("--export prints a live session secret below. It is also stored in ${sessionStore.location}.")
    }
    return result(
        LoginResult(
            pubky = session.identity.pubky,
            homeserver = session.homeserver,
            capabilities = session.capabilities.map { it.value },
            sessionSecret = session.sessionSecret.takeIf { export },
            storedAt = environment.configHome.toString(),
            sessionStore = sessionStore.location,
        ),
        buildString {
            appendLine("Signed in as ${session.identity.pubky}")
            appendLine("Homeserver: ${session.homeserver}")
            appendLine("Capabilities: ${session.capabilities.joinToString(", ") { it.value }}")
            appendLine("Session stored in ${sessionStore.location}")
            if (export) {
                appendLine()
                appendLine("LOOPKY_SESSION=${session.sessionSecret}")
                appendLine("Treat that like a password: it authorises writes to /pub/loopky until it expires.")
            }
        }.trimEnd(),
    )
}

/**
 * Await approval, giving up after [seconds] when one was asked for.
 *
 * **Not `withTimeout { complete() }`.** `complete()` blocks in the FFI on a `Dispatchers.IO`
 * thread, and `withContext` does not hand control back until its body returns whatever the job
 * says — measured, a 300 ms timeout around a 5 s blocking call returned null after 5 s. So the
 * wait happens on a thread of its own and the timeout is applied to a [CompletableDeferred],
 * which is a real suspension point.
 *
 * The thread is a daemon and is deliberately left where it is: it is parked inside a native call
 * that nothing on this side can interrupt, and the process exits as soon as this returns. What is
 * bought by exiting *here* rather than under `timeout -s KILL` is the `finally` at the call site —
 * the shutdown hook is removed and a `--qr-out` file holding a live auth URL is deleted (#240).
 *
 * **The deferred is completed on every path out of that thread, exceptions included.** Without the
 * `catch`, anything thrown out of `runBlocking` — an `Error` such as `UnsatisfiedLinkError` is not
 * what `runSuspendCatching` inside `complete()` is protecting against — killed the thread with the
 * deferred never completed, so the caller waited the *whole* `--timeout` and then reported
 * `timeout` for what was an FFI failure. A wrong diagnosis after a long wait is worse than either.
 */
internal suspend fun AuthFlowHandle.completeWithin(seconds: Int?): Result<Session> {
    if (seconds == null) return complete()
    val awaited = CompletableDeferred<Result<Session>>()
    Thread {
        val outcome = try {
            runBlocking { complete() }
        } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
            Result.failure(error)
        }
        awaited.complete(outcome)
    }.apply { isDaemon = true; name = "loopky-login-await" }.start()
    return withTimeoutOrNull(seconds.seconds) { awaited.await() } ?: throw CliError(
        ExitCode.Timeout,
        // Not "nothing was stored", which this cannot promise. `complete()` ends in
        // `persistSession`, and the thread is unobserved rather than stopped — an approval landing
        // in the moment between here and `exitProcess` still writes. Saying so is better than
        // asserting the opposite: an agent told a lie about its own credential state has no way to
        // find out. `whoami` is cheap and answers it.
        "Nobody approved the sign-in within ${seconds}s, so this process is not signed in. If " +
            "approval lands as it exits the session may still be stored — `loopky whoami` says. " +
            "The code that was on screen is spent either way — the FFI's auth flow is single-use " +
            "— so run `loopky login` again rather than retrying.",
    )
}

suspend fun whoami(
    identity: IdentityRepository,
    pubkyClient: PubkyClient,
    sessionStore: SecureSessionStore,
    environment: CliEnvironment,
    env: (String) -> String? = System::getenv,
): CommandResult {
    val session: Session = requireSession(identity, environment, env)
    val live = pubkyClient.revalidateSession(session.sessionSecret).isSuccess
    val source = if (env("LOOPKY_SESSION").isNullOrBlank()) "file" else "env"
    return result(
        WhoamiResult(
            pubky = session.identity.pubky,
            homeserver = session.homeserver,
            capabilities = session.capabilities.map { it.value },
            sessionSource = source,
            configHome = environment.configHome.toString(),
            sessionStore = sessionStore.location,
            environment = environment.name,
            indexer = environment.indexer,
            sessionLive = live,
        ),
        buildString {
            appendLine("Pubky:        ${session.identity.pubky}")
            appendLine("Homeserver:   ${session.homeserver}")
            appendLine("Capabilities: ${session.capabilities.joinToString(", ") { it.value }}")
            appendLine("Environment:  ${environment.name}")
            appendLine("Indexer:      ${environment.indexer}")
            // Not the JSON's `file`/`env`: that value is pinned by the schema, and on macOS it
            // no longer reads as anything.
            appendLine("Session from: ${if (source == "env") "LOOPKY_SESSION" else "this machine"}")
            appendLine("Config home:  ${environment.configHome}")
            appendLine("Session in:   ${sessionStore.location}")
            append("Session:      ${if (live) "live" else "NOT accepted by the homeserver — sign in again"}")
        },
    )
}

@Serializable
data class LogoutResult(
    /**
     * True when the homeserver confirmed the session is dead, rather than merely forgotten here.
     *
     * False when there was nothing to revoke, which is a success but not a revocation.
     */
    val revoked: Boolean,
    /**
     * False for a `LOOPKY_SESSION`, which was never written to this machine in the first place —
     * and false when the store *refused* to give the credential up, which is the case worth
     * exiting non-zero over (#213).
     */
    @SerialName("cleared_locally") val clearedLocally: Boolean,
)

/**
 * End the session — on the homeserver, and on this machine where there is one.
 *
 * The `LOOPKY_SESSION` case used to be refused outright on the grounds that there is nothing stored
 * to clear. True, and beside the point: sign-out does **two** things, and only the local half was
 * missing, so a secret minted with `login --export` and carried into a sandbox could never be
 * withdrawn. For a credential whose whole story is "hand this to an ephemeral agent box", revocation
 * is the wrong end to be missing.
 *
 * So an injected session is *revoked* rather than cleared, through `revokeSession` rather than
 * `signOut`: on a developer's machine both credentials can exist at once, and `signOut` would revoke
 * the injected one while wiping the stored one.
 */
suspend fun logout(
    identity: IdentityRepository,
    env: (String) -> String? = System::getenv,
): CommandResult {
    val injected = env("LOOPKY_SESSION")?.trim()?.takeIf { it.isNotEmpty() }
    if (injected != null) {
        // Checked here as well as in `requireSession`: `logout` is the one command that does not
        // go through it, and without this a typo'd variable comes back as `session_expired` —
        // the same confusion between "the hour is up" and "that is not a session secret" that the
        // check exists to prevent.
        requireSessionSecretShape(injected)
        // Asked, not assumed. "Nothing was stored on this machine" used to be asserted outright,
        // and it is false in the flow the README itself describes: a secret minted with
        // `login --export` *is* the stored one, so revoking it left a dead credential installed
        // here — `whoami` still answered ok with only `session_live: false` to hint at it.
        val stored = identity.loadPersistedSession()?.takeIf { it.sessionSecret == injected }
        identity.revokeSession(injected).getOrElse { throw asCliError(it, injected = true) }
        if (stored != null) {
            // The same credential, so clearing here takes nothing else with it — which is the one
            // thing routing this through `revokeSession` rather than `signOut` was protecting.
            identity.signOut(force = true).getOrElse { throw asCliError(it) }
        }
        return result(
            LogoutResult(revoked = true, clearedLocally = stored != null),
            if (stored != null) {
                "Revoked the session from LOOPKY_SESSION, and cleared this machine's copy of it — " +
                    "they were the same session. Unset the variable too."
            } else {
                "Revoked the session from LOOPKY_SESSION. This machine has no copy of that " +
                    "session, so nothing here was cleared — unset the variable too."
            },
        )
    }

    identity.loadPersistedSession()
    // force = true: the sign-out guard exists to stop a phone destroying the only copy of a
    // locally-minted key. This client never mints one — it holds a Ring-issued session and
    // nothing else — so there is no key here for the guard to protect.
    val outcome = identity.signOut(force = true).getOrElse { throw asCliError(it) }
    val report = LogoutResult(
        revoked = outcome.revokedRemotely,
        clearedLocally = outcome.hadSession && outcome.clearedLocally,
    )
    // A failure, not a success with a caveat. Everything else `logout` can get wrong leaves the
    // credential somewhere the user was told about; this leaves it exactly where they were told it
    // was destroyed, and an agent branching on the exit code would move on. The result still
    // travels, so `cleared_locally: false` is readable on the failure envelope.
    //
    // **Not conjoined with `hadSession`**, which would hide the likelier half of it. A store that
    // refuses a delete has usually just refused the *read* as well — same lock, same wedged
    // `securityd` — so `loadPersistedSession()` returned null, `hadSession` is false, and the
    // guard would report "Not signed in — nothing to revoke." and exit 0 over a credential that is
    // still there and was never even sent for revocation. `clearedLocally` is false only when the
    // store refused: an absent item deletes successfully (exit 44), as does an absent file key.
    if (!outcome.clearedLocally) throw unclearedSession(outcome, report)
    return result(
        report,
        when {
            // Idempotent, and says so. Reporting a revocation here would claim something happened
            // to a credential that was never there.
            !outcome.hadSession -> "Not signed in — nothing to revoke."
            outcome.revokedRemotely -> "Signed out."
            // Said plainly rather than folded into a success: the token is still live, and a user
            // told "signed out" would reasonably believe otherwise.
            else ->
                "Cleared this machine's session, but the homeserver did not confirm it was " +
                    "revoked — the session may still be usable until it expires. Try again when " +
                    "you are online."
        },
    )
}

private fun unclearedSession(outcome: SignOutOutcome, report: LogoutResult) = CliError(
    ExitCode.Internal,
    buildString {
        append("This machine's session store would not confirm the credential is gone. ")
        append("On macOS that is the login Keychain item `loopky.session`; remove it by hand ")
        append("with `security delete-generic-password -s loopky.session`. ")
        append(
            when {
                outcome.revokedRemotely ->
                    "The homeserver did revoke the session, so what remains is a dead token."
                // Nothing was *sent*: the store could not produce a secret to revoke with, so this
                // is weaker than the ordinary offline case rather than the same as it.
                !outcome.hadSession ->
                    "Nothing was sent to the homeserver either — the store could not produce the " +
                        "secret to revoke with — so any session it holds is live until it expires."
                else ->
                    "The homeserver did not confirm revocation either, so it may still be live."
            },
        )
    },
    data = logoutJson.encodeToJsonElement(report),
)

/** Encodes [LogoutResult] onto a failure envelope, where `result()` is not the one carrying it. */
private val logoutJson = Json { encodeDefaults = true }
