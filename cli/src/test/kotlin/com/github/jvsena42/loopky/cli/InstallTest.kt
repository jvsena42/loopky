package com.github.jvsena42.loopky.cli

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which tool owns this copy of `loopky` (#209).
 *
 * Getting a row wrong here is not cosmetic: [InstallMethod.Binary] is the one value that lets
 * `update` write over a file, so a `dpkg`-owned `/usr/bin/loopky` misread as a plain download is
 * an update the next `apt install --reinstall` silently reverts, with the package manager
 * describing a version that is no longer there.
 */
class InstallTest {

    private fun detect(
        image: String? = "runtime",
        env: Map<String, String> = emptyMap(),
        markers: Set<String> = emptySet(),
        path: String? = "/home/agent/.local/bin/loopky",
    ) = detectInstallation(
        env = { env[it] },
        property = { if (it == "org.graalvm.nativeimage.imagecode") image else null },
        exists = { it in markers },
        executable = { path?.let(Path::of) },
    )

    @Test
    fun `a downloaded binary in a user directory can update itself`() {
        val found = detect()
        assertEquals(InstallMethod.Binary, found.method)
        assertEquals(Path.of("/home/agent/.local/bin/loopky"), found.path)
    }

    /** No native image means the jar distribution: a directory of jars, not a file to swap. */
    @Test
    fun `a JVM run is the jar distribution, whatever its path says`() {
        val found = detect(image = null, path = "/opt/loopky/bin/loopky")
        assertEquals(InstallMethod.Jar, found.method)
        assertNull(found.path)
    }

    @Test
    fun `our own image declares itself, and the two marker files cover everyone else's`() {
        assertEquals(
            InstallMethod.Container,
            detect(env = mapOf("LOOPKY_CONTAINER" to "1"), path = "/usr/local/bin/loopky").method,
        )
        assertEquals(InstallMethod.Container, detect(markers = setOf("/.dockerenv")).method)
        assertEquals(InstallMethod.Container, detect(markers = setOf("/run/.containerenv")).method)
    }

    /**
     * `export LOOPKY_CONTAINER=` and a `docker run --env LOOPKY_CONTAINER` passthrough from a host
     * that has it unset both yield an **empty string**, not an absent variable — and an ordinary
     * `~/.local/bin/loopky` classified `Container` refuses to update itself and recommends
     * `docker pull` for an image it has nothing to do with.
     */
    @Test
    fun `an empty LOOPKY_CONTAINER is not a container`() {
        assertEquals(InstallMethod.Binary, detect(env = mapOf("LOOPKY_CONTAINER" to "")).method)
        assertEquals(InstallMethod.Binary, detect(env = mapOf("LOOPKY_CONTAINER" to "  ")).method)
    }

    @Test
    fun `a Cellar path is Homebrew's, on either prefix`() {
        assumePosixPaths()
        assertEquals(
            InstallMethod.Homebrew,
            detect(path = "/opt/homebrew/Cellar/loopky/0.8.0/bin/loopky").method,
        )
        assertEquals(
            InstallMethod.Homebrew,
            detect(path = "/usr/local/Cellar/loopky/0.8.0/bin/loopky").method,
        )
    }

    @Test
    fun `usr bin is dpkg's, and usr local bin is not`() {
        assumePosixPaths()
        assertEquals(InstallMethod.Debian, detect(path = "/usr/bin/loopky").method)
        assertEquals(InstallMethod.Binary, detect(path = "/usr/local/bin/loopky").method)
    }

    /**
     * Homebrew and dpkg do not own anything on Windows, so these two rows are POSIX-only by
     * subject matter — but that is not why they are skipped. `detectInstallation` matches
     * `"/Cellar/"` and `"/usr/bin/"` against `Path.toString()`, and on Windows `Path.of` normalises
     * those to `\Cellar\` and `\usr\bin\`, so every path classifies [InstallMethod.Binary] — the
     * one value that lets `update` write over a file. That is a real defect and its fix is the
     * Windows arm of the classifier (#301); skipping here keeps this test honest about what it
     * checks rather than asserting a contract the matcher does not yet keep.
     */
    private fun assumePosixPaths() = assumeTrue(
        Path.of("/usr/bin/loopky").toString().startsWith("/"),
        "needs POSIX path separators",
    )

    @Test
    fun `a binary that cannot find itself is unknown rather than guessed at`() {
        val found = detect(path = null)
        assertEquals(InstallMethod.Unknown, found.method)
        assertNull(found.path)
    }
}
