package com.github.jvsena42.loopky.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * How this copy of `loopky` got onto the machine, and therefore who is allowed to replace it.
 *
 * A self-updater is the one command that fetches an executable and runs it as the user, so the
 * interesting question is not "can I write to that path" but "is this file mine to write". A
 * Homebrew Cellar file and a `dpkg`-owned `/usr/bin/loopky` are both writable by a root shell and
 * neither is ours: overwriting one leaves the package manager describing a version that is no
 * longer installed, and the next `brew upgrade` or `apt install --reinstall` silently reverts the
 * update. So this is detected and **refused with the right command** rather than attempted —
 * issue #209's "refuse honestly where it cannot work".
 *
 * The container row is the same rule one level up: an image is replaced by pulling an image, and a
 * binary rewritten inside a running container disappears with it. The Dockerfile sets
 * `LOOPKY_CONTAINER=1` on the runtime stage so our own image says what it is rather than being
 * guessed at; the two well-known marker files cover an image somebody else built us into.
 */
enum class InstallMethod(val json: String) {
    /** A downloaded single file, in a directory the user owns. The only row that can self-update. */
    Binary("binary"),

    /**
     * A downloaded `loopky.exe`. Its own row rather than [Binary], because **`update` cannot replace
     * a running executable on Windows**: `ATOMIC_MOVE` over the target is refused by the OS, and
     * `replaceFailed` maps that to "not writable by this user — a read-only layer, or an install
     * that needs the owner", which tells an agent the machine is wrong rather than the method, and
     * to stop retrying. The fix is to rename the running file aside and sweep it at start-up; until
     * that lands this refuses by name and quotes `install.ps1` (#301).
     */
    WindowsBinary("windows-binary"),

    /** A Homebrew Cellar file, reached through a symlink in `bin`. */
    Homebrew("homebrew"),

    /** `dpkg`'s `/usr/bin/loopky`, from the published `.deb`. */
    Debian("deb"),

    /** Inside a container image. */
    Container("container"),

    /** The jar distribution: a directory of jars plus a start script, not a file. */
    Jar("jar"),

    /** A native binary whose own path could not be determined. */
    Unknown("unknown"),
    ;

    /** Whether `loopky update` may replace this installation in place. */
    val canSelfUpdate: Boolean get() = this == Binary
}

/** What was found: the method, and the file to replace when there is one. */
data class Installation(val method: InstallMethod, val path: Path?) {
    val canSelfUpdate: Boolean get() = method.canSelfUpdate && path != null
}

/**
 * Work out how this process was installed.
 *
 * Order matters and is not alphabetical. "Is this a native image at all" comes first because the
 * jar distribution has no single file to replace whatever its path says; the container check comes
 * next because the binary inside our image sits at `/usr/local/bin/loopky`, which is otherwise
 * indistinguishable from a hand-installed one.
 */
fun detectInstallation(
    env: (String) -> String? = System::getenv,
    property: (String) -> String? = System::getProperty,
    exists: (String) -> Boolean = { Files.exists(Paths.get(it)) },
    executable: () -> Path? = ::currentExecutable,
    osName: String = System.getProperty("os.name").orEmpty(),
): Installation {
    // `org.graalvm.nativeimage.imagecode` is `runtime` inside a native image and unset on a JVM.
    // The jar distribution is a `lib/` of jars plus a generated start script, so there is no one
    // file to swap and `update` says so instead of writing over the script.
    if (property("org.graalvm.nativeimage.imagecode") == null) return Installation(InstallMethod.Jar, null)

    // `isNotBlank`, not `!= null`, and the same convention as `LOOPKY_NO_UPDATE_CHECK`. POSIX
    // `export FOO=` and a `docker run --env LOOPKY_CONTAINER` passthrough from a host that has it
    // unset both yield an **empty string**, not an absent variable — and an ordinary
    // `~/.local/bin/loopky` classified `Container` refuses to update itself and recommends
    // `docker pull` for an image it has nothing to do with.
    if (env("LOOPKY_CONTAINER")?.isNotBlank() == true || exists("/.dockerenv") || exists("/run/.containerenv")) {
        return Installation(InstallMethod.Container, executable())
    }

    val path = executable() ?: return Installation(InstallMethod.Unknown, null)
    val text = path.toString()

    // **Windows is its own row, and the POSIX branches below are not merely inapplicable there —
    // they are wrong.** They match `/Cellar/` and `/usr/bin/` against `Path.toString()`, which
    // Windows spells with backslashes, so every path falls through to [InstallMethod.Binary]: the
    // one value that lets `update` write over a file. Harmless only while no `.exe` was published
    // and every Windows install resolved to [InstallMethod.Jar]; publishing one is what arms it.
    if (osName.startsWith("Windows", ignoreCase = true)) {
        return Installation(InstallMethod.WindowsBinary, path)
    }

    return when {
        // Homebrew installs into `<prefix>/Cellar/loopky/<version>/bin` and links from
        // `<prefix>/bin`. The Cellar segment is the one that appears on both Apple Silicon
        // (`/opt/homebrew`) and Intel (`/usr/local`) and in a custom prefix.
        text.contains("/Cellar/") || text.contains("/linuxbrew/") -> Installation(InstallMethod.Homebrew, path)
        // Where `cli/packaging/deb.sh` puts it, and where nothing else does — a hand-installed
        // binary goes in `~/.local/bin` or `/usr/local/bin`.
        text.startsWith("/usr/bin/") -> Installation(InstallMethod.Debian, path)
        else -> Installation(InstallMethod.Binary, path)
    }
}

/**
 * The path of the running executable, or null.
 *
 * `/proc/self/exe` first because it is exact, cheap and survives a `PATH` lookup, a relative
 * invocation and a rename; [ProcessHandle] is the fallback for macOS, which has no `/proc`. Null
 * rather than a guess: `update` refuses on [InstallMethod.Unknown], and guessing here would mean
 * downloading 60 MB over some other program's file.
 */
fun currentExecutable(): Path? {
    val proc = Paths.get("/proc/self/exe")
    runCatching {
        if (Files.exists(proc)) return proc.toRealPath()
    }
    return runCatching {
        ProcessHandle.current().info().command().orElse(null)?.let { Paths.get(it).toRealPath() }
    }.getOrNull()
}
