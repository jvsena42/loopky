package com.github.jvsena42.loopky.cli.commands

import com.github.jvsena42.loopky.cli.Args
import com.github.jvsena42.loopky.cli.CliError
import com.github.jvsena42.loopky.cli.CommandResult
import com.github.jvsena42.loopky.cli.ExitCode
import com.github.jvsena42.loopky.cli.Installation
import com.github.jvsena42.loopky.cli.SupportedHost
import com.github.jvsena42.loopky.cli.UpdateChecker
import com.github.jvsena42.loopky.cli.hostSupport
import com.github.jvsena42.loopky.cli.isWindowsOs
import com.github.jvsena42.loopky.cli.result
import com.github.jvsena42.loopky.cli.unsupportedHostMessage
import com.github.jvsena42.loopky.cli.updateAdvice
import com.github.jvsena42.loopky.data.storage.OwnerOnly
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
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
     * `jar`, `unknown`. Pair it with `can_self_update` rather than matching `binary` as a prefix:
     * `windows-binary` self-updates too (#301), but by renaming the running image aside rather than
     * writing over it, so it stays a separate value — and the two that refuse for a *reason*
     * (`homebrew`, `deb`) are the ones `advice` names another tool for.
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
internal fun replaceInPlace(target: Path, bytes: ByteArray, windows: Boolean = isWindowsOs()) {
    val temp = runCatching { incomingBinaryBeside(target, windows) }
        .getOrElse { throw replaceFailed(target, it) }
    runCatching {
        Files.write(temp, bytes)

        // **Re-hashed from disk, not carried over from memory.** `fetchVerifiedBinary` proved the
        // downloaded *bytes* matched the published digest; this proves the bytes that actually
        // landed are those bytes. It catches a short write, a directory somebody else can write to
        // between these two statements, and — the one that matters on this row — an anti-malware
        // product that quarantines or rewrites a freshly written executable before it is renamed.
        // Without it that arrives as a successful update to a file that will not run.
        if (runCatching { sha256(Files.readAllBytes(temp)) }.getOrNull() != sha256(bytes)) {
            throw IOException("what was written to $temp is not what was downloaded; nothing was replaced")
        }

        // Best-effort, like every other mode in this codebase: a filesystem with no POSIX mode
        // cannot express it, and failing here would leave the user on the old binary for a
        // guarantee that host was never going to give. A no-op on Windows, where the ACL the file
        // needs is the one it inherits — see [incomingBinaryBeside].
        runCatching { Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString(MODE)) }

        if (windows) {
            renameAside(target, temp)
        } else {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }.onFailure {
        // **Guarded for the same reason the sweep's delete is** (#301), and the asymmetry between
        // the two was the bug. On POSIX `unlink` never fails on an open file, so this was safe when
        // it was written. On Windows the likeliest cause of the failure being cleaned up after is a
        // scanner holding the freshly written `.new` open without `FILE_SHARE_DELETE` — and then
        // this line throws for that same reason, escapes past the classification below, and the
        // user is told about a temp file instead of being told their binary was rolled back and is
        // fine. In the compound case it destroys the recovery instruction from [renameAside].
        runCatching { Files.deleteIfExists(temp) }
        // A failure that already knows the state says so itself. [renameAside]'s two crafted
        // messages describe outcomes `replaceFailed` cannot infer from an exception — most of all
        // "there is nothing at the installed name" — and its generic arm would append "Nothing was
        // changed, and the old binary is untouched" to precisely that.
        if (it is CliError) throw it
        throw replaceFailed(target, it)
    }
}

/**
 * Where the new bytes are staged, which is **not** the same decision on both rows.
 *
 * POSIX gets an [OwnerOnly] temp: an executable everyone may run is the point, but the window in
 * which a half-written binary sits in a directory somebody else can read is worth closing, and the
 * mode is widened to 0755 before the rename.
 *
 * Windows gets a plain one, deliberately. `MoveFileEx` carries the *source* file's DACL onto the
 * destination, so an owner-only temp installs a `loopky.exe` only the updating account can run —
 * and under elevation that account is `BUILTIN\Administrators`, not the human, whose filtered token
 * holds that SID as `SE_GROUP_USE_FOR_DENY_ONLY` and is granted nothing by it. The result is an
 * update that reports success and leaves a binary its owner cannot execute. A plain temp inherits
 * the install directory's ACL, which is exactly what the replaced file should carry.
 */
private fun incomingBinaryBeside(target: Path, windows: Boolean): Path =
    if (windows) {
        Files.createTempFile(target.parent, target.fileName.toString(), ".new")
    } else {
        OwnerOnly.createTempFile(target.parent, target.fileName.toString(), ".new")
    }

/**
 * Replace a **running** executable, which Windows will not let you rename over (#301).
 *
 * It does allow the running image to be renamed *away*, because the handle follows the file rather
 * than the name. So the live binary is moved aside and the new one takes its place; the superseded
 * copy cannot be deleted while this process is executing it, which is what [sweepSupersededBinary]
 * is for on the next run.
 *
 * **The rollback is the part that earns its keep.** If the second move fails — a virus scanner
 * holding the new file open, a full disk — the original has already been renamed, and leaving it
 * there means the very next `loopky` finds nothing at the name it was installed under. An update
 * that fails must leave a working binary, not a working one under a different name.
 */
private fun renameAside(target: Path, incoming: Path) {
    val superseded = supersededPath(target)
    // **Tolerated, not required.** A second, longer-lived `loopky` — a `login --timeout` waiting on
    // approval — may still be executing the previous image, and Windows will not let a held file be
    // deleted. Failing here would refuse the update before it had attempted anything. The move below
    // fails on the same file anyway, and there the message can name the cause.
    runCatching { Files.deleteIfExists(superseded) }

    // `runCatching`/`getOrElse` rather than `try`/`catch`, here and below. It is the idiom every
    // other `CliError` thrower in this module uses, and the reason is not style: `CliError` carries
    // no cause, so a `catch` that folds the original's *message* into the text and drops the
    // exception is a swallowed one — which detekt says, correctly. Through `getOrElse` the failure
    // is a value being consumed rather than an exception being discarded.
    runCatching {
        Files.move(target, superseded, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }.getOrElse { failure ->
        // **Nothing has been changed at this point, so this is retryable — and it must not reach
        // [replaceFailed].** A scanner or backup agent holding the running image open without
        // `FILE_SHARE_DELETE` answers `ERROR_SHARING_VIOLATION`, which the JDK translates to a
        // plain [FileSystemException] — *not* `AccessDeniedException`, which is `ERROR_ACCESS_DENIED`
        // and is what this line gets from its other cause: `REPLACE_EXISTING` having to delete a
        // `.old` that is itself a running image, i.e. a second update in a row. Both arrive here,
        // and the distinction matters because only the second reaches `replaceFailed`'s permission
        // arm — "not writable by this user — a read-only layer, or an install that needs the
        // owner", exit 11 — the terminal verdict #312 removed from this row, arriving through a new
        // door: a lock that clears in seconds reported as an install somebody else owns.
        //
        // The original's message is folded into the text because it is the only thing naming
        // *which* file and *which* process, which is the whole of the diagnosis.
        throw CliError(
            ExitCode.Internal,
            "could not move the running ${target.fileName} aside — another process is holding it " +
                "(an anti-malware scanner, or a second loopky): ${failure.message}. " +
                "Nothing was changed; run `loopky update` again in a moment.",
        )
    }

    runCatching {
        Files.move(incoming, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }.getOrElse { failure ->
        // **The one moment the user has to be told where their binary is.** If the rollback also
        // fails, the installed name has nothing at it and the previous image is sitting under a
        // name nothing will run — the outcome this whole function exists to prevent — and a message
        // about `target` would not mention the file they actually need.
        if (runCatching {
                Files.move(superseded, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.isFailure
        ) {
            // **A [CliError], so the message survives intact.** Routed through `replaceFailed` this
            // would be re-wrapped by its generic arm, which appends "Nothing was changed, and the
            // old binary is untouched" — and this is the one state where both halves are false:
            // there is nothing at the installed name, and the previous image is under one nothing
            // will run. Telling somebody to recover by hand and then that there is nothing to
            // recover leaves them to pick, and the reassuring half is the wrong one.
            throw CliError(
                ExitCode.Internal,
                "the update failed and the previous binary could not be put back: it is at " +
                    "$superseded — rename it to ${target.fileName} by hand to recover. " +
                    "The cause was: ${failure.message}",
            )
        }
        throw failure
    }
}

/**
 * Remove the copy a previous [replaceInPlace] had to leave behind, if any.
 *
 * Called at start-up because that is the first moment the old image is no longer running. It is
 * **best-effort and silent**: the file is inert, a second `loopky` running concurrently may still
 * hold it, and failing a user's actual command over a leftover byte-for-byte copy of a binary they
 * already replaced would be the wrong trade in every direction.
 */
internal fun sweepSupersededBinary(installation: Installation, windows: Boolean = isWindowsOs()) {
    if (!windows) return
    val target = installation.path ?: return
    runCatching { Files.deleteIfExists(supersededPath(target)) }
}

/** The name the running image is moved to. Beside the target, so the rename never crosses a device. */
internal fun supersededPath(target: Path): Path =
    target.resolveSibling("${target.fileName}$SUPERSEDED_SUFFIX")

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

/**
 * Not `.bak`: this is not a backup anyone should restore from, it is the previous image kept alive
 * only because the OS will not free it while it runs. [sweepSupersededBinary] removes it.
 */
private const val SUPERSEDED_SUFFIX = ".old"
private const val MB = 1024 * 1024
private const val MAX_DOWNLOAD_BYTES = 256 * MB
private const val HTTP_NOT_FOUND = 404
private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 60_000
private val SUCCESS = 200..299
