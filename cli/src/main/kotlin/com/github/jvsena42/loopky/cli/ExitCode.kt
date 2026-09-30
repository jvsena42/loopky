package com.github.jvsena42.loopky.cli

import com.github.jvsena42.loopky.data.pubky.toErrorReason
import com.github.jvsena42.loopky.domain.model.ErrorReason
import kotlinx.serialization.json.JsonElement
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * What `loopky` exits with, and what an agent is supposed to do about it.
 *
 * The table is part of the contract, not an implementation detail: an agent branches on these
 * before it looks at anything else, so a code's meaning may be added to but never repurposed.
 *
 * [SessionExpired] has a code of its own, and that is the whole reason this enum is not three
 * values long. Loopky's homeserver session dies after roughly an hour and nothing renews it —
 * writes start failing while reads keep working (#165), which from the outside is
 * indistinguishable from a network wobble. An agent that cannot tell the two apart either retries
 * a dead session forever or gives up on a live network. Told which it is, it can stop, say so, and
 * resume after a human runs `loopky login` — which is what makes `--resume` worth having.
 *
 * [name] is what `--json` reports, so a caller can branch on a word rather than a number.
 *
 * [summary] is one line, and it exists because a binary is the only copy of this table an agent
 * has: `loopky commands --json` emits it (#240, finding 4). Keep it to a sentence, and keep it
 * saying what the caller should *do* — this is read by something deciding whether to retry.
 */
enum class ExitCode(val code: Int, val json: String, val summary: String) {
    /** The command did what it was asked. */
    Ok(0, "ok", "the command did what it was asked"),

    /** A failure this table has no better word for. Worth reporting as a bug. */
    Internal(1, "internal", "a failure this table has no better word for - worth reporting as a bug"),

    /** The command line was wrong: unknown command, missing argument, bad flag. */
    Usage(2, "usage", "the command line was wrong - do not retry it unchanged"),

    /** No session at all. Run `loopky login`, or set `LOOPKY_SESSION`. */
    NotSignedIn(3, "not_signed_in", "no session at all - run loopky login, or set LOOPKY_SESSION"),

    /** There was a session and the homeserver has stopped honouring it. See the class note. */
    SessionExpired(4, "session_expired", "the homeserver has stopped honouring this session - sign in again"),

    /** The homeserver, the relay or the indexer could not be reached. Retryable as-is. */
    Network(5, "network", "the homeserver, relay or indexer could not be reached - retryable as-is"),

    /** The deck, card or record asked for does not exist. */
    NotFound(6, "not_found", "the deck, card or record asked for does not exist"),

    /**
     * The homeserver refused the write because the account is out of quota (507).
     *
     * Terminal, never retried: re-hosting and compaction both *consume* quota, so nothing the
     * client can do digs it out, and a backoff chain against a full disk never converges.
     */
    StorageFull(7, "storage_full", "the account is out of homeserver quota - terminal, never retry"),

    /**
     * The session and the requested environment disagree.
     *
     * Its own code because the alternative is silent and worse: Nexus answers a query aimed at the
     * wrong network **successfully, with an empty result**, so an agent that writes a tag, reads it
     * back and sees `[]` concludes the write failed and retries. Caught at startup instead.
     */
    EnvironmentMismatch(8, "environment_mismatch", "the session and --env disagree - re-run against the other network"),

    /** An input file could not be read, or held nothing importable. */
    BadInput(9, "bad_input", "an operand or file was unusable - do not retry it unchanged"),

    /**
     * This machine is not one `libpubkycore` is built for. See [SupportedHost].
     *
     * Its own code because the alternative is not a vague code but a **wrong** one. An unshipped
     * host misses at `Native.load`, which throws `UnsatisfiedLinkError("Unable to load library
     * 'pubkycore': … not found in resource path …")` — and "not found" is what [of] matches on, so
     * the machine that can never run this binary reports [NotFound]: *the deck does not exist*.
     * `SupportedHostTest` pins that, because it is the reason this row exists.
     */
    UnsupportedHost(10, "unsupported_host", "no libpubkycore is built for this OS and architecture"),

    /**
     * `loopky update` found a newer release and may not install it here (#209).
     *
     * Its own code rather than 0 or 1. Zero would tell an agent that asked for an update that it
     * has one, which is the single most expensive thing this command could get wrong — the whole
     * reason the check exists is that a stale client writes an old shape while believing it is
     * current. [Internal] would be wrong in the other direction: a Homebrew install, a
     * `dpkg`-owned file, a container layer and a read-only directory are all correct states of the
     * world, and the command's answer in each is a different, correct instruction rather than a
     * bug. Nothing was downloaded and nothing was replaced.
     */
    UpdateUnsupported(11, "update_unsupported", "a newer release exists and this install may not replace itself"),

    /**
     * The homeserver answered with a 5xx of its own (#229, item 2).
     *
     * Split out of [Internal], which this CLI documents as "worth reporting as a bug" — and a 500
     * from the homeserver is not a bug in the client, is not the caller's input, and unlike every
     * other row here it may well succeed on the next attempt. A batch that reports `internal` sends
     * an agent looking through its own file for the row that broke; told `server_error` it retries
     * the rows that did not land.
     *
     * Deliberately not [Network], which promises the request never arrived: it did, and it may have
     * been applied. That distinction is what makes a resumed write safe to attempt.
     */
    ServerError(12, "server_error", "the homeserver answered 5xx - the request may have applied; worth retrying"),

    /**
     * `login --timeout` ran out before Pubky Ring approved (#240, finding 2).
     *
     * Its own code because the alternative is SIGKILL, which is what an unattended caller had.
     * `login` blocks until a human reaches for their phone, which is correct at a terminal and
     * unusable in a script — and `timeout -s KILL` skips the shutdown hook that sweeps a
     * `--qr-out` file, leaving a **live auth URL on disk**. Bounding the wait inside the process
     * is what keeps that cleanup.
     *
     * *This process* is not signed in. Deliberately not "nothing was stored": the await runs on a
     * thread that is unobserved rather than stopped, and `complete()` ends in `persistSession`, so
     * an approval landing between the timeout and `exitProcess` still writes. `whoami` answers it.
     *
     * Not [Network], which would say the relay was unreachable: it was reachable and nobody
     * answered. Retrying means running `login` again — the FFI's auth flow is a single global slot
     * that the first poll takes, so the code already on screen is spent either way.
     */
    Timeout(13, "timeout", "login --timeout ran out before anyone approved - check whoami"),

    /**
     * A proxy between this machine and the network refused the host, or its credentials (#212).
     *
     * Not [Network], which says "retryable as-is": an allowlist answers the same way every time,
     * and an agent told `network` retries a request that can never pass. The fix is on the other
     * side of the proxy — a host added to the allowlist, or working credentials in `HTTPS_PROXY`.
     */
    ProxyRefused(14, "proxy_refused", "a proxy refused this host or its credentials - allowlist it; retrying will not help"),

    /**
     * The server's certificate is not one this client trusts (#212) — in practice, a proxy
     * re-signing TLS with its own CA.
     *
     * Not [Internal], which it read as once trending stopped swallowing failures: nothing about it
     * is a bug. Not [Network] either: a CA the client does not trust will not become trusted on a
     * retry. The fix is the proxy's — exempt the host from interception — or, for the jar, a trust
     * store passed as `-Djavax.net.ssl.trustStore`.
     */
    TlsUntrusted(15, "tls_untrusted", "the certificate is not trusted - usually a proxy re-signing TLS; exempt the host from interception"),

    /**
     * pkarr could not resolve the homeserver's key to an address (#389): both relays timed out, or
     * the DHT did not answer.
     *
     * Not [Network], whose promise is a host that could not be reached — here there was no host to
     * reach yet. And above all not [ProxyRefused]: an unresolved name used to surface as a proxy
     * refusing to tunnel to `_pubky.<key>`, which sent a human to change a network policy that
     * `loopky doctor` then found nothing wrong with. Transient: a relay that missed a key answers
     * the next lookup from its cache. For `login` the approval is spent, so retrying means a new
     * code to scan.
     */
    HomeserverUnresolved(
        16,
        "homeserver_unresolved",
        "pkarr could not resolve the homeserver - transient, retry; not an allowlist problem",
    ),
    ;

    companion object {
        /**
         * The exit code for a failure that came back from the shared layer.
         *
         * Goes through [toErrorReason] rather than matching on messages here, so the CLI and the
         * two apps classify the same failure the same way — the classifier already knows that "no
         * homeserver record" and "the DHT did not answer" arrive through one call and are not the
         * same thing.
         */
        fun of(error: Throwable): ExitCode = when {
            // First: a proxy refusing a `_pubky.<key>` tunnel is this, not an allowlist gap.
            error.toErrorReason() == ErrorReason.HomeserverLookupFailed -> HomeserverUnresolved
            error.isProxyRefusal() -> ProxyRefused
            error.isUntrustedCertificate() -> TlsUntrusted
            else -> fromReason(error)
        }

        private fun fromReason(error: Throwable): ExitCode = when (error.toErrorReason()) {
            ErrorReason.NotSignedIn -> NotSignedIn
            ErrorReason.SessionExpired, ErrorReason.SessionUnreachable -> SessionExpired
            ErrorReason.Offline,
            ErrorReason.AuthRelayUnreachable,
            ErrorReason.ServerBusy,
            -> Network

            ErrorReason.HomeserverLookupFailed -> HomeserverUnresolved

            ErrorReason.NotFound, ErrorReason.NoHomeserverAccount -> NotFound
            ErrorReason.StorageFull -> StorageFull
            ErrorReason.RingNotInstalled, ErrorReason.AuthFailed -> Internal
            // Last, and only over `Unknown`: `toErrorReason` has already claimed 429, 507 and every
            // transport failure, so what is left to match a 5xx on is a homeserver that answered
            // with one.
            ErrorReason.Unknown -> if (error.isServerError()) ServerError else Internal
        }
    }
}

/**
 * A proxy said no, in either HTTP stack. The JDK names the CONNECT's status — only a 403 or 407 is
 * a refusal; a 502/503 is the proxy failing to reach a host it allows, and stays retryable. Nexus
 * reports a 407 as its own status. hyper-util's tunnel errors reach the FFI's message only through
 * its source chain, and `unsuccessful` **drops the status**, so that one is a judgment: behind an
 * allowlist it is a refusal on every call, and a wrong 14 costs one `loopky doctor` where a wrong 5
 * is a retry loop. A proxy that drops rather than refuses is a timeout, and stays
 * [ExitCode.Network].
 */
internal fun Throwable.isProxyRefusal(): Boolean {
    val message = message?.lowercase() ?: return false
    JDK_TUNNEL_STATUS.find(message)?.let { return it.groupValues[1] in REFUSING_STATUSES }
    return RUST_TUNNEL_REFUSALS.any { it in message } || PROXY_AUTH_STATUS.containsMatchIn(message)
}

/**
 * The certificate chain did not verify, in either stack. The JDK words it as a PKIX failure,
 * usually a cause or two below the `SSLHandshakeException`; rustls as "invalid peer certificate".
 */
internal fun Throwable.isUntrustedCertificate(): Boolean =
    generateSequence(this) { it.cause }.take(MAX_CAUSES).any { error ->
        val message = error.message?.lowercase().orEmpty()
        error is SSLPeerUnverifiedException || UNTRUSTED_CERTIFICATE.any { it in message }
    }

private val UNTRUSTED_CERTIFICATE = listOf(
    "pkix path building failed",
    "unable to find valid certification path",
    "invalid peer certificate",
)

private const val MAX_CAUSES = 8

private val JDK_TUNNEL_STATUS = Regex("""unable to tunnel through proxy\. proxy returns "http/[0-9.]+ ([0-9]{3})""")
private val REFUSING_STATUSES = setOf("403", "407")

private val RUST_TUNNEL_REFUSALS = listOf(
    "tunnel error: unsuccessful",
    "tunnel error: proxy authorization required",
)

private val PROXY_AUTH_STATUS = Regex("http 407(?![0-9])")

/**
 * The homeserver answered a 5xx.
 *
 * Substring matching, like every classifier in `PubkyErrors` and for the same reason: the FFI's
 * error text is not a stable contract, so a miss degrades to [ExitCode.Internal] rather than to
 * anything wrong. 507 is not here — it is out of storage, and terminal, and [toErrorReason] has
 * already claimed it.
 */
private fun Throwable.isServerError(): Boolean {
    val message = message?.lowercase() ?: return false
    return "internal server error" in message || SERVER_STATUS.containsMatchIn(message)
}

/**
 * `500`, `502`, `503` or `504` as a status code rather than as three digits inside something else.
 * Same hazard as `STATUS_507` in `PubkyErrors`: every failure message carries a `pubky://` URL, and
 * deck and card ids are random alphanumerics.
 */
private val SERVER_STATUS = Regex("(?<![0-9a-z])50[0234](?![0-9a-z])")

/**
 * A failure that already knows what the process should exit with.
 *
 * [data] is what the command had managed to do before it failed, in the `--json` shape that
 * command's success would have used. A batch write is the case it exists for: 35 of 665 rows had
 * landed when the homeserver 500'd, and an envelope carrying only a message left a caller with no
 * way to tell which (#229, item 2). Null everywhere else — a failure with nothing to report.
 */
class CliError(
    val exitCode: ExitCode,
    message: String,
    val data: JsonElement? = null,
) : RuntimeException(message.withoutRepeatedPrefix())

/**
 * `"Request failed: Request failed: Invalid request/URI: …"` said once (#240, finding 6).
 *
 * The doubling comes from the FFI, which wraps its own error a second time on the way out, so it
 * is not something this side can stop being produced — only stop repeating. Cosmetic, and worth a
 * function anyway: this string is the one an agent captures into a transcript, and a message that
 * stutters reads like two failures rather than one.
 *
 * **The leading segment only**, which is where the defect is and nowhere else. Collapsing adjacent
 * duplicates anywhere in the string edits the *data* a message quotes: this runs on every message
 * the CLI reports, so a failure naming a card whose front is `Hola: Hola` came back naming `Hola`.
 * `--json` is the verification channel, and an error string that silently rewrites the value it is
 * complaining about is the wrong place to be lossy. Two identical segments deeper in a message are
 * two frames saying the same word, which is a trace rather than a stutter.
 */
internal fun String.withoutRepeatedPrefix(): String {
    val head = substringBefore(SEGMENT, missingDelimiterValue = "")
    if (head.isEmpty()) return this
    val rest = substring(head.length + SEGMENT.length)
    return if (rest.startsWith(head + SEGMENT)) rest else this
}

private const val SEGMENT = ": "
