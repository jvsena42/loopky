package com.github.jvsena42.loopky.data.storage

import com.github.jvsena42.loopky.platform.isMacOs
import com.github.jvsena42.loopky.platform.isWindows
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
 * 3. The platform default: `~/.config/loopky` on Linux, `~/Library/Application Support/loopky` on
 *    macOS, `%LOCALAPPDATA%\loopky` on Windows.
 *
 * **The Windows row is `%LOCALAPPDATA%` rather than `%APPDATA%`, and that is a security choice
 * rather than a convention** (#301). A Windows profile roams everything except `AppData\Local` and
 * `AppData\LocalLow`, so with a roaming profile configured `%APPDATA%` — and `~/.config`, which
 * this used to fall into there — is copied to the domain's profile server at logoff, session
 * secret included.
 *
 * **`XDG_CONFIG_HOME` can undo that, and it is resolved before the Windows branch is reached.**
 * Windows dotfile setups commonly export it — neovim, fontconfig and several git-for-windows
 * guides suggest `%APPDATA%` or `%USERPROFILE%\.config`, both of which roam — so a user who set it
 * still gets the session on the profile server. It is honoured anyway, because both variables mean
 * "keep everything here" and silently ignoring one on a single platform would be a worse surprise
 * than the one it prevents. Said out loud here and in `--help` because nothing about it announces
 * itself.
 */
object ConfigHome {

    /**
     * Public because the client has to be able to *say* where it keeps things — `whoami` reports
     * it, and an agent debugging a container that has lost its session needs the path rather than
     * a description of the rules.
     */
    fun resolve(
        env: (String) -> String? = System::getenv,
        // Injectable for the same reason `env` is, and defaulted so no caller changes. Without it
        // the platform branch below is whatever host the test happens to run on, so the Windows row
        // could only ever be reached by the machine least likely to be running the suite.
        osName: String = System.getProperty("os.name").orEmpty(),
    ): Path {
        env("LOOPKY_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { return Paths.get(it) }
        env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { return Paths.get(it, APP_DIR) }
        // `env` and `osName`, not the defaults. Without them `LOCALAPPDATA` is read from the real
        // environment whatever was injected, so `resolve({ null })` on Windows answers with the
        // *host's* AppData rather than the stripped-environment fallback this file promises — and a
        // test written at this level would pass for the wrong reason. Nothing differs at runtime,
        // since the defaults are the same; what it costs is the injectability the KDoc leans on.
        return platformDefault(env, osName)
    }

    /**
     * Where state lives when nothing has been overridden.
     *
     * Split out because it is also the *test* [desktopSecureSessionStore] applies before it will
     * touch the macOS Keychain: an explicit `LOOPKY_CONFIG_HOME` means "keep everything here", and
     * a Keychain item shared across every config home would make a disposable one overwrite the
     * caller's real session.
     */
    fun platformDefault(
        env: (String) -> String? = System::getenv,
        osName: String = System.getProperty("os.name").orEmpty(),
        userHome: String = System.getProperty("user.home") ?: ".",
    ): Path {
        val home = Paths.get(userHome)
        return when {
            isMacOs(osName) -> home.resolve("Library/Application Support").resolve(APP_DIR)
            // `%LOCALAPPDATA%`, and **not** `%APPDATA%` (#301). Everything in a Windows profile
            // except `AppData\Local` roams: with a roaming profile configured, `%APPDATA%` is
            // copied to the domain's profile server at logoff, and the session secret goes with it.
            // Falling into the `.config` branch below did the same thing, since that is under the
            // profile root too — so this is a fix rather than a tidy-up.
            isWindows(osName) -> windowsLocalAppData(env, home).resolve(APP_DIR)
            else -> home.resolve(".config").resolve(APP_DIR)
        }
    }

    /**
     * `%LOCALAPPDATA%`, or the path it conventionally points at.
     *
     * Read rather than assembled because it is what the OS says. **Not** because Folder Redirection
     * moves it — that policy cannot redirect Local AppData at all, which is the same exclusion this
     * whole branch depends on. It moves when the profile is relocated (and `user.home` moves with
     * it, so the fallback would have been right anyway) or by a direct `User Shell Folders` edit,
     * and a machine that has done the latter by hand should not be second-guessed.
     *
     * The fallback is for a process that inherited a stripped environment; it is where the variable
     * points on every supported Windows.
     */
    private fun windowsLocalAppData(env: (String) -> String?, home: Path): Path =
        env("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let(Paths::get)
            ?: home.resolve("AppData").resolve("Local")

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
