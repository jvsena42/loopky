package com.github.jvsena42.loopky.data.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The macOS Keychain through `security(1)`, one subprocess per operation (#213).
 *
 * Chosen over JNA into Security.framework for two reasons, neither of them line count. A
 * `SecItemAdd` from the binary itself ties the item's ACL to *this executable*, and `loopky
 * update` replaces the executable — so the upgrade a user is told to run would start prompting
 * for a Keychain password. And `native-image` has to be able to fold whatever this uses into one
 * file; `ProcessBuilder` needs no metadata, where a new JNA surface needs registration in three
 * files and is the exact shape that has twice made the build emit a second one.
 *
 * **The password never appears in `argv`.** macOS lets any local user read another process's
 * command line, so `security add-generic-password -w <secret>` publishes the thing it is storing
 * for as long as it runs. `security -i` reads its command from *stdin* instead, which is a pipe
 * only this process holds — the whole reason the write path is shaped differently from the other
 * two. What that costs is quoting: `-i` splits its line the way a shell does, so the payload is
 * Base64 before it goes in, and [isArgvSafe] fails the write rather than letting a value that
 * would need escaping reach it.
 *
 * `-T /usr/bin/security` is the item's trusted-application list, and it is honest about its
 * ceiling: it stops the *confirmation dialog* on every read, and anything that can run `security`
 * as this user can therefore read the item. What the Keychain buys over the 0600 file is
 * encryption at rest, a credential that goes away when the keychain locks, and a session that is
 * not sitting in a directory people tar up and attach to bug reports.
 */
internal class SecurityCliKeychain(
    private val service: String = SESSION_SERVICE_NAME,
    private val account: String = SESSION_STORAGE_KEY,
    private val security: Path = SECURITY_BIN,
    /**
     * Injectable so a test can prove the bound *fires*. It was unreachable for a while and
     * nothing said so, because the only thing that had ever exercised this path was a `security`
     * that answers immediately.
     */
    private val timeoutSeconds: Long = TIMEOUT_SECONDS,
) : SecureItem {

    override val location: String = "the macOS Keychain (service $service)"

    private val subprocess = BoundedSubprocess(
        tool = "security",
        timeoutSeconds = timeoutSeconds,
        timeoutNote = "a locked keychain, or a confirmation prompt nobody can see",
    )

    override fun read(): SecureItemRead {
        val outcome = run(listOf("find-generic-password", "-s", service, "-a", account, "-w"))
        return when {
            outcome.exitCode == 0 -> SecureItemRead.Found(outcome.stdout.trim())
            outcome.exitCode == ITEM_NOT_FOUND -> SecureItemRead.Missing
            else -> SecureItemRead.Failed(outcome.describe(TOOL))
        }
    }

    /**
     * `find-generic-password` answers the same exit codes with and without `-w`; only `-w` prints
     * the password. So presence is answerable without pulling the secret through a pipe, which is
     * what [SecureItem.exists] requires of every implementation.
     */
    override fun exists(): Boolean? {
        val outcome = run(listOf("find-generic-password", "-s", service, "-a", account))
        return when (outcome.exitCode) {
            0 -> true
            ITEM_NOT_FOUND -> false
            else -> null
        }
    }

    override fun write(value: String): Result<Unit> {
        // L4: a `Result` return whose guard threw would take the caller's `onFailure` with it —
        // `save()`'s fallback to the file most of all — so the guard answers on the same path as
        // every other write failure.
        if (!isArgvSafe(value)) {
            return Result.failure(
                IllegalArgumentException(
                    "a keychain value has to survive `security -i` line splitting unquoted; " +
                        "this one would not",
                ),
            )
        }
        // `-U` so a second sign-in replaces the item rather than failing on a duplicate; `-l`/`-D`
        // so Keychain Access shows a person something they can recognise and delete by hand.
        val command = listOf(
            "add-generic-password", "-U",
            "-s", service,
            "-a", account,
            "-l", service,
            // Quoted because it has a space in it, and `-i` splits its line the way a shell does.
            // Every other token here is argv-safe by construction — [isArgvSafe] for the value,
            // and constants for the rest.
            "-D", "\"Loopky session\"",
            "-T", security.toString(),
            "-w", value,
        ).joinToString(" ")
        val outcome = run(emptyList(), stdin = "$command\n")
        return if (outcome.exitCode == 0) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(outcome.describe(TOOL)))
        }
    }

    override fun delete(): Result<Unit> {
        val outcome = run(listOf("delete-generic-password", "-s", service, "-a", account))
        return if (outcome.exitCode == 0 || outcome.exitCode == ITEM_NOT_FOUND) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(outcome.describe(TOOL)))
        }
    }

    /** One `security` subcommand, bounded — see [BoundedSubprocess] for why the bound is real. */
    private fun run(args: List<String>, stdin: String? = null): SubprocessOutcome =
        subprocess.run(
            listOf(security.toString()) + (if (stdin == null) args else listOf("-i") + args),
            stdin,
        )

    private companion object {
        const val TOOL = "security"

        /** `errSecItemNotFound` as `security(1)` reports it. An answer, not a failure. */
        const val ITEM_NOT_FOUND = 44
        const val TIMEOUT_SECONDS = 20L
    }
}

internal val SECURITY_BIN: Path = Paths.get("/usr/bin/security")

/** True when this host has the tool the Keychain is reached through. */
internal fun keychainToolPresent(security: Path = SECURITY_BIN): Boolean = Files.isExecutable(security)

/**
 * Whether `security -i`'s shell-like line splitting carries this value through intact.
 *
 * The Base64 alphabet is inside this set, so it is always true for a session — which is the point:
 * it keeps "no quoting needed" a checked fact rather than a comment, if something later stores a
 * value that is not Base64.
 */
private fun isArgvSafe(value: String): Boolean =
    value.isNotEmpty() && value.all { it.isLetterOrDigit() || it in "+/=_-." }
