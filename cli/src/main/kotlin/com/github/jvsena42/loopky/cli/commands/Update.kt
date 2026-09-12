package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.CommandResult
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.Installation
import com.github.jvsena42.loopky.cli.SupportedHost
import com.github.jvsena42.loopky.cli.UpdateChecker
import com.github.jvsena42.loopky.cli.hostSupport
import com.github.jvsena42.loopky.cli.result
import com.github.jvsena42.loopky.cli.unsupportedHostMessage
import com.github.jvsena42.loopky.cli.updateAdvice
import com.github.jvsena42.loopky.data.storage.OwnerOnly
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/**
 * What `loopky update` reports, in `--json`. One shape for `--check` and for a real run, so a caller
 * need not branch on which flag it passed: [applied] is the answer either way, and false for
 * `--check`, for "already current", and for an installation this command may not touch.
 */
@Serializable
data class UpdateResult(
    val current: String,
    val latest: String?,
    @SerialName("update_available") val updateAvailable: Boolean,
    val schema: Int,
    @SerialName("latest_schema") val latestSchema: Int?,
    @SerialName("schema_changed") val schemaChanged: Boolean,
    /**
     * How this copy was installed: `binary`, `windows-binary`, `homebrew`, `deb`, `container`,
     * `jar`, `unknown`. Pair it with `can_self_update` rather than matching `binary` as a prefix —
     * `windows-binary` is a downloaded file that `update` still refuses, and `advice` says why.
     */
    val install: String,
    val path: String?,
    @SerialName("can_self_update") val canSelfUpdate: Boolean,
    val applied: Boolean,
    /** True only when a downloaded file's SHA-256 matched the digest published beside it. */
    val verified: Boolean,
    /** What to run next, in words. Empty when there is nothing to do. */
    val advice: String,
)

/**
 * Replace this binary with the newest release (#209).
 *
 * The shape of the command follows from one fact: **a self-updater is the only command that fetches an
 * executable and then runs it as the user.** So it verifies the download against the published digest
 * and refuses on a mismatch rather than warning — where `cli/install.sh` degrades to "digest NOT
 * checked" on a host with no `sha256sum`, because there the alternative is a plain `curl` with no
 * check at all; here the alternative is simply not updating.
 *
 * It also refuses, with the right command, wherever the file is not ours to replace — a Homebrew
 * Cellar file, a `dpkg`-owned `/usr/bin/loopky`, a container layer, the jar distribution. That refusal
 * exits [ExitCode.UpdateUnsupported] rather than 0: an agent that asked for an update and got a zero
 * would carry on believing it had one.
 */
suspend fun update(
    args: Args,
    checker: UpdateChecker,
    installation: Installation,
    /**
     * The seam the tests replace: given a version, hand back a verified binary. A parameter rather than
     * a call, because the applied path is the one that writes over an executable and it is worth being
     * able to run it without a network.
     */
    fetchBinary: suspend (version: String) -> ByteArray = { version ->
        fetchVerifiedBinary(checker, version, hostSupport() ?: throw CliError(ExitCode.UnsupportedHost, unsupportedHostMessage()))
    },
): CommandResult {
    // Uncached: `--check` is a direct question and answering it out of a day-old file is the one
    // behaviour that would make this command less trustworthy than the ambient notice it exists
    // to act on.
    val manifest = checker.latest(force = true)
    val available = checker.available(manifest)
    val checkOnly = args.has("check")

    // Computed per report rather than once, because it is the *next* action and applying the
    // update is what makes there be none. Reported unchanged on the success path, it comes back as
    // `"applied": true, "advice": "Run \`loopky update\`."` — and an agent that treats `advice` as
    // the next action, which is exactly what the field invites, runs the whole forced, uncached
    // check again to be told it is current.
    val advice = if (available == null) "" else updateAdvice(installation, available.version)

    fun report(applied: Boolean, verified: Boolean, text: String) = result(
        UpdateResult(
            current = checker.currentVersion,
            latest = manifest?.version,
            updateAvailable = available != null,
            schema = checker.currentSchema,
            latestSchema = manifest?.schema,
            schemaChanged = available?.schemaChanged ?: false,
            install = installation.method.json,
            path = installation.path?.toString(),
            canSelfUpdate = installation.canSelfUpdate,
            applied = applied,
            verified = verified,
            advice = if (applied) "" else advice,
        ),
        text,
    )

    if (manifest == null) {
        // Not an error. No egress, an allowlist proxy and a release page with no manifest yet all
        // land here, and none of them is a reason to exit non-zero on a command that changed
        // nothing.
        return report(applied = false, verified = false, text = "Could not reach the release page. Nothing changed.")
    }
    if (available == null) {
        return report(
            applied = false,
            verified = false,
            text = "loopky ${checker.currentVersion} is the latest release.",
        )
    }
    if (checkOnly) {
        return report(applied = false, verified = false, text = "loopky ${available.version} is available. $advice")
    }
    if (!installation.canSelfUpdate) {
        throw CliError(ExitCode.UpdateUnsupported, "loopky ${available.version} is available, but $advice")
    }

    val target = requireNotNull(installation.path) { "canSelfUpdate implies a path" }
    replaceInPlace(target, fetchBinary(available.version))
    return report(
        applied = true,
        verified = true,
        text = "Updated loopky ${checker.currentVersion} -> ${available.version} at $target.",
    )
}

