package com.github.jvsena42.loopky.data.storage

import com.github.jvsena42.loopky.platform.isMacOs
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Where the desktop client keeps its state, and why it is a directory of files rather than a
 * keychain.
 *
 * The primary target for the JVM build is a **headless Linux box** — that is where an agent
 * actually runs (#54). libsecret is usually present on a desktop Linux and usually absent there,
 * so making the OS keyring the default and the file the fallback would fail exactly where the
 * tool is meant to work. The file is the default, deliberately, and the trade-off is stated in
 * `loopky --help` rather than only here: **a session secret on this machine is protected by file
 * permissions and nothing else.** It is a capability-scoped, expiring session, never a secret key.
 *
 * **None of that reasoning transfers to macOS**, which is why the session no longer lives here on
 * that row (#213): there is a human at the keyboard, the Keychain is always present, and
 * [desktopSecureSessionStore] uses it. This directory still holds everything else, and still
 * holds the session on a Mac whose Keychain will not answer — or one where **either**
 * `LOOPKY_CONFIG_HOME` or `XDG_CONFIG_HOME` is set, since both mean "keep everything here" and
 * both are resolved before [platformDefault] is reached.
 *
 * Resolution order, first hit wins:
 * 1. `LOOPKY_CONFIG_HOME` — an explicit override, so a container or a test can point somewhere
 *    disposable without touching the caller's real state.
 * 2. `$XDG_CONFIG_HOME/loopky`, the freedesktop location.
 * 3. `~/.config/loopky` (Linux) or `~/Library/Application Support/loopky` (macOS).
 */
object ConfigHome {

    /**
     * Public because the client has to be able to *say* where it keeps things — `whoami` reports
     * it, and an agent debugging a container that has lost its session needs the path rather than
     * a description of the rules.
     */
    fun resolve(env: (String) -> String? = System::getenv): Path {
        env("LOOPKY_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { return Paths.get(it) }
        env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { return Paths.get(it, APP_DIR) }
        return platformDefault()
    }

    /**
     * Where state lives when nothing has been overridden.
     *
     * Split out because it is also the *test* [desktopSecureSessionStore] applies before it will
     * touch the macOS Keychain: an explicit `LOOPKY_CONFIG_HOME` means "keep everything here", and
     * a Keychain item shared across every config home would make a disposable one overwrite the
     * caller's real session.
     */
    fun platformDefault(): Path {
        val home = Paths.get(System.getProperty("user.home") ?: ".")
        return if (isMacOs()) {
            home.resolve("Library/Application Support").resolve(APP_DIR)
        } else {
            home.resolve(".config").resolve(APP_DIR)
        }
    }

    /**
     * Create [dir] if it is missing, owner-only where the host can express that.
     *
     * Best-effort on the permissions, not on the directory: a filesystem with neither a POSIX mode
     * nor an ACL is a real host, and refusing to run there would trade a capability for a guarantee
     * it was never going to give. The file writes carry the same restriction, so a directory that
     * could not take one is not the only line of defence.
     *
     * [OwnerOnly] is what decides how this host spells it — a mode, or a DACL (#301). A directory
     * created before this existed keeps whatever it has: re-restricting one on every call would
     * fight a user who widened it deliberately.
     */
    fun prepare(dir: Path): Path {
        if (!Files.exists(dir)) OwnerOnly.createDirectories(dir)
        return dir
    }

    private const val APP_DIR = "loopky"
}
