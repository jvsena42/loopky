package com.github.jvsena42.loopky.cli

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        // Named rather than inherited from the host. `detectInstallation` defaults this to the real
        // `os.name`, and `:cli:test` runs on `windows-latest` — so without a fixed default here
        // every case below would take the Windows arm on that runner and assert the wrong row.
        osName: String = "Linux",
    ) = detectInstallation(
        env = { env[it] },
        property = { if (it == "org.graalvm.nativeimage.imagecode") image else null },
        exists = { it in markers },
        executable = { path?.let(Path::of) },
        osName = osName,
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

    /**
     * **Windows is its own row, and the reason is not that Homebrew and dpkg are absent there.** The
     * two branches above match `/Cellar/` and `/usr/bin/` against `Path.toString()`, which Windows
     * spells with backslashes — so every path fell through to [InstallMethod.Binary], the one value
     * that lets `update` write over a file. That was inert only while no `.exe` was published and
     * every Windows install resolved to [InstallMethod.Jar]; publishing one is what arms it (#301).
     */
    @Test
    fun `windows is its own row, whatever the path looks like`() {
        listOf(
            """C:\Users\agent\AppData\Local\Programs\loopky\loopky.exe""",
            """C:\Program Files\loopky\loopky.exe""",
            // The shapes the POSIX branches would have matched had the separators been forward
            // slashes, which is the misclassification this arm forecloses rather than side-steps.
            """C:\usr\bin\loopky.exe""",
            """C:\opt\homebrew\Cellar\loopky\0.8.0\bin\loopky.exe""",
        ).forEach { p ->
            val found = detect(path = p, osName = "Windows 11")
            assertEquals(InstallMethod.WindowsBinary, found.method, p)
            assertFalse(found.canSelfUpdate, "a running .exe cannot be replaced in place: $p")
            assertEquals(Path.of(p), found.path, "the path is still reported, so `update` can name it")
        }
    }

    /**
     * Order matters: the Windows arm sits *after* the jar and container checks, because neither of
     * those is about the host. A jar run on Windows is still a directory of jars rather than a file
     * to swap, and saying `windows-binary` there would quote `install.ps1` at somebody holding a
     * start script.
     */
    @Test
    fun `the jar and container rows still win on windows`() {
        assertEquals(InstallMethod.Jar, detect(image = null, osName = "Windows 11").method)
        assertEquals(
            InstallMethod.Container,
            detect(env = mapOf("LOOPKY_CONTAINER" to "1"), osName = "Windows 11").method,
        )
    }
}