/**
 * Download the release asset for [host] and refuse it unless its digest is the published one. [get] is
 * a seam so the verification policy can be exercised without a network.
 */
internal suspend fun fetchVerifiedBinary(
    checker: UpdateChecker,
    version: String,
    host: SupportedHost,
    get: (url: String) -> ByteArray = ::download,
): ByteArray = withContext(Dispatchers.IO) {
    val url = checker.assetUrl(version, host.asset)
    val binary = get(url)
    // A missing digest is a refusal, not a warning. The release workflow publishes one beside
    // every binary, so its absence means the release is malformed or something is answering for
    // github.com that should not be — and this is the one command where "carry on anyway" means
    // executing whatever came back.
    val published = runCatching { get("$url.sha256").decodeToString() }.getOrElse { failure ->
        // Only a genuine *absence* is a malformed release. A 503 or a read timeout from the object
        // store is an ordinary blip, and reporting it as "something is answering for github.com
        // that should not be" sends the reader at a supply-chain investigation — with exit 1,
        // which tells an agent this is an internal bug rather than something to retry. The refusal
        // is right either way; the diagnosis and the code are what would be wrong.
        if (failure is CliError && failure.exitCode != ExitCode.NotFound) throw failure
        throw CliError(
            ExitCode.Internal,
            "no published checksum at $url.sha256, so the download was not verified and has been " +
                "discarded. Nothing was replaced.",
        )
    }
    val expected = published.trim().substringBefore(' ').lowercase()
    val actual = sha256(binary)
    if (expected != actual) {
        throw CliError(
            ExitCode.Internal,
            "checksum mismatch for ${host.asset}: expected $expected, got $actual. The download has " +
                "been discarded and nothing was replaced.",
        )
    }
    binary
}

/**
 * A plain GET for **bytes**. Not [HttpFetcher], which decodes a body as UTF-8 — right for the JSON
 * everything else fetches, and silent corruption for a 60 MB executable. Redirects are followed because
 * `releases/download/…` is a 302 into GitHub's object store.
 */
private fun download(url: String): ByteArray {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        // Every failure leaves here already classified, and **404 is kept apart from the rest**:
        // it is the only status that means the file is genuinely not published, which is what the
        // caller needs to tell a malformed release from a bad minute on the network.
        val code = runCatching { connection.responseCode }
            .getOrElse { throw CliError(ExitCode.Network, "could not reach $url: ${it.message}") }
        if (code == HTTP_NOT_FOUND) throw CliError(ExitCode.NotFound, "$url does not exist")
        if (code !in SUCCESS) throw CliError(ExitCode.Network, "HTTP $code fetching $url")
        val bytes = runCatching { connection.inputStream.use { it.readNBytes(MAX_DOWNLOAD_BYTES + 1) } }
            .getOrElse { throw CliError(ExitCode.Network, "download of $url failed: ${it.message}") }
        if (bytes.size > MAX_DOWNLOAD_BYTES) {
            throw CliError(ExitCode.Internal, "$url is larger than ${MAX_DOWNLOAD_BYTES / MB} MB — refusing it.")
        }
        return bytes
    } finally {
        connection.disconnect()
    }
}

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * Write [bytes] over [target], whole or not at all: a temp file in the same directory, then an atomic
 * rename, so a process killed mid-write cannot leave a half-written executable under a name the next
 * command will run — and a `loopky` already running from that path keeps its open file. Same directory
 * rather than system temp because `ATOMIC_MOVE` cannot cross a filesystem.
 */
internal fun replaceInPlace(target: Path, bytes: ByteArray) {
    // Owner-only *while it is being written*, then widened to 0755 once it holds the real bytes.
    // This one is deliberately not an [OwnerOnly] file at rest — an executable everyone may run is
    // the point — but the window in which a half-written binary sits in a directory somebody else
    // can read is worth closing, and on a host with no POSIX mode it is the only restriction the
    // temp file gets at all (#301).
    val temp = runCatching { OwnerOnly.createTempFile(target.parent, target.fileName.toString(), ".new") }
        .getOrElse { throw replaceFailed(target, it) }
    runCatching {
        Files.write(temp, bytes)
        // Best-effort, like every other mode in this codebase: a filesystem with no POSIX mode
        // cannot express it, and failing here would leave the user on the old binary for a
        // guarantee that host was never going to give.
        //
        // **Latent on Windows, and the fence is no longer `SupportedHost`** (#301). This widening
        // is the "0755 at rest" half of the pair above and is a no-op there:
        // `setPosixFilePermissions` throws `UnsupportedOperationException` and is swallowed, while
        // `MoveFileEx` carries the temp's owner-only DACL onto the target — so an elevated `update`
        // of a shared install would leave a `loopky.exe` that no non-elevated shell can run. Same
        // species as the `getOwner` lockout, arriving through the other door.
        //
        // **Decided by the PR that published the Windows release asset — which is where this
        // comment said the decision had to be taken.** It is still unreachable, and no longer for
        // the reason it used to give: a Windows install resolved to `InstallMethod.Jar` only
        // because no `.exe` existed, and publishing one ended that. It now resolves to
        // `InstallMethod.WindowsBinary`, whose `canSelfUpdate` is false for a separate and more
        // durable reason — Windows refuses to rename over a *running* image — so `update` exits 11
        // long before here. What re-arms this is the rename-aside making that row self-updatable,
        // and the fix then is for the moved file to inherit its directory's ACL, not the temp's.
        runCatching { Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString(MODE)) }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }.onFailure {
        Files.deleteIfExists(temp)
        throw replaceFailed(target, it)
    }
}

/**
 * Why the replace did not happen, and **only a permission problem is [ExitCode.UpdateUnsupported]**.
 *
 * 11 means one specific thing — "this install is owned by something else, use that tool" — so mapping
 * every failure to it is how a full disk gets reported as Homebrew. `Files.write` can fail with
 * `ENOSPC`, and `ATOMIC_MOVE` with `AtomicMoveNotSupportedException`; an agent told 11 for either
 * concludes it is on a managed install and stops retrying.
 *
 * `AccessDeniedException` is the JDK's typed `EACCES`/`EPERM`; `EROFS` — the read-only container layer
 * this rule most exists for — arrives as a plain [FileSystemException] whose reason names it.
 */
internal fun replaceFailed(target: Path, cause: Throwable): CliError {
    val readOnly = cause is FileSystemException &&
        cause.reason?.contains("read-only", ignoreCase = true) == true
    return if (cause is AccessDeniedException || readOnly) {
        CliError(
            ExitCode.UpdateUnsupported,
            "could not replace $target (${cause.message}). The file or its directory is not " +
                "writable by this user — a read-only layer, or an install that needs the owner. " +
                "Nothing was changed.",
        )
    } else {
        CliError(
            ExitCode.Internal,
            "could not replace $target: ${cause::class.simpleName}: ${cause.message}. Nothing was " +
                "changed, and the old binary is untouched.",
        )
    }
}

private const val MODE = "rwxr-xr-x"
private const val MB = 1024 * 1024
private const val MAX_DOWNLOAD_BYTES = 256 * MB
private const val HTTP_NOT_FOUND = 404
private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 60_000
private val SUCCESS = 200..299
